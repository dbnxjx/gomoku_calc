package com.gomocalc.app.recognition;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 网页调用的拍照识别接口。capture 打开取景页并识别；recognize 用手动裁切的四角重新识别。 */
@CapacitorPlugin(name = "BoardRecognition")
public class BoardRecognitionPlugin extends Plugin {
    private static final int CROP_SIDE = 720;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            notifyListeners("backPressed", new JSObject());
        }
    };

    @Override
    public void load() {
        getActivity().getOnBackPressedDispatcher().addCallback(getActivity(), backCallback);
    }

    /** @param call {@code source}: {@code camera} 或 {@code gallery} */
    @PluginMethod
    public void capture(PluginCall call) {
        if ("gallery".equals(call.getString("source"))) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(call, intent, "onGalleryResult");
            return;
        }
        Intent intent = new Intent(getContext(), RecognitionCameraActivity.class);
        startActivityForResult(call, intent, "onCaptureResult");
    }

    /** 打开时系统返回键交给网页处理（结果页、裁切页要一层层退），而不是退出应用。 */
    @PluginMethod
    public void interceptBack(PluginCall call) {
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        getActivity().runOnUiThread(() -> {
            backCallback.setEnabled(enabled);
            call.resolve();
        });
    }

    @ActivityCallback
    private void onGalleryResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        Uri uri = data == null ? null : data.getData();
        if (result.getResultCode() != Activity.RESULT_OK || uri == null) {
            resolveCancelled(call);
            return;
        }
        executor.execute(() -> {
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法打开这张图片");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[64 * 1024];
                int read;
                while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
                byte[] bytes = out.toByteArray();
                RecognitionFiles.clear(getContext());
                String path = RecognitionFiles.saveUpright(getContext(), bytes, RecognitionFiles.exifRotation(bytes));
                run(call, path, null);
            } catch (IOException | RuntimeException error) {
                call.reject("读取图片失败: " + error.getMessage());
            }
        });
    }

    private static void resolveCancelled(PluginCall call) {
        JSObject cancelled = new JSObject();
        cancelled.put("cancelled", true);
        call.resolve(cancelled);
    }

    @ActivityCallback
    private void onCaptureResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null) {
            resolveCancelled(call);
            return;
        }
        String path = data.getStringExtra(RecognitionCameraActivity.EXTRA_IMAGE_PATH);
        executor.execute(() -> run(call, path, null));
    }

    @PluginMethod
    public void recognize(PluginCall call) {
        String path = call.getString("imagePath");
        JSArray quad = call.getArray("quad");
        if (path == null) {
            call.reject("缺少图片路径");
            return;
        }
        float[] manual = null;
        if (quad != null) {
            try {
                manual = toFloats(quad);
            } catch (JSONException error) {
                call.reject("四角坐标不正确");
                return;
            }
        }
        float[] manualQuad = manual;
        executor.execute(() -> run(call, path, manualQuad));
    }

    private static float[] toFloats(JSONArray quad) throws JSONException {
        if (quad.length() != 4) throw new JSONException("需要 4 个角点");
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            JSONArray point = quad.getJSONArray(i);
            out[i * 2] = (float) point.getDouble(0);
            out[i * 2 + 1] = (float) point.getDouble(1);
        }
        return out;
    }

    private void run(PluginCall call, String path, float[] manualQuad) {
        try {
            if (!RecognitionNative.ensureReady(getContext())) {
                call.reject("这台设备不支持棋盘识别");
                return;
            }
            Bitmap bitmap = RecognitionFiles.readBitmap(path);
            String json = RecognitionNative.recognize(bitmap, manualQuad);
            if (json == null) {
                bitmap.recycle();
                call.reject("识别失败");
                return;
            }
            JSObject result = new JSObject(json);
            result.put("imagePath", path);
            result.put("width", bitmap.getWidth());
            result.put("height", bitmap.getHeight());
            result.put("cropPath", writeCrop(bitmap, result));
            bitmap.recycle();
            call.resolve(result);
        } catch (IOException | JSONException | RuntimeException error) {
            call.reject("识别失败: " + error.getMessage());
        }
    }

    private String writeCrop(Bitmap bitmap, JSObject result) throws JSONException, IOException {
        float[] quad = toFloats(result.getJSONArray("quad"));
        Bitmap crop = Bitmap.createBitmap(CROP_SIDE, CROP_SIDE, Bitmap.Config.ARGB_8888);
        if (!RecognitionNative.renderCrop(bitmap, quad, result.getInt("n"), crop)) throw new IOException("生成裁切图失败");
        File file = new File(RecognitionFiles.folder(getContext()), "crop_" + System.currentTimeMillis() + ".jpg");
        RecognitionFiles.writeJpeg(crop, file);
        crop.recycle();
        return file.getAbsolutePath();
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdown();
        super.handleOnDestroy();
    }
}
