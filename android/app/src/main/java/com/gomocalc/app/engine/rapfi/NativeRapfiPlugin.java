package com.gomocalc.app.engine.rapfi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Capacitor plugin exposing the in-process Rapfi engine to the packaged Web UI.
 *
 * <p>Contract (see README "接入 Rapfi"):</p>
 * <pre>
 *   initialize({ engineType: "RAPFI" }) -> Promise
 *   sendCommand({ command: string })    -> Promise
 *   stop()                              -> Promise
 *   addListener("stdout", cb) / "stderr" / "status" / "exit"
 * </pre>
 *
 * <p><b>Engine weights (NNUE / classical).</b> The page already has an "引擎权重" selector whose
 * options map to {@code config.toml}, {@code classical220723.toml} and
 * {@code classical210901.toml}. The page asks for them with {@code RELOADCONFIG <name>}; this
 * plugin answers that command itself and maps the name onto the bundled, extracted (absolute-path)
 * config. The selected weight is persisted, so it survives restarts.</p>
 *
 * <p><b>Cross-session analysis persistence.</b> The engine is compiled with Rapfi's
 * Yixinboard-compatible database. The plugin points it at an app-private file
 * ({@code filesDir/databases/&lt;name&gt;.db}) via {@code YXSETDATABASE} and flushes after every
 * completed search. Databases can be listed, created, switched, deleted and imported/exported
 * through the system file picker.</p>
 */
@CapacitorPlugin(name = "NativeRapfi")
public class NativeRapfiPlugin extends Plugin {

    /** Engine line that terminates a search: a single or double "x,y" reply, or SWAP. */
    private static final Pattern BEST_MOVE = Pattern.compile("^\\d+,\\d+( \\d+,\\d+)?$");
    /** Engine message emitted after the evaluator is selected, e.g. "Evaluator set to mix9svq.". */
    private static final Pattern EVALUATOR_SET = Pattern.compile("Evaluator set to (\\S+)\\.");
    /** Engine command the page uses to pick a weight config. */
    private static final String CMD_RELOADCONFIG = "RELOADCONFIG";
    /** Delay before flushing, so the engine has left its "thinking" state. */
    private static final long FLUSH_DELAY_MS = 300L;
    /** Retry budget for deferring database commands until the engine is idle. */
    private static final int IDLE_RETRIES = 40;

    /** Directory (under filesDir) holding all analysis databases. */
    private static final String DATABASE_DIR = "databases";
    private static final String DATABASE_EXT = ".db";
    private static final String DEFAULT_DATABASE = "default";

    private static final String PREFS = "rapfi_database";
    private static final String PREF_NAME = "current";
    private static final String PREF_MODE = "mode";
    private static final String PREF_WEIGHT = "weight";

    /** Database usage modes exposed to the UI. */
    public static final String MODE_ON = "on";              // 使用数据库（读 + 写）
    public static final String MODE_READONLY = "readonly";  // 只读数据库（读缓存，不写入）
    public static final String MODE_OFF = "off";            // 不使用数据库

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RapfiJni engine;
    /** Rapfi's protocol is line-oriented; preserve Web command order across bridge calls. */
    private final ExecutorService commandQueue = Executors.newSingleThreadExecutor();
    /** Stable token so a pending flush can be replaced by a newer one. */
    private final Runnable flushDatabase;

    private volatile boolean initialized;
    /** Cached record count reported by the engine, or -1 when not yet known. */
    private volatile int dbRecordCount = -1;
    /** Active evaluator reported by the engine ("classical" until told otherwise). */
    private volatile String evaluatorName = "classical";
    private volatile String currentDatabase = DEFAULT_DATABASE;
    private volatile String databaseMode = MODE_ON;
    /** Selected weight config file name (one of {@link RapfiAssets#CONFIG_FILES}). */
    private volatile String weightName = RapfiAssets.CONFIG_NNUE;
    /** Whether the bundled NNUE weights are available at all. */
    private volatile boolean nnueAvailable;
    /** Whether the engine currently has a database storage attached. */
    private volatile boolean databaseAttached;

