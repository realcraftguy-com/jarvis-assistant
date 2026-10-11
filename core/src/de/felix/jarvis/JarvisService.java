package de.felix.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * The always-on part of Jarvis. "24/7" = reachable and checking on schedule/events,
 * not sending AI requests without a reason.
 */
public class JarvisService extends Service {
    private static final Object LOCK = new Object();
    private static volatile boolean poked, pokeSchool;
    private static volatile JarvisService running;
    private volatile boolean alive;
    private PowerManager.WakeLock wake;
    private Brain brain;

    public static void start(Context c) {
        if ("1".equals(Db.get(c).kv("stopped", "0"))) return;
        try {
            c.startForegroundService(new Intent(c, JarvisService.class));
        } catch (Exception e) {
            Db.get(c).log("E", "system", "Dienst konnte nicht starten: " + e.getMessage());
        }
    }

    public static boolean isRunning() { return running != null; }

    public static void poke(Context c) {
        synchronized (LOCK) { poked = true; LOCK.notifyAll(); }
    }

    public static void pokeSchool(Context c) {
        pokeSchool = true;
        poke(c);
        if (running == null) start(c);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("status", "Jarvis-Status", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("alerts", "Jarvis-Hinweise", NotificationManager.IMPORTANCE_HIGH));
        startForeground(1, statusNotification("Jarvis ist bereit"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        running = this;
        alive = true;
        brain = Brain.get(this);
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:service");
        wake.setReferenceCounted(false);
        wake.acquire();
        Db.get(this).log("I", "system", "Dienst gestartet");
        scheduleWatchdog(this);
        new Thread(this::mainLoop, "jarvis-main").start();
        new Thread(this::telegramLoop, "jarvis-telegram").start();
        new Thread(this::linkLoop, "jarvis-link").start();
        McpServer.start(this);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        poke(this);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        alive = false;
        running = null;
        poke(this);
        if (wake != null && wake.isHeld()) wake.release();
        Db.get(this).log("I", "system", "Dienst beendet");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ------------------------------------------------------------------ loops

    private void mainLoop() {
        long nextWait = 0;
        while (alive) {
            synchronized (LOCK) {
                if (!poked) {
                    try { LOCK.wait(Math.max(1000, Math.min(nextWait <= 0 ? 30_000 : nextWait, 30_000))); } catch (InterruptedException ignored) {}
                }
                poked = false;
            }
            if (!alive) break;
            try {
                boolean force = pokeSchool;
                pokeSchool = false;
                nextWait = brain.tick(force);
                updateNotification();
            } catch (Throwable t) {
                Db.get(this).log("E", "system", "Fehler im Hauptablauf: " + t);
                nextWait = 30_000;
            }
        }
    }

    private void telegramLoop() {
        Telegram tg = new Telegram(this);
        int failures = 0;
        while (alive) {
            try {
                if (Cfg.paused(this) || !tg.configured()) { SystemClock.sleep(5000); continue; }
                tg.poll(brain);
                failures = 0;
                Db.get(this).kvSet("tg_poll_error", null);
            } catch (Throwable t) {
                failures++;
                Db.get(this).kvSet("tg_poll_error", t.getMessage());
                if (failures == 1 || failures % 20 == 0) Db.get(this).log("W", "telegram", "Empfang gestört: " + t.getMessage());
                SystemClock.sleep(Math.min(5000L * failures, 120_000L));
            }
        }
    }

    private void linkLoop() {
        while (alive) {
            try {
                if (Cfg.paused(this)) { SystemClock.sleep(5000); continue; }
                CallLink.get(this).runSubscriber(() -> alive);
            } catch (Throwable t) {
                Db.get(this).kvSet("link_error", t.getMessage());
            }
            SystemClock.sleep(5000);
        }
    }

    // ------------------------------------------------------------------ notification

    private Notification statusNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "status")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Jarvis")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }

    private String lastText = "";

    private void updateNotification() {
        String t = brain.statusLine();
        if (!t.equals(lastText)) {
            lastText = t;
            getSystemService(NotificationManager.class).notify(1, statusNotification(t));
        }
    }

    /** Restarts the service if the system killed it (every 15 min, inexact, battery friendly). */
    static void scheduleWatchdog(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, new Intent(c, BootReceiver.class).setAction("de.felix.jarvis.WATCHDOG"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 15 * 60_000L,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
    }
}
