package com.gomocalc.katagodemo;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    // The asset deliberately has a .bin name so Android does not transparently
    // unpack a .gz asset while building the APK. Its bytes are still gzip data;
    // copy it to a .bin.gz filename before invoking KataGo so KataGo selects
    // the gzip model reader from the runtime filename.
    private static final String MODEL_ASSET = "zhizi_renju28b_s1600.bin";
    private static final String MODEL_FILE_NAME = "zhizi_renju28b_s1600.bin.gz";
    private static final String CONFIG_ASSET = "gtp.cfg";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView output;
    private Button versionButton;
    private Button gtpButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 24);

        TextView title = new TextView(this);
        title.setText("KataGo Android ARM64 Demo");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(this);
        description.setText("先测试可执行文件，再加载权重运行一次 GTP genmove。首次模型测试会复制约 270 MB 文件。\n");
        description.setPadding(0, 20, 0, 16);
        root.addView(description, new LinearLayout.LayoutParams(-1, -2));

        versionButton = new Button(this);
        versionButton.setText("测试 katago version");
        root.addView(versionButton, new LinearLayout.LayoutParams(-1, -2));

        gtpButton = new Button(this);
        gtpButton.setText("加载权重并执行 genmove B");
        root.addView(gtpButton, new LinearLayout.LayoutParams(-1, -2));

        output = new TextView(this);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());
        output.setPadding(12, 12, 12, 12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output, new ScrollView.LayoutParams(-1, -1));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        scrollParams.topMargin = 20;
        root.addView(scroll, scrollParams);

        versionButton.setOnClickListener(v -> runVersion());
        gtpButton.setOnClickListener(v -> runGtp());
        setContentView(root);
    }

    private void runVersion() {
        setButtonsEnabled(false);
        append("\n$ katago version\n");
        worker.execute(() -> {
            try {
                File engine = nativeEngine();
                ProcessResult result = runProcess(new String[]{engine.getAbsolutePath(), "version"}, engine.getParentFile(), null);
                append(formatResult(result));
            } catch (Exception e) {
                append("ERROR: " + e + "\n");
            } finally {
                setButtonsEnabled(true);
            }
        });
    }

    private void runGtp() {
        setButtonsEnabled(false);
        append("\n$ katago gtp -model " + MODEL_FILE_NAME + " -config " + CONFIG_ASSET + "\n");
        append("Preparing engine, config and model...\n");
        worker.execute(() -> {
            try {
                File engine = nativeEngine();
                // The config is tiny and may have been copied by an older APK.
                // Always refresh it so an installed upgrade cannot reuse stale
                // settings; the large model remains cached below.
                File config = prepareAsset(CONFIG_ASSET, CONFIG_ASSET, false, true);
                File model = prepareAsset(MODEL_ASSET, MODEL_FILE_NAME, false);
                String[] commands = new String[]{
                        "protocol_version", "name", "version", "boardsize 15",
                        "clear_board", "genmove B", "quit"
                };
                ProcessResult result = runProcess(
                        new String[]{engine.getAbsolutePath(), "gtp", "-model", model.getAbsolutePath(), "-config", config.getAbsolutePath()},
                        engine.getParentFile(), commands);
                append(formatResult(result));
                if (result.output.contains("Loaded model") && result.output.contains("=")) {
                    append("\n模型加载和 GTP 推理流程已完成。\n");
                } else {
                    append("\n未检测到完整的模型加载结果，请查看上面的引擎输出。\n");
                }
            } catch (Exception e) {
                append("ERROR: " + e + "\n");
            } finally {
                setButtonsEnabled(true);
            }
        });
    }

    /**
     * Executables copied to files/ can be blocked by Android's noexec mount.
     * Native libraries are extracted to an executable directory by the package
     * manager, so the PIE ELF can be launched from there.
     */
    private File nativeEngine() throws IOException {
        File engine = new File(getApplicationInfo().nativeLibraryDir, "libkatago.so");
        if (!engine.isFile() || !engine.canExecute()) {
            throw new IOException("Native engine is missing or not executable: " + engine);
        }
        return engine;
    }

    private File prepareAsset(String assetName, String fileName, boolean executable) throws IOException {
        return prepareAsset(assetName, fileName, executable, false);
    }

    private File prepareAsset(String assetName, String fileName, boolean executable, boolean forceCopy) throws IOException {
        File dir = new File(getFilesDir(), "katago-engine");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File target = new File(dir, fileName);
        long assetLength = assetLength(assetName);
        if (forceCopy || !target.exists() || (assetLength >= 0 && target.length() != assetLength)) {
            File temp = new File(dir, fileName + ".part");
            try (InputStream in = getAssets().open(assetName); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot replace " + target);
            }
            if (!temp.renameTo(target)) {
                throw new IOException("Cannot rename " + temp + " to " + target);
            }
        }
        return target;
    }

    private long assetLength(String assetName) {
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd(assetName)) {
            return fd.getLength();
        } catch (IOException ignored) {
            return -1;
        }
    }

    private ProcessResult runProcess(String[] command, File directory, String[] input) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder captured = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (captured) {
                        captured.append(line).append('\n');
                    }
                }
            } catch (IOException e) {
                synchronized (captured) {
                    captured.append("[read error] ").append(e).append('\n');
                }
            }
        });
        reader.start();

        if (input != null) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                for (String line : input) {
                    writer.write(line);
                    writer.newLine();
                }
                writer.flush();
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("KataGo timed out");
        }
        reader.join(TimeUnit.SECONDS.toMillis(5));
        String text;
        synchronized (captured) {
            text = captured.toString();
        }
        return new ProcessResult(process.exitValue(), text);
    }

    private String formatResult(ProcessResult result) {
        return "exitCode=" + result.exitCode + "\n" + result.output + "\n";
    }

    private void append(String text) {
        runOnUiThread(() -> output.append(text));
    }

    private void setButtonsEnabled(boolean enabled) {
        runOnUiThread(() -> {
            versionButton.setEnabled(enabled);
            gtpButton.setEnabled(enabled);
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
