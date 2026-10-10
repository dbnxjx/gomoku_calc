package com.gomocalc.app.engine.rapfi;

import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
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
 * <p><b>Cross-session analysis persistence.</b> The engine is compiled with Rapfi's
 * Yixinboard-compatible database. On initialize the plugin points it at an app-private file
 * ({@code <filesDir>/rapfi.db}) via {@code YXSETDATABASE}; the engine then reads previously
 * analyzed positions on startup and writes new ones during search. The plugin flushes the
 * database after every completed search (cheap no-op unless records changed) and reports the
 * cached record count as a {@code status} event so the effect is visible in the page's message
 * drawer.</p>
 */
@CapacitorPlugin(name = "NativeRapfi")
public class NativeRapfiPlugin extends Plugin {

    /** Engine line that terminates a search: a single or double "x,y" reply, or SWAP. */
    private static final Pattern BEST_MOVE = Pattern.compile("^\\d+,\\d+( \\d+,\\d+)?$");
    /** Delay before flushing, so the engine has left its "thinking" state. */
    private static final long FLUSH_DELAY_MS = 300L;
    /** Database file name inside the app-private files directory. */
    private static final String DATABASE_FILE = "rapfi.db";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RapfiJni engine;
    /** Stable token so a pending flush can be replaced by a newer one. */
    private final Runnable flushDatabase;
    private volatile boolean initialized;
    /** Cached record count reported by the engine, or -1 when not yet known. */
    private volatile int dbRecordCount = -1;

    public NativeRapfiPlugin() {
        this.engine = new RapfiJni(this::onEngineLine);
        this.flushDatabase = this.engine::saveDatabase;
    }

    /** Runs on engine worker threads; must be cheap and thread-safe. */
    private void onEngineLine(String line) {
        Integer records = RapfiProtocol.parseDatabaseRecordCount(line);
        if (records != null)
            dbRecordCount = records;

        mainHandler.post(() -> notifyListeners("stdout", new JSObject().put("output", line)));

        if (BEST_MOVE.matcher(line).matches()) {
            // A search just finished: persist any new records it produced.
            mainHandler.removeCallbacks(flushDatabase);
            mainHandler.postDelayed(flushDatabase, FLUSH_DELAY_MS);
        }
    }

    @PluginMethod
    public void initialize(PluginCall call) {
        if (!RapfiJni.isLibraryAvailable()) {
            call.reject("Native Rapfi .so is unavailable for this ABI");
            return;
        }
        try {
            if (!initialized) {
                // Enable the cross-session database at an app-private, writable path.
                File database = new File(getContext().getFilesDir(), DATABASE_FILE);
                RapfiJni.configureDatabase(database.getAbsolutePath());
                engine.start(null);   // built-in classical evaluation (self-contained)
                initialized = true;
            }
            notifyListeners("status", new JSObject().put("value", "ready"));
            call.resolve(new JSObject().put("engineType", "RAPFI"));
        } catch (Exception error) {
            call.reject("Unable to initialize native Rapfi: " + error.getMessage(), error);
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

    // ---- analysis database controls (available to a future UI commit) ----

    /** Enable/disable the cross-session analysis database entirely. */
    @PluginMethod
    public void setDatabaseEnabled(PluginCall call) {
        boolean enabled = !Integer.valueOf(0).equals(call.getInt("enabled"));
        try {
            engine.sendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_USEDATABASE + " "
                    + (enabled ? 1 : 0));
            call.resolve(new JSObject().put("enabled", enabled));
        } catch (Exception error) {
            call.reject("Unable to toggle Rapfi database: " + error.getMessage(), error);
        }
    }

    /** Keep reading cached results while refusing to write new ones. */
    @PluginMethod
    public void setDatabaseReadOnly(PluginCall call) {
        boolean readOnly = Integer.valueOf(1).equals(call.getInt("readOnly"));
        try {
            engine.sendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_DATABASE_READONLY + " "
                    + (readOnly ? 1 : 0));
            call.resolve(new JSObject().put("readOnly", readOnly));
        } catch (Exception error) {
            call.reject("Unable to toggle Rapfi database mode: " + error.getMessage(), error);
        }
    }

    /** Report the current database configuration and cached record count. */
    @PluginMethod
    public void getDatabaseStatus(PluginCall call) {
        JSObject result = new JSObject();
        String path = RapfiJni.getDatabasePath();
        result.put("configured", RapfiJni.isDatabaseConfigured());
        result.put("path", path == null ? "" : path);
        result.put("recordCount", dbRecordCount);
        call.resolve(result);
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

    /** Emit a status line so the page's message drawer shows the cached result count. */
    private void reportDatabaseStatus() {
        if (!RapfiJni.isDatabaseConfigured())
            return;
        int records = dbRecordCount;
        String text = records >= 0
                ? "Rapfi database: " + records + " positions cached (cross-session)"
                : "Rapfi database: enabled (cross-session result saving)";
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
