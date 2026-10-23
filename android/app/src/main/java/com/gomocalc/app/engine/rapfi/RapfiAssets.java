package com.gomocalc.app.engine.rapfi;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the bundled Rapfi engine assets (NNUE weights, classical models and the three weight
 * configs) from the APK into the app's private files directory so the native engine can read them
 * as ordinary files.
 *
 * <p>The three config files mirror the Web UI's "引擎权重" choices:</p>
 * <ul>
 *   <li>{@code config.toml} — mix9svq NNUE (strongest)</li>
 *   <li>{@code classical220723.toml} — classical, model220723.bin</li>
 *   <li>{@code classical210901.toml} — classical, model210901.bin</li>
 * </ul>
 *
 * <p>The engine normally resolves file paths relative to the config file. To remove that whole
 * class of failure, every extracted config is rewritten in place so each {@code weight_file*} /
 * {@code binary_file} points at an <b>absolute</b> path ({@code getModelFullPath()} returns
 * absolute paths unchanged).</p>
 *
 * <p>Every copy is verified against a minimum plausible size, because a truncated copy would
 * otherwise silently produce a config that loads but whose weights do not.</p>
 */
final class RapfiAssets {

    private static final String TAG = "RapfiAssets";

    /** Directory inside {@code assets/} holding the engine bundle. */
    static final String ASSET_DIR = "rapfi";
    /** NNUE config (also the default weight). */
    static final String CONFIG_NNUE = "config.toml";
    /** Classical configs, matching the Web UI's weight option order. */
    static final String CONFIG_CLASSICAL_220723 = "classical220723.toml";
    static final String CONFIG_CLASSICAL_210901 = "classical210901.toml";
    /** All config files accepted from the Web UI, in its option order. */
    static final String[] CONFIG_FILES = {
        CONFIG_NNUE, CONFIG_CLASSICAL_220723, CONFIG_CLASSICAL_210901
    };

    /** Bump when the bundled weights/config change, to force re-extraction. */
    private static final String ASSET_VERSION = "mix9svq-4";
    /** Marker file recording the extracted version. */
    private static final String MARKER_FILE = ".extracted";
    /** Every bundled NNUE weight is ~10 MB; anything under this means the copy was truncated. */
    private static final long MIN_WEIGHT_BYTES = 5L * 1024L * 1024L;

    /** Name of the NNUE evaluator used by the bundled NNUE config. */
    static final String EVALUATOR = "mix9svq";

    /** Keys in a config that name a file to be resolved by the engine. */
    private static final Pattern CONFIG_PATH_KEY = Pattern.compile(
            "(weight_file(?:_black|_white)?|binary_file)\\s*=\\s*\"([^\"]+)\"");

    /** Files the engine needs, in copy order. Each entry is {asset name, minimum size in bytes}. */
    private static final Object[][] EXPECTED = {
        {"mix9svqfreestyle_bsmix.bin.lz4", MIN_WEIGHT_BYTES},
        {"mix9svqstandard_bs15.bin.lz4", MIN_WEIGHT_BYTES},
        {"mix9svqrenju_bs15_black.bin.lz4", MIN_WEIGHT_BYTES},
        {"mix9svqrenju_bs15_white.bin.lz4", MIN_WEIGHT_BYTES},
        {"model210901.bin", 1024L},
        {"model220723.bin", 1024L},
        {CONFIG_NNUE, 1L},
        {CONFIG_CLASSICAL_220723, 1L},
        {CONFIG_CLASSICAL_210901, 1L},
    };

    private RapfiAssets() { }

    /** Result of an extraction attempt. */
    static final class Result {
        /** Absolute NNUE config path, or null when the bundle is missing/incomplete. */
        final String configPath;
        /** Human readable lines describing what happened (may be empty). */
        final List<String> log;

        Result(String configPath, List<String> log) {
            this.configPath = configPath;
            this.log = Collections.unmodifiableList(new ArrayList<>(log));
        }
    }

