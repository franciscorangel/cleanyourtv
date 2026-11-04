package br.casa.cinema;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A progress bar that emits light in its source's colour: a thin track, a fill that brightens toward the
 * playhead, a soft halo built from stacked translucent capsules (a cheap stand-in for a Gaussian glow, no
 * BlurMaskFilter), and a lit head at the current position. Drawn once; nothing animates per frame.
 */
final class ProgressGlow extends Drawable {
    final int color;
    final float progress;      // 0..1
    final float thickness;     // bar thickness in px
    final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();

    ProgressGlow(int color, int percent, float thickness) {
        this.color = color;
        this.progress = Math.max(0.02f, Math.min(1f, percent / 100f));
        this.thickness = thickness;
        track.setColor(0x38FFFFFF);
        halo.setColor(color);
    }

    @Override protected void onBoundsChange(Rect b) {
        float cy = b.exactCenterY();
        float end = b.left + b.width() * progress;
        int deep = blend(color, 0xFF000000, 0.25f), bright = blend(color, 0xFFFFFFFF, 0.35f);
        fill.setShader(new LinearGradient(b.left, cy, end, cy, deep, bright, Shader.TileMode.CLAMP));
        float hr = thickness * 2.2f;
        head.setShader(new RadialGradient(end, cy, hr,
                new int[] {0xFFFFFFFF, (color & 0x00FFFFFF) | 0xCC000000, (color & 0x00FFFFFF)},
                new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float cy = b.exactCenterY(), h = thickness / 2f;
        float end = b.left + b.width() * progress;

        r.set(b.left, cy - h, b.right, cy + h);
        c.drawRoundRect(r, h, h, track);

        // Halo: concentric capsules, each wider and fainter, so the light falls off smoothly.
        int base = color & 0x00FFFFFF;
        float reach = thickness * 2.4f;
        for (int i = 3; i >= 1; i--) {
            float grow = reach * i / 3f;
            int alpha = (int) (52 * (1f - (i - 1) / 3f));
            halo.setColor(base | (alpha << 24));
            r.set(b.left - grow * 0.4f, cy - h - grow, end + grow * 0.4f, cy + h + grow);
            c.drawRoundRect(r, h + grow, h + grow, halo);
        }

        r.set(b.left, cy - h, end, cy + h);
        c.drawRoundRect(r, h, h, fill);
        c.drawCircle(end, cy, thickness * 2.2f, head);
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