    public NativeRapfiPlugin() {
        this.engine = new RapfiJni(this::onEngineLine);
        this.flushDatabase = this.engine::saveDatabase;
    }

    /** Runs on engine worker threads; must be cheap and thread-safe. */
    private void onEngineLine(String line) {
        Integer records = RapfiProtocol.parseDatabaseRecordCount(line);
        if (records != null)
            dbRecordCount = records;

        Matcher evaluator = EVALUATOR_SET.matcher(line);
        if (evaluator.find())
            evaluatorName = evaluator.group(1);

        mainHandler.post(() -> notifyListeners("stdout", new JSObject().put("output", line)));

        if (BEST_MOVE.matcher(line).matches()) {
            // A search just finished: persist any new records it produced.
            mainHandler.removeCallbacks(flushDatabase);
            mainHandler.postDelayed(flushDatabase, FLUSH_DELAY_MS);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @PluginMethod
    public void initialize(PluginCall call) {
        if (!RapfiJni.isLibraryAvailable()) {
            call.reject("Native Rapfi .so is unavailable for this ABI");
            return;
        }
        // Extracting the bundled weights (~40 MB) and loading them is slow, so keep it off the
        // WebView bridge thread and resolve the call when the engine is ready.
        new Thread(() -> initializeBlocking(call), "rapfi-init").start();
    }

    private synchronized void initializeBlocking(PluginCall call) {
        try {
            if (!initialized) {
                Context context = getContext();
                SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
                String savedDatabase = sanitizeName(prefs.getString(PREF_NAME, DEFAULT_DATABASE));
                currentDatabase = savedDatabase == null ? DEFAULT_DATABASE : savedDatabase;
                String savedMode = prefs.getString(PREF_MODE, MODE_ON);
                databaseMode = isDatabaseMode(savedMode) ? savedMode : MODE_ON;
                String savedWeight = prefs.getString(PREF_WEIGHT, RapfiAssets.CONFIG_NNUE);
                weightName = RapfiAssets.isKnownConfig(savedWeight)
                        ? savedWeight : RapfiAssets.CONFIG_NNUE;

                // The engine does not create the database directory itself, so a missing
                // "databases/" folder makes it log "Failed to open YXDB file".
                ensureDatabaseDirectory();

                RapfiAssets.Result assetResult = RapfiAssets.ensureExtracted(context);
                emitStatusLines(assetResult.log);
                nnueAvailable = assetResult.configPath != null;
                if (!nnueAvailable)
                    weightName = null;   // no bundle: keep the engine's built-in classical config
                RapfiJni.configureConfig(nnueAvailable
                        ? RapfiAssets.configPath(context, weightName) : null);
                RapfiJni.configureDatabase(databaseFile(currentDatabase).getAbsolutePath());

                engine.start(null);   // loads the selected weight config and opens the database
                databaseAttached = RapfiJni.isDatabaseConfigured();
                initialized = true;
                applyMode();
            }
            mainHandler.post(() -> {
                notifyListeners("status", new JSObject().put("value", "ready"));
                JSObject result = new JSObject();
                result.put("engineType", "RAPFI");
                result.put("evaluator", evaluatorName);
                result.put("weight", weightName == null ? "" : weightName);
                result.put("nnue", RapfiAssets.CONFIG_NNUE.equals(weightName));
                call.resolve(result);
            });
        } catch (Exception error) {
            mainHandler.post(() -> call.reject(
                    "Unable to initialize native Rapfi: " + error.getMessage(), error));
        }
    }

    @PluginMethod
    public void sendCommand(PluginCall call) {
        String command = call.getString("command");
        if (command == null || command.isEmpty()) {
            call.reject("sendCommand requires a non-empty command string");
            return;
        }
        commandQueue.execute(() -> {
            try {
                // The page's "引擎权重" selector asks for "config.toml" / "classical*.toml" by name.
                // Those live in the app's private directory, so answer the request here with the
                // extracted config instead of forwarding a name the engine cannot resolve.
                if (command.regionMatches(true, 0, CMD_RELOADCONFIG, 0, CMD_RELOADCONFIG.length())) {
                    applyWeight(command.substring(CMD_RELOADCONFIG.length()).trim());
                } else {
                    if (isSearchCommand(command)) reportDatabaseStatus();
                    engine.sendCommand(command);
                }
                call.resolve();
            } catch (Exception error) {
                call.reject("Native Rapfi command failed: " + error.getMessage(), error);
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        commandQueue.execute(() -> {
            try {
                engine.stopThinking();
                call.resolve();
            } catch (Exception error) {
                call.reject("Native Rapfi stop failed: " + error.getMessage(), error);
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        try {
            engine.saveDatabase();   // persist the latest records before tearing the engine down
            engine.close();
        } finally {
            initialized = false;
            commandQueue.shutdownNow();
        }
    }

    // ------------------------------------------------- analysis database control

    /** List every analysis database plus the current mode/selection. */
    @PluginMethod
    public void listDatabases(PluginCall call) {
        call.resolve(databaseStatusObject());
    }

    /**
     * Create a new (empty) analysis database and switch to it.
     * @param name user chosen name, without the .db extension.
     */
    @PluginMethod
    public void createDatabase(PluginCall call) {
        String name = sanitizeName(call.getString("name"));
        if (name == null) {
            call.reject("数据库名称无效（不能为空、不能含 . / \\ 等字符）");
            return;
        }
        runWhenIdle(() -> {
            try {
                ensureDatabaseDirectory();
                currentDatabase = name;
                dbRecordCount = 0;          // freshly created databases start empty
                engine.openDatabase(databaseFile(name).getAbsolutePath());
                databaseAttached = true;
                applyMode();
                engine.saveDatabase();      // materialise the empty file right away
                persistPreferences();
                call.resolve(databaseStatusObject());
            } catch (Exception error) {
                call.reject("Unable to create database: " + error.getMessage(), error);
            }
        });
    }

    /** Switch to an existing analysis database. */
    @PluginMethod
    public void switchDatabase(PluginCall call) {
        String name = sanitizeName(call.getString("name"));
        if (name == null) {
            call.reject("Invalid database name");
            return;
        }
        runWhenIdle(() -> {
            try {
                switchDatabaseInternal(name);
                call.resolve(databaseStatusObject());
            } catch (Exception error) {
                call.reject("Unable to switch database: " + error.getMessage(), error);
            }
        });
    }

    /** Delete an analysis database (and its backups). */
    @PluginMethod
    public void deleteDatabase(PluginCall call) {
        String name = sanitizeName(call.getString("name"));
        if (name == null) {
            call.reject("Invalid database name");
            return;
        }
        runWhenIdle(() -> {
            try {
                boolean wasCurrent = name.equals(currentDatabase);
                boolean reset = false;

                // The engine's flush() re-creates the file when it is missing, and switching
                // away flushes the outgoing database. So move off (or detach) FIRST, then delete
                // the file(s) - otherwise the just-deleted file comes straight back.
                if (wasCurrent) {
                    String fallback = null;
                    for (String candidate : listDatabaseNames()) {
                        if (!candidate.equals(name)) { fallback = candidate; break; }
                    }
                    if (fallback != null) {
                        switchDatabaseInternal(fallback);
                    } else {
                        engine.setDatabaseEnabled(false);   // detach: flushes before deletion
                        databaseAttached = false;
                        currentDatabase = DEFAULT_DATABASE;
                        reset = true;
                    }
                }

                int removed = deleteDatabaseFiles(name);

                if (reset && !MODE_OFF.equals(databaseMode)) {
                    // A database must exist for analysis caching, so start from an empty one.
                    engine.openDatabase(databaseFile(currentDatabase).getAbsolutePath());
                    databaseAttached = true;
                    dbRecordCount = 0;
                    engine.saveDatabase();
                }

                persistPreferences();
                JSObject status = databaseStatusObject();
                status.put("removedFiles", removed);
                status.put("reset", reset);
                call.resolve(status);
            } catch (Exception error) {
                call.reject("Unable to delete database: " + error.getMessage(), error);
            }
        });
    }

    /** Set the database usage mode: {@code on}, {@code readonly} or {@code off}. */
    @PluginMethod
    public void setDatabaseMode(PluginCall call) {
        String requested = call.getString("mode");
        if (!MODE_ON.equals(requested) && !MODE_READONLY.equals(requested)
                && !MODE_OFF.equals(requested)) {
            call.reject("mode must be one of: on, readonly, off");
            return;
        }
        runWhenIdle(() -> {
            try {
                databaseMode = requested;
                applyMode();
                persistPreferences();
                call.resolve(databaseStatusObject());
            } catch (Exception error) {
                call.reject("Unable to set database mode: " + error.getMessage(), error);
            }
        });
    }

    /** Report mode, current database and cached record count. */
    @PluginMethod
    public void getDatabaseStatus(PluginCall call) {
        call.resolve(databaseStatusObject());
    }

    /** Diagnostic: report the bundled engine assets and the evaluator actually in use. */
    @PluginMethod
    public void getEngineStatus(PluginCall call) {
        call.resolve(engineStatusObject());
    }

    // ------------------------------------------------------ database import/export

    /** Export a database to a user-chosen location through the system file picker. */
    @PluginMethod
    public void exportDatabase(PluginCall call) {
        String name = sanitizeName(call.getString("name"));
        if (name == null)
            name = currentDatabase;
        final String exportName = name;
        runWhenIdle(() -> {
            engine.saveDatabase();   // make sure the file on disk is up to date
            File source = databaseFile(exportName);
            if (!source.isFile()) {
                call.reject("数据库文件不存在：" + exportName);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, exportName + DATABASE_EXT);
            startActivityForResult(call, intent, "exportDatabaseResult");
        });
    }

    @ActivityCallback
    private void exportDatabaseResult(PluginCall call, ActivityResult result) {
        if (call == null)
            return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) {
            call.reject("已取消导出");
            return;
        }
        String requested = sanitizeName(call.getString("name"));
        final String name = requested == null ? currentDatabase : requested;
        final Uri target = result.getData().getData();

        new Thread(() -> {
            try (InputStream in = new FileInputStream(databaseFile(name));
                 OutputStream out = getContext().getContentResolver().openOutputStream(target)) {
                if (out == null)
                    throw new IOException("无法写入所选位置");
                copy(in, out);
                mainHandler.post(() -> {
                    JSObject ok = new JSObject();
                    ok.put("name", name);
                    ok.put("bytes", databaseFile(name).length());
                    call.resolve(ok);
                });
            } catch (Exception error) {
                mainHandler.post(() -> call.reject("导出失败：" + error.getMessage(), error));
            }
        }, "rapfi-db-export").start();
    }

    /** Import a .db file picked by the user and switch to it. */
    @PluginMethod
    public void importDatabase(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(call, intent, "importDatabaseResult");
    }

    @ActivityCallback
    private void importDatabaseResult(PluginCall call, ActivityResult result) {
        if (call == null)
            return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) {
            call.reject("已取消导入");
            return;
        }
        final Uri source = result.getData().getData();

        String requested = sanitizeName(call.getString("name"));
        String name = requested;
        if (name == null) {
            name = sanitizeName(stripDatabaseExtension(displayName(source)));
            if (name == null)
                name = "imported-" + (System.currentTimeMillis() / 1000);
            name = uniqueDatabaseName(name);
        }
        final String importName = name;

        new Thread(() -> {
            try {
                ensureDatabaseDirectory();
                try (InputStream in = getContext().getContentResolver().openInputStream(source);
                     OutputStream out = new FileOutputStream(databaseFile(importName))) {
                    if (in == null)
                        throw new IOException("无法读取所选文件");
                    copy(in, out);
                }
                mainHandler.post(() -> runWhenIdle(() -> {
                    try {
                        switchDatabaseInternal(importName);
                        JSObject status = databaseStatusObject();
                        status.put("name", importName);
                        call.resolve(status);
                    } catch (Exception error) {
                        call.reject("导入后切换失败：" + error.getMessage(), error);
                    }
                }));
            } catch (Exception error) {
                mainHandler.post(() -> call.reject("导入失败：" + error.getMessage(), error));
            }
        }, "rapfi-db-import").start();
    }

    // ---------------------------------------------------------------- internals

    /** Apply a weight config selected by the page's "引擎权重" option. */
    private void applyWeight(String requestedName) {
        if (!nnueAvailable)
            return;   // no bundled bundle: keep the built-in classical configuration
        String name = RapfiAssets.isKnownConfig(requestedName)
                ? requestedName : RapfiAssets.CONFIG_NNUE;
        String path = RapfiAssets.configPath(getContext(), name);
        if (path == null)
            return;

        weightName = name;
        evaluatorName = RapfiAssets.CONFIG_NNUE.equals(name)
                ? RapfiAssets.EVALUATOR : "classical";
        engine.sendCommand(CMD_RELOADCONFIG + " " + path);
        // Reloading detaches the database (its config has enable_by_default = false).
        databaseAttached = false;
        if (!MODE_OFF.equals(databaseMode)) {
            engine.openDatabase(databaseFile(currentDatabase).getAbsolutePath());
            databaseAttached = true;
        }
        applyMode();
        persistPreferences();
    }

    /**
     * Defer an action until the engine has left its thinking state. The engine ignores
     * database commands while searching, so switching/mode changes must wait.
     */
    private void runWhenIdle(Runnable action) {
        runWhenIdle(IDLE_RETRIES, action);
    }

    private void runWhenIdle(int attemptsLeft, Runnable action) {
        mainHandler.post(() -> {
            if (!engine.isThinking() || attemptsLeft <= 0) {
                action.run();
                return;
            }
            engine.stopThinking();
            mainHandler.postDelayed(() -> runWhenIdle(attemptsLeft - 1, action), 100);
        });
    }

    /** Push the current mode to the engine, avoiding a redundant database reload. */
    private void applyMode() {
        boolean wantAttached = !MODE_OFF.equals(databaseMode);
        engine.setDatabaseReadOnly(MODE_READONLY.equals(databaseMode));
        if (wantAttached != databaseAttached) {
            engine.setDatabaseEnabled(wantAttached);
            databaseAttached = wantAttached;
        }
    }

    private void persistPreferences() {
        getContext().getSharedPreferences(PREFS, 0)
                .edit()
                .putString(PREF_NAME, currentDatabase)
                .putString(PREF_MODE, databaseMode)
                .putString(PREF_WEIGHT, weightName == null ? "" : weightName)
                .apply();
    }

    private void switchDatabaseInternal(String name) throws IOException {
        ensureDatabaseDirectory();
        currentDatabase = name;
        dbRecordCount = -1;
        engine.openDatabase(databaseFile(name).getAbsolutePath());
        databaseAttached = true;
        applyMode();
        persistPreferences();
    }

    /** Emit diagnostic lines through the status channel (shown in the message drawer). */
    private void emitStatusLines(List<String> lines) {
        for (String line : lines) {
            final String text = "Rapfi: " + line;
            mainHandler.post(() -> notifyListeners("status", new JSObject().put("value", text)));
        }
    }

    private File ensureDatabaseDirectory() throws IOException {
        File dir = databaseDirectory();
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create database directory: " + dir.getAbsolutePath());
        return dir;
    }

    private File databaseDirectory() {
        return new File(getContext().getFilesDir(), DATABASE_DIR);
    }

    private File databaseFile(String name) {
        return new File(databaseDirectory(), name + DATABASE_EXT);
    }

    /** Existing database names (without extension). Always includes the current one. */
    private List<String> listDatabaseNames() {
        List<String> names = new ArrayList<>();
        File[] files = databaseDirectory().listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String fileName = file.getName();
                if (!file.isFile() || !fileName.endsWith(DATABASE_EXT))
                    continue;
                String name = fileName.substring(0, fileName.length() - DATABASE_EXT.length());
                if (isBackupName(name))
                    continue;
                if (!names.contains(name))
                    names.add(name);
            }
        }
        if (!names.contains(currentDatabase))
            names.add(0, currentDatabase);
        if (names.isEmpty())
            names.add(DEFAULT_DATABASE);
        return names;
    }

    /** @return true for "&lt;name&gt;_bak&lt;N&gt;" backup entries. */
    private static boolean isBackupName(String name) {
        int index = name.lastIndexOf("_bak");
        if (index <= 0 || index + 4 >= name.length())
            return false;
        for (int i = index + 4; i < name.length(); i++)
            if (!Character.isDigit(name.charAt(i)))
                return false;
        return true;
    }

    /**
     * Delete {@code <name>.db} and its {@code <name>_bak<N>.db} backups.
     *
     * @return the number of files removed.
     */
    private int deleteDatabaseFiles(String name) {
        int removed = 0;
        File main = databaseFile(name);
        if (main.isFile() && main.delete())
            removed++;
        File[] files = databaseDirectory().listFiles();
        if (files != null) {
            String prefix = name + "_bak";
            for (File file : files) {
                String fileName = file.getName();
                if (!fileName.startsWith(prefix) || !fileName.endsWith(DATABASE_EXT))
                    continue;
                String stem = fileName.substring(0, fileName.length() - DATABASE_EXT.length());
                if (isBackupName(stem) && file.delete())
                    removed++;
            }
        }
        return removed;
    }

    private JSObject databaseStatusObject() {
        JSArray databases = new JSArray();
        for (String name : listDatabaseNames())
            databases.put(name);
        JSObject result = new JSObject();
        result.put("mode", databaseMode);
        result.put("name", currentDatabase);
        result.put("current", currentDatabase);
        result.put("path", databaseFile(currentDatabase).getAbsolutePath());
        result.put("recordCount", dbRecordCount);
        result.put("databases", databases);
        return result;
    }

    private JSObject engineStatusObject() {
        JSArray entries = new JSArray();
        for (String line : RapfiAssets.describeExtracted(getContext()))
            entries.put(line);
        JSObject result = new JSObject();
        result.put("evaluator", evaluatorName);
        result.put("weight", weightName == null ? "" : weightName);
        result.put("nnueAvailable", nnueAvailable);
        result.put("configPath", weightName == null ? ""
                : RapfiAssets.configPath(getContext(), weightName));
        result.put("assets", entries);
        return result;
    }

    /** Reject anything that could escape the database directory. */
    private static String sanitizeName(String raw) {
        if (raw == null)
            return null;
        String name = raw.trim();
        if (name.isEmpty() || name.length() > 64)
            return null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '.' || Character.isISOControl(c))
                return null;
        }
        return name;
    }

    private static boolean isDatabaseMode(String mode) {
        return MODE_ON.equals(mode) || MODE_READONLY.equals(mode) || MODE_OFF.equals(mode);
    }

    private static String stripDatabaseExtension(String name) {
        if (name == null)
            return null;
        String trimmed = name.trim();
        if (trimmed.toLowerCase().endsWith(DATABASE_EXT))
            trimmed = trimmed.substring(0, trimmed.length() - DATABASE_EXT.length());
        return trimmed;
    }

    /** Pick a non-clashing database name (name, name-2, name-3, ...). */
    private String uniqueDatabaseName(String base) {
        String candidate = base;
        int suffix = 2;
        while (databaseFile(candidate).exists() && suffix < 1000)
            candidate = base + "-" + suffix++;
        return candidate;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty())
                        return name;
                }
            }
        } catch (Exception ignored) {
            // fall through to the URI segment
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "imported" : segment;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1)
            out.write(buffer, 0, read);
        out.flush();
    }

    /** Emit a status line so the message drawer shows the cached result count. */
    private void reportDatabaseStatus() {
        if (MODE_OFF.equals(databaseMode) || !databaseAttached)
            return;
        int records = dbRecordCount;
        String text = records >= 0
                ? "Rapfi database [" + currentDatabase + "]: " + records
                  + " positions cached (cross-session)"
                : "Rapfi database [" + currentDatabase + "]: enabled (cross-session result saving)";
        mainHandler.post(() -> notifyListeners("status", new JSObject().put("value", text)));
    }

    /** Commands that start a search, and therefore a good moment to surface database status. */
    private static boolean isSearchCommand(String command) {
        return command.startsWith(RapfiProtocol.YXNBEST)
                || command.startsWith(RapfiProtocol.YXBALANCEONE)
                || command.startsWith(RapfiProtocol.YXBALANCETWO)
                || command.startsWith(RapfiProtocol.BOARD);
    }
}
