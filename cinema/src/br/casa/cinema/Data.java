package br.casa.cinema;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the TV provider (Watch Next, preview programs, channels) and the installed apps. Runs off the UI thread.
 * With READ_TV_LISTINGS the provider returns other apps' rows marked searchable; it refuses a selection, so whole
 * tables are read with named projections and filtered here (cleanyourtv docs/watch-next.md).
 */
final class Data {
    static final String TAG = "Cinema";
    static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    static final Uri CHANNEL = Uri.parse("content://android.media.tv/channel");

    static final long MAX_AGE_MS = 60L * 24 * 3600 * 1000;
    static final int DONE_PERCENT = 95, ROW_LIMIT = 15, PER_APP = 4;

    static final String STREMIO = "com.stremio.one";

    /** Dock order for the Apps row; every other launchable app follows alphabetically. */
    static final String[] APP_ORDER = {"com.netflix.ninja", "com.wbd.stream", "com.globo.globotv",
            "com.google.android.youtube.tv", "com.stremio.one", "com.sony.dtv.tvx", "com.android.vending"};
    static final Set<String> HIDDEN_APPS = new HashSet<>(java.util.Arrays.asList(
            "br.casa.cinema", "br.casa.tvkeys", "br.casa.tvprobe", "com.gathin.tvhome",
            "com.sony.dtv.tvxlauncher.programguide"));

    static final String[] WN_COLS = {"package_name", "title", "episode_title", "season_display_number",
            "episode_display_number", "watch_next_type", "last_engagement_time_utc_millis",
            "last_playback_position_millis", "duration_millis", "poster_art_uri", "poster_art_aspect_ratio",
            "thumbnail_uri", "intent_uri", "short_description"};
    static final String[] PV_COLS = {"package_name", "channel_id", "title", "episode_title", "season_display_number",
            "episode_display_number", "last_playback_position_millis", "duration_millis", "poster_art_uri",
            "poster_art_aspect_ratio", "thumbnail_uri", "intent_uri", "short_description"};
    static final String[] CH_COLS = {"_id", "package_name", "display_name", "browsable"};

    final Context context;
    final PackageManager pm;
    final Map<String, String> labels = new HashMap<>();

    Data(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
    }

    String label(String pkg) {
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = null;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            if (info.enabled) value = pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (value == null) value = "";
        labels.put(pkg, value);
        return value;
    }

    boolean installed(String pkg) { return !label(pkg).isEmpty(); }

    // ---------------------------------------------------------------- continue watching

    /**
     * Continue watching, newest first: the first card is always the last thing you watched that is still
     * unfinished. Only titles actually in progress (started, under DONE_PERCENT) are kept.
     * Watch Next rows carry their own last-engagement time. Stremio's "Continuar assistindo" channel carries none,
     * so its titles take the time Stremio itself was last used (Android usage stats), in the channel's own order.
     * A Watch Next row without a time also falls back to its app's last use.
     */
    List<Item> continueWatching() {
        long now = System.currentTimeMillis();
        Map<String, Long> lastUsed = lastUsed();
        List<Item> all = new ArrayList<>();
        try (Cursor c = query(WATCH_NEXT, WN_COLS)) {
            while (c != null && c.moveToNext()) {
                String pkg = s(c, 0);
                int type = i(c, 5);
                if (pkg == null || !installed(pkg) || type == 2) continue;        // 2 = "new": promotions
                Item it = program(Item.CONTINUE, pkg, s(c, 1), s(c, 2), s(c, 3), s(c, 4), l(c, 7), l(c, 8),
                        s(c, 9), i(c, 10), s(c, 11), s(c, 12), s(c, 13));
                if (it.progress <= 0 || it.progress >= DONE_PERCENT) continue;   // not started, or finished
                long engaged = l(c, 6);
                if (engaged <= 0) engaged = lastUsed.containsKey(pkg) ? lastUsed.get(pkg) - 1 : 0;
                if (engaged > 0 && now - engaged > MAX_AGE_MS) continue;
                it.engaged = engaged;
                all.add(it);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "watch next: " + e);
        }

        Long channel = stremioContinueChannel();
        if (channel != null && installed(STREMIO)) {
            long base = lastUsed.containsKey(STREMIO) ? lastUsed.get(STREMIO) : 0;
            int n = 0;
            for (Item it : previews(Item.CONTINUE, Collections.singleton(channel), 50)) {
                if (it.progress <= 0 || it.progress >= DONE_PERCENT) continue;
                // First title at Stremio's last use; the rest, undated, are assumed older (6 h apart) so the other
                // apps' dated titles fall in between by their real times.
                it.engaged = base > 0 ? base - n++ * 6L * 3600 * 1000 : 0;
                all.add(it);
            }
        }

        Collections.sort(all, (x, y) -> Long.compare(y.engaged, x.engaged));
        Map<String, Item> unique = new LinkedHashMap<>();
        for (Item it : all) unique.putIfAbsent(it.pkg + "|" + it.title, it);
        return new ArrayList<>(unique.values());   // full, newest first; the service rows use all of it
    }

