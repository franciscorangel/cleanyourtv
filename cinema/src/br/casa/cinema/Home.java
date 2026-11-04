package br.casa.cinema;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Cinema" Home: the focused title's art fills the screen, with Continuar assistindo, Para você and Apps rows.
 * Plain framework views, sized from a 1920x1080 design (px(n) scales to the real screen).
 */
public final class Home extends Activity {
    static final int BG = 0xFF0A0A0B, TEXT = 0xFFFAFAFA, TEXT_2 = 0xFFD4D4D8, TEXT_3 = 0xFFA1A1AA, TEXT_4 = 0xFF8E8E96;
    static final Locale PT = new Locale("pt", "BR");
    static final Map<String, Integer> APP_COLORS = new HashMap<>();
    static {
        // Dots follow the remote's colour keys: red YouTube, green HBO Max, yellow Globoplay, blue Stremio.
        APP_COLORS.put("com.google.android.youtube.tv", 0xFFE5484D);
        APP_COLORS.put("com.wbd.stream", 0xFF3DD68C);
        APP_COLORS.put("com.globo.globotv", 0xFFF5C451);
        APP_COLORS.put("com.stremio.one", 0xFF5B8DEF);
        APP_COLORS.put("com.netflix.ninja", 0xFFE4E4E7);
        APP_COLORS.put("com.sony.dtv.tvx", 0xFF4FC3A1);
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    Data data;
    Images images;
    float scale;
    Typeface light, regular, medium;

    final ExecutorService fx = Executors.newSingleThreadExecutor();
    Backdrop backdrop;
    String bgUri;
    TextView clock, date, heroApp, heroKind, heroTitle, heroMeta, heroDesc, heroLeft, heroAction;
    View heroDot;
    LinearLayout heroProgress;
    View heroBarFill;
    LinearLayout rows;
    final List<Row> rowList = new ArrayList<>();
    Row continueRow, appsRow;
    final List<Row> serviceRows = new ArrayList<>();
    static final int APP_COLUMNS = 6;
    View topFrame, topSleep, topInputs, topSettings;
    View headerView, clipView;
    SettingsPanel settings;
    View heroView;
    Item heroItem;
    boolean firstBind = true;
    final Runnable heroUpdate = this::applyHero;
    final Runnable backdropUpdate = () -> {
        Item it = heroItem;
        if (it != null) setBackdrop(it.backdropUri != null ? it.backdropUri : it.imageUri, it.imageUri);
    };
    boolean firstBackdrop = true;
    Item pendingHero;
    // A method reference, not an anonymous class: d8 8.2 crashes on anonymous classes in field initializers.
    final Runnable tick = this::tickClock;

    void tickClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", PT).format(now));
        date.setText(new SimpleDateFormat("EEE, d 'de' MMMM", PT).format(now));
        ui.postDelayed(tick, 30_000);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        data = new Data(this);
        images = new Images(this);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        light = font(300);
        regular = font(400);
        medium = font(500);
        setContentView(build());
        wantApps = ACTION_APPS.equals(getIntent().getAction());
    }

    @Override protected void onResume() {
        super.onResume();
        tick.run();
        reload();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    /** Sent by the remote's Apps button (br.casa.tvkeys): open Home straight on the apps grid. */
    static final String ACTION_APPS = "br.casa.cinema.action.APPS";
    boolean wantApps;

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (ACTION_APPS.equals(intent.getAction())) { showApps(); return; }
        // Home pressed while Home is in front: back to the first title.
        focusFirst();
    }

    void showApps() {
        if (appsRow == null || appsRow.cards.isEmpty()) { wantApps = true; return; }   // after the first bind
        wantApps = false;
        View first = appsRow.cards.get(0);
        if (first.hasFocus()) scrollToTile(first); else first.requestFocus();
    }

