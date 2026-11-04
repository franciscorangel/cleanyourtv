package br.casa.cinema;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The one side panel every Cinema menu uses, so they all look and behave alike: Ajustes and the menus of a held
 * title or app. Ajustes has three entries: Ajustes da TV (the TV's own settings), Home (row order, which streamings
 * get a row, hidden apps) and Botões do controle (the app each colour opens). In Home, hold OK on an app to pick it
 * up, move it with Up/Down, OK to drop it. Every change applies at once. Back climbs one level, then closes.
 */
final class SettingsPanel {
    static final int WIDTH = 640;
    static final String KEY_ORDER_MODE = "order_mode", KEY_ORDER = "order", MANUAL = "manual", RECENT = "recent";

    final Home h;
    final FrameLayout layer;
    final View scrim;
    final LinearLayout panel, content;
    final TextView title, note;
    LinearLayout services;
    final List<String> order = new ArrayList<>();       // streaming apps in the order shown
    final List<View> serviceRows = new ArrayList<>();
    Radio recentRadio, manualRadio;
    View returnFocus;
    boolean open;
    int moving = -1;                                      // index of the app being moved, or -1
    final Runnable applyLater;
    Runnable back;                                        // Back inside a sub-screen; null closes the panel

    SettingsPanel(Home h) {
        this.h = h;
        applyLater = h::reload;
        layer = new FrameLayout(h);
        layer.setVisibility(View.GONE);
        scrim = new View(h);
        scrim.setBackgroundColor(0x990A0A0B);
        layer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(h);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFF151518, 0xFF111113}));
        panel.setPadding(h.px(56), h.px(64), h.px(56), h.px(32));
        panel.setElevation(h.px(24));

        title = h.text(40, h.light, Home.TEXT);
        title.setLetterSpacing(-0.02f);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(title);
        note = h.text(18, h.regular, Home.TEXT_3);
        LinearLayout.LayoutParams nl = Home.wrap();
        nl.topMargin = h.px(10);
        panel.addView(note, nl);

        ScrollView scroll = new ScrollView(h);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(h);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, h.px(28), 0, h.px(24));
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        View edge = new View(h);
        edge.setBackgroundColor(0x1FFFFFFF);
        layer.addView(panel, new FrameLayout.LayoutParams(h.px(WIDTH), -1, Gravity.END));
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(Math.max(1, h.px(1.5f)), -1, Gravity.END);
        el.rightMargin = h.px(WIDTH);
        layer.addView(edge, el);
    }

    boolean isOpen() { return open; }
    boolean isMoving() { return moving >= 0; }

    SharedPreferences prefs() { return h.getSharedPreferences(Home.PREFS, Home.MODE_PRIVATE); }

    /** Ajustes: three entries, each with its own screen. Back climbs one level at a time. */
    void show(View from) {
        if (open) return;
        open = true;
        present(from, "Ajustes", null, this::fillRoot, true);
    }

    /** Back: from a sub-screen to the one above it, otherwise close the panel. */
    void back() {
        Runnable b = back;
        if (b != null) b.run(); else hide();
    }

    private void showRoot(String focusTag) {
        present(returnFocus, "Ajustes", null, this::fillRoot, false);
        focus(focusTag);
    }

    private void fillRoot() {
        // A fresh task each time: Sony's settings otherwise pile up one copy per visit, and Back walks through them.
        content.addView(actionRow("Ajustes da TV", () -> {
            hide();
            h.open(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }));
        View home = actionRow("Home", "Linhas e apps", this::showHomeSettings);
        home.setTag("home");
        content.addView(home);
        View keys = actionRow("Botões do controle", "Cores", () -> showKeys(null));
        keys.setTag("keys");
        content.addView(keys);
    }

    /** Ajustes → Home: how the rows are ordered, which streamings get a row, and the apps hidden from the grid. */
    private void showHomeSettings() {
        h.worker.execute(() -> {
            List<String> all = h.orderedCandidates();
            List<String> on = h.enabledServices();
            List<String> hidden = new ArrayList<>();
            for (String pkg : h.hiddenApps()) if (!h.data.label(pkg).isEmpty()) hidden.add(pkg);   // still installed
            h.ui.post(() -> {
                if (!open) return;
                present(returnFocus, "Home", null, () -> fillHome(all, on, hidden), false);
                back = () -> showRoot("home");
            });
        });
    }

    /** Ajustes → Botões do controle: the four colours, each with the app it opens. */
    private void showKeys(String focusTag) {
        present(returnFocus, "Botões do controle", "O app que cada cor do controle abre", () -> {
            for (int i = 0; i < Home.KEY_COLORS.length; i++) {
                int key = i;
                String label = h.data.label(Home.keyApp(prefs(), i));
                View row = actionRow(Home.KEY_NAMES[i], label.isEmpty() ? "Não instalado" : label, () -> chooseKeyApp(key));
                row.setTag("key_" + Home.KEY_COLORS[i]);
                View dot = new View(h);
                dot.setBackground(new Glow(Home.KEY_DOTS[i], 0.42f));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(h.px(24), h.px(24));
                dl.rightMargin = h.px(12);
                ((LinearLayout) row).addView(dot, 0, dl);
                content.addView(row);
            }
        }, false);
        focus(focusTag);
        back = () -> showRoot("keys");
    }

    /** Botões do controle → one colour: every app on the TV, the current one marked. Picking it applies at once. */
    private void chooseKeyApp(int i) {
        String tag = "key_" + Home.KEY_COLORS[i];
        h.worker.execute(() -> {
            List<Item> apps = h.data.apps();
            String current = Home.keyApp(prefs(), i);
            h.ui.post(() -> {
                if (!open) return;
                present(returnFocus, "Botão " + Home.KEY_NAMES[i], "Escolha o app que este botão abre", () -> {
                    for (Item app : apps) {
                        View row = actionRow(app.title, app.pkg.equals(current) ? "Atual" : null, () -> {
                            prefs().edit().putString(tag, app.pkg).apply();
                            showKeys(tag);
                        });
                        if (app.pkg.equals(current)) row.setTag("current");
                        content.addView(row);
                    }
                }, false);
                focus("current");
                back = () -> showKeys(tag);
            });
        });
    }

    private void focus(String tag) {
        View target = tag == null ? null : content.findViewWithTag(tag);
        if (target != null) target.requestFocus();
    }

    /** A plain menu in the same panel: each line runs its action after the panel closes. */
    void showMenu(View from, String heading, String sub, List<String> labels, List<Runnable> actions) {
        if (open) return;
        open = true;
        present(from, heading, sub, () -> {
            for (int i = 0; i < labels.size(); i++) {
                Runnable action = actions.get(i);
                content.addView(actionRow(labels.get(i), () -> { hide(); action.run(); }));
            }
        }, true);
    }

    /** Fills the panel; animate slides it in, otherwise the contents change in place (a sub-screen). */
    private void present(View from, String heading, String sub, Runnable fill, boolean animate) {
        returnFocus = from;
        back = null;
        title.setText(heading);
        note.setText(sub);
        note.setVisibility(sub == null ? View.GONE : View.VISIBLE);
        content.removeAllViews();
        serviceRows.clear();
        moving = -1;
        fill.run();
        ((View) content.getParent()).scrollTo(0, 0);
        if (animate) {
            h.blockContent(true);
            layer.setVisibility(View.VISIBLE);
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(Spring.BASE).start();
            panel.setTranslationX(h.px(WIDTH));
            panel.animate().translationX(0).setDuration(Spring.SLOW).setInterpolator(Spring.UI).start();
        }
        for (int i = 0; i < content.getChildCount(); i++) if (content.getChildAt(i).requestFocus()) break;   // skips headings
    }

    private void fillHome(List<String> all, List<String> on, List<String> hidden) {
        content.addView(section("ORDEM DAS LINHAS", null));
        boolean manual = MANUAL.equals(prefs().getString(KEY_ORDER_MODE, RECENT));
        recentRadio = new Radio(h);
        manualRadio = new Radio(h);
        recentRadio.set(!manual);
        manualRadio.set(manual);
        content.addView(radioRow("Usados por último primeiro", recentRadio, () -> setMode(RECENT)));
        content.addView(radioRow("Minha ordem", manualRadio, () -> setMode(MANUAL)));

        content.addView(section("STREAMINGS NA HOME", "Segure OK em um app para mudar a posição"));
        services = new LinearLayout(h);
        services.setOrientation(LinearLayout.VERTICAL);
        content.addView(services);
        order.clear();
        order.addAll(all);
        for (String pkg : all) {
            View row = toggleRow(pkg, h.data.label(pkg), on.contains(pkg));
            serviceRows.add(row);
            services.addView(row);
        }

        if (hidden.isEmpty()) return;
        View header = section("APPS OCULTOS", "OK mostra o app de novo na Home");
        content.addView(header);
        for (String pkg : hidden) {
            View[] self = new View[1];
            self[0] = actionRow(h.data.label(pkg), () -> {
                Set<String> set = new HashSet<>(h.hiddenApps());
                set.remove(pkg);
                prefs().edit().putStringSet(Home.KEY_HIDDEN_APPS, set).apply();
                int i = content.indexOfChild(self[0]);
                content.removeView(self[0]);
                if (set.isEmpty()) { content.removeView(header); i = content.getChildCount(); }
                View next = content.getChildAt(Math.min(i, content.getChildCount() - 1));
                if (next != null && next.isFocusable()) next.requestFocus(); else content.getChildAt(0).requestFocus();
                applySoon();
            });
            content.addView(self[0]);
        }
    }

    void hide() {
        if (!open) return;
        if (isMoving()) endMove();
        open = false;
        scrim.animate().alpha(0f).setDuration(Spring.BASE).start();
        panel.animate().translationX(h.px(WIDTH)).setDuration(Spring.BASE).setInterpolator(Spring.UI)
                .withEndAction(() -> layer.setVisibility(View.GONE)).start();
        h.blockContent(false);
        if (returnFocus != null) returnFocus.requestFocus();
    }

    // ---------------------------------------------------------------- order

    private void setMode(String mode) {
        prefs().edit().putString(KEY_ORDER_MODE, mode).apply();
        recentRadio.set(RECENT.equals(mode));
        manualRadio.set(MANUAL.equals(mode));
        applySoon();
    }

    /** Up/Down while an app is picked up: it swaps places with its neighbour. */
    void move(int step) {
        int to = moving + step;
        if (to < 0 || to >= order.size()) return;
        String pkg = order.remove(moving);
        order.add(to, pkg);
        View row = serviceRows.remove(moving);
        serviceRows.add(to, row);
        services.removeView(row);
        services.addView(row, to);
        moving = to;
        row.requestFocus();
        prefs().edit().putString(KEY_ORDER, String.join(",", order)).apply();
        if (!manualRadio.on) setMode(MANUAL); else applySoon();
    }

    void endMove() {
        if (moving < 0) return;
        View row = serviceRows.get(moving);
        moving = -1;
        focusLook(row, row.hasFocus(), false);
    }

    private void startMove(View row) {
        moving = serviceRows.indexOf(row);
        focusLook(row, true, true);
    }

    // ---------------------------------------------------------------- rows

    private View section(String label, String hint) {
        LinearLayout box = new LinearLayout(h);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(h.px(18), h.px(36), 0, h.px(10));
        TextView t = h.text(17, h.medium, Home.TEXT_3);
        t.setText(label);
        t.setLetterSpacing(0.08f);
        box.addView(t);
        if (hint != null) {
            TextView s = h.text(16, h.regular, Home.TEXT_4);
            s.setText(hint);
            LinearLayout.LayoutParams lp = Home.wrap();
            lp.topMargin = h.px(6);
            box.addView(s, lp);
        }
        return box;
    }

    private View toggleRow(String pkg, String label, boolean on) {
        LinearLayout row = baseRow();
        View dot = new View(h);
        dot.setBackground(new Glow(h.colorFor(pkg), 0.42f));
        row.addView(dot, new LinearLayout.LayoutParams(h.px(24), h.px(24)));
        TextView name = h.text(22, h.regular, Home.TEXT_2);
        name.setText(label);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.leftMargin = h.px(12);
        row.addView(name, nl);
        Toggle toggle = new Toggle(h, on);
        View sw = new View(h);
        sw.setBackground(toggle);
        row.addView(sw, new LinearLayout.LayoutParams(h.px(56), h.px(32)));
        row.setContentDescription(label);
        row.setOnFocusChangeListener((v, has) -> {
            focusLook(row, has, has && serviceRows.indexOf(row) == moving);
            name.setTextColor(has ? Home.TEXT : Home.TEXT_2);
        });
        row.setOnClickListener(v -> {
            if (isMoving()) { endMove(); return; }
            toggle.set(!toggle.on);
            save(pkg, toggle.on);
        });
        row.setOnLongClickListener(v -> { startMove(row); return true; });
        return row;
    }

    private View radioRow(String label, Radio radio, Runnable choose) {
        LinearLayout row = baseRow();
        TextView name = h.text(22, h.regular, Home.TEXT_2);
        name.setText(label);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View r = new View(h);
        r.setBackground(radio);
        row.addView(r, new LinearLayout.LayoutParams(h.px(26), h.px(26)));
        row.setOnFocusChangeListener((v, has) -> {
            focusLook(row, has, false);
            name.setTextColor(has ? Home.TEXT : Home.TEXT_2);
        });
        row.setOnClickListener(v -> choose.run());
        return row;
    }

    private View actionRow(String label, Runnable action) { return actionRow(label, null, action); }

    /** A line that does something on OK; value, when given, sits quietly on the right (the current choice). */
    private View actionRow(String label, String value, Runnable action) {
        LinearLayout row = baseRow();
        TextView name = h.text(22, h.regular, Home.TEXT_2);
        name.setText(label);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) {
            TextView v = h.text(20, h.regular, Home.TEXT_3);
            v.setText(value);
            LinearLayout.LayoutParams vl = Home.wrap();
            vl.rightMargin = h.px(12);
            row.addView(v, vl);
        }
        TextView chevron = h.text(26, h.light, Home.TEXT_3);
        chevron.setText("›");
        row.addView(chevron);
        row.setOnFocusChangeListener((v, has) -> {
            focusLook(row, has, false);
            name.setTextColor(has ? Home.TEXT : Home.TEXT_2);
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(h);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setPadding(h.px(18), 0, h.px(18), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, h.px(72)));
        return row;
    }

    /** Focus: a quiet lit surface. Picked up for moving: brighter, with a firm white edge and a lift. */
    private void focusLook(View row, boolean has, boolean picked) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(h.px(14));
        d.setColor(picked ? 0x2EFFFFFF : has ? 0x1FFFFFFF : 0x00000000);
        if (picked) d.setStroke(h.px(2), 0xF2FAFAFA);
        else if (has) d.setStroke(Math.max(1, h.px(1.5f)), 0x33FFFFFF);
        row.setBackground(d);
        row.animate().scaleX(picked ? 1.03f : 1f).scaleY(picked ? 1.03f : 1f).setDuration(Spring.FAST).start();
    }

    private void save(String pkg, boolean on) {
        Set<String> set = new HashSet<>(h.enabledServices());
        if (on) set.add(pkg); else set.remove(pkg);
        prefs().edit().putStringSet(Home.KEY_SERVICES, set).apply();
        applySoon();
    }

    private void applySoon() {
        h.ui.removeCallbacks(applyLater);
        h.ui.postDelayed(applyLater, 450);       // several quick changes become one reload
    }

    /** A Cinema switch: white track with a dark knob when on, a quiet track when off; the knob glides. */
    static final class Toggle extends Drawable {
        final Home h;
        boolean on;
        float t;
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Toggle(Home h, boolean on) {
            this.h = h;
            this.on = on;
            this.t = on ? 1f : 0f;
        }

        void set(boolean value) {
            on = value;
            ValueAnimator a = ValueAnimator.ofFloat(t, value ? 1f : 0f);
            a.setDuration(Spring.FAST);
            a.setInterpolator(Spring.UI);
            a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidateSelf(); });
            a.start();
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float hgt = b.height(), rad = hgt / 2f;
            track.setColor(ProgressGlow.blend(0xFF3A3A3F, 0xFFFAFAFA, t));
            r.set(b);
            c.drawRoundRect(r, rad, rad, track);
            knob.setColor(ProgressGlow.blend(0xFFA1A1AA, 0xFF0A0A0B, t));
            float cx = b.left + rad + (b.width() - hgt) * t;
            c.drawCircle(cx, b.exactCenterY(), rad - h.px(4), knob);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A radio mark: a quiet ring, filled with a white dot when chosen. */
    static final class Radio extends Drawable {
        final Home h;
        boolean on;
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        Radio(Home h) {
            this.h = h;
            ring.setStyle(Paint.Style.STROKE);
            dot.setColor(Home.TEXT);
        }

        void set(boolean value) { on = value; invalidateSelf(); }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            ring.setStrokeWidth(h.px(2));
            ring.setColor(on ? Home.TEXT : 0xFF6B6B73);
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r - h.px(1.5f), ring);
            if (on) c.drawCircle(b.exactCenterX(), b.exactCenterY(), r * 0.42f, dot);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
