package br.casa.tvkeys;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Opens one app (or the launcher's app library) and finishes; never shows a window of its own. */
public abstract class Abrir extends Activity {
    static final String TAG = "TvKeys";
    static final String LIBRARY = "biblioteca";

    abstract String alvo();

    /** The colour this key is, for the Cinema Home's Botões do controle setting; null for keys that are not colours. */
    String cor() { return null; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String alvo = alvo();
        try {
            startActivity(intentFor(alvo));
            Log.i(TAG, "opened " + alvo);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "could not open " + alvo, e);
        } finally {
            finish();
        }
    }

    private Intent intentFor(String alvo) {
        if (cor() != null) {
            // The app for each colour is chosen in the Cinema Home (Ajustes); the built-in one only without Cinema.
            Intent chosen = new Intent("br.casa.cinema.action.KEY").setClassName("br.casa.cinema", "br.casa.cinema.Botao")
                    .putExtra("cor", cor()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().resolveActivity(chosen, 0) != null) return chosen;
        }
        if (LIBRARY.equals(alvo)) {
            // The Cinema Home's own apps grid; the cleanyourtv launcher's library only when Cinema is not installed.
            if (getPackageManager().getLaunchIntentForPackage("br.casa.cinema") != null) {
                return new Intent("br.casa.cinema.action.APPS")
                        .setClassName("br.casa.cinema", "br.casa.cinema.Home")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            return new Intent("com.gathin.tvhome.action.APP_LIBRARY")
                    .setClassName("com.gathin.tvhome", "com.gathin.tvhome.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(alvo);
        if (launch == null) launch = pm.getLaunchIntentForPackage(alvo);
        if (launch == null) launch = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + alvo));
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
