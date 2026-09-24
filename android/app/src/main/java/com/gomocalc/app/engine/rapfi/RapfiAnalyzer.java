package com.gomocalc.app.engine.rapfi;

import com.gomocalc.app.engine.EngineEvent;
import com.gomocalc.app.model.AnalysisPoint;
import com.gomocalc.app.model.AnalysisState;
import com.gomocalc.app.model.BoardPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges raw Rapfi stdout lines into {@link AnalysisState} snapshots.
 *
 * <p>Rapfi reports eval and winrate from the side-to-move perspective; this class converts both
 * to black's perspective as required by the stable {@link AnalysisState} contract. Rapfi is an
 * alpha-beta engine: it reports node counts, not MCTS visit counts, so {@code visits} stays 0.</p>
 */
final class RapfiAnalyzer {

    private static final class Pv {
        int depth;
        int seldepth;
        long nodes;
        Double score;       // black-perspective eval, null when unknown/non-numeric
        Double blackWinRate;
        List<BoardPoint> line = new ArrayList<>();
    }

    private final int sideToMove;   // 1 black, 2 white
    private final List<Pv> pvs = new ArrayList<>();
    private int currentPv;
    private Pv current;
    private long totalNodes;
    private long speed;
    private final List<BoardPoint> forbidden = new ArrayList<>();
    private BoardPoint bestMove;

    RapfiAnalyzer(int sideToMove) {
        this.sideToMove = sideToMove;
    }

    /**
     * Feed one raw stdout line. Returns an event to publish, or null when the line carried no
     * complete snapshot.
     */
    EngineEvent onLine(String line) {
        if (line == null || line.isEmpty())
            return null;

        int i = line.indexOf(' ');
        if (i == -1) {
            if (RapfiProtocol.OK.equals(line))
                return null;
            if (RapfiProtocol.SWAP.equals(line))
                return null;
            // Final best move: "x,y"
            BoardPoint p = parsePoint(line);
            if (p != null) {
                bestMove = p;
                return EngineEvent.of(EngineEvent.Type.FINISHED, snapshot());
            }
            return null;
        }

        String head = line.substring(0, i);
        String tail = line.substring(i + 1);

        if (RapfiProtocol.FORBID.equals(head)) {
            parseForbid(tail, forbidden);
            return null;
        }
        if (RapfiProtocol.ERROR.equals(head)) {
            return EngineEvent.error(tail);
        }
        if (!RapfiProtocol.INFO.equals(head))
            return null;

        int j = tail.indexOf(' ');
        if (j == -1)
            return null;
        String key = tail.substring(0, j);
        String val = tail.substring(j + 1);

        switch (key) {
            case RapfiProtocol.PV:
                if ("DONE".equals(val)) {
                    return finishCurrentPv();
                }
                currentPv = parseInt(val, 0);
                current = ensurePv(currentPv);
                return null;

            case RapfiProtocol.DEPTH:
                if (current != null) current.depth = parseInt(val, 0);
                return null;

            case RapfiProtocol.SELDEPTH:
                if (current != null) current.seldepth = parseInt(val, 0);
                return null;

            case RapfiProtocol.NODES:
                if (current != null) current.nodes = parseLong(val, 0L);
                return null;

            case RapfiProtocol.TOTALNODES:
                totalNodes = parseLong(val, 0L);
                return null;

            case RapfiProtocol.SPEED:
                speed = parseLong(val, 0L);
                return null;

            case RapfiProtocol.EVAL:
                if (current != null) current.score = toBlackScore(parseEval(val));
                return null;

            case RapfiProtocol.WINRATE:
                if (current != null) current.blackWinRate = toBlackWinRate(parseDouble(val));
                return null;

            case RapfiProtocol.BESTLINE:
                if (current != null) current.line = parseBestline(val);
                return null;

            default:
                return null;
        }
    }

