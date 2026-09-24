package com.gomocalc.app.engine.rapfi;

/**
 * Low-level JNI transport for the in-process Rapfi engine.
 *
 * <p>Owns {@code librapfi_jni.so} loading and the raw command stream. Engine output arrives
 * as complete stdout lines on {@link Listener#onStdout}; Rapfi writes its protocol output
 * (OK / INFO ... / x,y / FORBID / ERROR) on stdout, so a single line channel is sufficient.</p>
 *
 * <p>Threading: {@link #sendCommand} and lifecycle methods must be called from one thread at a
 * time; {@link Listener#onStdout} may be invoked from any native worker thread.</p>
 */
public final class RapfiJni implements AutoCloseable {

    /** Receives raw engine stdout lines. */
    public interface Listener {
        void onStdout(String line);
    }

    private static final boolean LIBRARY_AVAILABLE;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("rapfi_jni");
            loaded = true;
        } catch (Throwable ignored) {
            // Missing ABI .so: isLibraryAvailable() reports false and callers degrade gracefully.
        }
        LIBRARY_AVAILABLE = loaded;
    }

    private final Listener listener;
    private volatile boolean started;

    public RapfiJni(Listener listener) {
        this.listener = listener;
    }

    /** @return true when the native library could be loaded for this ABI. */
    public static boolean isLibraryAvailable() {
        return LIBRARY_AVAILABLE;
    }

    /**
     * Start the engine and load its config.
     *
     * @param configPath absolute path to a config.toml, or null to use the built-in
     *                   self-contained classical evaluation (no external weights).
     */
    public synchronized void start(String configPath) {
        if (!LIBRARY_AVAILABLE)
            throw new IllegalStateException("librapfi_jni.so is not available for this ABI");
        if (started)
            return;
        int rc = nativeInit(configPath);
        if (rc != 0)
            throw new IllegalStateException("Rapfi config failed to load (code " + rc + ")");
        started = true;
    }

    /** Send one protocol command line, e.g. {@code INFO RULE 0} or {@code YXNBEST 3}. */
    public void sendCommand(String command) {
        if (command != null && !command.isEmpty())
            nativeSendCommand(command);
    }

    /** Stop the current search (equivalent to the {@code YXSTOP} command). */
    public void stopThinking() {
        nativeStopThinking();
    }

    /** @return true while the engine is searching. */
    public boolean isThinking() {
        return nativeIsThinking() != 0;
    }

    @Override
    public synchronized void close() {
        if (started) {
            nativeTerminate();
            started = false;
        }
    }

    /** Called from native code (any thread) with one raw stdout line. */
    @SuppressWarnings("unused")
    private void onOutputLine(String line) {
        if (listener != null)
            listener.onStdout(line);
    }

    private native int nativeInit(String configPath);
    private native void nativeSendCommand(String command);
    private native void nativeStopThinking();
    private native int nativeIsThinking();
    private native void nativeTerminate();
}
