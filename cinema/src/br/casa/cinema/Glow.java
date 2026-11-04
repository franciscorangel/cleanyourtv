package br.casa.cinema;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** A source's colour as a lit dot: a solid core with a soft halo of the same colour around it. */
final class Glow extends Drawable {
    final int color;
    final float coreRatio;
    final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** coreRatio: core diameter as a fraction of the drawable (the rest is halo). */
    Glow(int color, float coreRatio) {
        this.color = color;
        this.coreRatio = coreRatio;
        core.setColor(color);
    }

    @Override protected void onBoundsChange(android.graphics.Rect b) {
        float r = Math.min(b.width(), b.height()) / 2f;
        int c = color & 0x00FFFFFF;
        halo.setShader(new RadialGradient(b.exactCenterX(), b.exactCenterY(), Math.max(1, r),
                new int[] {0x99000000 | c, 0x33000000 | c, 0x00000000 | c}, new float[] {0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float r = Math.min(b.width(), b.height()) / 2f;
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, halo);
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r * coreRatio, core);
    }

    @Override public void setAlpha(int alpha) { core.setAlpha(alpha); halo.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { core.setColorFilter(cf); halo.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