    /** The Continue row: every service you use gets a place, at most PER_APP titles each, newest first. */
    static List<Item> capPerApp(List<Item> newestFirst) {
        List<Item> out = new ArrayList<>();
        Map<String, Integer> perApp = new HashMap<>();
        for (Item it : newestFirst) {
            int n = perApp.containsKey(it.pkg) ? perApp.get(it.pkg) : 0;
            if (n >= PER_APP) continue;
            perApp.put(it.pkg, n + 1);
            out.add(it);
            if (out.size() >= ROW_LIMIT) break;
        }
        return out;
    }

    /** When each app was last in the foreground (needs GET_USAGE_STATS, granted with appops); empty without it. */
    Map<String, Long> lastUsed() {
        Map<String, Long> out = new HashMap<>();
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats =
                    usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - MAX_AGE_MS, now);
            if (stats != null) for (android.app.usage.UsageStats st : stats) {
                Long prev = out.get(st.getPackageName());
                if (prev == null || st.getLastTimeUsed() > prev) out.put(st.getPackageName(), st.getLastTimeUsed());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "usage stats: " + e);
        }
        return out;
    }

    private Long stremioContinueChannel() {
        try (Cursor c = query(CHANNEL, CH_COLS)) {
            while (c != null && c.moveToNext()) {
                String name = s(c, 2);
                if (STREMIO.equals(s(c, 1)) && name != null
                        && (name.contains("Continu") || name.contains("continu"))) return c.getLong(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "channels: " + e);
        }
        return null;
    }

    // ---------------------------------------------------------------- one row per streaming service

    /** Streaming services that get a row of their own, when installed. */
    static final String[] SERVICES = {"com.netflix.ninja", "com.wbd.stream", "com.globo.globotv",
            "com.google.android.youtube.tv", "com.stremio.one"};

    static final class Service {
        final String pkg, label;
        final List<Item> items = new ArrayList<>();
        long recent;
        Service(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    /**
     * Per service: what you were watching there first, then what the app itself recommends (its browsable
     * channels; for Stremio, your library). Services used most recently come first; the rest keep SERVICES order.
     */
    List<Service> services(List<Item> continuing, List<String> enabled, boolean byRecency) {
        Map<String, Service> map = new LinkedHashMap<>();
        for (String pkg : enabled) if (installed(pkg)) map.put(pkg, new Service(pkg, label(pkg)));
        for (Item it : continuing) {
            Service svc = map.get(it.pkg);
            if (svc == null) continue;
            svc.items.add(it);
            svc.recent = Math.max(svc.recent, it.engaged);
        }
        Map<Long, String> channels = new HashMap<>();
        try (Cursor c = query(CHANNEL, CH_COLS)) {
            while (c != null && c.moveToNext()) {
                String pkg = s(c, 1), name = s(c, 2);
                if (!map.containsKey(pkg)) continue;
                boolean library = name != null && (name.contains("Biblioteca") || name.contains("Library"));
                boolean continueChannel = STREMIO.equals(pkg) && name != null && name.toLowerCase().contains("continu");
                if (!continueChannel && (i(c, 3) == 1 || library)) channels.put(c.getLong(0), pkg);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "channels: " + e);
        }
        Map<Long, Integer> perChannel = new HashMap<>();
        for (Item it : previews(Item.FOR_YOU, channels.keySet(), 800)) {
            Service svc = map.get(it.pkg);
            if (svc == null || svc.items.size() >= ROW_LIMIT) continue;
            int n = perChannel.containsKey(it.channel) ? perChannel.get(it.channel) : 0;
            if (n >= 8) continue;
            boolean dup = false;
            for (Item other : svc.items) if (other.title.equals(it.title)) { dup = true; break; }
            if (dup) continue;
            perChannel.put(it.channel, n + 1);
            svc.items.add(it);
        }
        List<Service> out = new ArrayList<>();
        for (Service svc : map.values()) if (!svc.items.isEmpty()) out.add(svc);
        if (byRecency) Collections.sort(out, (x, y) -> Long.compare(y.recent, x.recent));   // stable; else your order
        return out;
    }

    /**
     * Streaming apps the Home can show: the known services plus every installed app that publishes Continue
     * watching or channels. Known services first, the rest alphabetically.
     */
    List<String> candidates() {
        java.util.Set<String> pkgs = new java.util.TreeSet<>();
        try (Cursor c = query(CHANNEL, CH_COLS)) {
            while (c != null && c.moveToNext()) if (s(c, 1) != null) pkgs.add(s(c, 1));
        } catch (RuntimeException e) {
            Log.w(TAG, "channels: " + e);
        }
        try (Cursor c = query(WATCH_NEXT, WN_COLS)) {
            while (c != null && c.moveToNext()) if (s(c, 0) != null) pkgs.add(s(c, 0));
        } catch (RuntimeException e) {
            Log.w(TAG, "watch next: " + e);
        }
        List<String> out = new ArrayList<>();
        for (String pkg : SERVICES) if (installed(pkg)) out.add(pkg);
        List<String> rest = new ArrayList<>();
        for (String pkg : pkgs) {
            if (out.contains(pkg) || !installed(pkg) || pkg.startsWith("com.sony.") || pkg.startsWith("com.android.")) continue;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue;
            rest.add(pkg);
        }
        Collections.sort(rest, (x, y) -> label(x).compareToIgnoreCase(label(y)));
        out.addAll(rest);
        return out;
    }

    // ---------------------------------------------------------------- shared readers

    private List<Item> previews(int kind, Set<Long> channels, int limit) {
        List<Item> out = new ArrayList<>();
        if (channels.isEmpty()) return out;
        try (Cursor c = query(PREVIEW, PV_COLS)) {
            while (c != null && c.moveToNext() && out.size() < limit) {
                long ch = c.getLong(1);
                if (!channels.contains(ch)) continue;
                String pkg = s(c, 0);
                if (pkg == null || !installed(pkg)) continue;
                Item it = program(kind, pkg, s(c, 2), s(c, 3), s(c, 4), s(c, 5), l(c, 6), l(c, 7),
                        s(c, 8), i(c, 9), s(c, 10), s(c, 11), s(c, 12));
                it.channel = ch;
                out.add(it);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "previews: " + e);
        }
        return out;
    }

    private Item program(int kind, String pkg, String title, String episodeTitle, String season, String episode,
                         long position, long duration, String poster, int posterAspect, String thumb,
                         String intentUri, String description) {
        Item it = new Item(kind);
        it.pkg = pkg;
        it.appLabel = label(pkg);
        it.title = title == null ? "" : title.trim();
        it.description = description;
        it.intentUri = intentUri;
        // Landscape art suits both the 16:9 card and the backdrop; a portrait poster is the last resort.
        boolean portrait = posterAspect == 4 || posterAspect == 5;
        it.imageUri = portrait && thumb != null ? thumb : (poster != null ? poster : thumb);
        it.backdropUri = largest(it.imageUri);
        it.position = Math.max(0, position);
        if (duration > 0 && position > 0) it.progress = (int) Math.min(100, position * 100 / duration);

        String se = (season != null && episode != null) ? "T" + season + ":E" + episode : null;
        String left = null;
        if (duration > 0 && position > 0 && position < duration) {
            long min = Math.max(1, (duration - position) / 60000);
            left = min >= 60 ? "faltam " + (min / 60) + " h " + (min % 60) + " min" : "faltam " + min + " min";
        }
        StringBuilder sub = new StringBuilder();
        if (se != null) sub.append(se);
        if (kind == Item.CONTINUE && left != null) sub.append(sub.length() > 0 ? " · " : "").append(left);
        if (sub.length() == 0) sub.append(it.appLabel);
        it.subtitle = sub.toString();

        StringBuilder meta = new StringBuilder();
        if (season != null && episode != null) meta.append("Temporada ").append(season).append(" · Episódio ").append(episode);
        if (episodeTitle != null && !episodeTitle.trim().isEmpty()) {
            meta.append(meta.length() > 0 ? " · " : "").append(episodeTitle.trim());
        }
        it.meta = meta.toString();
        it.left = left;
        return it;
    }

    /** Asks the image servers we know for a bigger rendition, so the backdrop is not a stretched thumbnail. */
    static String largest(String uri) {
        if (uri == null) return null;
        if (uri.contains("discomax.com")) return uri.replaceAll("([?&])w=\\d+", "$1w=1600");          // HBO Max
        if (uri.contains("metahub.space/background/")) return uri.replace("/medium/", "/large/"); // Stremio
        if (uri.contains("ytimg.com/vi/") && uri.contains("hq720.jpg")) {                        // YouTube
            return uri.substring(0, uri.indexOf("hq720.jpg")) + "maxresdefault.jpg";
        }
        return uri;
    }

    // ---------------------------------------------------------------- apps

    List<Item> apps() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, Item> found = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (HIDDEN_APPS.contains(pkg) || found.containsKey(pkg)) continue;
            Item it = new Item(Item.APP);
            it.pkg = pkg;
            it.title = ri.loadLabel(pm).toString();
            it.appLabel = it.title;
            it.subtitle = "";
            found.put(pkg, it);
        }
        List<Item> out = new ArrayList<>();
        for (String pkg : APP_ORDER) {
            Item it = found.remove(pkg);
            if (it != null) out.add(it);
        }
        List<Item> rest = new ArrayList<>(found.values());
        Collections.sort(rest, (a, b) -> a.title.compareToIgnoreCase(b.title));
        out.addAll(rest);
        return out;
    }

    // ---------------------------------------------------------------- cursor helpers

    private Cursor query(Uri uri, String[] cols) {
        ContentResolver cr = context.getContentResolver();
        return cr.query(uri, cols, null, null, null);
    }

    private static String s(Cursor c, int i) { return c.isNull(i) ? null : c.getString(i); }
    private static int i(Cursor c, int i) { return c.isNull(i) ? -1 : c.getInt(i); }
    private static long l(Cursor c, int i) { return c.isNull(i) ? 0 : c.getLong(i); }
}
