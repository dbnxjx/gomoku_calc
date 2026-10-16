package com.gomocalc.app.engine.katago;

import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Capacitor transport for the ARM64 KataGo executable. The executable is kept in the APK native
 * library directory; model/config files are copied to app-private storage and only the GTP
 * process communicates with the Web UI.
 */
@CapacitorPlugin(name = "NativeKataGo")
public final class NativeKataGoPlugin extends Plugin {
    private static final String CONFIG_ASSET = "katago/gtp.cfg";
    // Keep the compressed model compressed on disk. KataGo's model loader supports .bin.gz,
    // which avoids expanding another ~290 MB copy in the app's private storage.
    // Use a neutral asset suffix because Android Gradle Plugin auto-decompresses .gz assets.
    // The bytes remain gzip-compressed and are copied to the .bin.gz path KataGo expects.
    private static final String MODEL_ASSET = "katago/zhizi_renju28b_s1600.model";
    private static final String MODEL_NAME = "zhizi_renju28b_s1600.bin.gz";
    private static final long MODEL_LENGTH = 269873929L;
    private static final String CONFIG_NAME = "gtp.cfg";
    private static final String COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";
    private static final int ANALYSIS_INTERVAL_CS = 10;
    private static final String BENCHMARK_PREFS = "katago-benchmark";
    private static final int BENCHMARK_VERSION = 3;
    private static final int BENCHMARK_VISITS = 64;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object writeLock = new Object();
    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean ready;
    private volatile boolean analysisRunning;
    private volatile boolean moveResponseExpected;
    private volatile boolean responsePending;
    private volatile boolean protocolVersionReceived;
    private volatile int readyResponseCount;
    private volatile int processExitCode = Integer.MIN_VALUE;
    private volatile long analysisStartedNanos;
    private volatile int analysisMaxMoves = 10;
    private volatile String startupError;
    private volatile String lastOutputLine;
    private volatile boolean benchmarkRunning;
    private volatile Process benchmarkProcess;
    private volatile long analysisId;
    private volatile CountDownLatch pendingAnalysisStopResponse;
    private volatile String pendingAnalysisStopResponseId;
    private volatile boolean stopResponseMatched;
    private volatile String stopResponseError;
    private int nextGtpCommandId = 1;
    private final Map<String, HeatPoint> heatmap = new LinkedHashMap<>();
    private final Map<String, Long> lastDebugByRank = new HashMap<>();
    private volatile int boardSize = 15;
    private volatile String sideToMove = "B";

    @PluginMethod
    public void initialize(PluginCall call) {
        io.execute(() -> {
            try {
                startProcess();
                awaitReady();
                ready = true;
                emitStatus("ready");
                resolve(call, new JSObject().put("engineType", "KATAGO"));
            } catch (Exception error) {
                ready = false;
                closeProcess();
                reject(call, "Unable to initialize native KataGo: " + error.getMessage(), error);
            }
        });
    }

