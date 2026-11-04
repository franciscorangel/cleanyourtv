package br.casa.tvkeys;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Exists only so InputManager can find the key layouts in its meta-data; it handles no broadcast. */
public final class Layouts extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { }
}
