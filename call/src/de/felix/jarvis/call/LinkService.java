package de.felix.jarvis.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import de.felix.jarvis.shared.Link;

import org.json.JSONObject;

import java.security.KeyPair;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the encrypted link to Jarvis open (so Jarvis can ring this phone) and sends messages to him.
 * Runs as a foreground service; on Xiaomi/HyperOS "Autostart" and "Akku: Keine Einschränkungen" are needed.
 */
public class LinkService extends Service {
    private static volatile LinkService instance;
    private static final ExecutorService SEND = Executors.newSingleThreadExecutor();
    private static final Link.Seen SEEN = new Link.Seen();
    private static final Link.Assembler PARTS = new Link.Assembler();
    private volatile boolean alive;
    private volatile Thread loop;

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, LinkService.class)); } catch (Exception e) { Vault.log(c, "Dienststart fehlgeschlagen: " + e.getMessage()); }
    }

    static LinkService get() { return instance; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("link", "Verbindung zu Jarvis", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        instance = this;
        alive = true;
        loop = new Thread(this::run, "jarvis-link");
        loop.start();
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        alive = false;
        instance = null;
        if (loop != null) loop.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "link").setSmallIcon(R.drawable.ic_call)
                .setContentTitle("Jarvis erreichbar").setContentText(Vault.paired(this) ? "Bereit für Anrufe" : "Noch nicht gekoppelt")
                .setOngoing(true).setContentIntent(open).build();
    }

    /**
     * Makes the microphone usable for calls. Android only grants it if this happens while a Jarvis screen is
     * visible; afterwards it stays usable in the background (measured: works 70 s later while the phone app's call
     * screen is in front). Nothing is recorded until a call is active. Lost after a reboot until the app is opened.
     */
    void inCall(boolean active) {
        if (!active) return; // keep the capability; recording itself only happens during calls
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
        } catch (Exception e) {
            Vault.log(this, "Mikrofon-Bereitschaft nicht möglich: " + e.getMessage());
        }
    }

    boolean micReady() {
        return (getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0;
    }

    static boolean micReadyNow() {
        LinkService s = instance;
        return s != null && s.micReady();
    }

    // ---------------------------------------------------------------- receive loop

    private void run() {
        int fails = 0;
        long lastPing = 0;
        while (alive) {
            String topic = Vault.get(this, "topic_b", null);
            byte[] key = key();
            if (topic == null || key == null) { SystemClock.sleep(5000); continue; }
            try {
                String since = Vault.get(this, "since", "60s");
                String last = Link.subscribe(server(), topic, since, new Link.Listener() {
                    @Override public void onOpen() {
                        Vault.set(LinkService.this, "connected", String.valueOf(System.currentTimeMillis()));
                        // report a crash from last time to Jarvis (once)
                        String crash = Vault.get(LinkService.this, "last_crash", "");
                        if (!crash.isEmpty() && Vault.paired(LinkService.this)) {
                            Vault.log(LinkService.this, "Absturz von " + CallSession.fmt(Long.parseLong(Vault.get(LinkService.this, "last_crash_at", "0"))) + " an Jarvis gemeldet");
                            try { send(LinkService.this, "crash", new org.json.JSONObject().put("trace", crash)); } catch (Exception ignored) {}
                            Vault.set(LinkService.this, "last_crash", "");
                        }
                        // after an update the voice key may be missing: ask Jarvis once per hour
                        long asked = Long.parseLong(Vault.get(LinkService.this, "keys_asked", "0"));
                        if (Vault.paired(LinkService.this) && Vault.secret(LinkService.this, Vault.GEMINI) == null
                                && System.currentTimeMillis() - asked > 3600_000L) {
                            Vault.set(LinkService.this, "keys_asked", String.valueOf(System.currentTimeMillis()));
                            send(LinkService.this, "need_keys", null);
                        }
                    }

                    @Override public void onMessage(String id, String body) {
                        Vault.set(LinkService.this, "since", id);
                        JSONObject m = Link.open(key(), topic, body);
                        if (m != null && SEEN.accept(m, 10 * 60_000L)) handle(m);
                    }
                }, () -> alive);
                Vault.set(this, "since", last);
                fails = 0;
            } catch (Exception e) {
                fails++;
                Vault.set(this, "link_error", e.getMessage());
                SystemClock.sleep(Math.min(3000L * fails, 60_000L));
            }
            if (System.currentTimeMillis() - lastPing > 60 * 60_000L && Vault.paired(this)) {
                lastPing = System.currentTimeMillis();
                send(this, "ping", null);
            }
        }
    }

    private void handle(JSONObject m) {
        String t = m.optString("t");
        Vault.set(this, "last_from_jarvis", String.valueOf(System.currentTimeMillis()));
        switch (t) {
            case "welcome":
                Vault.set(this, "welcomed", "1");
                String g = m.optString("groq", "");
                if (!g.isEmpty()) Vault.secret(this, Vault.GROQ, g);
                String gem = m.optString("gemini", "");
                if (!gem.isEmpty()) Vault.secret(this, Vault.GEMINI, gem);
                Vault.log(this, "Mit Jarvis gekoppelt" + (g.isEmpty() ? " (noch kein Sprachschlüssel)" : ""));
                getSystemService(NotificationManager.class).notify(1, notification());
                MainActivity.refreshAll();
                break;
            case "ring":
                CallSession.get(this).incoming(m.optString("call"), m.optString("reason"), m.optLong("ts"));
                break;
            case "cancel":
                CallSession.get(this).remoteCancel(m.optString("call"));
                break;
            case "say":
                CallSession.get(this).say(m.optString("call"), m.optString("text"), m.optBoolean("final", true));
                break;
            case "live_config":
            case "tool_result": {
                String packed = PARTS.add(m.optString("group"), m.optInt("i"), m.optInt("n"), m.optString("chunk"));
                if (packed == null) break;
                try {
                    String payload = Link.unpack(packed);
                    if (t.equals("live_config")) CallSession.get(this).liveConfig(m.optString("call"), new JSONObject(payload));
                    else CallSession.get(this).toolResult(m.optString("call"), m.optString("tid"), payload);
                } catch (Exception e) {
                    Vault.log(this, "Nachricht von Jarvis unlesbar: " + e.getMessage());
                }
                break;
            }
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- pairing & sending

    private byte[] key() {
        String k = Vault.secret(this, Vault.LINK_KEY);
        return k == null ? null : Link.unb64(k);
    }

    private String server() { return Vault.get(this, "server", Link.DEFAULT_SERVER); }

    /** Takes the code from Jarvis ("JARVIS1.…"), derives the shared key and announces itself. */
    static String pair(Context c, String code) throws Exception {
        code = code.trim();
        if (!code.startsWith("JARVIS1.")) throw new IllegalArgumentException("Das ist kein Jarvis-Kopplungscode.");
        JSONObject j = new JSONObject(new String(Link.unb64(code.substring(8)), "UTF-8"));
        KeyPair kp = Link.newKeyPair();
        byte[] k = Link.sharedKey(Link.priv(kp), j.getString("k"), Link.unb64(j.getString("t")));
        Vault.secret(c, Vault.LINK_KEY, Link.b64(k));
        Vault.set(c, "topic_a", j.getString("a"));
        Vault.set(c, "topic_b", j.getString("b"));
        Vault.set(c, "server", j.optString("s", Link.DEFAULT_SERVER));
        Vault.set(c, "welcomed", "0");
        Vault.set(c, "since", "30s");
        JSONObject hello = new JSONObject().put("t", "hello").put("pub", Link.pub(kp))
                .put("proof", Link.proof(k, "hello:" + j.getString("t"))).put("device", Build.MANUFACTURER + " " + Build.MODEL);
        Link.publish(j.optString("s", Link.DEFAULT_SERVER), j.getString("a"), hello.toString());
        Vault.log(c, "Kopplungsanfrage gesendet");
        if (instance != null && instance.loop != null) instance.loop.interrupt(); // reconnect with the new topic
        start(c);
        return "Kopplung angefragt – Jarvis bestätigt gleich.";
    }

    /** Fire-and-forget encrypted message to Jarvis. */
    static void send(Context c, String type, JSONObject extra) {
        SEND.submit(() -> {
            try {
                String topic = Vault.get(c, "topic_a", null);
                String k = Vault.secret(c, Vault.LINK_KEY);
                if (topic == null || k == null) return;
                JSONObject m = Link.msg(type);
                if (extra != null) for (java.util.Iterator<String> it = extra.keys(); it.hasNext(); ) { String n = it.next(); m.put(n, extra.get(n)); }
                Link.publish(Vault.get(c, "server", Link.DEFAULT_SERVER), topic, Link.seal(Link.unb64(k), topic, m));
            } catch (Exception e) {
                Vault.log(c, "Senden an Jarvis fehlgeschlagen (" + type + "): " + e.getMessage());
            }
        });
    }
}
