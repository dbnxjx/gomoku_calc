package com.gomocalc.app.recognition;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

import java.nio.ByteBuffer;

/** librecognition_jni.so 的入口。模型只加载一次；只有 arm64 设备有这个库。 */
public final class RecognitionNative {
    private static boolean libraryLoaded;
    private static boolean ready;

    private RecognitionNative() {}

    /** 加载库和模型，耗时操作，不要放在主线程。 */
    public static synchronized boolean ensureReady(Context context) {
        if (ready) return true;
        try {
            if (!libraryLoaded) {
                System.loadLibrary("recognition_jni");
                libraryLoaded = true;
            }
        } catch (UnsatisfiedLinkError error) {
            return false;
        }
        ready = nativeInit(context.getApplicationContext().getAssets());
        return ready;
    }

    /** @return {分数, 能否构成棋盘, x0, y0, ..., x3, y3}，坐标是转正后图像上 0~1 的比例 */
    public static float[] detectLive(ByteBuffer rgba, int width, int height, int stride, int rotation) {
        return nativeDetectLive(rgba, width, height, stride, rotation);
    }

    /** @param manualQuad 手动裁切的 8 个坐标，为空时自动定位 */
    public static String recognize(Bitmap bitmap, float[] manualQuad) {
        return nativeRecognize(bitmap, manualQuad);
    }

    public static boolean renderCrop(Bitmap source, float[] quad, int boardSize, Bitmap target) {
        return nativeRenderCrop(source, quad, boardSize, target);
    }

    private static native boolean nativeInit(AssetManager assets);
    private static native float[] nativeDetectLive(ByteBuffer rgba, int width, int height, int stride, int rotation);
    private static native String nativeRecognize(Bitmap bitmap, float[] manualQuad);
    private static native boolean nativeRenderCrop(Bitmap source, float[] quad, int boardSize, Bitmap target);
}