    /** Called when {@code INFO PV DONE} closes the current multi-PV line. */
    private EngineEvent finishCurrentPv() {
        if (current != null)
            current = null;
        return EngineEvent.of(EngineEvent.Type.UPDATE, snapshot());
    }

    private Pv ensurePv(int index) {
        while (pvs.size() <= index)
            pvs.add(new Pv());
        return pvs.get(index);
    }

    private AnalysisState snapshot() {
        AnalysisState.Builder b = new AnalysisState.Builder();
        b.depth(current != null ? current.depth : (pvs.isEmpty() ? 0 : pvs.get(0).depth));
        if (current != null) {
            b.score(current.score);
            b.blackWinRate(current.blackWinRate);
        } else if (!pvs.isEmpty()) {
            b.score(pvs.get(0).score);
            b.blackWinRate(pvs.get(0).blackWinRate);
        }
        b.nodes(totalNodes);
        b.visits(0L);                    // alpha-beta engine: no MCTS visit counts
        b.visitsPerSecond((double) speed);
        b.candidates(candidates());
        b.heatmap(new ArrayList<>());
        b.killPoints(new ArrayList<>());
        b.principalVariation(!pvs.isEmpty() ? pvs.get(0).line : new ArrayList<>());
        b.forbiddenPoints(forbidden);
        b.bestMove(bestMove);
        return b.build();
    }

    private List<AnalysisPoint> candidates() {
        List<AnalysisPoint> out = new ArrayList<>();
        for (Pv pv : pvs) {
            if (pv.line.isEmpty())
                continue;
            out.add(new AnalysisPoint(pv.line.get(0), pv.score, pv.blackWinRate, 0L, pv.line));
        }
        return out;
    }

    // ---- black-perspective conversion ----
    private Double toBlackScore(Double sideToMoveEval) {
        if (sideToMoveEval == null)
            return null;
        return sideToMove == 1 ? sideToMoveEval : -sideToMoveEval;
    }

    private Double toBlackWinRate(Double sideToMoveWinRate) {
        if (sideToMoveWinRate == null)
            return null;
        return sideToMove == 1 ? sideToMoveWinRate : 1.0 - sideToMoveWinRate;
    }

    // ---- primitive parsers ----
    private static BoardPoint parsePoint(String s) {
        String[] parts = s.split(",");
        if (parts.length < 2)
            return null;
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            if (x < 0 || y < 0)
                return null;
            return new BoardPoint(x, y);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<BoardPoint> parseBestline(String s) {
        List<BoardPoint> out = new ArrayList<>();
        for (String part : s.split("\\s+")) {
            BoardPoint p = parsePoint(part);
            if (p != null)
                out.add(p);
        }
        return out;
    }

    private static void parseForbid(String s, List<BoardPoint> out) {
        out.clear();
        // Rapfi prints each forbidden point as four digits "xxyy", a trailing '.' ends the list.
        for (int k = 0; k + 4 <= s.length(); k += 4) {
            String chunk = s.substring(k, k + 4);
            boolean digits = true;
            for (int c = 0; c < 4; c++)
                if (!Character.isDigit(chunk.charAt(c))) digits = false;
            if (!digits)
                continue;
            try {
                int x = Integer.parseInt(chunk.substring(0, 2));
                int y = Integer.parseInt(chunk.substring(2, 4));
                out.add(new BoardPoint(x, y));
            } catch (NumberFormatException ignored) {
                // skip malformed chunk
            }
        }
    }

    /** Eval may be numeric, a mate score ({@code +M2}/{@code -M2}) or {@code VAL_NONE}. */
    private static Double parseEval(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            String t = s.trim();
            if (t.startsWith("+M")) {
                try { return 40000.0 - Double.parseDouble(t.substring(2)); }
                catch (NumberFormatException ignored) { }
            } else if (t.startsWith("-M")) {
                try { return -40000.0 + Double.parseDouble(t.substring(2)); }
                catch (NumberFormatException ignored) { }
            }
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