    @PluginMethod
    public void sendCommand(PluginCall call) {
        String command = call.getString("command");
        if (command == null || command.trim().isEmpty()) {
            call.reject("sendCommand requires a non-empty command string");
            return;
        }
        io.execute(() -> {
            try {
                ensureReady();
                forward(command.trim());
                resolve(call, null);
            } catch (Exception error) {
                reject(call, "Native KataGo command failed: " + error.getMessage(), error);
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        io.execute(() -> {
            try {
                if (writer != null) stopAnalysisAndAwait();
                resolve(call, null);
            } catch (Exception error) {
                reject(call, "Native KataGo stop failed: " + error.getMessage(), error);
            }
        });
    }

    @PluginMethod
    public void benchmark(PluginCall call) {
        synchronized (this) {
            if (benchmarkRunning) {
                call.reject("KataGo benchmark is already running");
                return;
            }
            benchmarkRunning = true;
        }
        resolve(call, new JSObject().put("started", true));
        io.execute(this::runBenchmark);
    }

    private void startProcess() throws IOException {
        closeProcess();
        File engine = new File(getContext().getApplicationInfo().nativeLibraryDir, "libkatago.so");
        if (!engine.isFile() || !engine.canExecute()) {
            throw new IOException("KataGo executable is missing or not executable: " + engine);
        }
        File directory = new File(getContext().getFilesDir(), "katago-engine");
        if (!directory.exists() && !directory.mkdirs())
            throw new IOException("Cannot create " + directory);
        File config = copyAsset(CONFIG_ASSET, new File(directory, CONFIG_NAME), true);
        File model = copyModel(new File(directory, MODEL_NAME));
        File home = new File(directory, "katago-home");
        if (!home.exists() && !home.mkdirs()) throw new IOException("Cannot create " + home);

        List<String> command = new ArrayList<>();
        command.add(engine.getAbsolutePath());
        command.add("gtp");
        command.add("-model");
        command.add(model.getAbsolutePath());
        command.add("-config");
        command.add(config.getAbsolutePath());
        BenchmarkConfig best = loadBestConfig();
        if (best != null) {
            command.add("-override-config");
            command.add(best.toOverrideConfig());
            emit("debug", String.format(Locale.US,
                    "使用已保存的最快配置：搜索线程 %d，后端线程 %d，批大小 %d，FP32，%.2f visits/s",
                    best.searchThreads, best.nnServerThreads, best.batchSize, best.visitsPerSecond));
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(engine.getParentFile())
                .redirectErrorStream(true);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("USERPROFILE", home.getAbsolutePath());
        builder.environment().put("XDG_CACHE_HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", directory.getAbsolutePath());
        Process started = builder.start();
        process = started;
        processExitCode = Integer.MIN_VALUE;
        lastOutputLine = null;
        readyResponseCount = 0;
        responsePending = false;
        protocolVersionReceived = false;
        moveResponseExpected = false;
        startupError = null;
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> readOutput(started), "katago-output");
        reader.setDaemon(true);
        reader.start();
        // Wait for one explicit GTP response before sending the remaining setup commands. This
        // avoids treating interleaved KataGo startup diagnostics as handshake responses.
        writeLine("protocol_version");
    }

    private void readOutput(Process activeProcess) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                activeProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (process != activeProcess) return;
                lastOutputLine = line;
                if (!line.trim().isEmpty() && shouldEmitDebug(line)) emit("debug", line);
                if (line.startsWith("info ")) {
                    if (analysisRunning) {
                        long outputAnalysisId = analysisId;
                        for (String normalized : normalizeAnalysisLines(line)) {
                            emitAnalysis(normalized, outputAnalysisId);
                        }
                    }
                } else {
                    if (line.startsWith("=") || line.startsWith("?")) {
                        responsePending = true;
                        if (isPendingStopResponse(line)) {
                            stopResponseMatched = true;
                            if (line.startsWith("?")) stopResponseError = line.substring(1).trim();
                        }
                    }
                    else if (line.trim().isEmpty() && responsePending) {
                        responsePending = false;
                        readyResponseCount++;
                        if (!protocolVersionReceived) protocolVersionReceived = true;
                        if (stopResponseMatched) {
                            CountDownLatch stopResponse = pendingAnalysisStopResponse;
                            if (stopResponse != null) stopResponse.countDown();
                            stopResponseMatched = false;
                        }
                    }
                    if (line.startsWith("?")) {
                        String message = line.substring(1).trim();
                        if (!ready) startupError = message;
                        emit("stdout", "ERROR " + message);
                    } else if (moveResponseExpected && line.startsWith("= ")) {
                        String vertex = line.substring(2).trim();
                        if (isGtpVertex(vertex)) {
                            emit("stdout", toPoint(vertex));
                            moveResponseExpected = false;
                        }
                    } else {
                        emit("stdout", line);
                    }
                }
            }
        } catch (IOException error) {
            if (process == activeProcess) emit("stderr", error.toString());
        } finally {
            if (process == activeProcess) {
                try {
                    processExitCode = activeProcess.exitValue();
                } catch (IllegalThreadStateException ignored) {
                    processExitCode = Integer.MIN_VALUE;
                }
                ready = false;
                analysisRunning = false;
                CountDownLatch stopResponse = pendingAnalysisStopResponse;
                if (stopResponse != null) stopResponse.countDown();
                emit("exit", processExitCode == Integer.MIN_VALUE
                        ? "process exited"
                        : "process exited with code " + processExitCode);
            }
        }
    }

