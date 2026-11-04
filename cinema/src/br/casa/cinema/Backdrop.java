package br.casa.cinema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;

/**
 * The full-screen art behind Home, pre-composed into ONE bitmap per title on a worker thread: the blurred fill,
 * the sharp image (whole, right-aligned, upscaled at most 2x, feathered into the blur, slightly dimmed), a whisper
 * of the image's own colour, and the scrims that keep the text readable. On screen that is a single opaque layer
 * (two only during a crossfade), instead of six translucent full-screen layers recomposed on every focus frame,
 * which the MT5891's GPU could not keep up with at 1080p.
 */
final class Backdrop {
    static final int BASE = 0xFF0A0A0B;
    static final float MAX_UPSCALE = 2f;

    final ExecutorService worker;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ImageView frameA, frameB;
    final View curtain;
    final int width, height;
    boolean aOnTop;
    int generation;
    /** Runs once, when the first composed frame is fully opaque on screen. */
    Runnable onFirstShown;

    Backdrop(Context context, ExecutorService worker, int width, int height) {
        this.worker = worker;
        this.width = width;
        this.height = height;
        frameA = frame(context);
        frameB = frame(context);
        curtain = new View(context);
        curtain.setBackgroundColor(BASE);
        curtain.setAlpha(0f);
    }

    void attach(FrameLayout root) {
        root.addView(frameA, new FrameLayout.LayoutParams(-1, -1));
        root.addView(frameB, new FrameLayout.LayoutParams(-1, -1));
        // Apps grid: a near-black curtain over the art (drawn only while its alpha is above zero).
        root.addView(curtain, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Grid mode on: the art goes away under a near-black ground; off: it comes back. */
    void setDark(boolean dark) {
        curtain.animate().alpha(dark ? 0.96f : 0f).setDuration(Spring.SLOW).setInterpolator(Spring.UI).start();
    }

    void show(Bitmap art) {
        int gen = ++generation;
        worker.execute(() -> {
            if (gen != generation) return;                 // a newer title arrived while this one waited
            Bitmap composed = compose(art);
            ui.post(() -> {
                if (gen != generation) { composed.recycle(); return; }
                ImageView in = aOnTop ? frameB : frameA, out = aOnTop ? frameA : frameB;
                aOnTop = !aOnTop;
                in.animate().cancel();
                out.animate().cancel();
                in.setImageBitmap(composed);
                // Both frames are opaque and stay in place under the content; the crossfade is two alpha moves.
                // Interrupted mid-fade: never snap a half-visible frame back to 0.
                in.setAlpha(Math.min(in.getAlpha(), 0.15f));
                in.animate().alpha(1f).setDuration(Spring.SLOW).setInterpolator(Spring.UI).withEndAction(() -> {
                    if (onFirstShown != null) { onFirstShown.run(); onFirstShown = null; }
                }).start();
                out.animate().alpha(0f).setDuration(Spring.SLOW).setInterpolator(Spring.UI).start();
            });
        });
    }

    /** Everything the backdrop shows, drawn once in software into a screen-sized opaque bitmap. */
    Bitmap compose(Bitmap art) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawColor(BASE);

        // 1. Blurred fill: never pixelated, never a cropped face.
        Bitmap small = Bitmap.createScaledBitmap(art, 160, 90, true);
        Bitmap blurred = blur(small, 8, 3);
        small.recycle();
        c.drawBitmap(blurred, null, new Rect(0, 0, width, height), p);
        blurred.recycle();

        // 2. The sharp art, whole, top-right, feathered left and bottom (and top if it does not reach it).
        float bw = art.getWidth(), bh = art.getHeight();
        float scale = Math.min(Math.min(width / bw, height / bh), MAX_UPSCALE);
        float w = bw * scale, h = bh * scale, left = width - w, top = h < height ? (height - h) * 0.15f : 0;
        RectF drawn = new RectF(left, top, left + w, top + h);
        int save = c.saveLayer(drawn, null);
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(left, top);
        Paint art2 = new Paint(Paint.FILTER_BITMAP_FLAG);
        art2.setColorFilter(new LightingColorFilter(0xFFC4C4C4, 0));
        c.drawBitmap(art, m, art2);
        Paint mask = new Paint();
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        mask.setShader(new LinearGradient(left, 0, left + w * 0.55f, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(drawn, mask);
        mask.setShader(new LinearGradient(0, drawn.bottom, 0, drawn.bottom - h * 0.58f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(drawn, mask);
        if (top > 1) {
            mask.setShader(new LinearGradient(0, top, 0, top + h * 0.18f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRect(drawn, mask);
        }
        c.restoreToCount(save);

        // 3. No colour wash: the ground stays the layout's neutral near-black whatever the art's hue.
        Paint g = new Paint();

        // 4. Scrims (the Cinema layout's values): text side, then rows side.
        g.setShader(new LinearGradient(0, 0, width, 0,
                new int[] {a(BASE, 0.94f), a(BASE, 0.80f), a(BASE, 0.18f), a(BASE, 0f)},
                new float[] {0f, 0.30f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, width, height, g);
        g.setShader(new LinearGradient(0, height, 0, 0,
                new int[] {a(BASE, 1f), a(BASE, 0.92f), a(BASE, 0f)},
                new float[] {0f, 0.36f, 0.66f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, width, height, g);
        return out;
    }

    /** The image's dominant hue as a deep, saturated colour that reads as light, never as a flat grey. */
    static int ambientColor(Bitmap small) {
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        float[] hsv = new float[3];
        double sx = 0, sy = 0, weight = 0;
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            if (hsv[2] < 0.12f || hsv[2] > 0.97f) continue;
            double wgt = hsv[1] * hsv[2];
            double rad = Math.toRadians(hsv[0]);
            sx += Math.cos(rad) * wgt;
            sy += Math.sin(rad) * wgt;
            weight += wgt;
        }
        if (weight < 1) return BASE;
        float hue = (float) ((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360);
        float sat = (float) Math.min(0.75, Math.max(0.35, weight / px.length * 2.2));
        return Color.HSVToColor(new float[] {hue, sat, 0.42f});
    }

    /** Box blur repeated `passes` times (close to Gaussian), then darkened and partly desaturated. */
    static Bitmap blur(Bitmap src, int radius, int passes) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            boxH(a, b, w, h, radius);
            boxV(b, a, w, h, radius);
        }
        for (int i = 0; i < a.length; i++) {
            int c = a[i];
            int r0 = (c >> 16) & 0xFF, g0 = (c >> 8) & 0xFF, b0 = c & 0xFF;
            int grey = (r0 * 30 + g0 * 59 + b0 * 11) / 100;
            int r = (grey + (r0 - grey) * 6 / 10) * 42 / 100;
            int g = (grey + (g0 - grey) * 6 / 10) * 42 / 100;
            int bl = (grey + (b0 - grey) * 6 / 10) * 42 / 100;
            a[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + Math.min(w - 1, Math.max(0, i))];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[row + Math.min(w - 1, x + r + 1)], sub = in[row + Math.max(0, x - r)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[Math.min(h - 1, Math.max(0, i)) * w + x];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[Math.min(h - 1, y + r + 1) * w + x], sub = in[Math.max(0, y - r) * w + x];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static ImageView frame(Context context) {
        ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setAlpha(0f);
        return v;
    }

    static int a(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }
}
