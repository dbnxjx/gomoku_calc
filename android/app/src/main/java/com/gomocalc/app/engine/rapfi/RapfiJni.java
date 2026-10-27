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

    /**
     * Absolute path of the engine config (NNUE weights + database) to load, or null for the
     * built-in classical evaluation. Set by the app layer before {@link #start(String)}.
     */
    private static volatile String configPath;

    /**
     * Absolute path of the Yixinboard-compatible analysis database, or null when
     * cross-session persistence is disabled.
     */
    private static volatile String databasePath;

    /** Set the engine config path used on the next {@link #start}. Null = built-in classical. */
    public static void configureConfig(String absolutePath) {
        configPath = absolutePath == null || absolutePath.isEmpty() ? null : absolutePath;
    }

    /** @return the configured engine config path, or null for the built-in classical config. */
    public static String getConfigPath() {
        return configPath;
    }

    /** @return true when an external (NNUE) config is configured. */
    public static boolean isNnueConfigured() {
        return configPath != null;
    }

    /** Set the analysis database path used on the next {@link #start}. */
    public static void configureDatabase(String absolutePath) {
        databasePath = absolutePath;
    }

    /** @return the configured analysis database path, or null when disabled. */
    public static String getDatabasePath() {
        return databasePath;
    }

    /** @return true when cross-session analysis persistence is configured. */
    public static boolean isDatabaseConfigured() {
        String path = databasePath;
        return path != null && !path.isEmpty();
    }

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
        String effectiveConfig = (configPath != null && !configPath.isEmpty())
                ? configPath : RapfiJni.configPath;
        int rc = nativeInit(effectiveConfig);
        if (rc != 0)
            throw new IllegalStateException("Rapfi config failed to load (code " + rc + ")");
        started = true;

        // Open the cross-session analysis database at the app-provided writable path.
        // YXSETDATABASE takes the rest of the line, so paths containing spaces are safe.
        String dbPath = databasePath;
        if (dbPath != null && !dbPath.isEmpty())
            nativeSendCommand(RapfiProtocol.YXSETDATABASE + " " + dbPath);
    }

    /**
     * Switch to another database file immediately (flushing the current one first).
     * Safe to call while the engine is running; the engine flushes and reloads on switch.
     */
    public synchronized void openDatabase(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty())
            return;
        databasePath = absolutePath;
        if (started) {
            nativeSendCommand(RapfiProtocol.YXSAVEDATABASE);   // persist the outgoing database
            nativeSendCommand(RapfiProtocol.YXSETDATABASE + " " + absolutePath);
        }
    }

    /** Attach or detach the configured analysis database. */
    public void setDatabaseEnabled(boolean enabled) {
        if (started)
            nativeSendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_USEDATABASE + " "
                              + (enabled ? 1 : 0));
    }

    /** Keep reading cached results while refusing to write new ones. */
    public void setDatabaseReadOnly(boolean readOnly) {
        if (started)
            nativeSendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_DATABASE_READONLY + " "
                              + (readOnly ? 1 : 0));
    }

    /**
     * Flush accumulated analysis records to the database file. The engine skips the write
     * when nothing changed, so this is cheap to call after every completed search.
     */
    public void saveDatabase() {
        if (started)
            nativeSendCommand(RapfiProtocol.YXSAVEDATABASE);
    }

    /** Send one protocol command line, e.g. {@code INFO RULE 0} or {@code YXNBEST 3}. */
    public void sendCommand(String command) {
        if (command != null && !command.isEmpty())
            nativeSendCommand(command);
    }

    /** Stop the current search and wait for completion. Call on an engine worker. */
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