    /** Extract (if needed), verify and path-rewrite the bundled assets. */
    static Result ensureExtracted(Context context) {
        List<String> log = new ArrayList<>();
        File targetDir = engineDirectory(context);
        File configFile = new File(targetDir, CONFIG_NNUE);
        File marker = new File(targetDir, MARKER_FILE);
        AssetManager assets = context.getAssets();

        String[] children = null;
        try {
            children = assets.list(ASSET_DIR);
        } catch (IOException error) {
            Log.w(TAG, "cannot list assets/" + ASSET_DIR, error);
        }
        int listed = children == null ? 0 : children.length;
        log.add("assets/" + ASSET_DIR + " lists " + listed + " entries");
        Log.i(TAG, "assets list count=" + listed);
        if (listed == 0) {
            log.add("bundle missing from APK; using classical eval");
            return new Result(null, log);
        }

        // Fast path: already extracted, verified and rewritten.
        if (ASSET_VERSION.equals(readTrimmed(marker)) && allPresent(targetDir)) {
            log.add("already extracted (" + ASSET_VERSION + ")");
            return new Result(configFile.getAbsolutePath(), log);
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            log.add("cannot create " + targetDir.getAbsolutePath());
            return new Result(null, log);
        }

        for (Object[] entry : EXPECTED) {
            String name = (String) entry[0];
            long minBytes = (Long) entry[1];
            File target = new File(targetDir, name);
            try {
                long copied = copyAsset(assets, ASSET_DIR + "/" + name, target);
                long size = target.length();
                Log.i(TAG, "extracted " + name + " copied=" + copied + " size=" + size);
                if (size < minBytes) {
                    log.add("asset " + name + " too small (" + size + " < " + minBytes
                            + "); copy was truncated");
                    return new Result(null, log);
                }
                log.add(name + ": " + size + " bytes");
            } catch (IOException error) {
                Log.w(TAG, "extract failed for " + name, error);
                log.add("asset " + name + " missing/unreadable: " + error.getMessage());
                return new Result(null, log);
            }
        }

        try {
            int rewritten = 0;
            for (String configName : CONFIG_FILES)
                rewritten += makeConfigPathsAbsolute(new File(targetDir, configName), targetDir);
            log.add("configs rewritten with " + rewritten + " absolute path(s)");
            Log.i(TAG, "configs rewritten paths=" + rewritten);
        } catch (IOException error) {
            Log.w(TAG, "cannot rewrite configs", error);
            log.add("cannot rewrite configs: " + error.getMessage());
            return new Result(null, log);
        }

        writeMarker(marker);
        if (!configFile.isFile()) {
            log.add(CONFIG_NNUE + " missing after extraction");
            return new Result(null, log);
        }
        log.add("extracted to " + targetDir.getAbsolutePath());
        return new Result(configFile.getAbsolutePath(), log);
    }

    /**
     * Absolute path of a bundled config, or null when {@code name} is not one of the configs the
     * Web UI is allowed to select (also rejects path traversal).
     */
    static String configPath(Context context, String name) {
        String wanted = (name == null || name.isEmpty()) ? CONFIG_NNUE : name.trim();
        for (String allowed : CONFIG_FILES) {
            if (allowed.equals(wanted))
                return new File(engineDirectory(context), allowed).getAbsolutePath();
        }
        return null;
    }

    /** @return true when {@code name} is one of the bundled weight configs. */
    static boolean isKnownConfig(String name) {
        if (name == null)
            return false;
        for (String allowed : CONFIG_FILES)
            if (allowed.equals(name.trim()))
                return true;
        return false;
    }

    /** Report the on-disk state of the bundled files (for diagnostics from the UI). */
    static List<String> describeExtracted(Context context) {
        List<String> lines = new ArrayList<>();
        File dir = engineDirectory(context);
        lines.add("dir=" + dir.getAbsolutePath() + " exists=" + dir.isDirectory()
                  + " version=" + readTrimmed(new File(dir, MARKER_FILE)));
        for (Object[] entry : EXPECTED) {
            File file = new File(dir, (String) entry[0]);
            lines.add(entry[0] + ": exists=" + file.isFile() + " size=" + file.length());
        }
        return lines;
    }

    /**
     * Rewrite every file reference in a config to an absolute path, so the engine never has to
     * resolve it relative to the config directory.
     *
     * @return the number of references rewritten.
     */
    private static int makeConfigPathsAbsolute(File configFile, File dir) throws IOException {
        String text = readText(configFile);
        Matcher matcher = CONFIG_PATH_KEY.matcher(text);
        StringBuffer out = new StringBuffer();
        int rewritten = 0;
        while (matcher.find()) {
            String value = matcher.group(2);
            if (new File(value).isAbsolute()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String absolute = new File(dir, value).getAbsolutePath();
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(matcher.group(1) + " = \"" + absolute + "\""));
            rewritten++;
        }
        matcher.appendTail(out);
        writeText(configFile, out.toString());
        return rewritten;
    }

    private static boolean allPresent(File dir) {
        for (Object[] entry : EXPECTED) {
            File file = new File(dir, (String) entry[0]);
            if (!file.isFile() || file.length() < (Long) entry[1])
                return false;
        }
        return true;
    }

    private static long copyAsset(AssetManager assets, String assetPath, File target)
            throws IOException {
        long total = 0;
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static File engineDirectory(Context context) {
        return new File(context.getFilesDir(), ASSET_DIR);
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    private static String readTrimmed(File file) {
        if (!file.isFile())
            return "";
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[128];
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, "UTF-8").trim();
        } catch (IOException error) {
            return "";
        }
    }

    private static void writeMarker(File marker) {
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(ASSET_VERSION.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // The marker is only an optimisation; failing to write it just re-extracts later.
        }
    }
}
