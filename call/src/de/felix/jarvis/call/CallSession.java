package de.felix.jarvis.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** State of the one Jarvis call (incoming or outgoing). UI and Telecom connection follow it. */
public final class CallSession {
    enum State { IDLE, RINGING, DIALING, ACTIVE, ENDED }

    interface Listener { void onCallChanged(); }

    private static CallSession instance;

    static synchronized CallSession get(Context c) {
        if (instance == null) instance = new CallSession(c.getApplicationContext());
        return instance;
    }

    final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    volatile State state = State.IDLE;
    volatile String callId, reason, status = "";
    volatile boolean outgoing, muted, speaker, showText;
    volatile long startedAt, activeAt;
    final List<String[]> transcript = new ArrayList<>(); // {who, text}
    JarvisConnection connection;
    VoiceEngine engine;
    LiveEngine live;
    private volatile boolean liveWanted;
    private Ringtone ringtone;

    private CallSession(Context c) { ctx = c; }

    void listen(Listener l) { synchronized (listeners) { listeners.add(l); } }

    void unlisten(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    void changed() {
        main.post(() -> {
            List<Listener> copy;
            synchronized (listeners) { copy = new ArrayList<>(listeners); }
            for (Listener l : copy) l.onCallChanged();
        });
    }

    void setStatus(String s) { status = s; changed(); }

    // ---------------------------------------------------------------- telecom

    /** Jarvis' address. A SIP address can never become a (paid) mobile call: SIM accounts cannot dial "sip:". */
    static final String SIP = "jarvis@jarvis.assistant";
    static final Uri SIP_URI = Uri.fromParts("sip", SIP, null);
    /** Number shown for Jarvis (chosen by the Boss, not a real line). Calls to it are redirected to Jarvis. */
    static final String NUMBER = "+4917604069024";
    static final Uri NUMBER_URI = Uri.fromParts("tel", NUMBER, null);

    static boolean isJarvisNumber(String n) {
        if (n == null) return false;
        String d = n.replaceAll("[^0-9+]", "");
        if (d.startsWith("0049")) d = "+49" + d.substring(4);
        else if (d.startsWith("0") && !d.startsWith("00")) d = "+49" + d.substring(1);
        return d.equals(NUMBER);
    }

    /** The redirection role guarantees that the shown number is never dialled through the SIM. */
    static boolean redirectionActive(Context c) {
        android.app.role.RoleManager rm = c.getSystemService(android.app.role.RoleManager.class);
        return rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_REDIRECTION)
                && rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_REDIRECTION);
    }

    static PhoneAccountHandle handle(Context c) {
        return new PhoneAccountHandle(new ComponentName(c, JarvisConnectionService.class), "jarvis");
    }

    /** Managed calling account: calls appear in the phone app's own screens (Google Phone etc.). */
    static PhoneAccountHandle systemHandle(Context c) {
        return new PhoneAccountHandle(new ComponentName(c, JarvisConnectionService.class), "jarvis-system");
    }

    static void registerAccount(Context c) {
        TelecomManager tm = c.getSystemService(TelecomManager.class);
        try {
            tm.registerPhoneAccount(PhoneAccount.builder(handle(c), "Jarvis")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .setShortDescription("Jarvis-Anruf")
                    .setIcon(Icon.createWithResource(c, R.drawable.ic_call))
                    .addSupportedUriScheme("jarvis").build());
        } catch (Exception e) {
            Vault.log(c, "Telefonkonto nicht registriert: " + e.getMessage());
        }
        try {
            tm.registerPhoneAccount(PhoneAccount.builder(systemHandle(c), "Jarvis")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setAddress(SIP_URI).setSubscriptionAddress(SIP_URI)
                    .setShortDescription("Persönlicher Assistent")
                    .setIcon(Icon.createWithResource(c, R.drawable.avatar))
                    .setHighlightColor(0xFF4FC3F7)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_SIP).build());
        } catch (Exception e) {
            Vault.log(c, "Anrufkonto nicht registriert: " + e.getMessage());
        }
    }

    /** True if the user enabled the "Jarvis" calling account (Settings → Calling accounts). */
    static boolean systemEnabled(Context c) {
        if ("off".equals(Vault.get(c, "system_ui", "on"))) return false;
        try {
            PhoneAccount pa = c.getSystemService(TelecomManager.class).getPhoneAccount(systemHandle(c));
            if (pa == null) Vault.log(c, "Anrufkonto: getPhoneAccount lieferte null");
            return pa != null && pa.isEnabled();
        } catch (Exception e) {
            Vault.log(c, "Anrufkonto nicht prüfbar: " + e);
            return false;
        }
    }

    /** Current call runs in the phone app's UI (managed) instead of Jarvis' own screen. */
    volatile boolean managed;

    // ---------------------------------------------------------------- outgoing: the Boss calls Jarvis

    /** The Boss started the call in the phone app (contact "Jarvis"): the connection already exists. */
    synchronized void startFromSystem(JarvisConnection c) {
        if (state == State.RINGING || state == State.DIALING || state == State.ACTIVE) return;
        if (!LinkService.micReadyNow()) bootstrapMic();
        connection = c;
        managed = true;
        startOutgoing();
        connection = c;
    }

    synchronized void startOutgoing() {
        if (state == State.RINGING || state == State.DIALING || state == State.ACTIVE) return;
        reset();
        outgoing = true;
        callId = de.felix.jarvis.shared.Link.randomId(9);
        state = State.DIALING;
        startedAt = System.currentTimeMillis();
        setStatus("Verbinde mit Jarvis …");
        if (connection == null) {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            managed = systemEnabled(ctx) && ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            try {
                Bundle extras = new Bundle();
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, managed ? systemHandle(ctx) : handle(ctx));
                tm.placeCall(managed ? SIP_URI : Uri.fromParts("jarvis", "jarvis", null), extras);
            } catch (Exception e) {
                managed = false;
                Vault.log(ctx, "System-Anruf nicht möglich (" + e.getMessage() + "), App-Modus");
            }
        }
        liveWanted = liveAvailable();
        try { LinkService.send(ctx, "call_start", new JSONObject().put("call", callId).put("live", liveWanted)); } catch (Exception ignored) {}
        main.postDelayed(() -> {
            if (state == State.DIALING && callId != null) {
                Vault.log(ctx, "Jarvis hat nicht geantwortet");
                setStatus("Jarvis antwortet nicht");
                end(false, "Jarvis nicht erreichbar");
            }
        }, 20_000);
        changed();
    }

    // ---------------------------------------------------------------- incoming: Jarvis calls

    synchronized void incoming(String id, String why, long ts) {
        if (System.currentTimeMillis() - ts > 45_000) return; // stale ring after a reconnect
        if (state == State.RINGING || state == State.DIALING || state == State.ACTIVE) {
            LinkService.send(ctx, "declined", obj("call", id));
            return;
        }
        reset();
        outgoing = false;
        callId = id;
        reason = why;
        state = State.RINGING;
        startedAt = System.currentTimeMillis();
        status = "Eingehender Anruf";
        LinkService.send(ctx, "ringing", obj("call", id));
        boolean viaTelecom = false;
        // the phone app's screen only if the microphone is ready in the background, otherwise Jarvis' own screen
        managed = systemEnabled(ctx) && LinkService.micReadyNow();
        if (systemEnabled(ctx) && !managed) Vault.log(ctx, "Mikrofon nicht bereit (App seit Neustart nicht geöffnet) – Jarvis-Anrufbildschirm");
        try {
            Bundle extras = new Bundle();
            // with the number protected, incoming calls show it, so the phone app shows the contact (name + photo)
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, managed ? (redirectionActive(ctx) ? NUMBER_URI : SIP_URI) : Uri.fromParts("jarvis", "jarvis", null));
            ctx.getSystemService(TelecomManager.class).addNewIncomingCall(managed ? systemHandle(ctx) : handle(ctx), extras);
            viaTelecom = true;
        } catch (Exception e) {
            Vault.log(ctx, "System-Anruf nicht möglich: " + e.getMessage());
        }
        if (!viaTelecom) showIncomingUi();
        main.postDelayed(() -> {
            if (state == State.RINGING && id.equals(callId)) {
                missed();
            }
        }, 45_000);
        changed();
    }

    /** Briefly brings a Jarvis screen to the front so Android allows the microphone (then it closes itself). */
    void bootstrapMic() {
        try {
            ctx.startActivity(new Intent(ctx, MicBootstrapActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception e) {
            Vault.log(ctx, "Mikrofon-Freigabe nicht möglich: " + e.getMessage());
        }
    }

    /** Called by the connection (or directly without Telecom): ring and show the call screen. */
    void showIncomingUi() {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("ring", "Eingehende Jarvis-Anrufe", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null); // the app plays the ringtone itself (self-managed call)
        nm.createNotificationChannel(ch);
        Intent full = new Intent(ctx, InCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(ctx, 1, full, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent answer = PendingIntent.getActivity(ctx, 2, new Intent(ctx, InCallActivity.class).setAction("answer")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent decline = PendingIntent.getBroadcast(ctx, 3, new Intent(ctx, CallActions.class).setAction("decline"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Person jarvis = new Person.Builder().setName("Jarvis").setIcon(Icon.createWithResource(ctx, R.drawable.avatar)).setImportant(true).build();
        Notification n = new Notification.Builder(ctx, "ring").setSmallIcon(R.drawable.ic_call)
                .setStyle(Notification.CallStyle.forIncomingCall(jarvis, decline, answer))
                .setContentText(reason == null || reason.isEmpty() ? "Jarvis ruft an" : reason)
                .setFullScreenIntent(fullPi, true).setCategory(Notification.CATEGORY_CALL).setOngoing(true).build();
        nm.notify(2, n);
        startRinging();
    }

    private void startRinging() {
        try {
            Uri uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(ctx, uri);
            if (ringtone != null) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ringtone.setLooping(true);
                ringtone.play();
            }
            Vibrator v = ctx.getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 1200}, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
        } catch (Exception e) {
            Vault.log(ctx, "Klingelton: " + e.getMessage());
        }
    }

    private void stopRinging() {
        try { if (ringtone != null) ringtone.stop(); } catch (Exception ignored) {}
        ringtone = null;
        Vibrator v = ctx.getSystemService(Vibrator.class);
        if (v != null) v.cancel();
        ctx.getSystemService(NotificationManager.class).cancel(2);
    }

    synchronized void answer() {
        if (state != State.RINGING) return;
        stopRinging();
        liveWanted = liveAvailable();
        JSONObject early = earlyConfig;
        earlyConfig = null;
        boolean ready = liveWanted && early != null;
        try { LinkService.send(ctx, "answered", new JSONObject().put("call", callId).put("live", liveWanted).put("has_config", ready)); } catch (Exception ignored) {}
        goActive();
        setStatus("Verbinde …");
        if (ready) liveConfig(callId, early); // prepared by Jarvis while it was ringing: Gemini starts at once
    }

    /** Live setup Jarvis sent while the phone was still ringing (his own calls). */
    private JSONObject earlyConfig;

    synchronized void decline() {
        if (state != State.RINGING) return;
        Vault.log(ctx, "Anruf abgelehnt");
        stopRinging();
        LinkService.send(ctx, "declined", obj("call", callId));
        Vault.addHistory(ctx, fmt(startedAt) + "  ✕ Abgelehnt: " + nz(reason));
        finish(DisconnectCause.REJECTED);
    }

    private void missed() {
        stopRinging();
        LinkService.send(ctx, "declined", obj("call", callId));
        Vault.addHistory(ctx, fmt(startedAt) + "  ✕ Verpasst: " + nz(reason));
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("missed", "Verpasste Jarvis-Anrufe", NotificationManager.IMPORTANCE_DEFAULT));
        nm.notify(3, new Notification.Builder(ctx, "missed").setSmallIcon(R.drawable.ic_call).setContentTitle("Verpasster Anruf von Jarvis")
                .setContentText(nz(reason)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 4, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).build());
        finish(DisconnectCause.MISSED);
    }

    synchronized void remoteCancel(String id) {
        if (id.equals(callId) && state == State.RINGING) missed();
    }

    // ---------------------------------------------------------------- conversation

    boolean liveAvailable() {
        return Vault.secret(ctx, Vault.GEMINI) != null && !"off".equals(Vault.get(ctx, "live_mode", "on"));
    }

    private void goActive() {
        state = State.ACTIVE;
        activeAt = System.currentTimeMillis();
        if (connection != null) connection.setActive();
        LinkService s = LinkService.get();
        if (s != null) s.inCall(true);
        if (liveWanted) {
            // the live setup comes from Jarvis; if it does not arrive, the classic chain takes over
            String id = callId;
            main.postDelayed(() -> { if (live == null && engine == null && id != null && id.equals(callId) && state == State.ACTIVE) liveFailed("keine Antwort von Jarvis"); }, 10_000);
        } else {
            startClassic();
        }
        changed();
    }

    private void startClassic() {
        if (engine != null) return;
        engine = new VoiceEngine(this);
        engine.start();
    }

    /** Jarvis sent the setup for Gemini Live. */
    synchronized void liveConfig(String id, JSONObject cfg) {
        if (callId == null || !id.equals(callId)) return;
        if (state == State.RINGING) { earlyConfig = cfg; return; }
        if (state == State.DIALING) {
            Vault.addHistory(ctx, fmt(startedAt) + "  ↗ Du hast Jarvis angerufen");
            goActive();
        }
        if (state != State.ACTIVE || live != null || engine != null) return;
        new Thread(() -> {
            LiveEngine l = new LiveEngine(this);
            try {
                l.start(cfg);
                synchronized (this) {
                    if (callId != null && id.equals(callId) && state == State.ACTIVE) { live = l; l.setMuted(muted); }
                    else l.stop();
                }
            } catch (Exception e) {
                l.stop();
                liveFailed(e.getMessage());
            }
        }, "live-start").start();
    }

    synchronized void toolResult(String id, String tid, String result) {
        if (live != null && id.equals(callId)) live.toolResult(tid, result);
    }

    private synchronized void liveFailed(String why) {
        Vault.log(ctx, "Gemini Live nicht verfügbar: " + why + " – Ersatzstimme");
        try { LinkService.send(ctx, "live_failed", new JSONObject().put("call", callId).put("why", why).put("reason", outgoing ? "" : nz(reason))); } catch (Exception ignored) {}
        startClassic();
    }

    /** Live connection lost mid-call: continue with the classic chain, the call goes on. */
    synchronized void liveLost(String why) {
        if (live == null || state != State.ACTIVE) return;
        live = null;
        startClassic();
        engine.speak("Kurze Störung in der Leitung – ich bin noch da.", true);
    }

    void setMuted(boolean m) {
        muted = m;
        if (engine != null) engine.setMuted(m);
        if (live != null) live.setMuted(m);
    }

    void route(boolean speakerOn) {
        if (live != null) live.route(speakerOn);
        else if (engine != null) engine.route(speakerOn);
    }

    /** Jarvis' words (streamed sentence by sentence). */
    synchronized void say(String id, String text, boolean last) {
        if (callId == null || !id.equals(callId)) return;
        if (state == State.DIALING) {
            liveWanted = false; // Jarvis answered classically
            goActive();
            Vault.addHistory(ctx, fmt(startedAt) + "  ↗ Du hast Jarvis angerufen");
        }
        if (state == State.ACTIVE && engine == null && live == null) startClassic();
        if (state != State.ACTIVE || engine == null) return;
        if (!text.trim().isEmpty()) addLine("Jarvis", text);
        engine.speak(text, last);
    }

    void addLine(String who, String text) {
        synchronized (transcript) { transcript.add(new String[]{who, text}); }
        changed();
    }

    /** The Boss said something (after speech recognition). */
    void utterance(String text, long sttMs, long speechEndAt) {
        addLine("Du", text);
        try {
            LinkService.send(ctx, "utterance", new JSONObject().put("call", callId).put("text", text).put("stt_ms", sttMs).put("end_at", speechEndAt));
        } catch (Exception ignored) {}
    }

    synchronized void hangup() {
        if (state == State.IDLE || state == State.ENDED) return;
        if (state == State.RINGING) { decline(); return; }
        end(true, null);
    }

    synchronized void end(boolean notifyJarvis, String why) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        Vault.log(ctx, "Anruf beendet (" + (why == null ? "aufgelegt" : why) + ") via " + (st.length > 2 ? st[1].getMethodName() + "<" + st[2].getClassName().replace("de.felix.jarvis.call.", "") + "." + st[2].getMethodName() : "?"));
        if (notifyJarvis && callId != null) LinkService.send(ctx, "hangup", obj("call", callId));
        if (state == State.ACTIVE) {
            long dur = (System.currentTimeMillis() - activeAt) / 1000;
            Vault.addHistory(ctx, fmt(startedAt) + "  " + (outgoing ? "↗" : "↙") + " " + dur / 60 + ":" + String.format("%02d", dur % 60)
                    + (outgoing ? "" : "  " + nz(reason)));
        } else if (why != null) {
            Vault.addHistory(ctx, fmt(startedAt) + "  ✕ " + why);
        }
        finish(DisconnectCause.LOCAL);
    }

    private void finish(int cause) {
        // called from Telecom on the main thread: nothing here may throw, or the app crashes on hang-up
        try { stopRinging(); } catch (Throwable t) { Vault.log(ctx, "Auflegen/Klingeln: " + t); }
        try { if (engine != null) engine.stop(); } catch (Throwable t) { Vault.log(ctx, "Auflegen/Stimme: " + t); }
        engine = null;
        try { if (live != null) live.stop(); } catch (Throwable t) { Vault.log(ctx, "Auflegen/Gemini: " + t); }
        live = null;
        liveWanted = false;
        if (connection != null) {
            try { connection.setDisconnected(new DisconnectCause(cause)); connection.destroy(); } catch (Throwable t) { Vault.log(ctx, "Auflegen/Telecom: " + t); }
            connection = null;
        }
        LinkService s = LinkService.get();
        if (s != null) s.inCall(false);
        state = State.ENDED;
        callId = null;
        changed();
        main.postDelayed(() -> { if (state == State.ENDED) { state = State.IDLE; changed(); } }, 1500);
    }

    private void reset() {
        muted = false;
        earlyConfig = null;
        if (state == State.IDLE || state == State.ENDED) managed = false;
        speaker = false;
        reason = null;
        synchronized (transcript) { transcript.clear(); }
    }

    static JSONObject obj(String k, String v) {
        try { return new JSONObject().put(k, v); } catch (Exception e) { return new JSONObject(); }
    }

    static String fmt(long ms) { return new java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMAN).format(new java.util.Date(ms)); }

    static String nz(String s) { return s == null ? "" : s; }
}
