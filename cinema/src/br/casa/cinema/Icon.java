package br.casa.cinema;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Top-bar icons drawn as paths on a 24x24 grid, so they stay crisp at any size and take any colour. */
final class Icon extends Drawable {
    static final int INPUT = 1, SETTINGS = 2, SLEEP = 3, FRAME = 4;

    final int type;
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    Icon(int type, int color) {
        this.type = type;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(1.7f);
        fill.setStyle(Paint.Style.FILL);
        setColor(color);
    }

    void setColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        switch (type) {
            case INPUT: input(canvas); break;
            case SETTINGS: settings(canvas); break;
            case FRAME: frame(canvas); break;
            default: sleep(canvas); break;
        }
        canvas.restoreToCount(save);
    }

    /** A screen with an arrow coming into it. */
    private void input(Canvas c) {
        c.drawRoundRect(new RectF(3f, 4.5f, 21f, 16.5f), 2.2f, 2.2f, stroke);
        c.drawLine(9f, 20f, 15f, 20f, stroke);
        c.drawLine(12f, 16.5f, 12f, 20f, stroke);
        c.drawLine(7.5f, 10.5f, 13.5f, 10.5f, stroke);
        Path head = new Path();
        head.moveTo(11.2f, 8.2f);
        head.lineTo(13.5f, 10.5f);
        head.lineTo(11.2f, 12.8f);
        c.drawPath(head, stroke);
    }

    /** A gear: eight teeth around a ring, with a hole. */
    private void settings(Canvas c) {
        Path gear = new Path();
        gear.addCircle(12f, 12f, 6.4f, Path.Direction.CW);
        for (int i = 0; i < 8; i++) {
            Path tooth = new Path();
            tooth.addRoundRect(new RectF(10.6f, 2.6f, 13.4f, 6.6f), 0.8f, 0.8f, Path.Direction.CW);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setRotate(i * 45f, 12f, 12f);
            tooth.transform(m);
            gear.op(tooth, Path.Op.UNION);
        }
        Path hole = new Path();
        hole.addCircle(12f, 12f, 2.7f, Path.Direction.CW);
        gear.op(hole, Path.Op.DIFFERENCE);
        c.drawPath(gear, fill);
    }

    /** A picture frame with a mountain and a sun: picture-frame (ambient) mode. */
    private void frame(Canvas c) {
        c.drawRoundRect(new RectF(3f, 4.5f, 21f, 19.5f), 2.4f, 2.4f, stroke);
        Path hill = new Path();
        hill.moveTo(5.5f, 17f);
        hill.lineTo(10f, 11.5f);
        hill.lineTo(13f, 14.5f);
        hill.lineTo(15f, 12.5f);
        hill.lineTo(18.5f, 17f);
        hill.close();
        c.drawPath(hill, fill);
        c.drawCircle(16.2f, 8.6f, 1.6f, fill);
    }

    /** A crescent moon with a small "z": sleep timer. */
    private void sleep(Canvas c) {
        Path moon = new Path();
        moon.addCircle(11f, 13f, 7.5f, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(15.2f, 9.2f, 6.2f, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);
        c.drawPath(moon, fill);
        Path z = new Path();
        z.moveTo(16f, 3.5f);
        z.lineTo(20f, 3.5f);
        z.lineTo(16f, 7.5f);
        z.lineTo(20f, 7.5f);
        Paint thin = new Paint(stroke);
        thin.setStrokeWidth(1.4f);
        c.drawPath(z, thin);
    }

    @Override public void setAlpha(int alpha) { stroke.setAlpha(alpha); fill.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { stroke.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
