package br.casa.cinema;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * A colour button of the remote, forwarded by br.casa.tvkeys: opens the app chosen for that colour in Ajustes
 * (Botões do controle) and finishes; never shows a window of its own.
 */
public final class Botao extends Activity {
    static final String ACTION = "br.casa.cinema.action.KEY", EXTRA_COLOR = "cor";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int i = java.util.Arrays.asList(Home.KEY_COLORS).indexOf(getIntent().getStringExtra(EXTRA_COLOR));
        if (i >= 0) {
            String pkg = Home.keyApp(getSharedPreferences(Home.PREFS, MODE_PRIVATE), i);
            PackageManager pm = getPackageManager();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) launch = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(Data.TAG, "key " + Home.KEY_COLORS[i] + " -> " + pkg + ": " + e);
            }
        }
        finish();
    }
}
