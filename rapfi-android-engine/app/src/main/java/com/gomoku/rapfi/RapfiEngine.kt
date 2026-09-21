package com.gomoku.rapfi

import android.os.Handler
import android.os.Looper

/**
 * Native Android Rapfi engine.
 *
 * This is the Kotlin counterpart of the web version's `src/ai/engine.js`:
 *
 *   web                           Kotlin
 *   ---------------------------   -----------------------------------------
 *   init(callback, loadFull)      init(configPath, listener, handler)
 *   sendCommand(cmd)              sendCommand(cmd)
 *   stopThinking()                stopThinking()
 *
 * The engine itself is the unmodified Rapfi C++ engine compiled with the NDK
 * and loaded through `librapfi_jni.so`. Commands and outputs use the exact same
 * text protocol as the web build, so this class can drive any feature the web
 * version supports (analysis, balance mode, forbidden points, ...).
 *
 * Threading: search output arrives on the engine's native worker threads. Pass
 * a [Handler] (e.g. `Handler(Looper.getMainLooper())`) to [init] to have all
 * events dispatched on your UI thread; otherwise events are delivered directly
 * from the producing thread.
 */
class RapfiEngine {

    /** Receives parsed engine events. */
    fun interface Listener {
        fun onEvent(event: EngineEvent)
    }

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var handler: Handler? = null

    @Volatile
    private var ready = false

    /**
     * Initialise the engine.
     *
     * @param configPath path to a TOML config file, or null to use the built-in
     *   self-contained classical evaluation (no NNUE). To use NNUE, pass a path
     *   whose directory also contains the weight files referenced by the config.
     * @param listener receives parsed engine events.
     * @param handler optional handler used to dispatch events (e.g. main looper).
     * @return true on success.
     */
    fun init(configPath: String?, listener: Listener, handler: Handler? = null): Boolean {
        this.listener = listener
        this.handler = handler
        return try {
            nativeInit(configPath)
            ready = true
            listener.onEvent(EngineEvent.Ready)
            true
        } catch (e: Exception) {
            ready = false
            false
        }
    }

    /** Send one protocol command, e.g. `INFO RULE 0` or `YXNBEST 3`. */
    fun sendCommand(cmd: String) {
        check(ready) { "Engine is not ready" }
        nativeSendCommand(cmd)
    }

    /**
     * Stop the current search. Mirrors the web "threads" path: returns false
     * (the engine keeps running and emits the best move found so far).
     */
    fun stopThinking(): Boolean {
        nativeStopThinking()
        return false
    }

    /** @return true while the engine is searching. */
    fun isThinking(): Boolean = nativeIsThinking() != 0

    /** Shut the engine down and release native resources. */
    fun terminate() {
        if (ready) {
            nativeTerminate()
            ready = false
        }
    }

    /**
     * Called from native code (any thread) with one raw engine output line.
     * The line is parsed and delivered to the listener.
     */
    private fun onOutputLine(line: String) {
        val event = EngineOutputParser.parse(line) ?: return
        val l = listener ?: return
        val h = handler
        if (h != null) {
            h.post { l.onEvent(event) }
        } else {
            l.onEvent(event)
        }
    }

    private external fun nativeInit(configPath: String?)
    private external fun nativeSendCommand(cmd: String)
    private external fun nativeStopThinking()
    private external fun nativeIsThinking(): Int
    private external fun nativeTerminate()

    companion object {
        init {
            System.loadLibrary("rapfi_jni")
        }
    }
}