    /** Convert the existing Web command stream to KataGo GTP. */
    private void forward(String command) throws IOException {
        String[] parts = command.split("\\s+");
        if (parts.length == 0) return;
        String head = parts[0].toUpperCase(Locale.US);
        if ("START".equals(head) && parts.length == 2) {
            boardSize = 15;
            stopAnalysisAndAwait();
            writeLine("boardsize 15");
            writeLine("clear_board");
            sideToMove = "B";
            return;
        }
        if (KataGoProtocol.YXBOARD.equals(head) || "BOARD".equals(head)
                || KataGoProtocol.SET_POSITION.equals(head)) {
            replacePosition(parts, 1);
            return;
        }
        if (KataGoProtocol.YXNBEST.equals(head)) {
            int n = parts.length > 1 ? Math.min(20, parsePositive(parts[1], 3)) : 3;
            stopAnalysisAndAwait();
            analysisId = parts.length > 2 ? parseAnalysisId(parts[2]) : analysisId + 1;
            analysisRunning = true;
            analysisMaxMoves = n;
            synchronized (heatmap) { heatmap.clear(); }
            synchronized (lastDebugByRank) { lastDebugByRank.clear(); }
            analysisStartedNanos = System.nanoTime();
            emit("debug", "GTP > kata-analyze " + sideToMove + " " + ANALYSIS_INTERVAL_CS
                    + " maxmoves " + n + " pvVisits true");
            writeLine("kata-analyze " + sideToMove + " " + ANALYSIS_INTERVAL_CS
                    + " maxmoves " + n + " pvVisits true");
            return;
        }
        if (KataGoProtocol.YXSTOP.equals(head)) {
            stopAnalysisAndAwait();
            return;
        }
        if ("INFO".equals(head)) return;
        if (KataGoProtocol.PLAY.equals(head) || KataGoProtocol.UNDO.equals(head)
                || "CLEAR_BOARD".equals(head)) {
            if (analysisRunning) moveResponseExpected = false;
            stopAnalysisAndAwait();
        }
        if (KataGoProtocol.PLAY.equals(head) && parts.length >= 3) {
            sideToMove = "B".equalsIgnoreCase(parts[1]) ? "W" : "B";
            if (parts.length == 3 && parts[2].contains(",")) {
                String[] point = parts[2].split(",");
                try {
                    writeLine("play " + parts[1] + " "
                            + toVertex(Integer.parseInt(point[0]), Integer.parseInt(point[1])));
                    return;
                } catch (NumberFormatException error) {
                    throw new IOException("Invalid PLAY point: " + parts[2], error);
                }
            }
            if (parts.length == 3) {
                writeLine("play " + parts[1] + " " + parts[2]);
                return;
            }
        } else if ("CLEAR_BOARD".equals(head)) {
            sideToMove = "B";
            writeLine("clear_board");
            return;
        } else if (KataGoProtocol.UNDO.equals(head)) {
            sideToMove = sideToMove.equals("B") ? "W" : "B";
            writeLine("undo");
            return;
        }
        writeLine(command);
    }

    private void replacePosition(String[] parts, int offset) throws IOException {
        List<String> stones = new ArrayList<>();
        int black = 0;
        int white = 0;
        for (int i = offset; i < parts.length && !"DONE".equalsIgnoreCase(parts[i]); i++) {
            String[] p = parts[i].split(",");
            if (p.length != 3) continue;
            try {
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                int color = Integer.parseInt(p[2]);
                if (x < 0 || y < 0 || x >= boardSize || y >= boardSize
                        || (color != 1 && color != 2)) continue;
                if (color == 1) black++;
                else white++;
                stones.add((color == 1 ? "B" : "W") + " " + toVertex(x, y));
            } catch (NumberFormatException ignored) { }
        }
        sideToMove = black <= white ? "B" : "W";
        stopAnalysisAndAwait();
        if (stones.isEmpty()) writeLine("clear_board");
        else writeLine("set_position " + String.join(" ", stones));
    }

    private List<String> normalizeAnalysisLines(String line) {
        List<String> output = new ArrayList<>();
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length == 0 || !"info".equals(tokens[0])) {
            output.add(line);
            return output;
        }

