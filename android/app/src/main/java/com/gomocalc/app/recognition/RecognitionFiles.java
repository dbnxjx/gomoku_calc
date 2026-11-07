package com.gomocalc.app.recognition;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 识别用到的临时图片：都存在缓存目录，已转正并限制大小。 */
final class RecognitionFiles {
    /** 棋盘格距远大于分类器需要的 32 像素，更大的图只会占内存。和 export/app_parity/compare.py 一致。 */
    private static final int MAX_SIDE = 2048;
    private static final int JPEG_QUALITY = 92;

    private RecognitionFiles() {}

    static File folder(Context context) {
        File dir = new File(context.getCacheDir(), "recognition");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建缓存目录");
        return dir;
    }

    /** 新的一次拍照开始前清掉上一次的图。 */
    static void clear(Context context) {
        File[] files = folder(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.delete()) file.deleteOnExit();
        }
    }

    /** 相册图片的方向记录在 EXIF 里。 */
    static int exifRotation(byte[] data) {
        try {
            int value = new ExifInterface(new ByteArrayInputStream(data))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (value == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (value == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (value == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (IOException ignored) {
            // 没有 EXIF 的格式（如 PNG）按原方向处理
        }
        return 0;
    }

    /** 解码、转正、缩到上限后存成 JPEG，返回文件路径。 */
    static String saveUpright(Context context, byte[] encoded, int rotation) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法读取这张图片");

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2;
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        if (decoded == null) throw new IOException("无法读取这张图片");

        Matrix matrix = new Matrix();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(decoded.getWidth(), decoded.getHeight()));
        if (scale < 1f) matrix.postScale(scale, scale);
        if (rotation != 0) matrix.postRotate(rotation);
        Bitmap upright = matrix.isIdentity() ? decoded
                : Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        File file = new File(folder(context), "photo_" + System.currentTimeMillis() + ".jpg");
        writeJpeg(upright, file);
        if (upright != decoded) upright.recycle();
        decoded.recycle();
        return file.getAbsolutePath();
    }

    static Bitmap readBitmap(String path) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) throw new IOException("无法读取图片: " + path);
        return bitmap;
    }

    static void writeJpeg(Bitmap bitmap, File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw new IOException("保存图片失败");
        }
    }
}
