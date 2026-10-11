package de.felix.jarvis.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootStart extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        CallSession.registerAccount(c);
        LinkService.start(c);
        if (CallSession.systemEnabled(c) && Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel("ready", "Jarvis bereit machen", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            nm.notify(4, new android.app.Notification.Builder(c, "ready").setSmallIcon(R.drawable.ic_call)
                    .setContentTitle("Jarvis nach Neustart einmal öffnen").setContentText("Damit Anrufe in der Telefon-App gehört werden – einmal antippen genügt.")
                    .setAutoCancel(true).setContentIntent(android.app.PendingIntent.getActivity(c, 5, new Intent(c, MainActivity.class),
                            android.app.PendingIntent.FLAG_IMMUTABLE)).build());
        }
    }
}
