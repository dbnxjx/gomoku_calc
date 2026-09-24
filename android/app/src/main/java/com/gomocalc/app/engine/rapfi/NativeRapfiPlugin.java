package com.gomocalc.app.engine.rapfi;

import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

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
 */
@CapacitorPlugin(name = "NativeRapfi")
public class NativeRapfiPlugin extends Plugin {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RapfiJni engine;
    private volatile boolean initialized;

    public NativeRapfiPlugin() {
        this.engine = new RapfiJni(new RapfiJni.Listener() {
            @Override public void onStdout(String line) {
                mainHandler.post(() ->
                        notifyListeners("stdout", new JSObject().put("output", line)));
            }
        });
    }

    @PluginMethod
    public void initialize(PluginCall call) {
        if (!RapfiJni.isLibraryAvailable()) {
            call.reject("Native Rapfi .so is unavailable for this ABI");
            return;
        }
        try {
            if (!initialized) {
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
            engine.close();
        } finally {
            initialized = false;
        }
    }
}
