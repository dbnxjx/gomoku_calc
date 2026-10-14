package com.gomocalc.katagodemo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small, long-lived GTP client used to exercise KataGo on an Android device. */
public class MainActivity extends Activity {
    private static final String MODEL_ASSET = "zhizi_renju28b_s1600.bin";
    private static final String MODEL_FILE_NAME = "zhizi_renju28b_s1600.bin.gz";
    private static final String CONFIG_ASSET = "gtp.cfg";
    private static final int SEARCH_THREADS = 8;
    private static final int NN_SERVER_THREADS = 2;
    private static final int ANALYSIS_INTERVAL_CS = 10;
    private static final int MAX_OUTPUT_CHARS = 120_000;
    private static final String BEST_CONFIG_FILE_NAME = "katago-best.properties";
    private static final int BENCHMARK_VERSION = 3;
    private static final int BENCHMARK_VISITS = 64;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, AnalysisPoint> analysisPoints = new LinkedHashMap<>();
    private KataGoSession session;
    private TextView output;
    private TextView analysis;
    private EditText position;
    private EditText playMove;
    private Spinner color;
    private Button start;
    private Button analyze;
    private Button stopAnalyze;
    private Button genmove;
    private Button undo;
    private Button clear;
    private Button showBoard;
    private Button play;
    private Button benchmark;
    private TextView benchmarkStatus;
    // Commands are written by the worker while responses are read by the
    // KataGo reader thread, so these flags must be visible to both threads.
    private volatile boolean analysisRunning;
    private boolean analysisRenderPending;
    private long lastAnalysisRenderNanos;
    private volatile long genmoveStartedNanos;
    private volatile boolean genmoveResponsePending;
    private volatile int startupResponsesRemaining;
    private volatile boolean startupResponsePending;
    private volatile boolean engineReady;
    private volatile boolean analysisStopRequested;
    private volatile boolean analysisStopResponsePending;
    private volatile BenchmarkConfig activeConfig;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 12);

        TextView title = new TextView(this);
        title.setText("KataGo Android Renju");
        title.setTextSize(21);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("长驻 GTP 会话，多线程搜索，支持落子、悔棋、局面和实时分析");
        root.addView(hint);

        LinearLayout engineRow = new LinearLayout(this);
        engineRow.setOrientation(LinearLayout.HORIZONTAL);
        start = button("启动引擎");
        engineRow.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        benchmark = button("自动测速并保存");
        engineRow.addView(benchmark, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(engineRow);

        benchmarkStatus = new TextView(this);
        benchmarkStatus.setTextSize(12);
        benchmarkStatus.setTextColor(Color.DKGRAY);
        root.addView(benchmarkStatus);

        LinearLayout moveRow = new LinearLayout(this);
        moveRow.setOrientation(LinearLayout.HORIZONTAL);
        color = new Spinner(this);
        color.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"B", "W"}));
        moveRow.addView(color, new LinearLayout.LayoutParams(0, -2, 1));
        genmove = button("GENMOVE");
        moveRow.addView(genmove, new LinearLayout.LayoutParams(0, -2, 2));
        undo = button("悔棋");
        moveRow.addView(undo, new LinearLayout.LayoutParams(0, -2, 1));
        clear = button("清空");
        moveRow.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(moveRow);

        showBoard = button("显示棋盘");
        root.addView(showBoard, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout playRow = new LinearLayout(this);
        playRow.setOrientation(LinearLayout.HORIZONTAL);
        playMove = new EditText(this);
        playMove.setSingleLine(true);
        playMove.setHint("当前盘面落子：B H8");
        playMove.setInputType(InputType.TYPE_CLASS_TEXT);
        playRow.addView(playMove, new LinearLayout.LayoutParams(0, -2, 2));
        play = button("PLAY");
        playRow.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(playRow);

        position = new EditText(this);
        position.setHint("替换整盘：15行×15列 B/W/.；或 set_position B H8 W G7");
        position.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(position);
        Button apply = button("应用指定盘面");
        root.addView(apply);

        LinearLayout analysisRow = new LinearLayout(this);
        analysisRow.setOrientation(LinearLayout.HORIZONTAL);
        analyze = button("开始流式分析");
        analysisRow.addView(analyze, new LinearLayout.LayoutParams(0, -2, 1));
        stopAnalyze = button("停止分析");
        analysisRow.addView(stopAnalyze, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(analysisRow);

        analysis = new TextView(this);
        analysis.setTextSize(13);
        analysis.setTextColor(Color.DKGRAY);
        analysis.setMovementMethod(new ScrollingMovementMethod());
        ScrollView analysisScroll = new ScrollView(this);
        analysisScroll.addView(analysis);
        root.addView(analysisScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        output = new TextView(this);
        output.setTextSize(11);
        output.setMovementMethod(new ScrollingMovementMethod());
        output.setTextIsSelectable(true);
        ScrollView outputScroll = new ScrollView(this);
        outputScroll.addView(output);
        root.addView(outputScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        start.setOnClickListener(v -> startEngine());
        benchmark.setOnClickListener(v -> runBenchmark());
        genmove.setOnClickListener(v -> sendCommand("genmove " + color.getSelectedItem()));
        undo.setOnClickListener(v -> sendCommand("undo"));
        clear.setOnClickListener(v -> sendCommand("clear_board"));
        showBoard.setOnClickListener(v -> sendCommand("showboard"));
        play.setOnClickListener(v -> playCurrentPosition());
        apply.setOnClickListener(v -> applyPosition());
        analyze.setOnClickListener(v -> startAnalysis());
        stopAnalyze.setOnClickListener(v -> stopAnalysis());
        setEngineControls(false);
        stopAnalyze.setEnabled(false);
        updateBestConfigStatus(loadBestConfig());
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private void startEngine() {
        setEngineControls(false);
        // Keep the launch button disabled while the model is being loaded so
        // a second tap cannot create another long-lived KataGo process.
        runOnUiThread(() -> {
            start.setEnabled(false);
            benchmark.setEnabled(false);
        });
        append("启动 KataGo...\n");
        worker.execute(() -> {
            try {
                startEngineOnWorker();
            } catch (Exception error) {
                append("启动失败: " + error + "\n");
                engineReady = false;
                startupResponsesRemaining = 0;
                closeSession();
                runOnUiThread(() -> {
                    setEngineControls(false);
                    benchmark.setEnabled(true);
                    start.setEnabled(true);
                });
            }
        });
    }

    private void startEngineOnWorker() throws Exception {
        append("检查资源...\n");
        File engine = nativeEngine();
        File config = prepareAsset(CONFIG_ASSET, CONFIG_ASSET, true);
        File model = prepareAsset(MODEL_ASSET, MODEL_FILE_NAME, false);
        BenchmarkConfig best = loadBestConfig();
        activeConfig = best;
        String override = best == null ? null : best.toOverrideConfig();
        if (best != null) {
            append(String.format(Locale.US,
                    "使用已保存的最快配置：搜索线程 %d，后端线程 %d，批大小 %d（%.2f visits/s）。\n",
                    best.searchThreads, best.nnServerThreads, best.batchSize, best.visitsPerSecond));
        }
        append("资源就绪，创建长驻 GTP 进程...\n");
        session = new KataGoSession(engine, config, model, override);
        session.start(this::onEngineLine);
        append("进程已创建，等待 GTP 初始化...\n");
        engineReady = false;
        startupResponsesRemaining = 5;
        startupResponsePending = false;
        sendToSession("protocol_version");
        sendToSession("name");
        sendToSession("version");
        sendToSession("boardsize 15");
        sendToSession("clear_board");
        append("已发送初始化命令，等待 GTP 就绪...\n");
    }

    private void runBenchmark() {
        if (analysisRunning) {
            append("请先停止流式分析，再开始测速。\n");
            return;
        }
        runOnUiThread(() -> {
            benchmark.setEnabled(false);
            start.setEnabled(false);
        });
        setEngineControls(false);
        append("开始自动测速：固定使用 OpenCL FP32；每组配置先预热，再执行 64 visits 的搜索。\n");
        worker.execute(() -> {
            try {
                closeSession();
                engineReady = false;
                File engine = nativeEngine();
                File config = prepareAsset(CONFIG_ASSET, CONFIG_ASSET, true);
                File model = prepareAsset(MODEL_ASSET, MODEL_FILE_NAME, false);
                BenchmarkResult result = benchmarkConfigurations(engine, config, model);
                if (result.best == null) {
                    throw new IOException("所有测速组合都失败");
                }
                saveBestConfig(result.best);
                append(String.format(Locale.US,
                        "测速完成，最佳配置：搜索线程 %d，后端线程 %d，批大小 %d，%.2f visits/s。已保存，今后启动自动使用。\n",
                        result.best.searchThreads, result.best.nnServerThreads, result.best.batchSize,
                        result.best.visitsPerSecond));
                runOnUiThread(() -> updateBestConfigStatus(result.best));
                // Keep the button useful immediately: start a normal session
                // with the configuration just selected by the benchmark.
                startEngineOnWorker();
            } catch (Exception error) {
                append("测速失败: " + error + "\n");
                runOnUiThread(() -> {
                    benchmark.setEnabled(true);
                    start.setEnabled(true);
                });
            }
        });
    }

    private BenchmarkResult benchmarkConfigurations(File engine, File config, File model) {
        BenchmarkConfig[] candidates = new BenchmarkConfig[]{
                new BenchmarkConfig(1, 1, 1, 0.0),
                new BenchmarkConfig(2, 1, 4, 0.0),
                new BenchmarkConfig(4, 1, 4, 0.0),
                new BenchmarkConfig(4, 2, 8, 0.0),
                new BenchmarkConfig(8, 1, 8, 0.0),
                new BenchmarkConfig(8, 2, 8, 0.0),
                new BenchmarkConfig(8, 2, 4, 0.0),
                new BenchmarkConfig(8, 3, 8, 0.0)
        };
        BenchmarkConfig best = null;
        for (int i = 0; i < candidates.length; i++) {
            BenchmarkConfig candidate = candidates[i];
            try {
                append(String.format(Locale.US, "测速 %d/%d：搜索线程 %d，后端线程 %d，批大小 %d...\n",
                        i + 1, candidates.length, candidate.searchThreads,
                        candidate.nnServerThreads, candidate.batchSize));
                double visitsPerSecond = benchmarkOne(engine, config, model, candidate);
                candidate.visitsPerSecond = visitsPerSecond;
                append(String.format(Locale.US, "  FP32，实际 %d visits，结果 %.2f visits/s%s\n",
                        candidate.rootVisits, visitsPerSecond,
                        candidate.fp16Details == null ? "" : "；实际路径 " + candidate.fp16Details));
                if (best == null || visitsPerSecond > best.visitsPerSecond) {
                    best = candidate;
                }
            } catch (Exception error) {
                append("  FP32 该组合失败：" + error.getMessage() + "\n");
            }
        }
        return new BenchmarkResult(best);
    }

    private double benchmarkOne(File engine, File config, File model, BenchmarkConfig candidate)
            throws IOException, InterruptedException {
        String override = candidate.toOverrideConfig()
                + ",maxVisits=" + BENCHMARK_VISITS
                + ",nnCacheSizePowerOfTwo=-1"
                + ",maxTime=120,logToStderr=true,logSearchInfo=false,startupPrintMessageToStderr=false";
        ProcessBuilder builder = new ProcessBuilder(engine.getAbsolutePath(), "gtp",
                "-model", model.getAbsolutePath(), "-config", config.getAbsolutePath(),
                "-override-config", override)
                .directory(engine.getParentFile())
                .redirectErrorStream(true);
        File homeDirectory = new File(config.getParentFile(), "katago-home");
        if (!homeDirectory.exists() && !homeDirectory.mkdirs()) {
            throw new IOException("Cannot create KataGo home directory: " + homeDirectory);
        }
        builder.environment().put("HOME", homeDirectory.getAbsolutePath());
        builder.environment().put("USERPROFILE", homeDirectory.getAbsolutePath());
        Process process = builder.start();
        try (BenchmarkProcess benchmarkProcess = new BenchmarkProcess(process)) {
            sendBenchmarkCommand(benchmarkProcess, candidate, "protocol_version");
            sendBenchmarkCommand(benchmarkProcess, candidate, "name");
            sendBenchmarkCommand(benchmarkProcess, candidate, "version");
            sendBenchmarkCommand(benchmarkProcess, candidate, "boardsize 15");
            sendBenchmarkCommand(benchmarkProcess, candidate, "clear_board");
            // Warm up on a different position. Using the same position for
            // warm-up and measurement lets the NN cache answer the second
            // search without running the model.
            sendBenchmarkCommand(benchmarkProcess, candidate, "play B A1");
            sendBenchmarkCommand(benchmarkProcess, candidate, "play W C2");
            sendBenchmarkCommand(benchmarkProcess, candidate, "play B A3");
            sendBenchmarkCommand(benchmarkProcess, candidate, "play W C3");
            sendBenchmarkCommand(benchmarkProcess, candidate, "search_debug B");
            sendBenchmarkCommand(benchmarkProcess, candidate, "clear_board");
            sendBenchmarkCommand(benchmarkProcess, candidate, "play B H8");
            sendBenchmarkCommand(benchmarkProcess, candidate, "play W G7");
            sendBenchmarkCommand(benchmarkProcess, candidate, "play B J8");
            sendBenchmarkCommand(benchmarkProcess, candidate, "play W F7");
            benchmarkProcess.lastRootVisits = -1L;
            long started = System.nanoTime();
            String response = sendBenchmarkCommand(benchmarkProcess, candidate, "search_debug B");
            long elapsedNanos = System.nanoTime() - started;
            if (response.startsWith("?")) {
                throw new IOException(response);
            }
            if (benchmarkProcess.lastRootVisits <= 0) {
                throw new IOException("search_debug 没有返回 Root visits");
            }
            candidate.rootVisits = benchmarkProcess.lastRootVisits;
            double seconds = elapsedNanos / 1_000_000_000.0;
            return benchmarkProcess.lastRootVisits / Math.max(seconds, 0.001);
        }
    }

    private String sendBenchmarkCommand(BenchmarkProcess benchmarkProcess, BenchmarkConfig candidate,
                                       String command) throws IOException, InterruptedException {
        benchmarkProcess.writer.write(command);
        benchmarkProcess.writer.newLine();
        benchmarkProcess.writer.flush();
        String response = null;
        StringBuilder diagnostics = new StringBuilder();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException("GTP 命令超时（90 秒），已终止该测速组合");
            }
            String line = benchmarkProcess.lines.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new IOException("GTP 命令超时（90 秒），已终止该测速组合");
            }
            if (line.startsWith("=") || line.startsWith("?")) {
                response = line;
            } else if (line.equals("[benchmark-eof]")) {
                throw new IOException(diagnostics.length() == 0
                        ? "KataGo 进程提前退出" : diagnostics.toString());
            } else if (line.contains("FP16Storage ")) {
                candidate.fp16Details = line.substring(line.indexOf("FP16Storage ")).trim();
                append("  引擎 FP16 实际状态：" + candidate.fp16Details + "\n");
            } else if (line.startsWith("Root visits:")) {
                String visits = line.substring("Root visits:".length()).trim();
                try {
                    benchmarkProcess.lastRootVisits = Long.parseLong(visits);
                } catch (NumberFormatException ignored) {
                    benchmarkProcess.lastRootVisits = -1L;
                }
            } else if (line.contains("ERROR") || line.contains("Error") || line.startsWith("[reader]")) {
                if (diagnostics.length() < 800) {
                    diagnostics.append(line).append(' ');
                }
            }
            if (response != null && line.trim().isEmpty()) {
                if (response.startsWith("?")) {
                    throw new IOException(response + (diagnostics.length() == 0 ? "" : " " + diagnostics));
                }
                return response;
            }
        }
    }

    private void saveBestConfig(BenchmarkConfig config) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("searchThreads", Integer.toString(config.searchThreads));
        properties.setProperty("benchmarkVersion", Integer.toString(BENCHMARK_VERSION));
        properties.setProperty("nnServerThreads", Integer.toString(config.nnServerThreads));
        properties.setProperty("batchSize", Integer.toString(config.batchSize));
        properties.setProperty("visitsPerSecond", String.format(Locale.US, "%.6f", config.visitsPerSecond));
        File target = getFileStreamPath(BEST_CONFIG_FILE_NAME);
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            properties.store(stream, "KataGo Android benchmark result");
            stream.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace " + target);
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("Cannot save " + target);
        }
    }

    private BenchmarkConfig loadBestConfig() {
        File source = getFileStreamPath(BEST_CONFIG_FILE_NAME);
        if (!source.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream stream = new FileInputStream(source)) {
            properties.load(stream);
            if (Integer.parseInt(properties.getProperty("benchmarkVersion", "0")) != BENCHMARK_VERSION) {
                return null;
            }
            int searchThreads = Integer.parseInt(properties.getProperty("searchThreads"));
            int nnServerThreads = Integer.parseInt(properties.getProperty("nnServerThreads"));
            int batchSize = Integer.parseInt(properties.getProperty("batchSize"));
            double visitsPerSecond = Double.parseDouble(properties.getProperty("visitsPerSecond", "0"));
            if (searchThreads < 1 || searchThreads > 64 || nnServerThreads < 1 || nnServerThreads > 8
                    || batchSize < 1 || batchSize > 64
                    || !Double.isFinite(visitsPerSecond)) {
                return null;
            }
            return new BenchmarkConfig(searchThreads, nnServerThreads, batchSize, visitsPerSecond);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void updateBestConfigStatus(BenchmarkConfig config) {
        if (config == null) {
            benchmarkStatus.setText("尚未测速，将使用默认 OpenCL FP32 配置");
        } else {
            benchmarkStatus.setText(String.format(Locale.US,
                    "已保存：线程 %d / 后端 %d / 批 %d / FP32，%.2f visits/s",
                    config.searchThreads, config.nnServerThreads, config.batchSize,
                    config.visitsPerSecond));
        }
    }

    private void sendCommand(String command) {
        worker.execute(() -> {
            try {
                ensureSession();
                if (analysisRunning && !command.equals("stop")) {
                    append("请先停止流式分析，再执行：" + command + "\n");
                    return;
                }
                append("$ " + command + "\n");
                if (command.startsWith("genmove ")) {
                    genmoveStartedNanos = System.nanoTime();
                    genmoveResponsePending = false;
                    runOnUiThread(() -> genmove.setEnabled(false));
                }
                if (command.equals("kata-analyze") || command.startsWith("kata-analyze ")) {
                    analysisRunning = true;
                    analysisStopRequested = false;
                }
                session.command(command);
            } catch (Exception error) {
                append("ERROR: " + error + "\n");
                if (command.startsWith("genmove ")) {
                    genmoveStartedNanos = 0L;
                    genmoveResponsePending = false;
                    runOnUiThread(() -> genmove.setEnabled(engineReady));
                }
                if (command.equals("kata-analyze") || command.startsWith("kata-analyze ")) {
                    stopAnalysisUi();
                }
            }
        });
    }

    private void stopAnalysis() {
        worker.execute(() -> {
            try {
                ensureSession();
                analysisStopRequested = true;
                analysisStopResponsePending = false;
                append("$ stop\n");
                session.command("stop");
            } catch (Exception error) {
                append("ERROR: " + error + "\n");
                stopAnalysisUi();
            }
        });
    }

    private void sendToSession(String command) throws IOException {
        session.command(command);
    }

    private void playCurrentPosition() {
        String text = playMove.getText().toString().trim();
        String[] tokens = text.split("\\s+");
        int offset = 0;
        if (tokens.length > 0 && tokens[0].equalsIgnoreCase("play")) {
            offset = 1;
        }
        if (tokens.length - offset != 2) {
            append("PLAY 格式：B H8，或 play B H8。它只在当前盘面追加一手。\n");
            return;
        }
        String moveColor = tokens[offset].toUpperCase(Locale.US);
        String vertex = tokens[offset + 1].toUpperCase(Locale.US);
        if (!(moveColor.equals("B") || moveColor.equals("W")) || !isGtpVertex(vertex)) {
            append("PLAY 格式错误：颜色必须是 B/W，坐标必须是 A-H、J-P 加 1-15。\n");
            return;
        }
        sendCommand("play " + moveColor + " " + vertex);
    }

    private void applyPosition() {
        String text = position.getText().toString();
        worker.execute(() -> {
            try {
                ensureSession();
                if (analysisRunning) {
                    append("请先停止流式分析，再应用局面。\n");
                    return;
                }
                List<PositionStone> stones = parsePosition(text);
                if (stones == null) {
                    return;
                }
                if (stones.isEmpty()) {
                    append("$ clear_board\n");
                    session.command("clear_board");
                } else {
                    StringBuilder command = new StringBuilder("set_position");
                    for (PositionStone stone : stones) {
                        command.append(' ').append(stone.color).append(' ').append(stone.vertex);
                    }
                    append("$ set_position (" + stones.size() + " 个棋子)\n");
                    session.command(command.toString());
                }
                append("指定局面已发送，可选择执棋颜色后执行 GENMOVE 或开始分析。\n");
            } catch (Exception error) {
                append("ERROR: " + error + "\n");
            }
        });
    }

    /**
     * Converts either a 15x15 text board or a GTP set_position command to
     * one atomic set_position command. Rows in the text board are top to
     * bottom (15 to 1), and columns use GTP's A-H,J-P coordinates.
     */
    private List<PositionStone> parsePosition(String text) {
        String trimmed = text.trim();
        List<PositionStone> stones = new ArrayList<>();
        if (trimmed.isEmpty()) {
            return stones;
        }

        String[] lines = trimmed.split("[;\\n]+");
        boolean looksLikeBoard = lines.length == 15;
        if (looksLikeBoard) {
            for (String line : lines) {
                if (line.trim().length() != 15 || !line.trim().matches("[BbWw.0_-]{15}")) {
                    looksLikeBoard = false;
                    break;
                }
            }
        }
        if (looksLikeBoard) {
            final String columns = "ABCDEFGHJKLMNOP";
            for (int row = 0; row < 15; row++) {
                String boardRow = lines[row].trim();
                int gtpRow = 15 - row;
                for (int column = 0; column < 15; column++) {
                    char cell = Character.toUpperCase(boardRow.charAt(column));
                    if (cell == 'B' || cell == 'W') {
                        stones.add(new PositionStone(cell, columns.charAt(column) + Integer.toString(gtpRow)));
                    }
                }
            }
            return stones;
        }

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            int offset;
            if (tokens[0].equalsIgnoreCase("set_position")) {
                offset = 1;
            } else if (tokens[0].equalsIgnoreCase("play")) {
                append("指定盘面区域只接受 set_position；当前盘面追加落子请使用上面的 PLAY。\n");
                return null;
            } else {
                // Also accept a compact sequence such as "B H8 W G7".
                // This is convenient when a position comes from a text protocol.
                offset = 0;
            }
            if ((tokens.length - offset) == 0 || ((tokens.length - offset) % 2) != 0) {
                append("局面格式错误：颜色和坐标必须成对出现。\n");
                return null;
            }
            for (int i = offset; i < tokens.length; i += 2) {
                String color = tokens[i].toUpperCase(Locale.US);
                String vertex = tokens[i + 1].toUpperCase(Locale.US);
                if (!(color.equals("B") || color.equals("W")) || !isGtpVertex(vertex)) {
                    append("局面格式错误：非法棋子或坐标 " + tokens[i] + " " + tokens[i + 1] + "。\n");
                    return null;
                }
                stones.add(new PositionStone(color.charAt(0), vertex));
            }
        }
        return stones;
    }

    private boolean isGtpVertex(String vertex) {
        if (vertex.length() < 2 || vertex.length() > 3) {
            return false;
        }
        if ("ABCDEFGHJKLMNOP".indexOf(vertex.charAt(0)) < 0) {
            return false;
        }
        try {
            int row = Integer.parseInt(vertex.substring(1));
            return row >= 1 && row <= 15;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void startAnalysis() {
        if (analysisRunning || !engineReady) return;
        runOnUiThread(() -> {
            analyze.setEnabled(false);
            stopAnalyze.setEnabled(true);
            analysis.setText("等待分析输出...\n");
        });
        synchronized (analysisPoints) {
            analysisPoints.clear();
        }
        sendCommand("kata-analyze " + color.getSelectedItem() + " " + ANALYSIS_INTERVAL_CS
                + " maxmoves 20 pvVisits true");
    }

    private void onEngineLine(String line) {
        if (line.startsWith("[reader]")) {
            append(line + "\n");
            engineReady = false;
            startupResponsesRemaining = 0;
            startupResponsePending = false;
            genmoveStartedNanos = 0L;
            genmoveResponsePending = false;
            analysisStopResponsePending = false;
            stopAnalysisUi();
            runOnUiThread(() -> setEngineControls(false));
            return;
        }
        // Keep high-rate analysis lines in the parsed candidate panel. Posting
        // every raw info line to the Android UI thread makes the controls lag.
        if (!line.startsWith("info ")) {
            append(line + "\n");
        }
        if (line.startsWith("info ")) {
            List<AnalysisPoint> points = parseAnalysis(line);
            if (!points.isEmpty()) {
                synchronized (analysisPoints) {
                    analysisPoints.clear();
                    for (AnalysisPoint point : points) {
                        analysisPoints.put(point.move, point);
                    }
                }
                renderAnalysis();
            }
        }
        if (genmoveStartedNanos != 0L && (line.startsWith("=") || line.startsWith("?"))) {
            genmoveResponsePending = true;
        }
        if (analysisStopRequested && (line.startsWith("=") || line.startsWith("?"))) {
            analysisStopResponsePending = true;
        }
        // GTP terminates every response with a blank line. For kata-analyze,
        // this is emitted after the stop command and marks the end of a stream.
        if (!engineReady && startupResponsesRemaining > 0) {
            if (line.startsWith("=") || line.startsWith("?")) {
                startupResponsePending = true;
            } else if (line.trim().isEmpty() && startupResponsePending) {
                startupResponsePending = false;
                if (--startupResponsesRemaining == 0) {
                    engineReady = true;
                    BenchmarkConfig running = activeConfig;
                    append(running == null
                            ? String.format(Locale.US, "引擎已就绪：%d 个搜索线程、%d 个神经网络后端线程、FP32。\n",
                            SEARCH_THREADS, NN_SERVER_THREADS)
                            : String.format(Locale.US, "引擎已就绪：%d 个搜索线程、%d 个神经网络后端线程、批大小 %d、FP32。\n",
                            running.searchThreads, running.nnServerThreads, running.batchSize));
                    setEngineControls(true);
                    runOnUiThread(() -> benchmark.setEnabled(true));
                }
            }
        }
        if (line.trim().isEmpty()) {
            if (genmoveStartedNanos != 0L && genmoveResponsePending) {
                long elapsed = (System.nanoTime() - genmoveStartedNanos) / 1_000_000L;
                genmoveStartedNanos = 0L;
                genmoveResponsePending = false;
                append(String.format(Locale.US, "genmove 完成，耗时 %.2f 秒。\n", elapsed / 1000.0));
                runOnUiThread(() -> genmove.setEnabled(true));
            }
            if (analysisRunning && analysisStopRequested && analysisStopResponsePending) {
                stopAnalysisUi();
                analysisStopResponsePending = false;
            }
        }
    }

    private void stopAnalysisUi() {
        analysisRunning = false;
        analysisStopRequested = false;
        runOnUiThread(() -> {
            stopAnalyze.setEnabled(false);
            analyze.setEnabled(engineReady);
        });
    }

    private List<AnalysisPoint> parseAnalysis(String line) {
        String[] tokens = line.trim().split("\\s+");
        List<AnalysisPoint> points = new ArrayList<>();
        String move = null;
        int visits = 0;
        double winrate = Double.NaN;
        double scoreLead = Double.NaN;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals("info")) {
                if (move != null) {
                    points.add(new AnalysisPoint(move, visits, winrate, scoreLead));
                }
                move = null;
                visits = 0;
                winrate = Double.NaN;
                scoreLead = Double.NaN;
                continue;
            }
            if (i + 1 >= tokens.length) {
                break;
            }
            switch (tokens[i]) {
                case "move":
                    move = tokens[++i];
                    break;
                case "visits":
                    visits = parseInt(tokens[++i], 0);
                    break;
                case "winrate":
                    winrate = parseDouble(tokens[++i]);
                    break;
                case "scoreLead":
                    scoreLead = parseDouble(tokens[++i]);
                    break;
                default:
                    break;
            }
        }
        if (move != null) {
            points.add(new AnalysisPoint(move, visits, winrate, scoreLead));
        }
        return points;
    }

    private void renderAnalysis() {
        final long renderDelayMillis;
        synchronized (analysisPoints) {
            if (analysisRenderPending) {
                return;
            }
            analysisRenderPending = true;
            long elapsed = System.nanoTime() - lastAnalysisRenderNanos;
            renderDelayMillis = elapsed >= 200_000_000L ? 0L :
                    (200_000_000L - elapsed) / 1_000_000L;
        }
        analysis.postDelayed(() -> {
            List<AnalysisPoint> points;
            synchronized (analysisPoints) {
                points = new ArrayList<>(analysisPoints.values());
            }
            points.sort(Comparator.comparingInt((AnalysisPoint p) -> p.visits).reversed());
            StringBuilder text = new StringBuilder("实时候选点（按访问量排序）\n");
            for (AnalysisPoint point : points) {
                text.append(String.format(Locale.US, "%s  胜率 %s  访问 %d\n",
                        point.move, formatWinrate(point.winrate), point.visits));
            }
            analysis.setText(text.toString());
            synchronized (analysisPoints) {
                lastAnalysisRenderNanos = System.nanoTime();
                analysisRenderPending = false;
            }
        }, renderDelayMillis);
    }

    private String formatWinrate(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.US, "%.1f%%", value * 100.0);
    }

    private String formatScore(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.US, "%+.2f", value);
    }

    private int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double parseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private void ensureSession() throws IOException {
        if (session == null || !engineReady) {
            throw new IOException(session == null ? "请先启动引擎" : "引擎尚未就绪");
        }
    }

    private void setEngineControls(boolean enabled) {
        runOnUiThread(() -> {
            start.setEnabled(!enabled);
            genmove.setEnabled(enabled && genmoveStartedNanos == 0L);
            undo.setEnabled(enabled);
            clear.setEnabled(enabled);
            showBoard.setEnabled(enabled);
            play.setEnabled(enabled && !analysisRunning);
            playMove.setEnabled(enabled && !analysisRunning);
            position.setEnabled(!analysisRunning);
            analyze.setEnabled(enabled && !analysisRunning);
            stopAnalyze.setEnabled(enabled && analysisRunning);
        });
    }

    private void append(String text) {
        runOnUiThread(() -> {
            output.append(text);
            int length = output.length();
            if (length > MAX_OUTPUT_CHARS) {
                output.setText(output.getText().subSequence(length - MAX_OUTPUT_CHARS, length));
            }
        });
    }

    private File nativeEngine() throws IOException {
        File engine = new File(getApplicationInfo().nativeLibraryDir, "libkatago.so");
        if (!engine.isFile() || !engine.canExecute()) {
            throw new IOException("Native engine missing or not executable: " + engine);
        }
        return engine;
    }

    private File prepareAsset(String assetName, String fileName, boolean force) throws IOException {
        File directory = new File(getFilesDir(), "katago-engine");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory);
        }
        File target = new File(directory, fileName);
        if (force || !target.exists()) {
            File temporary = new File(directory, fileName + ".part");
            try (InputStream input = getAssets().open(assetName);
                 FileOutputStream outputStream = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, count);
                }
                outputStream.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot replace " + target);
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("Cannot rename " + temporary + " to " + target);
            }
        }
        return target;
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Override
    protected void onDestroy() {
        closeSession();
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class AnalysisPoint {
        final String move;
        final int visits;
        final double winrate;
        final double scoreLead;

        AnalysisPoint(String move, int visits, double winrate, double scoreLead) {
            this.move = move;
            this.visits = visits;
            this.winrate = winrate;
            this.scoreLead = scoreLead;
        }
    }

    private static final class PositionStone {
        final char color;
        final String vertex;

        PositionStone(char color, String vertex) {
            this.color = color;
            this.vertex = vertex;
        }
    }

    private static final class BenchmarkConfig {
        final int searchThreads;
        final int nnServerThreads;
        final int batchSize;
        String fp16Details;
        long rootVisits;
        double visitsPerSecond;

        BenchmarkConfig(int searchThreads, int nnServerThreads, int batchSize, double visitsPerSecond) {
            this.searchThreads = searchThreads;
            this.nnServerThreads = nnServerThreads;
            this.batchSize = batchSize;
            this.visitsPerSecond = visitsPerSecond;
        }

        String toOverrideConfig() {
            return "numSearchThreads=" + searchThreads
                    + ",numNNServerThreadsPerModel=" + nnServerThreads
                    + ",nnMaxBatchSize=" + batchSize
                    + ",openclUseFP16=false";
        }
    }

    private static final class BenchmarkResult {
        final BenchmarkConfig best;

        BenchmarkResult(BenchmarkConfig best) {
            this.best = best;
        }
    }

    private static final class BenchmarkProcess implements AutoCloseable {
        final Process process;
        final BufferedWriter writer;
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        volatile long lastRootVisits = -1L;

        BenchmarkProcess(Process process) {
            this.process = process;
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        lines.put(line);
                    }
                } catch (Exception error) {
                    lines.offer("[reader] " + error);
                } finally {
                    lines.offer("[benchmark-eof]");
                }
            }, "katago-benchmark-output");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void close() {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException error) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class KataGoSession {
        private final File engine;
        private final File config;
        private final File model;
        private final String overrideConfig;
        private Process process;
        private BufferedWriter writer;
        private LineListener listener;

        KataGoSession(File engine, File config, File model, String overrideConfig) {
            this.engine = engine;
            this.config = config;
            this.model = model;
            this.overrideConfig = overrideConfig;
        }

        void start(LineListener lineListener) throws IOException {
            listener = lineListener;
            File homeDirectory = new File(config.getParentFile(), "katago-home");
            if (!homeDirectory.exists() && !homeDirectory.mkdirs()) {
                throw new IOException("Cannot create KataGo home directory: " + homeDirectory);
            }
            List<String> command = new ArrayList<>();
            command.add(engine.getAbsolutePath());
            command.add("gtp");
            command.add("-model");
            command.add(model.getAbsolutePath());
            command.add("-config");
            command.add(config.getAbsolutePath());
            if (overrideConfig != null && !overrideConfig.isEmpty()) {
                command.add("-override-config");
                command.add(overrideConfig);
            }
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(engine.getParentFile())
                    .redirectErrorStream(true);
            // KataGo's OpenCL tuner stores its cache below $HOME/.katago.
            // Android may expose an inherited HOME such as /data, which is
            // not writable by an ordinary app. Point it at app-private storage.
            builder.environment().put("HOME", homeDirectory.getAbsolutePath());
            builder.environment().put("USERPROFILE", homeDirectory.getAbsolutePath());
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        listener.line(line);
                    }
                } catch (IOException error) {
                    listener.line("[reader] " + error);
                } finally {
                    listener.line("[reader] process exited");
                }
            }, "katago-output");
            reader.setDaemon(true);
            reader.start();
        }

        synchronized void command(String command) throws IOException {
            if (writer == null) {
                throw new IOException("session not started");
            }
            writer.write(command);
            writer.newLine();
            writer.flush();
        }

        void close() {
            try {
                if (writer != null) {
                    writer.write("quit");
                    writer.newLine();
                    writer.flush();
                }
            } catch (Exception ignored) {
                // The process may already have stopped while the Activity closes.
            }
            if (process != null) {
                process.destroy();
            }
        }
    }

    private interface LineListener {
        void line(String line);
    }
}
