package de.felix.jarvis.call;

import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings tile "Jarvis": one tap calls Jarvis. */
public class JarvisTile extends TileService {
    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.setLabel("Jarvis");
        t.setSubtitle("anrufen");
        t.updateTile();
    }

    @Override public void onClick() {
        Intent i = new Intent(this, QuickCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
