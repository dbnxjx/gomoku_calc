package com.gomocalc.app.recognition;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 拍照的取景页：实时显示绿色棋盘轮廓，结果是一张转正后的图片路径。 */
public class RecognitionCameraActivity extends AppCompatActivity {
    static final String EXTRA_IMAGE_PATH = "imagePath";
    private static final String TAG = "BoardRecognition";

    private static final float SCORE_MIN = 0.3f;  // 和 pipeline/recognize.py 的 CORNER_SCORE_MIN 相同
    private static final long HOLD_MS = 250;  // 偶尔一帧没认出时不让框闪烁

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();

    private PreviewView previewView;
    private QuadOverlayView overlay;
    private TextView hint;
    private View shutter;
    private ImageCapture imageCapture;
    private ProcessCameraProvider provider;
    private boolean busy;
    private volatile boolean closing;
    private long lastSeenAt;
    private int speedFrames;
    private double speedTotalMs;
    private long speedWindowStart;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else {
                    shutter.setEnabled(false);
                    shutter.setAlpha(0.3f);
                    hint.setText("没有相机权限，可以取消后从相册选图");
                }
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        buildLayout();
        RecognitionFiles.clear(this);
        analysisExecutor.execute(() -> RecognitionNative.ensureReady(this));
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else permissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new QuadOverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setText("把棋盘放进取景框，绿框对准后拍照");
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        hintParams.topMargin = dp(24);
        root.addView(hint, hintParams);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), dp(12), dp(24), dp(12));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        barParams.bottomMargin = dp(32);

        TextView cancel = textButton("取消");
        cancel.setOnClickListener(v -> finishCancelled());
        bar.addView(cancel, new LinearLayout.LayoutParams(0, dp(72), 1f));

        FrameLayout shutterBox = new FrameLayout(this);
        shutter = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(5), Color.rgb(170, 170, 170));
        shutter.setBackground(ring);
        shutter.setContentDescription("拍照");
        shutter.setOnClickListener(v -> takePhoto());
        shutterBox.addView(shutter, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        bar.addView(shutterBox, new LinearLayout.LayoutParams(0, dp(72), 1f));

        // 右边留空，让快门保持在正中
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(72), 1f));

        root.addView(bar, barParams);
        setContentView(root);
    }

    private TextView textButton(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                provider = ProcessCameraProvider.getInstance(this).get();
            } catch (Exception error) {
                hint.setText("无法打开相机，可以取消后从相册选图");
                return;
            }
            bindUseCases();
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        // 预览、拍照和分析都用 4:3 全画幅，这样框的位置和拍到的内容一致
        ResolutionSelector fourThree = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build();
        ResolutionSelector analysisSize = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();

        Preview preview = new Preview.Builder().setResolutionSelector(fourThree).build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(fourThree)
                .build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(analysisSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyze);

        try {
            provider.unbindAll();
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis);
        } catch (Exception error) {
            hint.setText("无法打开相机，可以取消后从相册选图");
        }
    }

    private void analyze(@NonNull ImageProxy image) {
        try {
            if (closing || !RecognitionNative.ensureReady(this)) return;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rotation = image.getImageInfo().getRotationDegrees();
            long started = SystemClock.elapsedRealtimeNanos();
            float[] result = RecognitionNative.detectLive(buffer, image.getWidth(), image.getHeight(), plane.getRowStride(), rotation);
            logSpeed(started, image);
            if (result == null) return;
            boolean swapped = rotation == 90 || rotation == 270;
            float aspect = swapped ? (float) image.getHeight() / image.getWidth() : (float) image.getWidth() / image.getHeight();
            boolean visible = result[0] >= SCORE_MIN && result[1] > 0.5f;
            float[] quad = null;
            if (visible) {
                quad = new float[8];
                System.arraycopy(result, 2, quad, 0, 8);
            }
            showQuad(quad, aspect);
        } finally {
            image.close();
        }
    }

    // 每 60 帧记一次平均耗时和帧率，用来核对真实相机帧上的速度
    private void logSpeed(long started, ImageProxy image) {
        speedTotalMs += (SystemClock.elapsedRealtimeNanos() - started) / 1e6;
        if (++speedFrames < 60) return;
        long now = SystemClock.elapsedRealtime();
        if (speedWindowStart > 0) {
            Log.i(TAG, String.format(Locale.US, "live %dx%d 单帧 %.1fms 分析帧率 %.1f",
                    image.getWidth(), image.getHeight(), speedTotalMs / speedFrames, speedFrames * 1000.0 / (now - speedWindowStart)));
        }
        speedWindowStart = now;
        speedFrames = 0;
        speedTotalMs = 0;
    }

    private void showQuad(float[] quad, float aspect) {
        runOnUiThread(() -> {
            long now = SystemClock.elapsedRealtime();
            if (quad != null) {
                lastSeenAt = now;
                overlay.setQuad(quad, aspect);
            } else if (now - lastSeenAt > HOLD_MS) {
                overlay.setQuad(null, aspect);
            }
        });
    }

    private void takePhoto() {
        if (busy || imageCapture == null) return;
        setBusy("正在识别…");
        imageCapture.takePicture(fileExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    int rotation = image.getImageInfo().getRotationDegrees();
                    image.close();
                    finishWith(RecognitionFiles.saveUpright(RecognitionCameraActivity.this, data, rotation));
                } catch (IOException | RuntimeException error) {
                    fail("保存照片失败: " + error.getMessage());
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException error) {
                fail("拍照失败: " + error.getMessage());
            }
        });
    }

    private void setBusy(String message) {
        busy = true;
        hint.setText(message);
        shutter.setAlpha(0.4f);
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            busy = false;
            shutter.setAlpha(1f);
            hint.setText("把棋盘放进取景框，绿框对准后拍照");
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void finishWith(String path) {
        closing = true;
        runOnUiThread(() -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_IMAGE_PATH, path));
            finish();
        });
    }

    private void finishCancelled() {
        closing = true;
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onDestroy() {
        closing = true;
        if (provider != null) provider.unbindAll();
        analysisExecutor.shutdown();
        fileExecutor.shutdown();
        super.onDestroy();
    }
}
