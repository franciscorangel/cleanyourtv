package br.casa.tvprobe;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Reads what other apps publish to the TV provider (Watch Next, preview programs, channels) and logs one line per
 * row under the tag TvProbe. Read-only. Named projections and no selection: without ACCESS_ALL_EPG_DATA the
 * provider rejects a selection, and a null projection fails on some firmware (cleanyourtv docs/watch-next.md).
 */
public final class Probe extends Activity {
    static final String TAG = "TvProbe";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            dump("watch_next", Uri.parse("content://android.media.tv/watch_next_program"), new String[] {
                    "package_name", "title", "episode_title", "season_display_number", "episode_display_number",
                    "watch_next_type", "last_engagement_time_utc_millis", "last_playback_position_millis",
                    "duration_millis", "poster_art_uri", "intent_uri"});
            dump("preview", Uri.parse("content://android.media.tv/preview_program"), new String[] {
                    "package_name", "channel_id", "title", "last_playback_position_millis", "duration_millis",
                    "poster_art_uri", "intent_uri"});
            dump("channel", Uri.parse("content://android.media.tv/channel"), new String[] {
                    "_id", "package_name", "display_name", "type", "browsable"});
        } finally {
            Log.i(TAG, "done");
            finish();
        }
    }

    private void dump(String table, Uri uri, String[] columns) {
        int rows = 0;
        try (Cursor c = getContentResolver().query(uri, columns, null, null, null)) {
            if (c == null) {
                Log.i(TAG, table + " null-cursor");
                return;
            }
            while (c.moveToNext()) {
                StringBuilder line = new StringBuilder(table);
                for (int i = 0; i < columns.length; i++) {
                    String value = c.isNull(i) ? "" : c.getString(i);
                    if (value.length() > 120) value = value.substring(0, 120) + "…";
                    line.append(" | ").append(columns[i]).append('=').append(value);
                }
                Log.i(TAG, line.toString());
                rows++;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, table + " failed: " + e);
        }
        Log.i(TAG, table + " rows=" + rows);
    }
}
