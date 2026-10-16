package com.gomocalc.app.engine.rapfi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Raw engine stdout lines are published as {@code { output: line }} events, which the page's
 * Rapfi parser consumes unchanged.</p>
 *
 * <p><b>NNUE.</b> On initialize the bundled weights are extracted from {@code assets/rapfi/} into
 * {@code filesDir/rapfi/} (see {@link RapfiAssets}) and the engine is started with that config, so
 * the stronger mix9svq NNUE evaluator is used instead of the built-in classical one.</p>
 *
 * <p><b>Cross-session analysis persistence.</b> The engine is compiled with Rapfi's
 * Yixinboard-compatible database. The plugin points it at an app-private file
 * ({@code filesDir/databases/&lt;name&gt;.db}) via {@code YXSETDATABASE}; the engine reads
 * previously analyzed positions on startup and writes new ones during search (only records deep
 * enough, see the engine's {@code [database.search]} restrictions). It flushes after every
 * completed search and reports the cached record count as a {@code status} event. Mode and
 * current database are persisted in SharedPreferences.</p>
 */
@CapacitorPlugin(name = "NativeRapfi")
public class NativeRapfiPlugin extends Plugin {

    /** Engine line that terminates a search: a single or double "x,y" reply, or SWAP. */
    private static final Pattern BEST_MOVE = Pattern.compile("^\\d+,\\d+( \\d+,\\d+)?$");
    /** Engine message emitted after the evaluator is selected, e.g. "Evaluator set to mix9svq.". */
    private static final Pattern EVALUATOR_SET = Pattern.compile("Evaluator set to (\\S+)\\.");
    /** Delay before flushing, so the engine has left its "thinking" state. */
    private static final long FLUSH_DELAY_MS = 300L;
    /** Retry budget for deferring database commands until the engine is idle. */
    private static final int IDLE_RETRIES = 40;

    /** Directory (under filesDir) holding all analysis databases. */
    private static final String DATABASE_DIR = "databases";
    private static final String DATABASE_EXT = ".db";
    private static final String DEFAULT_DATABASE = "default";

    /** Asset holding the injected database settings panel. */
    private static final String SCRIPT_DATABASE_PANEL = "rapfi_database_panel.js";

    private static final String PREFS = "rapfi_database";
    private static final String PREF_NAME = "current";
    private static final String PREF_MODE = "mode";

    /** Database usage modes exposed to the UI. */
    public static final String MODE_ON = "on";              // 使用数据库（读 + 写）
    public static final String MODE_READONLY = "readonly";  // 只读数据库（读缓存，不写入）
    public static final String MODE_OFF = "off";            // 不使用数据库

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RapfiJni engine;
    /** Stable token so a pending flush can be replaced by a newer one. */
    private final Runnable flushDatabase;

    private volatile boolean initialized;
    /** Cached record count reported by the engine, or -1 when not yet known. */
    private volatile int dbRecordCount = -1;
    /** Active evaluator reported by the engine ("classical" until told otherwise). */
    private volatile String evaluatorName = "classical";
    private volatile String currentDatabase = DEFAULT_DATABASE;
    private volatile String databaseMode = MODE_ON;
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
        // Extracting the bundled NNUE weights (~40 MB) and loading them is slow, so keep it off
        // the WebView bridge thread and resolve the call when the engine is ready.
        new Thread(() -> initializeBlocking(call), "rapfi-init").start();
    }

    private synchronized void initializeBlocking(PluginCall call) {
        try {
            if (!initialized) {
                Context context = getContext();
                SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
                currentDatabase = prefs.getString(PREF_NAME, DEFAULT_DATABASE);
                databaseMode = prefs.getString(PREF_MODE, MODE_ON);

                // Bundled NNUE weights + config; incomplete bundle falls back to classical eval.
                RapfiAssets.Result assetResult = RapfiAssets.ensureExtracted(context);
                emitStatusLines(assetResult.log);
                RapfiJni.configureConfig(assetResult.configPath);
                RapfiJni.configureDatabase(databaseFile(currentDatabase).getAbsolutePath());

                engine.start(null);   // loads the NNUE config and opens the database
                databaseAttached = RapfiJni.isDatabaseConfigured();
                initialized = true;
                applyMode();
                mainHandler.post(this::injectDatabasePanel);
            }
            mainHandler.post(() -> {
                notifyListeners("status", new JSObject().put("value", "ready"));
                JSObject result = new JSObject();
                result.put("engineType", "RAPFI");
                result.put("evaluator", evaluatorName);
                result.put("nnue", RapfiJni.isNnueConfigured());
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
        try {
            if (isSearchCommand(command))
                reportDatabaseStatus();
            engine.sendCommand(command);
            call.resolve();
        } catch (Exception error) {
            call.reject("Native Rapfi command failed: " + error.getMessage(), error);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            engine.stopThinking();
            call.resolve();
        } catch (Exception error) {
            call.reject("Native Rapfi stop failed: " + error.getMessage(), error);
        }
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        try {
            engine.saveDatabase();   // persist the latest records before tearing the engine down
            engine.close();
        } finally {
            initialized = false;
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
                File file = databaseFile(name);
                File dir = file.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs())
                    throw new IllegalStateException("Cannot create database directory");
                currentDatabase = name;
                dbRecordCount = 0;          // freshly created databases start empty
                engine.openDatabase(file.getAbsolutePath());
                databaseAttached = true;
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

    /** Delete an analysis database file. The current one falls back to the default. */
    @PluginMethod
    public void deleteDatabase(PluginCall call) {
        String name = sanitizeName(call.getString("name"));
        if (name == null) {
            call.reject("Invalid database name");
            return;
        }
        if (DEFAULT_DATABASE.equals(name)) {
            call.reject("默认数据库不可删除");
            return;
        }
        runWhenIdle(() -> {
            try {
                File file = databaseFile(name);
                if (file.exists() && !file.delete())
                    throw new IllegalStateException("Unable to delete database file");
                if (name.equals(currentDatabase))
                    switchDatabaseInternal(DEFAULT_DATABASE);
                call.resolve(databaseStatusObject());
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
        JSArray entries = new JSArray();
        for (String line : RapfiAssets.describeExtracted(getContext()))
            entries.put(line);
        JSObject result = new JSObject();
        result.put("evaluator", evaluatorName);
        result.put("nnue", RapfiJni.isNnueConfigured());
        result.put("configPath", RapfiJni.getConfigPath() == null ? "" : RapfiJni.getConfigPath());
        result.put("assets", entries);
        call.resolve(result);
    }

    // ---------------------------------------------------------------- internals

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
                .apply();
    }

    private void switchDatabaseInternal(String name) {
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

    /**
     * Inject the self-contained database settings panel into the WebView. The panel lives in
     * {@code assets/rapfi_database_panel.js} and attaches outside the page's application root,
     * so no packaged Web UI asset has to be modified.
     */
    private void injectDatabasePanel() {
        String script = readAsset(SCRIPT_DATABASE_PANEL);
        if (script == null)
            return;
        WebView webView = getBridge() == null ? null : getBridge().getWebView();
        if (webView == null)
            return;
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private String readAsset(String name) {
        try (InputStream in = getContext().getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);
            return out.toString("UTF-8");
        } catch (Exception error) {
            return null;
        }
    }

    private File databaseDirectory() {
        return new File(getContext().getFilesDir(), DATABASE_DIR);
    }

    private File databaseFile(String name) {
        return new File(databaseDirectory(), name + DATABASE_EXT);
    }

    /** Existing database names (without extension), always including the default. */
    private List<String> listDatabaseNames() {
        List<String> names = new ArrayList<>();
        names.add(DEFAULT_DATABASE);
        File[] files = databaseDirectory().listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String fileName = file.getName();
                if (!file.isFile() || !fileName.endsWith(DATABASE_EXT))
                    continue;
                String name = fileName.substring(0, fileName.length() - DATABASE_EXT.length());
                if (!DEFAULT_DATABASE.equals(name) && !names.contains(name))
                    names.add(name);
            }
        }
        return names;
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
