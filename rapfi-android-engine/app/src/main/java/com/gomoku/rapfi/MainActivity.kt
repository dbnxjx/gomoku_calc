package com.gomoku.rapfi

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device self-test for the native Rapfi engine.
 *
 * It performs the same checks as tests/run_tests.py (init -> START -> analysis
 * -> best move), so you can verify the engine works on a real Android device by
 * simply running this activity and reading the on-screen log.
 */
class MainActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(24, 24, 24, 24)
        }
        setContentView(logView)

        append("Starting engine self-test...")
        Thread(::runSelfTest, "rapfi-self-test").start()
    }

    private fun append(msg: String) {
        log.append(msg).append('\n')
        mainHandler.post { logView.text = log.toString() }
    }

    private fun runSelfTest() {
        val engine = RapfiEngine()
        val gotBestMove = AtomicBoolean(false)
        val gotInfo = AtomicBoolean(false)
        val done = CountDownLatch(1)

        val listener = RapfiEngine.Listener { event ->
            when (event) {
                is EngineEvent.Ready -> append("[ok] engine ready")
                is EngineEvent.Depth -> { gotInfo.set(true) }
                is EngineEvent.Eval -> append("       eval ${event.eval}")
                is EngineEvent.BestLine ->
                    append("       bestline ${event.bestline.joinToString { "[${it[0]},${it[1]}]" }}")
                is EngineEvent.Pos -> {
                    append("[ok] best move (${event.x},${event.y})")
                    gotBestMove.set(true)
                    done.countDown()
                }
                is EngineEvent.Error -> append("[!!] engine error: ${event.error}")
                else -> {}
            }
        }

        try {
            if (!engine.init(configPath = null, listener = listener, handler = mainHandler)) {
                append("[!!] init failed")
                return
            }

            engine.sendCommand("START 15")
            engine.sendCommand("INFO RULE 0")
            engine.sendCommand("INFO THREAD_NUM 1")
            engine.sendCommand("INFO TIMEOUT_TURN 2000")
            engine.sendCommand("INFO MAX_DEPTH 8")
            engine.sendCommand("INFO SHOW_DETAIL 3")
            engine.sendCommand("YXBOARD 7,7,1 DONE")
            engine.sendCommand("YXNBEST 3")

            if (!done.await(60, TimeUnit.SECONDS)) {
                append("[!!] timeout waiting for best move")
            }

            append(if (gotBestMove.get()) "[PASS] engine produced a best move"
                   else "[FAIL] no best move received")
            append(if (gotInfo.get()) "[PASS] engine produced INFO search output"
                   else "[FAIL] no INFO output received")

            engine.terminate()
            append("engine terminated")
        } catch (t: Throwable) {
            append("[!!] exception: ${t.message}")
            Log.e(TAG, "self-test failed", t)
        }
    }

    companion object {
        private const val TAG = "RapfiEngine"
    }
}