        int segmentStart = 0;
        for (int i = 1; i <= tokens.length; i++) {
            if (i < tokens.length && !"info".equals(tokens[i])) continue;
            normalizeAnalysisSegment(tokens, segmentStart, i, output);
            segmentStart = i;
        }
        return output;
    }

    private void normalizeAnalysisSegment(String[] tokens, int start, int end, List<String> output) {
        if (start >= end || !"info".equals(tokens[start])) return;
        String move = null;
        String visits = null;
        String winrate = null;
        String order = null;
        List<String> pv = new ArrayList<>();
        for (int i = start + 1; i + 1 < end; i++) {
            String key = tokens[i];
            String value = tokens[++i];
            if ("move".equals(key)) move = value;
            else if ("visits".equals(key)) visits = value;
            else if ("winrate".equals(key)) winrate = value;
            else if ("order".equals(key)) order = value;
            else if ("pv".equals(key)) {
                pv.add(value);
                while (i + 1 < end && !isKey(tokens[i + 1])) pv.add(tokens[++i]);
            }
        }
        if (move == null || "pass".equalsIgnoreCase(move)) return;
        output.add("INFO PV 0");
        if (visits != null) {
            output.add("INFO NODES " + visits);
            output.add("INFO TOTALNODES " + visits);
        }
        Long visitCount = parseLong(visits);
        int rank = order == null ? 0 : parseRank(order);
        if (rank > 0) output.add("INFO RANK " + rank);
        Double rate = parseNumber(winrate);
        if (rate != null) {
            output.add("INFO WINRATE " + String.format(Locale.US, "%.6f", rate));
            output.add("INFO BLACK_WINRATE " + String.format(Locale.US, "%.6f", rate));
        }
        StringBuilder out = new StringBuilder("INFO BESTLINE ");
        for (int i = 0; i < pv.size(); i++) {
            if (i > 0) out.append(' ');
            out.append(toPoint(pv.get(i), boardSize));
        }
        if (pv.isEmpty()) out.append(toPoint(move, boardSize));
        output.add(out.toString());
        if (rate != null) {
            output.add("INFO EVAL " + String.format(Locale.US, "%.6f", rate - 0.5));
        }
        if (rate != null && visitCount != null) {
            String point = toPoint(move, boardSize);
            synchronized (heatmap) {
                String[] coordinates = point.split(",");
                if (coordinates.length == 2) {
                    String candidateKey = point;
                    HeatPoint previous = heatmap.get(candidateKey);
                    int pointRank = rank > 0 ? rank : previous != null ? previous.rank : heatmap.size() + 1;
                    heatmap.put(candidateKey, new HeatPoint(Integer.parseInt(coordinates[0]),
                            Integer.parseInt(coordinates[1]), rate, visitCount, pointRank));
                    trimHeatmap();
                }
                if (analysisStartedNanos != 0L) {
                    long totalVisits = 0L;
                    for (HeatPoint candidate : heatmap.values()) totalVisits += candidate.visits;
                    double seconds = Math.max(0.001, (System.nanoTime() - analysisStartedNanos) / 1e9);
                    output.add(String.format(Locale.US, "INFO SPEED %.3f", totalVisits / seconds));
                }
                output.add("HEATMAP " + serializeHeatmap());
            }
        }
        output.add("INFO PV DONE");
    }

    private static boolean isKey(String token) {
        return "visits".equals(token) || "utility".equals(token) || "winrate".equals(token)
                || "scoreMean".equals(token) || "scoreStdev".equals(token) || "scoreLead".equals(token)
                || "prior".equals(token) || "lcb".equals(token) || "utilityLcb".equals(token)
                || "weight".equals(token) || "order".equals(token) || "pv".equals(token)
                || "pvVisits".equals(token);
    }

    private static String toPoint(String vertex) { return toPoint(vertex, 15); }

    private static String toPoint(String vertex, int size) {
        if (vertex == null || vertex.length() < 2) return "";
        int x = COLUMNS.indexOf(Character.toUpperCase(vertex.charAt(0)));
        try {
            int row = Integer.parseInt(vertex.substring(1));
            if (x < 0 || x >= size || row < 1 || row > size) return "";
            return x + "," + (size - row);
        }
        catch (NumberFormatException error) { return ""; }
    }

    private String serializeHeatmap() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        List<HeatPoint> points = new ArrayList<>(heatmap.values());
        sortHeatPoints(points);
        Map<Integer, Boolean> usedRanks = new HashMap<>();
        int nextRank = 1;
        for (HeatPoint point : points) {
            if (!first) json.append(',');
            first = false;
            int rank = point.rank;
            if (rank < 1 || usedRanks.containsKey(rank)) {
                while (usedRanks.containsKey(nextRank)) nextRank++;
                rank = nextRank++;
            }
            usedRanks.put(rank, true);
            json.append('[').append(point.x).append(',').append(point.y).append(',')
                    .append(String.format(Locale.US, "%.6f", point.winrate)).append(',')
                    .append(point.visits).append(',').append(rank).append(']');
        }
        return json.append(']').toString();
    }

    private void trimHeatmap() {
        List<HeatPoint> points = new ArrayList<>(heatmap.values());
        sortHeatPoints(points);
        for (int i = analysisMaxMoves; i < points.size(); i++) {
            HeatPoint point = points.get(i);
            heatmap.remove(point.x + "," + point.y);
        }
    }

    private static void sortHeatPoints(List<HeatPoint> points) {
        points.sort((a, b) -> {
            int rankA = a.rank > 0 ? a.rank : Integer.MAX_VALUE;
            int rankB = b.rank > 0 ? b.rank : Integer.MAX_VALUE;
            if (rankA != rankB) return Integer.compare(rankA, rankB);
            return Long.compare(b.visits, a.visits);
        });
    }

    private boolean shouldEmitDebug(String line) {
        if (!line.startsWith("info ")) return true;
        String key = null;
        String[] tokens = line.split("\\s+");
        for (int i = 1; i + 1 < tokens.length; i++) {
            if ("order".equals(tokens[i])) {
                key = "rank:" + parseRank(tokens[i + 1]);
                break;
            }
            if ("move".equals(tokens[i])) key = "move:" + tokens[i + 1];
        }
        if (key == null) key = "info";
        long now = System.nanoTime();
        synchronized (lastDebugByRank) {
            Long previous = lastDebugByRank.get(key);
            if (previous != null && now - previous < 250_000_000L) return false;
            lastDebugByRank.put(key, now);
        }
        return true;
    }

    private static Double parseNumber(String value) {
        if (value == null) return null;
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) return null;
            // KataGo's default GTP protocol emits winrate as a fraction. This fork's
            // original lz-analyze mode may emit basis points, so normalize both forms.
            return number > 100.0 ? number / 10000.0 : number > 1.0 ? number / 100.0 : number;
        } catch (NumberFormatException error) { return null; }
    }

    private static Long parseLong(String value) {
        if (value == null) return null;
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { return null; }
    }

    private static int parseRank(String value) {
        try {
            // KataGo's `order` is zero-based; the UI displays candidate numbers from one.
            return Math.max(1, Integer.parseInt(value) + 1);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static final class HeatPoint {
        final int x;
        final int y;
        final double winrate;
        final long visits;
        final int rank;

        HeatPoint(int x, int y, double winrate, long visits, int rank) {
            this.x = x;
            this.y = y;
            this.winrate = winrate;
            this.visits = visits;
            this.rank = rank;
        }
    }

    private static boolean isGtpVertex(String vertex) {
        if (vertex == null || vertex.length() < 2) return false;
        int x = COLUMNS.indexOf(Character.toUpperCase(vertex.charAt(0)));
        try {
            int row = Integer.parseInt(vertex.substring(1));
            return x >= 0 && x < COLUMNS.length() && row > 0 && row <= COLUMNS.length();
        } catch (NumberFormatException error) { return false; }
    }

    private String toVertex(int x, int y) {
        if (x < 0 || y < 0 || x >= boardSize || y >= boardSize || boardSize > COLUMNS.length())
            throw new IllegalArgumentException("Point is outside the supported board");
        return COLUMNS.charAt(x) + Integer.toString(boardSize - y);
    }

    private File copyAsset(String assetName, File target, boolean always) throws IOException {
        if (!always && target.isFile() && target.length() == MODEL_LENGTH) return target;
        File part = new File(target.getPath() + ".part");
        try (InputStream input = getContext().getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(part)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
        if (!part.renameTo(target)) throw new IOException("Cannot rename " + part);
        return target;
    }

    private File copyModel(File target) throws IOException {
        try {
            return copyAsset(MODEL_ASSET, target, false);
        } catch (IOException primary) {
            // Some Android Gradle Plugin versions preserve the .gz suffix in AssetManager,
            // while others expose a noCompress .bin entry. Support both package layouts.
            String alternate = "katago/zhizi_renju28b_s1600.bin.gz";
            if (MODEL_ASSET.equals(alternate)) throw primary;
            try {
                return copyAsset(alternate, target, false);
            } catch (IOException ignored) {
                primary.addSuppressed(ignored);
                throw primary;
            }
        }
    }

    private void awaitReady() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (process != null && process.isAlive() && System.currentTimeMillis() < deadline) {
            if (startupError != null) throw new IOException("KataGo startup failed: " + startupError);
            Thread.sleep(100L);
            if (protocolVersionReceived) {
                // The engine is fully initialized once protocol_version has answered. The setup
                // commands can be queued now without making page initialization depend on five
                // separate response timing windows.
                writeLine("name");
                writeLine("version");
                writeLine("boardsize 15");
                writeLine("clear_board");
                return;
            }
        }
        if (startupError != null) throw new IOException("KataGo startup failed: " + startupError);
        String detail = lastOutputLine == null ? "no engine output" : lastOutputLine;
        if (processExitCode != Integer.MIN_VALUE) {
            throw new IOException("KataGo exited with code " + processExitCode + ": " + detail);
        }
        throw new IOException("KataGo did not become ready: " + detail);
    }

    private void emit(String event, String value) {
        mainHandler.post(() -> notifyListeners(event, new JSObject().put("output", value)));
    }

    private void emitAnalysis(String value, long id) {
        mainHandler.post(() -> notifyListeners("stdout", new JSObject()
                .put("output", value).put("analysisId", id)));
    }

    private void emitStatus(String value) {
        mainHandler.post(() -> notifyListeners("status", new JSObject().put("value", value)));
    }

    private void resolve(PluginCall call, JSObject value) {
        mainHandler.post(() -> { if (value == null) call.resolve(); else call.resolve(value); });
    }

    private void reject(PluginCall call, String message, Exception error) {
        mainHandler.post(() -> call.reject(message, error));
    }

    private void writeLine(String command) throws IOException {
        synchronized (writeLock) {
            if (writer == null) throw new IOException("KataGo process is not running");
            writer.write(command);
            writer.newLine();
            writer.flush();
        }
    }

    private void stopAnalysisAndAwait() throws IOException {
        if (!analysisRunning) return;
        CountDownLatch response = new CountDownLatch(1);
        int commandId = nextGtpCommandId++;
        pendingAnalysisStopResponse = response;
        pendingAnalysisStopResponseId = Integer.toString(commandId);
        stopResponseMatched = false;
        stopResponseError = null;
        try {
            writeLine(commandId + " stop");
            if (!response.await(15, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for KataGo analysis to stop");
            }
            if (stopResponseError != null) throw new IOException("KataGo rejected stop: " + stopResponseError);
            analysisRunning = false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping KataGo analysis", error);
        } finally {
            if (pendingAnalysisStopResponse == response) pendingAnalysisStopResponse = null;
            pendingAnalysisStopResponseId = null;
            stopResponseMatched = false;
            stopResponseError = null;
        }
    }

    private boolean isPendingStopResponse(String line) {
        String expectedId = pendingAnalysisStopResponseId;
        if (expectedId == null || line.length() < 2) return false;
        String responseId = line.substring(1).trim().split("\\s+", 2)[0];
        return expectedId.equals(responseId);
    }

    private void ensureReady() throws IOException {
        if (!ready || process == null || !process.isAlive()) throw new IOException("KataGo is not ready");
    }

    private void closeProcess() {
        Process active = process;
        process = null;
        ready = false;
        analysisRunning = false;
        CountDownLatch stopResponse = pendingAnalysisStopResponse;
        if (stopResponse != null) stopResponse.countDown();
        if (active != null) active.destroy();
        writer = null;
    }

    private static int parsePositive(String text, int fallback) {
        try { return Math.max(1, Integer.parseInt(text)); }
        catch (NumberFormatException error) { return fallback; }
    }

    private static long parseAnalysisId(String text) {
        try { return Math.max(0L, Long.parseLong(text)); }
        catch (NumberFormatException error) { return 0L; }
    }

    private void runBenchmark() {
        notifyBenchmark("开始自动测速：固定使用 OpenCL FP32；每组配置先预热，再执行 64 visits 搜索。", true);
        ready = false;
        closeProcess();
        BenchmarkConfig[] candidates = new BenchmarkConfig[]{
                new BenchmarkConfig(1, 1, 1),
                new BenchmarkConfig(2, 1, 4),
                new BenchmarkConfig(4, 1, 4),
                new BenchmarkConfig(4, 2, 8),
                new BenchmarkConfig(8, 1, 8),
                new BenchmarkConfig(8, 2, 8),
                new BenchmarkConfig(8, 2, 4),
                new BenchmarkConfig(8, 3, 8)
        };
        BenchmarkConfig best = null;
        try {
            File engine = new File(getContext().getApplicationInfo().nativeLibraryDir, "libkatago.so");
            File directory = new File(getContext().getFilesDir(), "katago-engine");
            File config = copyAsset(CONFIG_ASSET, new File(directory, CONFIG_NAME), true);
            File model = copyModel(new File(directory, MODEL_NAME));
            for (int i = 0; i < candidates.length; i++) {
                BenchmarkConfig candidate = candidates[i];
                notifyBenchmark(String.format(Locale.US,
                        "测速 %d/%d：搜索线程 %d，后端线程 %d，批大小 %d...",
                        i + 1, candidates.length, candidate.searchThreads,
                        candidate.nnServerThreads, candidate.batchSize), true);
                try {
                    candidate.visitsPerSecond = benchmarkOne(engine, config, model, candidate);
                    notifyBenchmark(String.format(Locale.US,
                            "FP32，实际 %d visits，结果 %.2f visits/s",
                            candidate.rootVisits, candidate.visitsPerSecond), true);
                    if (best == null || candidate.visitsPerSecond > best.visitsPerSecond) best = candidate;
                } catch (Exception error) {
                    notifyBenchmark("该组合失败：" + error.getMessage(), true);
                }
            }
            if (best == null) throw new IOException("所有测速组合都失败");
            saveBestConfig(best);
            notifyBenchmark(String.format(Locale.US,
                    "测速完成，最佳配置：搜索线程 %d，后端线程 %d，批大小 %d，%.2f visits/s。已保存并自动重启引擎。",
                    best.searchThreads, best.nnServerThreads, best.batchSize, best.visitsPerSecond), true);
        } catch (Exception error) {
            notifyBenchmark("测速失败：" + error.getMessage(), true);
        } finally {
            try {
                startProcess();
                awaitReady();
                ready = true;
                emitStatus("ready");
                notifyBenchmark("KataGo 引擎已就绪。", false);
            } catch (Exception error) {
                ready = false;
                emit("stderr", "测速后无法重启 KataGo：" + error.getMessage());
                notifyBenchmark("测速后无法重启 KataGo：" + error.getMessage(), false);
            }
            benchmarkRunning = false;
        }
    }

    private double benchmarkOne(File engine, File config, File model, BenchmarkConfig candidate)
            throws IOException, InterruptedException {
        String override = candidate.toOverrideConfig()
                + ",maxVisits=" + BENCHMARK_VISITS
                + ",nnCacheSizePowerOfTwo=-1,maxTime=120,logToStderr=true"
                + ",logSearchInfo=false,startupPrintMessageToStderr=false";
        ProcessBuilder builder = new ProcessBuilder(engine.getAbsolutePath(), "gtp",
                "-model", model.getAbsolutePath(), "-config", config.getAbsolutePath(),
                "-override-config", override)
                .directory(engine.getParentFile()).redirectErrorStream(true);
        File home = new File(config.getParentFile(), "katago-home");
        if (!home.exists() && !home.mkdirs()) throw new IOException("Cannot create " + home);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("USERPROFILE", home.getAbsolutePath());
        builder.environment().put("XDG_CACHE_HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", config.getParentFile().getAbsolutePath());
        Process active = builder.start();
        benchmarkProcess = active;
        try (BenchmarkSession session = new BenchmarkSession(active)) {
            sendBenchmarkCommand(session, "protocol_version");
            sendBenchmarkCommand(session, "name");
            sendBenchmarkCommand(session, "version");
            sendBenchmarkCommand(session, "boardsize 15");
            sendBenchmarkCommand(session, "clear_board");
            sendBenchmarkCommand(session, "play B A1");
            sendBenchmarkCommand(session, "play W C2");
            sendBenchmarkCommand(session, "play B A3");
            sendBenchmarkCommand(session, "play W C3");
            sendBenchmarkCommand(session, "search_debug B");
            sendBenchmarkCommand(session, "clear_board");
            sendBenchmarkCommand(session, "play B H8");
            sendBenchmarkCommand(session, "play W G7");
            sendBenchmarkCommand(session, "play B J8");
            sendBenchmarkCommand(session, "play W F7");
            session.lastRootVisits = -1L;
            long started = System.nanoTime();
            String response = sendBenchmarkCommand(session, "search_debug B");
            long elapsed = System.nanoTime() - started;
            if (response.startsWith("?")) throw new IOException(response);
            if (session.lastRootVisits <= 0) throw new IOException("search_debug 没有返回 Root visits");
            candidate.rootVisits = session.lastRootVisits;
            return session.lastRootVisits / Math.max(elapsed / 1_000_000_000.0, 0.001);
        } finally {
            benchmarkProcess = null;
        }
    }

    private String sendBenchmarkCommand(BenchmarkSession session, String command)
            throws IOException, InterruptedException {
        session.writer.write(command);
        session.writer.newLine();
        session.writer.flush();
        String response = null;
        StringBuilder diagnostics = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("GTP 命令超时（90 秒）");
            String line = session.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null) throw new IOException("GTP 命令超时（90 秒）");
            if (line.startsWith("=") || line.startsWith("?")) response = line;
            else if ("[benchmark-eof]".equals(line)) {
                throw new IOException(diagnostics.length() == 0 ? "KataGo 进程提前退出" : diagnostics.toString());
            } else if (line.startsWith("Root visits:")) {
                try { session.lastRootVisits = Long.parseLong(line.substring("Root visits:".length()).trim()); }
                catch (NumberFormatException ignored) { session.lastRootVisits = -1L; }
            } else if ((line.contains("ERROR") || line.contains("Error") || line.startsWith("[reader]"))
                    && diagnostics.length() < 800) {
                diagnostics.append(line).append(' ');
            }
            if (response != null && line.trim().isEmpty()) {
                if (response.startsWith("?")) throw new IOException(response + " " + diagnostics);
                return response;
            }
        }
    }

    private BenchmarkConfig loadBestConfig() {
        android.content.SharedPreferences preferences = getContext().getSharedPreferences(BENCHMARK_PREFS, 0);
        if (preferences.getInt("benchmarkVersion", 0) != BENCHMARK_VERSION) return null;
        int searchThreads = preferences.getInt("searchThreads", 0);
        int nnServerThreads = preferences.getInt("nnServerThreads", 0);
        int batchSize = preferences.getInt("batchSize", 0);
        float speed = preferences.getFloat("visitsPerSecond", 0f);
        if (searchThreads < 1 || searchThreads > 64 || nnServerThreads < 1 || nnServerThreads > 8
                || batchSize < 1 || batchSize > 64 || Float.isNaN(speed) || Float.isInfinite(speed)) return null;
        return new BenchmarkConfig(searchThreads, nnServerThreads, batchSize, speed);
    }

    private void saveBestConfig(BenchmarkConfig config) {
        getContext().getSharedPreferences(BENCHMARK_PREFS, 0).edit()
                .putInt("benchmarkVersion", BENCHMARK_VERSION)
                .putInt("searchThreads", config.searchThreads)
                .putInt("nnServerThreads", config.nnServerThreads)
                .putInt("batchSize", config.batchSize)
                .putFloat("visitsPerSecond", (float) config.visitsPerSecond)
                .apply();
    }

    private void notifyBenchmark(String message, boolean running) {
        mainHandler.post(() -> notifyListeners("benchmark", new JSObject()
                .put("message", message).put("running", running)));
    }

    private static final class BenchmarkConfig {
        final int searchThreads;
        final int nnServerThreads;
        final int batchSize;
        long rootVisits;
        double visitsPerSecond;

        BenchmarkConfig(int searchThreads, int nnServerThreads, int batchSize) {
            this(searchThreads, nnServerThreads, batchSize, 0.0);
        }

        BenchmarkConfig(int searchThreads, int nnServerThreads, int batchSize, double visitsPerSecond) {
            this.searchThreads = searchThreads;
            this.nnServerThreads = nnServerThreads;
            this.batchSize = batchSize;
            this.visitsPerSecond = visitsPerSecond;
        }

        String toOverrideConfig() {
            return "numSearchThreads=" + searchThreads
                    + ",numNNServerThreadsPerModel=" + nnServerThreads
                    + ",nnMaxBatchSize=" + batchSize + ",openclUseFP16=false";
        }
    }

    private static final class BenchmarkSession implements AutoCloseable {
        final Process process;
        final BufferedWriter writer;
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        volatile long lastRootVisits = -1L;

        BenchmarkSession(Process process) {
            this.process = process;
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) lines.put(line);
                } catch (Exception error) {
                    lines.offer("[reader] " + error);
                } finally {
                    lines.offer("[benchmark-eof]");
                }
            }, "katago-benchmark-output");
            reader.setDaemon(true);
            reader.start();
        }

        @Override public void close() {
            try { writer.close(); } catch (IOException ignored) { }
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    protected void handleOnDestroy() {
        io.execute(this::closeProcess);
        io.shutdownNow();
        super.handleOnDestroy();
    }
}
