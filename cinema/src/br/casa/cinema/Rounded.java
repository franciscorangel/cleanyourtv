package br.casa.cinema;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Card art with its rounded corners baked into the draw (a BitmapShader on a round rect, centre-cropped), so the
 * card needs no clipToOutline: on the MT5891, dozens of rounded clips per frame were the costliest draw commands.
 * Also carries the focus reflection: a diagonal band of light drawn inside the same round rect, no extra view.
 */
final class Rounded extends Drawable {
    final Bitmap bitmap;
    final float radius;
    final BitmapShader shader;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Matrix matrix = new Matrix();
    final RectF rect = new RectF();
    float sheen = -1f;   // 0..1 while the reflection crosses, below 0 when idle

    Rounded(Bitmap bitmap, float radius) {
        this.bitmap = bitmap;
        this.radius = radius;
        shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
    }

    @Override protected void onBoundsChange(Rect b) {
        rect.set(b);
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float scale = Math.max(b.width() / bw, b.height() / bh);
        matrix.setScale(scale, scale);
        matrix.postTranslate(b.left + (b.width() - bw * scale) / 2f, b.top + (b.height() - bh * scale) / 2f);
        shader.setLocalMatrix(matrix);
    }

    void setSheen(float progress) {
        sheen = progress;
        invalidateSelf();
    }

    @Override public void draw(Canvas c) {
        c.drawRoundRect(rect, radius, radius, paint);
        if (sheen >= 0f) {
            float w = rect.width(), band = w * 0.45f;
            float x = rect.left - band + (w + band * 2) * sheen;
            sheenPaint.setShader(new LinearGradient(x, rect.top, x + band, rect.top + band * 0.5f,
                    new int[] {0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, radius, radius, sheenPaint);
        }
    }

    @Override public int getIntrinsicWidth() { return bitmap.getWidth(); }
    @Override public int getIntrinsicHeight() { return bitmap.getHeight(); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
