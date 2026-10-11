package de.felix.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts Jarvis after a reboot or app update, and is the target of the 15-minute watchdog alarm. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) {
            Db.get(c).log("I", "system", "Gerät neu gestartet – Jarvis startet");
        }
        if (!JarvisService.isRunning()) JarvisService.start(c);
        JarvisService.scheduleWatchdog(c);
    }
}
