package br.casa.cinema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small image loader: memory LRU (by bytes) + disk cache in cacheDir, downsampled to the size asked for.
 * Network only for http(s) art; content:// and android.resource:// go through the ContentResolver.
 */
final class Images {
    interface Target { void onBitmap(String key, Bitmap bitmap); }

    static final int MEMORY_BYTES = 20 * 1024 * 1024;
    static final long DISK_BYTES = 60L * 1024 * 1024;

    final Context context;
    final File dir;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "cinema-img");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    final LruCache<String, Bitmap> memory = new BitmapCache(MEMORY_BYTES);

    static final class BitmapCache extends LruCache<String, Bitmap> {
        BitmapCache(int bytes) { super(bytes); }
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    }

    Images(Context context) {
        this.context = context.getApplicationContext();
        this.dir = new File(context.getCacheDir(), "img");
        dir.mkdirs();
    }

    static String key(String uri, int width) { return width + "|" + uri; }

    Bitmap cached(String uri, int width) { return uri == null ? null : memory.get(key(uri, width)); }

    void load(String uri, int width, Target target) { load(uri, width, target, null); }

    /** onFail runs on the UI thread when the image cannot be fetched or decoded. */
    void load(String uri, int width, Target target, Runnable onFail) {
        if (uri == null || uri.isEmpty()) return;
        String key = key(uri, width);
        Bitmap hit = memory.get(key);
        if (hit != null) { target.onBitmap(key, hit); return; }
        pool.execute(() -> {
            Bitmap bmp = null;
            try {
                byte[] bytes = bytes(uri);
                if (bytes != null) bmp = decode(bytes, width);
                if (bmp == null) Log.w(Data.TAG, "image undecodable (" + (bytes == null ? 0 : bytes.length) + " B) " + uri);
            } catch (Exception e) {
                Log.w(Data.TAG, "image " + uri + ": " + e);
            }
            if (bmp != null) memory.put(key, bmp);
            Bitmap result = bmp;
            if (result != null) main.post(() -> target.onBitmap(key, result));
            else if (onFail != null) main.post(onFail);
        });
    }

    private byte[] bytes(String uri) throws Exception {
        if (!uri.startsWith("http")) {
            try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
                return in == null ? null : read(in);
            }
        }
        File file = new File(dir, sha1(uri));
        if (file.isFile()) {
            file.setLastModified(System.currentTimeMillis());
            return Files.readAllBytes(file.toPath());
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(10000);
        conn.setInstanceFollowRedirects(true);
        try (InputStream in = conn.getInputStream()) {
            byte[] data = read(in);
            // Write to a temporary file and rename, so a card and the backdrop asking for the same art at the
            // same time never read a half-written cache file.
            File temp = File.createTempFile("download", ".part", dir);
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); }
            if (!temp.renameTo(file)) temp.delete();
            trimDisk();
            return data;
        } finally {
            conn.disconnect();
        }
    }

    private void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_BYTES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_BYTES * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    private static Bitmap decode(byte[] data, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        if (bmp == null || bmp.getWidth() <= width) return bmp;
        int h = Math.max(1, Math.round(bmp.getHeight() * (width / (float) bmp.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, width, h, true);
        if (scaled != bmp) bmp.recycle();
        return scaled;
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
