package com.gomocalc.app.recognition;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 取景框上的绿色棋盘轮廓。预览是按比例完整显示的，所以比例坐标直接映射到显示区域。 */
final class QuadOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] quad;
    private float imageAspect = 3f / 4f;  // 宽 / 高

    QuadOverlayView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.rgb(0, 230, 80));
        paint.setStrokeWidth(3f * context.getResources().getDisplayMetrics().density);
    }

    /** @param points 8 个 0~1 的比例坐标；为空时不显示 */
    void setQuad(float[] points, float aspect) {
        quad = points;
        imageAspect = aspect;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (quad == null) return;
        float viewW = getWidth(), viewH = getHeight();
        float shownW = viewW, shownH = viewW / imageAspect;
        if (shownH > viewH) {
            shownH = viewH;
            shownW = viewH * imageAspect;
        }
        float left = (viewW - shownW) / 2, top = (viewH - shownH) / 2;
        path.reset();
        path.moveTo(left + quad[0] * shownW, top + quad[1] * shownH);
        for (int i = 1; i < 4; i++) path.lineTo(left + quad[i * 2] * shownW, top + quad[i * 2 + 1] * shownH);
        path.close();
        canvas.drawPath(path, paint);
    }
}