    @Override public void onBackPressed() {
        View focus = getCurrentFocus();
        Row first = firstVisibleRow();
        if (first != null && (focus == null || first.items.isEmpty() || focus != first.cards.get(0))) focusFirst();
        // Home never finishes on Back.
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        images.pool.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- data

    void reload() {
        worker.execute(() -> {
            List<Item> apps = data.apps();
            Set<String> hiddenApps = hiddenApps();
            apps.removeIf(a -> hiddenApps.contains(a.pkg));
            List<Item> cont = data.continueWatching();
            List<String> enabled = enabledServices();
            Set<String> hidden = hiddenTitles(cont);
            List<Item> shown = new ArrayList<>();
            for (Item it : cont) if (enabled.contains(it.pkg) && !hidden.contains(it.hiddenKey())) shown.add(it);
            List<Item> contFinal = Data.capPerApp(shown);
            List<Data.Service> services = data.services(shown, enabled, !manualOrder());
            Log.i(Data.TAG, "reload: continue=" + cont.size() + " services=" + services.size() + " apps=" + apps.size());
            StringBuilder order = new StringBuilder();
            for (int i = 0; i < Math.min(5, contFinal.size()); i++) {
                order.append(i + 1).append(". ").append(contFinal.get(i).appLabel).append(" @").append(contFinal.get(i).engaged).append("; ");
            }
            Log.i(Data.TAG, "continue order: " + order);
            ui.post(() -> {
                if (isDestroyed()) return;
                bind(continueRow, contFinal);
                for (int i = 0; i < serviceRows.size(); i++) {
                    Row row = serviceRows.get(i);
                    if (i < services.size()) {
                        Data.Service svc = services.get(i);
                        row.label.setText(svc.label);
                        row.label.setCompoundDrawablesRelative(glowDot(colorFor(svc.pkg), 24), null, null, null);
                        bind(row, svc.items);
                    } else {
                        bind(row, new ArrayList<>());
                    }
                }
                bind(appsRow, apps);
                if (firstBind) {
                    firstBind = false;
                    int n = 0;
                    for (Row row : rowList) {
                        if (row.block.getVisibility() != View.VISIBLE) continue;
                        row.block.setTranslationY(px(28));
                        row.block.animate().translationY(0).setStartDelay(60L * n++)
                                .setDuration(Spring.SLOW).setInterpolator(Spring.UI).start();
                    }
                    if (wantApps) showApps(); else focusFirst();
                } else if (wantApps) {
                    showApps();
                } else if (getCurrentFocus() == null) {
                    focusFirst();
                }
            });
        });
    }

    // ---------------------------------------------------------------- layout

    View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setClipChildren(false);

        // Composed at 1280x720 and scaled by the GPU: a dimmed, feathered background loses nothing visible,
        // and the texture upload per title is less than half of a full 1920x1080 one.
        backdrop = new Backdrop(this, fx, 1280, 720);
        backdrop.attach(root);
        // Once an opaque backdrop covers the screen, the window's and the root's solid black underneath are two
        // full screens of fill per frame that nobody sees: drop them (this GPU is fill-rate bound at 1080p).
        backdrop.onFirstShown = () -> {
            root.setBackground(null);
            getWindow().setBackgroundDrawable(null);
        };
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(200));
        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x990A0A0B, 0x000A0A0B}));
        root.addView(topShade, topLp);

        headerView = header();
        root.addView(headerView, at(112, 48, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 112));
        heroView = hero();
        // Nothing in the hero (no bar, no OK hint) until the first title is known; it then fades in with the rows.
        heroView.setAlpha(0f);
        root.addView(heroView, at(112, 130, px(780), px(470), 0));

        FrameLayout clip = new FrameLayout(this);
        clip.setClipChildren(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        continueRow = row("Continuar assistindo", Item.CONTINUE);
        for (int i = 0; i < 12; i++) serviceRows.add(row("", Item.FOR_YOU));
        appsRow = row("Apps", Item.APP);
        clip.addView(rows, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Taller than the screen on purpose: the rows slide up inside it, and a container capped at the visible
        // height squeezed the last row (Apps) to zero.
        FrameLayout.LayoutParams clipLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4600));
        clipLp.topMargin = px(626);
        root.addView(clip, clipLp);
        clipView = clip;
        settings = new SettingsPanel(this);
        root.addView(settings.layer, match());
        return root;
    }

    View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(30, light, TEXT);
        clock.setFontFeatureSettings("tnum");
        clock.setLetterSpacing(-0.01f);
        date = text(20, regular, TEXT_3);
        bar.addView(clock);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.leftMargin = px(16);
        bar.addView(date, dateLp);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        topFrame = pill("Quadro", Icon.FRAME, v -> open(new Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")));
        topSleep = pill("Desligar", Icon.SLEEP, v -> open(new Intent(Intent.ACTION_MAIN)
                .setClassName("com.sony.dtv.timers", "com.sony.dtv.activity.TimersMainActivity")));
        // The same input picker the remote's Input key opens.
        topInputs = pill("Entradas", Icon.INPUT, v -> open(new Intent("com.android.tv.action.VIEW_INPUTS")));
        topSettings = pill("Ajustes", Icon.SETTINGS, v -> settings.show(v));
        for (View b : new View[] {topFrame, topSleep, topInputs, topSettings}) {
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(56));
            gap.leftMargin = px(14);
            bar.addView(b, gap);
        }
        return bar;
    }

    View hero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.BOTTOM);

        LinearLayout appLine = new LinearLayout(this);
        appLine.setGravity(Gravity.CENTER_VERTICAL);
        heroDot = new View(this);
        appLine.addView(heroDot, new LinearLayout.LayoutParams(px(26), px(26)));
        heroApp = text(20, medium, TEXT_2);
        heroKind = text(20, regular, TEXT_3);
        LinearLayout.LayoutParams l1 = wrap();
        l1.leftMargin = px(4);
        appLine.addView(heroApp, l1);
        LinearLayout.LayoutParams l2 = wrap();
        l2.leftMargin = px(10);
        appLine.addView(heroKind, l2);
        hero.addView(appLine);

        heroTitle = text(84, light, TEXT);
        heroTitle.setLetterSpacing(-0.035f);
        heroTitle.setLineSpacing(0, 1.0f);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(heroTitle, spaced(14));

        heroMeta = text(22, regular, 0xFFC4C4C8);
        heroMeta.setMaxLines(1);
        heroMeta.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(heroMeta, spaced(14));

        heroDesc = text(24, regular, TEXT_2);
        heroDesc.setMaxLines(2);
        heroDesc.setEllipsize(TextUtils.TruncateAt.END);
        heroDesc.setLineSpacing(0, 1.4f);
        hero.addView(heroDesc, sized(px(660), px(14)));

        heroProgress = new LinearLayout(this);
        heroProgress.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(round(0x2EFFFFFF, 2));
        heroBarFill = new View(this);
        bar.addView(heroBarFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        heroProgress.addView(bar, new LinearLayout.LayoutParams(px(260), px(4)));
        heroLeft = text(19, regular, TEXT_3);
        heroLeft.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams ll = wrap();
        ll.leftMargin = px(14);
        heroProgress.addView(heroLeft, ll);
        hero.addView(heroProgress, spaced(16));

        LinearLayout hint = new LinearLayout(this);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        TextView ok = text(15, medium, TEXT);
        ok.setText("OK");
        ok.setGravity(Gravity.CENTER);
        GradientDrawable key = new GradientDrawable();
        key.setCornerRadius(px(8));
        key.setStroke(Math.max(1, px(2)), 0x8CFAFAFA);
        ok.setBackground(key);
        hint.addView(ok, new LinearLayout.LayoutParams(px(42), px(30)));
        heroAction = text(18, regular, TEXT_3);
        LinearLayout.LayoutParams al = wrap();
        al.leftMargin = px(12);
        hint.addView(heroAction, al);
        hero.addView(hint, spaced(18));
        return hero;
    }

    // ---------------------------------------------------------------- rows and cards

    final class Row {
        final int kind;
        final LinearLayout block, strip, grid;   // strip for card rows, grid for the apps
        final HorizontalScrollView scroller;
        final TextView label;
        final List<Item> items = new ArrayList<>();
        final List<View> cards = new ArrayList<>();
        String signature = "";
        int lastIndex;

        Row(int kind, LinearLayout block, LinearLayout strip, LinearLayout grid, HorizontalScrollView scroller, TextView label) {
            this.kind = kind;
            this.block = block;
            this.strip = strip;
            this.grid = grid;
            this.scroller = scroller;
            this.label = label;
        }
    }

    Row row(String title, int kind) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        block.setClipToPadding(false);
        TextView label = text(22, medium, 0xFFE4E4E7);
        label.setText(title);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setCompoundDrawablePadding(px(4));
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = px(kind == Item.FOR_YOU ? 104 : 112);
        block.addView(label, lp);

        if (kind == Item.APP) {
            LinearLayout grid = new LinearLayout(this);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setClipChildren(false);
            grid.setClipToPadding(false);
            grid.setPadding(px(112), px(16), px(112), px(12));
            block.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.bottomMargin = px(28);
            rows.addView(block, blp);
            Row row = new Row(kind, block, null, grid, null, label);
            rowList.add(row);
            block.setVisibility(View.GONE);
            return row;
        }

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setSmoothScrollingEnabled(true);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setPadding(px(112), px(16), px(112), px(12));
        scroller.addView(strip);
        block.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = px(28);
        rows.addView(block, blp);
        Row row = new Row(kind, block, strip, null, scroller, label);
        rowList.add(row);
        block.setVisibility(View.GONE);
        return row;
    }

    void bind(Row row, List<Item> items) {
        StringBuilder sig = new StringBuilder();
        for (Item it : items) sig.append(it.key()).append(it.progress).append('\n');
        if (sig.toString().equals(row.signature)) return;
        row.signature = sig.toString();

        String keepFocus = null;
        View focus = getCurrentFocus();
        int index = row.cards.indexOf(focus);
        if (index >= 0) keepFocus = row.items.get(index).key();

        row.cards.clear();
        row.items.clear();
        row.items.addAll(items);
        if (row.kind == Item.APP) {
            row.grid.removeAllViews();
            LinearLayout line = null;
            for (int i = 0; i < items.size(); i++) {
                if (i % APP_COLUMNS == 0) {
                    line = new LinearLayout(this);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    line.setClipChildren(false);
                    LinearLayout.LayoutParams llp = wrap();
                    llp.bottomMargin = px(32);
                    row.grid.addView(line, llp);
                }
                View tile = appTile(items.get(i));
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
                lp.rightMargin = px(28);
                line.addView(tile, lp);
                row.cards.add(tile);
            }
            row.block.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
            if (row != activeRow) cache(row, true);
            restoreFocus(row, keepFocus, index);
            return;
        }
        row.strip.removeAllViews();
        for (Item it : items) {
            View card = row.kind == Item.APP ? appTile(it) : card(it);
            LinearLayout.LayoutParams lp = card.getLayoutParams() instanceof LinearLayout.LayoutParams
                    ? (LinearLayout.LayoutParams) card.getLayoutParams() : wrap();
            lp.rightMargin = px(row.kind == Item.APP ? 24 : 28);
            row.strip.addView(card, lp);
            row.cards.add(card);
        }
        row.block.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (row != activeRow) cache(row, true);
        restoreFocus(row, keepFocus, index);
    }

    /** Focus goes back to the same item; if it left the row (hidden, uninstalled), to whatever took its place. */
    private void restoreFocus(Row row, String keepFocus, int index) {
        if (keepFocus == null || row.cards.isEmpty()) return;
        for (int i = 0; i < row.items.size(); i++) {
            if (row.items.get(i).key().equals(keepFocus)) { row.cards.get(i).requestFocus(); return; }
        }
        row.cards.get(Math.min(index, row.cards.size() - 1)).requestFocus();
    }

    View card(Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClipChildren(false);
        card.setContentDescription(it.title + ", " + it.appLabel);

        FrameLayout thumb = new FrameLayout(this);
        thumb.setBackground(round(0xFF1C1C1F, 16));
        thumb.setOutlineProvider(rounded(16));       // for the focus shadow only; no clipping (see Rounded)
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        thumb.addView(img, match());
        // Depth: light from above (a faint sheen at the top), shade at the bottom where the badge and bar sit.
        thumb.addView(shade(), match());

        TextView badge = text(15, medium, TEXT);
        badge.setText(it.appLabel);
        badge.setCompoundDrawablesRelative(dot(colorFor(it.pkg), 8), null, null, null);
        badge.setCompoundDrawablePadding(px(7));
        badge.setPadding(px(10), px(5), px(12), px(5));
        badge.setBackground(chip(colorFor(it.pkg)));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = px(12);
        blp.topMargin = px(12);
        thumb.addView(badge, blp);

        if (it.progress >= 0 && it.kind == Item.CONTINUE) {
            View bar = progressBar(colorFor(it.pkg), px(320) - px(28), it.progress);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(24), Gravity.BOTTOM);
            plp.leftMargin = px(16);
            plp.rightMargin = px(16);
            plp.bottomMargin = px(2);
            thumb.addView(bar, plp);
        }
        // Unfocused cards sit slightly back; the dimmer is one cheap view, not alpha on the whole card.

        thumb.setForeground(rim(false));
        card.addView(thumb, new LinearLayout.LayoutParams(px(320), px(180)));

        TextView title = text(20, medium, TEXT_3);
        title.setText(it.title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title, sized(px(320), px(14)));
        TextView sub = text(17, regular, TEXT_4);
        sub.setFontFeatureSettings("tnum");
        sub.setLetterSpacing(0.005f);
        sub.setText(it.subtitle);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(sub, sized(px(320), px(3)));

        loadInto(img, it.imageUri, px(400), px(16));
        card.setOnFocusChangeListener((v, has) -> {
            lift(thumb, has, 1.07f, px(20));
            sweep(img, has);
            rowOf(v).lastIndex = Math.max(0, rowOf(v).cards.indexOf(v));
            thumb.setForeground(rim(has));
            title.setTextColor(has ? TEXT : TEXT_3);
            sub.setTextColor(has ? TEXT_3 : TEXT_4);
            if (has) onCardFocused(it, v);
        });
        card.setOnClickListener(v -> launch(it));
        if (it.kind == Item.CONTINUE) card.setOnLongClickListener(v -> { showTitleMenu(it, v); return true; });
        return card;
    }

    /** App tile in the Cinema card language: rounded art, light rim, neutral glow, reflection, name below. */
    View appTile(Item it) {
        int w = px(256), h = px(144);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClipChildren(false);
        cell.setContentDescription(it.title);

        FrameLayout thumb = new FrameLayout(this);
        thumb.setBackground(round(0xFF1C1C1F, 16));
        thumb.setOutlineProvider(rounded(16));         // shadow only, no clipping (see Rounded)
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        Drawable banner = null;
        try {
            Intent launch = data.pm.getLeanbackLaunchIntentForPackage(it.pkg);
            if (launch != null && launch.getComponent() != null) banner = data.pm.getActivityBanner(launch.getComponent());
            if (banner == null) banner = data.pm.getApplicationBanner(it.pkg);
            if (banner == null) banner = data.pm.getApplicationIcon(it.pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas bc = new android.graphics.Canvas(bmp);
        bc.drawColor(0xFF1C1C1F);
        if (banner != null) {
            boolean icon = banner.getIntrinsicWidth() > 0 && banner.getIntrinsicWidth() <= banner.getIntrinsicHeight() * 1.2f;
            if (icon) {                                   // square launcher icon: centred, not stretched
                int size = Math.round(h * 0.55f);
                banner.setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2);
            } else {
                banner.setBounds(0, 0, w, h);
            }
            banner.draw(bc);
        }
        img.setImageDrawable(new Rounded(bmp, px(16)));
        thumb.addView(img, match());
        thumb.addView(shade(), match());
        thumb.setForeground(rim(false));
        cell.addView(thumb, new LinearLayout.LayoutParams(w, h));

        TextView name = text(18, medium, TEXT_3);
        name.setText(it.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLetterSpacing(0.005f);
        cell.addView(name, sized(w, px(12)));

        cell.setOnFocusChangeListener((v, has) -> {
            lift(thumb, has, 1.07f, px(18));
            thumb.setForeground(rim(has));
            name.setTextColor(has ? TEXT : TEXT_3);
            sweep(img, has);
            if (has) { appsRow.lastIndex = Math.max(0, appsRow.cards.indexOf(v)); activate(appsRow); scrollToTile(cell); }
        });
        cell.setOnClickListener(v -> launch(it));
        cell.setOnLongClickListener(v -> { showAppMenu(it, v); return true; });
        cell.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    // ---------------------------------------------------------------- focus, hero and rows

    void onCardFocused(Item it, View card) {
        for (Row row : rowList) if (row.cards.contains(card)) { scrollRows(row); activate(row); }
        pendingHero = it;
        ui.removeCallbacks(heroUpdate);
        ui.post(heroUpdate);
    }

    void applyHero() {
        Item it = pendingHero;
        if (it == null) return;
        if (heroItem == null) {
            heroView.setTranslationY(px(10));
            heroView.animate().alpha(1f).translationY(0).setDuration(Spring.SLOW).setInterpolator(Spring.UI).start();
        } else if (heroItem != it && heroView.getAlpha() > 0.5f) {
            // Text changes through a quick dip and a small rise, never a hard swap.
            heroView.animate().cancel();
            heroView.setAlpha(0.35f);
            heroView.setTranslationY(px(6));
            heroView.animate().alpha(1f).translationY(0).setDuration(Spring.BASE).setInterpolator(Spring.UI).withLayer().start();
        }
        heroItem = it;
        heroDot.setBackground(new Glow(colorFor(it.pkg), 0.40f));
        heroApp.setText(it.appLabel);
        heroKind.setText("·  " + (it.kind == Item.CONTINUE ? "Continuar" : "Para você"));
        heroTitle.setText(it.title);
        int len = it.title.length();
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(len > 34 ? 56 : len > 20 ? 68 : 84));
        heroTitle.setLetterSpacing(len > 20 ? -0.025f : -0.035f);
        heroMeta.setText(it.meta);
        heroMeta.setVisibility(TextUtils.isEmpty(it.meta) ? View.GONE : View.VISIBLE);
        heroDesc.setText(it.description == null ? "" : it.description);
        heroDesc.setVisibility(TextUtils.isEmpty(it.description) ? View.GONE : View.VISIBLE);
        boolean progress = it.kind == Item.CONTINUE && it.progress >= 0;
        heroProgress.setVisibility(progress ? View.VISIBLE : View.GONE);
        if (progress) {
            heroProgress.removeViewAt(0);
            heroProgress.addView(progressBar(colorFor(it.pkg), px(260), it.progress), 0,
                    new LinearLayout.LayoutParams(px(260), px(28)));
            heroLeft.setText(it.left == null ? it.progress + "%" : it.left);
        }
        heroAction.setText(it.kind == Item.CONTINUE ? "Continuar de onde parou" : "Abrir no " + it.appLabel);
        // The art is the expensive part (download, 1600 px decode, blur): wait until the focus rests, so a fast
        // D-pad run through a row does not queue a dozen of them.
        ui.removeCallbacks(backdropUpdate);
        ui.postDelayed(backdropUpdate, firstBackdrop ? 0 : 320);
        firstBackdrop = false;
    }

    void setBackdrop(String uri, String fallback) {
        if (uri == null || uri.equals(bgUri)) return;
        bgUri = uri;
        images.load(uri, px(1600), (key, bmp) -> {
            if (uri.equals(bgUri)) backdrop.show(bmp);
        }, () -> {
            // The bigger rendition does not exist: fall back to the art the app published.
            if (uri.equals(bgUri) && fallback != null && !fallback.equals(uri)) {
                images.load(fallback, px(1600), (key, bmp) -> { if (uri.equals(bgUri)) backdrop.show(bmp); }, null);
            }
        });
    }

    void scrollRows(Row target) {
        if (target == appsRow) { scrollToTile(null); return; }
        heroView.animate().alpha(1f).setDuration(Spring.BASE).setInterpolator(Spring.UI).start();
        backdrop.setDark(false);
        int visibleIndex = 0;
        for (Row row : rowList) {
            if (row.block.getVisibility() != View.VISIBLE) continue;
            if (row == target) break;
            visibleIndex++;
        }
        float y = -target.block.getTop();
        moveRows(y);
        for (Row row : rowList) {
            float alpha = row.block.getTop() < target.block.getTop() ? 0f : 1f;
            fadeBlock(row, alpha);
        }
    }

    /**
     * Grid mode: the hero text fades out and the apps grid rises to just under the top bar (screen y 170); the
     * focused line is kept above the bottom edge. Leaving the grid (scrollRows) brings the hero back.
     */
    void scrollToTile(View tile) {
        // Coming back from another app (Apps button), Home may still be laying out: positions read now would be 0.
        if (rows.isLayoutRequested() || appsRow.block.getTop() == 0) {
            rows.post(() -> scrollToTile(tile));
            return;
        }
        heroView.animate().alpha(0f).setDuration(Spring.BASE).setInterpolator(Spring.UI).start();
        backdrop.setDark(true);
        float y = -appsRow.block.getTop() - px(456);
        if (tile != null) {
            View line = (View) tile.getParent();
            float lineTop = px(170) + appsRow.grid.getTop() + line.getTop();
            float limit = px(1080 - 190 - 60);
            if (lineTop > limit) y -= lineTop - limit;
        }
        moveRows(y);
        for (Row row : rowList) {
            float alpha = row.block.getTop() < appsRow.block.getTop() ? 0f : 1f;
            fadeBlock(row, alpha);
        }
    }

    float rowsTarget;

    /**
     * Moves the rows only when the target changes. A focus move inside a row used to restart this animation (and
     * every row's fade, each on a temporary GPU layer) on every key press: seven rows re-rasterized per press.
     */
    void moveRows(float y) {
        if (Math.abs(y - rowsTarget) < 0.5f) return;
        rowsTarget = y;
        rows.animate().translationY(y).setDuration(Spring.SLOW).setInterpolator(Spring.UI).start();
    }

    void fadeBlock(Row row, float alpha) {
        Object target = row.block.getTag(R_FADE);
        if (target instanceof Float && (Float) target == alpha) return;
        row.block.setTag(R_FADE, alpha);
        row.block.animate().alpha(alpha).setDuration(Spring.BASE).setInterpolator(Spring.UI).withLayer().start();
    }

    static final int R_FADE = 0x7f0a0002;

    Row activeRow;

    /**
     * Only the row you are in is drawn live. Every other row is kept as a GPU texture (a hardware layer on its
     * viewport) and redrawn only when its own content changes, so a focus move costs one row, not the whole
     * screen of cards, badges, gradients and text.
     */
    void activate(Row row) {
        if (row == activeRow) return;
        if (activeRow != null) cache(activeRow, true);
        cache(row, false);
        activeRow = row;
    }

    void cache(Row row, boolean on) {
        View viewport = row.scroller != null ? row.scroller : row.grid;
        viewport.setLayerType(on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
    }

    Row rowOf(View v) {
        for (Row row : rowList) if (row.cards.contains(v)) return row;
        return appsRow;
    }

    /** One pass of light across the art when focus lands; nothing runs while resting. */
    /** One pass of light across the art when focus lands (drawn inside Rounded); nothing runs while resting. */
    void sweep(ImageView img, boolean has) {
        if (!(img.getDrawable() instanceof Rounded)) return;
        Rounded r = (Rounded) img.getDrawable();
        Object running = img.getTag(R_SWEEP);
        if (running instanceof android.animation.ValueAnimator) ((android.animation.ValueAnimator) running).cancel();
        if (!has) { r.setSheen(-1f); return; }
        android.animation.ValueAnimator an = android.animation.ValueAnimator.ofFloat(0f, 1f);
        an.setStartDelay(60);
        an.setDuration(480);
        an.setInterpolator(new DecelerateInterpolator(1.4f));
        an.addUpdateListener(v -> r.setSheen((float) v.getAnimatedValue()));
        an.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { r.setSheen(-1f); }
        });
        img.setTag(R_SWEEP, an);
        an.start();
    }

    static final int R_SWEEP = 0x7f0a0001;

    /**
     * Up/Down between rows lands on the card you last had in that row (tvOS), not on whichever card happens to
     * sit above or below. Inside the apps grid, Android's own focus search moves between tiles.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        if (settings != null && settings.isOpen()) {
            // The panel owns the remote while open; Back closes it (on release, so the key never reaches Home).
            if (settings.isMoving()) {
                // An app is picked up: Up/Down carry it, OK or Back drop it.
                if (e.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_UP) { settings.move(-1); return true; }
                if (e.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) { settings.move(1); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                    // A new press drops it; the release of the long press that picked it up does not.
                    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) settings.endMove();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) settings.endMove();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) settings.back();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_LEFT)) {
            // Sideways never leaves the row (or the grid line): at either end the key is simply swallowed, so the
            // focus cannot jump to another row and get lost. Rows change only with Up and Down.
            View focus = getCurrentFocus();
            Row current = null;
            for (Row row : rowList) if (row.cards.contains(focus)) current = row;
            if (current != null) {
                int i = current.cards.indexOf(focus);
                boolean right = key == KeyEvent.KEYCODE_DPAD_RIGHT;
                boolean edge = current == appsRow
                        ? (right ? (i + 1) % APP_COLUMNS == 0 || i == current.cards.size() - 1 : i % APP_COLUMNS == 0)
                        : (right ? i == current.cards.size() - 1 : i == 0);
                if (edge) return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP)) {
            View focus = getCurrentFocus();
            Row current = null;
            for (Row row : rowList) if (row.cards.contains(focus)) current = row;
            boolean inGrid = current == appsRow;
            boolean gridTopLine = inGrid && appsRow.grid.indexOfChild((View) focus.getParent()) == 0;
            if (current != null && (!inGrid || (key == KeyEvent.KEYCODE_DPAD_UP && gridTopLine))) {
                int from = rowList.indexOf(current), step = key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                for (int i = from + step; i >= 0 && i < rowList.size(); i += step) {
                    Row target = rowList.get(i);
                    if (target.block.getVisibility() != View.VISIBLE || target.cards.isEmpty()) continue;
                    int index = target == appsRow ? 0 : Math.min(target.lastIndex, target.cards.size() - 1);
                    target.cards.get(index).requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    Row firstVisibleRow() {
        for (Row row : rowList) if (row.block.getVisibility() == View.VISIBLE && !row.cards.isEmpty()) return row;
        return null;
    }

    void focusFirst() {
        Row first = firstVisibleRow();
        if (first == null) return;
        if (first.scroller != null) first.scroller.scrollTo(0, 0);
        first.cards.get(0).requestFocus();
        scrollRows(first);
    }

    // ---------------------------------------------------------------- actions

    void launch(Item it) {
        Intent intent = null;
        if (it.intentUri != null) {
            try {
                intent = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                if (intent.getPackage() == null && intent.getComponent() == null) intent.setPackage(it.pkg);
            } catch (Exception e) {
                Log.w(Data.TAG, "bad intent " + it.intentUri + ": " + e);
            }
        }
        if (intent == null) intent = data.pm.getLeanbackLaunchIntentForPackage(it.pkg);
        if (intent == null) intent = data.pm.getLaunchIntentForPackage(it.pkg);
        if (intent == null) return;
        if (!open(intent) && it.intentUri != null) {
            Intent app = data.pm.getLeanbackLaunchIntentForPackage(it.pkg);
            if (app != null) open(app);
        }
    }

    boolean open(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(Data.TAG, "open " + intent + ": " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- settings

    static final String PREFS = "cinema", KEY_SERVICES = "services", KEY_HIDDEN = "hidden", KEY_HIDDEN_APPS = "hidden_apps";

    /** The remote's colour buttons, in the order on the remote, and the app each opens until the user picks another. */
    static final String[] KEY_COLORS = {"vermelho", "verde", "amarelo", "azul"};
    static final String[] KEY_NAMES = {"Vermelho", "Verde", "Amarelo", "Azul"};
    static final int[] KEY_DOTS = {0xFFE5484D, 0xFF30A46C, 0xFFF5D90A, 0xFF3E63DD};
    static final String[] KEY_DEFAULTS = {"com.google.android.youtube.tv", "com.wbd.stream", "com.globo.globotv",
            "com.stremio.one"};

    static String keyApp(SharedPreferences p, int i) { return p.getString("key_" + KEY_COLORS[i], KEY_DEFAULTS[i]); }

    /** The services shown on Home, in candidate order; by default the known streaming services. */
    List<String> enabledServices() {
        java.util.Set<String> saved = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_SERVICES, null);
        List<String> out = new ArrayList<>();
        if (saved == null) {
            for (String pkg : Data.SERVICES) out.add(pkg);
            return out;
        }
        for (String pkg : orderedCandidates()) if (saved.contains(pkg)) out.add(pkg);
        return out;
    }

    boolean manualOrder() {
        return SettingsPanel.MANUAL.equals(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(SettingsPanel.KEY_ORDER_MODE, SettingsPanel.RECENT));
    }

    /** Every streaming candidate, in the user's saved order first (any new app after), else the default order. */
    List<String> orderedCandidates() {
        List<String> all = data.candidates();
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(SettingsPanel.KEY_ORDER, "");
        if (saved.isEmpty()) return all;
        List<String> out = new ArrayList<>();
        for (String pkg : saved.split(",")) if (all.contains(pkg) && !out.contains(pkg)) out.add(pkg);
        for (String pkg : all) if (!out.contains(pkg)) out.add(pkg);
        return out;
    }

    /** While the settings panel is open, nothing behind it can take focus. */
    void blockContent(boolean block) {
        int mode = block ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS;
        ((ViewGroup) headerView).setDescendantFocusability(mode);
        ((ViewGroup) clipView).setDescendantFocusability(mode);
    }

    /** Hold OK on a Continue watching title: watch it, or take it off the list until it is watched again. */
    void showTitleMenu(Item it, View from) {
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        names.add("Continuar assistindo");
        actions.add(() -> launch(it));
        names.add("Remover de Continuar assistindo");
        actions.add(() -> {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            Set<String> set = new HashSet<>(p.getStringSet(KEY_HIDDEN, new HashSet<>()));
            set.add(it.hiddenKey());
            p.edit().putStringSet(KEY_HIDDEN, set).apply();
            focusNeighbour(from);
            reload();
        });
        settings.showMenu(from, it.title, it.appLabel + (it.left != null ? " · " + it.left : ""), names, actions);
    }

    /** Hold OK on an app in the grid: open it, hide it from Home, or remove it with the TV's own screens. */
    void showAppMenu(Item it, View from) {
        boolean system = true;
        try {
            system = (data.pm.getApplicationInfo(it.pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        Uri pkg = Uri.fromParts("package", it.pkg, null);
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        names.add("Abrir");
        actions.add(() -> launch(it));
        names.add("Ocultar da Home");
        actions.add(() -> {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            Set<String> set = new HashSet<>(p.getStringSet(KEY_HIDDEN_APPS, new HashSet<>()));
            set.add(it.pkg);
            p.edit().putStringSet(KEY_HIDDEN_APPS, set).apply();
            focusNeighbour(from);
            reload();
        });
        if (system) {
            // Apps that came with the TV cannot be uninstalled, only turned off, on their page in the TV settings.
            names.add("Desativar");
            actions.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)));
        } else {
            names.add("Desinstalar");
            actions.add(() -> open(new Intent(Intent.ACTION_DELETE, pkg)));   // the TV asks to confirm
        }
        settings.showMenu(from, it.title, null, names, actions);
    }

    Set<String> hiddenApps() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_HIDDEN_APPS, new HashSet<>());
    }

    /** Before a card leaves its row, hand focus to the one beside it so the remote stays where it was. */
    void focusNeighbour(View card) {
        Row row = rowOf(card);
        int i = row.cards.indexOf(card);
        int next = i + 1 < row.cards.size() ? i + 1 : i - 1;
        if (i >= 0 && next >= 0) row.cards.get(next).requestFocus();
    }

    /**
     * Titles taken off Continue watching. An entry stays only while the app still reports the title at the same
     * position: once it is watched again the position moves, the entry no longer matches and is dropped.
     */
    Set<String> hiddenTitles(List<Item> cont) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> saved = p.getStringSet(KEY_HIDDEN, new HashSet<>());
        if (saved.isEmpty() || cont.isEmpty()) return saved;
        Set<String> live = new HashSet<>();
        for (Item it : cont) live.add(it.hiddenKey());
        Set<String> kept = new HashSet<>(saved);
        kept.retainAll(live);
        if (kept.size() != saved.size()) p.edit().putStringSet(KEY_HIDDEN, kept).apply();
        return kept;
    }

    // ---------------------------------------------------------------- helpers

    int px(float design) { return Math.round(design * scale); }

    Typeface font(int weight) {
        try {
            return new Typeface.Builder(getAssets(), "fonts/Geist.ttf")
                    .setFontVariationSettings("'wght' " + weight).setWeight(weight).build();
        } catch (RuntimeException e) {
            return Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
    }

    TextView text(float size, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Focus "lift", tvOS style: the card grows toward you and casts a deeper, lighter-tinted shadow. */
    /** Focus lift: one standard glow for every card and app, a soft neutral light, not the source's colour. */
    static final int GLOW_SPOT = 0x47E8E8F0, GLOW_AMBIENT = 0x1FE8E8F0;

    void lift(View v, boolean has, float scaleTo, int z) {
        v.animate().scaleX(has ? scaleTo : 1f).scaleY(has ? scaleTo : 1f).translationZ(has ? z : 0)
                .setDuration(Spring.BASE).setInterpolator(Spring.UI).start();
        v.setOutlineSpotShadowColor(GLOW_SPOT);
        v.setOutlineAmbientShadowColor(GLOW_AMBIENT);
    }

    /** A faint top sheen and a bottom shade, so art reads as a lit surface instead of a flat sticker. */
    View shade() {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x1FFFFFFF, 0x00FFFFFF, 0x00000000, 0x8C000000});
        d.setCornerRadius(px(16));
        v.setBackground(d);
        return v;
    }

    Glow glowDot(int color, int size) {
        Glow g = new Glow(color, 0.36f);
        g.setBounds(0, 0, px(size), px(size));
        return g;
    }

    /** App chip on art: a dark, nearly solid pill so the label reads on any thumbnail, rimmed in the app colour. */
    GradientDrawable chip(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xE00C0C0E);
        d.setCornerRadius(px(999));
        d.setStroke(Math.max(1, px(1.5f)), (color & 0x00FFFFFF) | 0x66000000);
        return d;
    }

    /** Progress in the source colour, lit: halo, brightening fill and a glowing playhead (ProgressGlow). */
    View progressBar(int color, int width, int progress) {
        View v = new View(this);
        v.setBackground(new ProgressGlow(color, progress, px(4)));
        return v;
    }

    /** Rim: a hairline edge catching light when resting, a solid white ring when focused. */
    GradientDrawable rim(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(16));
        d.setColor(Color.TRANSPARENT);
        if (focused) d.setStroke(px(2.5f), 0xF2FAFAFA);
        else d.setStroke(Math.max(1, px(1.5f)), 0x1FFFFFFF);
        return d;
    }

    /** Translucent "material": a light wash with a brighter edge, solid white when focused. */
    GradientDrawable glass(float radius, boolean focused) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[] {TEXT, TEXT} : new int[] {0x33FFFFFF, 0x1AFFFFFF});
        d.setCornerRadius(px(radius));
        d.setStroke(Math.max(1, px(1.5f)), focused ? TEXT : 0x2EFFFFFF);
        return d;
    }

    /** Top-bar control: an icon on glass; on focus it turns white and opens up to show its name. */
    View pill(String label, int iconType, View.OnClickListener click) {
        TextView b = text(20, medium, BG);
        Icon icon = new Icon(iconType, TEXT);
        icon.setBounds(0, 0, px(24), px(24));
        b.setCompoundDrawablesRelative(icon, null, null, null);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(15), 0, px(15), 0);
        b.setMinWidth(px(56));
        b.setFocusable(true);
        b.setContentDescription(label);
        b.setBackground(glass(28, false));
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            icon.setColor(has ? BG : TEXT);
            b.setBackground(glass(28, has));
            b.setText(has ? label : "");
            b.setCompoundDrawablePadding(has ? px(10) : 0);
            b.setPadding(px(15), 0, px(has ? 22 : 15), 0);
            b.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? px(14) : 0)
                    .setDuration(Spring.BASE).setInterpolator(Spring.UI).start();
            if (has) {
                moveRows(0);
                heroView.animate().alpha(1f).setDuration(Spring.BASE).start();
                backdrop.setDark(false);
                for (Row row : rowList) fadeBlock(row, 1f);
            }
        });
        return b;
    }

    int colorFor(String pkg) {
        Integer c = APP_COLORS.get(pkg);
        return c == null ? TEXT_3 : c;
    }

    GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        return d;
    }

    GradientDrawable dot(int color, int size) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setSize(px(size), px(size));
        d.setBounds(0, 0, px(size), px(size));
        return d;
    }

    ViewOutlineProvider rounded(float radius) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(radius));
            }
        };
    }

    void loadInto(ImageView img, String uri, int width, int radius) {
        if (uri == null) return;
        img.setTag(uri);
        Bitmap hit = images.cached(uri, width);
        if (hit != null) { img.setImageDrawable(new Rounded(hit, radius)); return; }
        // Defer network until the first frame is drawn, so Home is on screen before any artwork request.
        img.post(() -> images.load(uri, width, (key, bmp) -> {
            if (uri.equals(img.getTag())) {
                img.setImageDrawable(new Rounded(bmp, radius));
                img.setAlpha(0f);
                img.animate().alpha(1f).setDuration(Spring.FAST).start();
            }
        }));
    }

    static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(top);
        return lp;
    }

    LinearLayout.LayoutParams sized(int width, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    FrameLayout.LayoutParams at(int left, int top, int width, int height, int right) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        lp.leftMargin = px(left);
        lp.topMargin = px(top);
        lp.rightMargin = px(right);
        return lp;
    }
}
