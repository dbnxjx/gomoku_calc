package com.gomoku.rapfi

/**
 * Parsed engine output events.
 *
 * This is a faithful port of the web version's output parser
 * (`src/ai/engine.js` -> `onEngineStdout`), so any UI already written against
 * the web API can consume these events with minimal changes.
 */
sealed class EngineEvent {

    /** Engine initialised and ready to accept commands (`{ok: true}` in the web API). */
    object Ready : EngineEvent()

    /** A single best move `x,y`. */
    data class Pos(val x: Int, val y: Int) : EngineEvent()

    /** Two moves on one line `x1,y1 x2,y2` (e.g. balance-two / swap-two output). */
    data class TwoMoves(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : EngineEvent()

    /** The engine chose to swap (`SWAP`). */
    object Swap : EngineEvent()

    /** A plain `MESSAGE ...` line. */
    data class Msg(val msg: String) : EngineEvent()

    /** Real-time search feedback: `MESSAGE REALTIME BEST/LOST [x,y]`. */
    data class Realtime(val type: String, val x: Int?, val y: Int?) : EngineEvent()

    /** `INFO PV <n|DONE>` - current multi-PV line index. */
    data class MultiPV(val multipv: String) : EngineEvent()

    data class NumPV(val numpv: Int) : EngineEvent()
    data class Depth(val depth: Int) : EngineEvent()
    data class SelDepth(val seldepth: Int) : EngineEvent()
    data class Nodes(val nodes: Long) : EngineEvent()
    data class TotalNodes(val totalnodes: Long) : EngineEvent()
    data class TotalTime(val totaltime: Long) : EngineEvent()
    data class Speed(val speed: Long) : EngineEvent()

    /** Raw evaluation string (may be a number or a mate score like `+M2`). */
    data class Eval(val eval: String) : EngineEvent()

    data class WinRate(val winrate: Double) : EngineEvent()

    /** `INFO BESTLINE x,y x,y ...` - principal variation as coordinate pairs. */
    data class BestLine(val bestline: List<IntArray>) : EngineEvent()

    /** `FORBID ...` - list of forbidden points (renju), each as [x, y]. */
    data class Forbid(val forbid: List<IntArray>) : EngineEvent()

    data class Error(val error: String) : EngineEvent()
    data class Unknown(val unknown: String) : EngineEvent()
}

/**
 * Port of the web `onEngineStdout` line parser. Returns null for lines that
 * carry no event (e.g. the bare `OK` acknowledgement).
 */
object EngineOutputParser {

    fun parse(line: String): EngineEvent? {
        val i = line.indexOf(' ')
        if (i == -1) {
            return when (line) {
                "OK" -> null
                "SWAP" -> EngineEvent.Swap
                else -> {
                    val coord = line.split(',')
                    if (coord.size >= 2 && coord[0].toIntOrNull() != null && coord[1].toIntOrNull() != null)
                        EngineEvent.Pos(coord[0].toInt(), coord[1].toInt())
                    else
                        EngineEvent.Unknown(line)
                }
            }
        }

        var head = line.substring(0, i)
        var tail = line.substring(i + 1)

        return when (head) {
            "MESSAGE" -> {
                if (tail.startsWith("REALTIME")) {
                    val r = tail.split(' ')
                    if (r.size < 3 || r[1].isEmpty()) {
                        EngineEvent.Realtime(r.getOrElse(1) { "" }, null, null)
                    } else {
                        val coord = r[2].split(',')
                        EngineEvent.Realtime(
                            r[1],
                            coord.getOrNull(0)?.toIntOrNull(),
                            coord.getOrNull(1)?.toIntOrNull()
                        )
                    }
                } else {
                    EngineEvent.Msg(tail)
                }
            }

            "INFO" -> {
                val j = tail.indexOf(' ')
                head = tail.substring(0, j)
                tail = tail.substring(j + 1)
                when (head) {
                    "PV" -> EngineEvent.MultiPV(tail)
                    "NUMPV" -> EngineEvent.NumPV(tail.toIntOrNull() ?: 0)
                    "DEPTH" -> EngineEvent.Depth(tail.toIntOrNull() ?: 0)
                    "SELDEPTH" -> EngineEvent.SelDepth(tail.toIntOrNull() ?: 0)
                    "NODES" -> EngineEvent.Nodes(tail.toLongOrNull() ?: 0L)
                    "TOTALNODES" -> EngineEvent.TotalNodes(tail.toLongOrNull() ?: 0L)
                    "TOTALTIME" -> EngineEvent.TotalTime(tail.toLongOrNull() ?: 0L)
                    "SPEED" -> EngineEvent.Speed(tail.toLongOrNull() ?: 0L)
                    "EVAL" -> EngineEvent.Eval(tail)
                    "WINRATE" -> EngineEvent.WinRate(tail.toDoubleOrNull() ?: 0.0)
                    "BESTLINE" -> EngineEvent.BestLine(parseCoordList(tail))
                    else -> EngineEvent.Unknown(tail)
                }
            }

            "FORBID" -> EngineEvent.Forbid(parseForbid(tail))
            "ERROR" -> EngineEvent.Error(tail)

            else -> {
                if (head.contains(',')) {
                    val c1 = head.split(',')
                    val c2 = tail.split(',')
                    if (c1.size >= 2 && c2.size >= 2 &&
                        c1[0].toIntOrNull() != null && c1[1].toIntOrNull() != null &&
                        c2[0].toIntOrNull() != null && c2[1].toIntOrNull() != null
                    ) {
                        EngineEvent.TwoMoves(
                            c1[0].toInt(), c1[1].toInt(),
                            c2[0].toInt(), c2[1].toInt()
                        )
                    } else {
                        EngineEvent.Unknown(tail)
                    }
                } else {
                    EngineEvent.Unknown(tail)
                }
            }
        }
    }

    /** `x,y x,y ...` -> list of [x, y] pairs (matches JS `/\d+,\d+/g`). */
    private fun parseCoordList(s: String): List<IntArray> {
        val result = mutableListOf<IntArray>()
        Regex("""\d+,\d+""").findAll(s).forEach { m ->
            val parts = m.value.split(',')
            result.add(intArrayOf(parts[0].toInt(), parts[1].toInt()))
        }
        return result
    }

    /** `0707.` -> [[7, 7]] (matches JS `/.{4}/g` + two-digit split). */
    private fun parseForbid(s: String): List<IntArray> {
        val result = mutableListOf<IntArray>()
        s.chunked(4).forEach { chunk ->
            if (chunk.length == 4 && chunk.all { it.isDigit() }) {
                result.add(intArrayOf(chunk.substring(0, 2).toInt(), chunk.substring(2, 4).toInt()))
            }
        }
        return result
    }
}
