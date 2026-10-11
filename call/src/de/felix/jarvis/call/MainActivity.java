package de.felix.jarvis.call;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Contact-card style home: call Jarvis, recent calls, pairing and the few settings that matter. */
public class MainActivity extends Activity {
    private static MainActivity visible;
    private LinearLayout content;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextToSpeech preview;

    static void refreshAll() {
        MainActivity a = visible;
        if (a != null) a.h.post(a::render);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        CallSession.registerAccount(this);
        LinkService.start(this);
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.setBackgroundColor(0xFF0B1118);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(40));
        sv.addView(content);
        setContentView(sv);
        if ("call".equals(getIntent().getAction())) call();
        if ("mic_test".equals(getIntent().getAction()) && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Vault.set(this, "mic_test", "1");
            boolean fgs = getIntent().getBooleanExtra("fgs", true);
            h.postDelayed(() -> { LinkService ls = LinkService.get(); if (ls != null) ls.inCall(fgs); finish(); }, 2000); // while visible
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if ("mic_test".equals(i.getAction()) && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Vault.set(this, "mic_test", "1");
            LinkService ls = LinkService.get();
            if (ls != null) ls.inCall(i.getBooleanExtra("fgs", true));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = this;
        // while visible: make the microphone ready for calls in the phone app
        h.postDelayed(() -> { LinkService ls = LinkService.get(); if (ls != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ls.inCall(true); }, 800);
        render();
    }

    @Override protected void onPause() { super.onPause(); visible = null; }

    @Override protected void onDestroy() { if (preview != null) preview.shutdown(); super.onDestroy(); }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { render(); }

    private void call() {
        if (!Vault.paired(this)) { Toast.makeText(this, "Erst mit Jarvis koppeln", Toast.LENGTH_LONG).show(); return; }
        if (CallSession.systemEnabled(this) && checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            // the phone app shows the call; the microphone service starts now, while Jarvis' app is visible
            LinkService.start(this);
            LinkService ls = LinkService.get();
            if (ls != null) ls.inCall(true);
            CallSession.get(this).startOutgoing();
            if ("call".equals(getIntent().getAction())) h.postDelayed(() -> moveTaskToBack(true), 700); // the phone app's call screen takes over
            return;
        }
        startActivity(new Intent(this, InCallActivity.class).setAction("dial"));
    }

    private void render() {
        content.removeAllViews();
        boolean paired = Vault.paired(this);

        // contact card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(0, dp(12), 0, dp(20));
        ImageView av = new ImageView(this);
        av.setImageResource(R.drawable.avatar);
        card.addView(av, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView n = text("Jarvis", 32, 0xFFFFFFFF);
        n.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        card.addView(n);
        long last = Long.parseLong(Vault.get(this, "last_from_jarvis", "0"));
        card.addView(text(paired ? "Persönlicher Assistent · " + (System.currentTimeMillis() - last < 3 * 3600_000L ? "erreichbar" : "zuletzt " + ago(last))
                : "Noch nicht gekoppelt", 14, paired ? 0xFF8FB8CC : 0xFFFFB74D));
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER_HORIZONTAL);
        btn.setPadding(0, dp(20), 0, 0);
        ImageView callBtn = new ImageView(this);
        callBtn.setImageResource(R.drawable.ic_call);
        callBtn.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(paired ? 0xFF43A047 : 0xFF455A64);
        callBtn.setBackground(g);
        callBtn.setContentDescription("Jarvis anrufen");
        callBtn.setOnClickListener(v -> call());
        btn.addView(callBtn, new LinearLayout.LayoutParams(dp(72), dp(72)));
        btn.addView(text("Anrufen", 13, 0xFFB0BEC5));
        card.addView(btn);
        content.addView(card);

        if (!paired) pairing();

        section("Letzte Anrufe");
        String hist = Vault.get(this, "history", "");
        content.addView(text(hist.isEmpty() ? "Noch keine." : hist, 14, 0xFFCFD8DC, Gravity.START));

        String lat = Vault.get(this, "latencies", "");
        if (!lat.isEmpty()) {
            String[] p = lat.split(",");
            long sum = 0;
            for (String x : p) sum += Long.parseLong(x);
            section("Antwortzeit (dein Satzende → Jarvis spricht)");
            content.addView(text("zuletzt " + p[0] + " ms · Ø " + sum / p.length + " ms über " + p.length + " Antworten", 14, 0xFFCFD8DC, Gravity.START));
        }

        section("Einrichtung");
        check("Mikrofon", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        NotificationManager nm = getSystemService(NotificationManager.class);
        check("Vollbild-Anrufe (Klingeln bei gesperrtem Handy)", nm.canUseFullScreenIntent(),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName()))));
        check("Akku: keine Einschränkung", getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()),
                () -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        boolean pa = false;
        try { pa = getSystemService(TelecomManager.class).getSelfManagedPhoneAccounts().contains(CallSession.handle(this)); } catch (Exception ignored) {}
        check("Telefon-Integration", pa, () -> CallSession.registerAccount(this));
        boolean sys = CallSession.systemEnabled(this);
        check("Anrufkonto „Jarvis“ (Anrufe in der Telefon-App)", sys,
                () -> startActivity(new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)));
        check("Anrufe über die Telefon-App starten", checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                        && checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED,
                () -> requestPermissions(new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_NUMBERS}, 2));
        boolean redirect = CallSession.redirectionActive(this);
        check("Schutz: Jarvis-Nummer wird nie über die SIM gewählt", redirect, () -> {
            android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
            startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_REDIRECTION), 9);
        });
        content.addView(button(redirect ? "Jarvis als Kontakt speichern (+49 176 04069024)" : "Jarvis als Kontakt speichern (ohne Nummer – erst Schutz aktivieren)",
                v -> saveContact(redirect)));
        content.addView(text("Mit aktiviertem Anrufkonto klingelt Jarvis wie ein normaler Anruf und das Gespräch läuft in deiner Telefon-App. "
                + "Der Schutz (Rolle „Anrufweiterleitung“) leitet Anrufe an Jarvis' Nummer direkt zu Jarvis um, bevor das Handy wählt – "
                + "ohne ihn speichert Jarvis den Kontakt nur mit der Adresse " + CallSession.SIP + ".",
                13, 0xFF8A94A3, Gravity.START));
        content.addView(text("Xiaomi/HyperOS: Einstellungen → Apps → Jarvis Anruf → Autostart einschalten und Energiesparen → „Keine Einschränkungen“. "
                + "Sonst kann HyperOS die Verbindung im Hintergrund trennen und Jarvis' Anrufe kommen nicht an (Jarvis schreibt dir dann per Telegram).", 13, 0xFF8A94A3, Gravity.START));
        content.addView(button("Verknüpfung „Jarvis“ auf den Startbildschirm", v -> pinShortcut()));

        section("Stimme von Jarvis");
        if (Vault.secret(this, Vault.GEMINI) != null) {
            boolean liveOn = !"off".equals(Vault.get(this, "live_mode", "on"));
            content.addView(text(liveOn ? "Telefonate laufen über Gemini Live (natürliche Stimme: " + Vault.get(this, "live_voice", "Charon")
                    + "). Fällt Gemini aus, spricht die Ersatzstimme unten." : "Gemini Live ist ausgeschaltet.", 14, 0xFFCFD8DC, Gravity.START));
            if (!Vault.get(this, "live_preview", "").isEmpty()) content.addView(text(Vault.get(this, "live_preview", ""), 12, 0xFF8A94A3, Gravity.START));
            content.addView(button("Gemini-Stimme wählen und anhören", v -> pickLiveVoice()));
            content.addView(button(liveOn ? "Gemini Live ausschalten" : "Gemini Live einschalten", v -> {
                Vault.set(this, "live_mode", liveOn ? "off" : "on");
                render();
            }));
            section("Ersatzstimme (offline)");
        }
        if (NeuralVoice.downloaded(this)) {
            boolean on = "neural".equals(Vault.get(this, "voice_engine", "neural"));
            content.addView(text((on ? "Natürliche Stimme aktiv: " + Vault.get(this, "neural_voice", "M5") : "Google-Stimme aktiv")
                    + (Vault.get(this, "neural_test", "").isEmpty() ? "" : "\n" + Vault.get(this, "neural_test", "")), 14, 0xFFCFD8DC, Gravity.START));
            content.addView(button("Stimme wählen und anhören", v -> pickNeural()));
            content.addView(button(on ? "Stattdessen Google-Stimme nutzen" : "Natürliche Stimme nutzen", v -> {
                Vault.set(this, "voice_engine", on ? "google" : "neural");
                render();
            }));
            if (!on) content.addView(button("Google-Stimme wählen", v -> pickVoice()));
        } else {
            String prog = Vault.get(this, "neural_progress", "");
            content.addView(text("Jarvis klingt am natürlichsten mit der Offline-Stimme „Supertonic 3“. Einmaliger Download: 398 MB (am besten im WLAN), "
                    + "danach läuft sie ohne Internet und kostenlos." + (prog.isEmpty() ? "" : "\n" + prog), 14, 0xFFCFD8DC, Gravity.START));
            content.addView(button(downloading ? "Lädt …" : "Natürliche Stimme laden", v -> downloadNeural()));
            content.addView(button("Bis dahin: Google-Stimme wählen", v -> pickVoice()));
        }

        if (paired) {
            section("Kopplung");
            content.addView(text("Gekoppelt. Neuer Code von Jarvis ersetzt die Kopplung.", 13, 0xFF8A94A3, Gravity.START));
            pairing();
        }
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        section("Version " + ver + (Vault.secret(this, Vault.GEMINI) != null ? " · Gemini-Schlüssel vorhanden" : " · Gemini-Schlüssel fehlt noch"));
        section("Protokoll");
        String log = Vault.get(this, "log", "");
        content.addView(text(log.length() > 1500 ? log.substring(0, 1500) : log, 12, 0xFF8A94A3, Gravity.START));
    }

    private void pairing() {
        EditText code = new EditText(this);
        code.setHint("Kopplungscode von Jarvis (JARVIS1.…)");
        code.setTextColor(0xFFFFFFFF);
        code.setHintTextColor(0xFF8A94A3);
        code.setSingleLine(true);
        content.addView(code);
        LinearLayout r = new LinearLayout(this);
        r.addView(button("Einfügen", v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) code.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(this));
        }));
        r.addView(button("Koppeln", v -> {
            String c = code.getText().toString();
            new Thread(() -> {
                String res;
                try { res = LinkService.pair(this, c); } catch (Exception e) { res = "Fehler: " + e.getMessage(); }
                String fr = res;
                h.post(() -> { Toast.makeText(this, fr, Toast.LENGTH_LONG).show(); h.postDelayed(this::render, 4000); });
            }).start();
        }));
        content.addView(r);
    }

    private static volatile boolean downloading;

    private void downloadNeural() {
        if (downloading) return;
        downloading = true;
        render();
        new Thread(() -> {
            String res;
            try {
                NeuralVoice.download(this, (done, total, file) -> {
                    Vault.set(this, "neural_progress", String.format(Locale.GERMAN, "%.0f / %.0f MB", done / 1e6, total / 1e6));
                    refreshAll();
                });
                Vault.set(this, "voice_engine", "neural");
                res = "Natürliche Stimme bereit.";
            } catch (Exception e) {
                res = "Download unterbrochen (" + e.getMessage() + ") – nochmal tippen setzt fort.";
            }
            downloading = false;
            Vault.set(this, "neural_progress", res);
            Vault.log(this, res);
            refreshAll();
        }).start();
    }

    private void pickNeural() {
        String[] names = {"M1 (männlich)", "M2 (männlich)", "M3 (männlich)", "M4 (männlich)", "M5 (männlich)",
                "F1 (weiblich)", "F2 (weiblich)", "F3 (weiblich)", "F4 (weiblich)", "F5 (weiblich)"};
        new android.app.AlertDialog.Builder(this).setTitle("Stimme (tippen = Hörprobe)").setItems(names, (d, i) -> {
            Vault.set(this, "neural_voice", NeuralVoice.VOICES[i]);
            Toast.makeText(this, "Rechne Hörprobe …", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    NeuralVoice v = NeuralVoice.get(this);
                    String sample = "Ist erledigt, Boss. Der Mathe-Leistungsnachweis steht am Dienstag im Kalender. Das Lernen überlasse ich dir.";
                    long t0 = System.currentTimeMillis();
                    float[] pcm = v.synth(sample, Integer.parseInt(Vault.get(this, "neural_steps", "6")),
                            Float.parseFloat(Vault.get(this, "neural_speed", "1.05")), () -> true);
                    long ms = System.currentTimeMillis() - t0;
                    Vault.set(this, "neural_test", NeuralVoice.VOICES[i] + ": " + ms + " ms Rechenzeit für " + pcm.length * 1000L / v.sampleRate + " ms Sprache");
                    Vault.log(this, Vault.get(this, "neural_test", ""));
                    android.media.AudioTrack t = new android.media.AudioTrack.Builder()
                            .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                                    .setSampleRate(v.sampleRate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(pcm.length * 4).setTransferMode(android.media.AudioTrack.MODE_STATIC).build();
                    for (int k = 0; k < pcm.length; k++) pcm[k] = Math.max(-1f, Math.min(1f, pcm[k] * 1.6f));
                    t.write(pcm, 0, pcm.length, android.media.AudioTrack.WRITE_BLOCKING);
                    t.play();
                    android.os.SystemClock.sleep(pcm.length * 1000L / v.sampleRate + 300);
                    t.release();
                } catch (Exception e) {
                    Vault.log(this, "Hörprobe fehlgeschlagen: " + e);
                }
                refreshAll();
            }).start();
        }).show();
    }

    static final String[][] LIVE_VOICES = {{"Charon", "informativ, ruhig"}, {"Iapetus", "klar"}, {"Algieba", "weich"},
            {"Orus", "bestimmt"}, {"Sadaltager", "kundig"}, {"Schedar", "gleichmäßig"}, {"Rasalgethi", "informativ"},
            {"Umbriel", "locker"}, {"Gacrux", "reif"}, {"Alnilam", "bestimmt"}, {"Achird", "freundlich"}, {"Enceladus", "hauchig"}};

    private void pickLiveVoice() {
        String[] names = new String[LIVE_VOICES.length];
        for (int i = 0; i < names.length; i++) names[i] = LIVE_VOICES[i][0] + " – " + LIVE_VOICES[i][1]
                + (LIVE_VOICES[i][0].equals(Vault.get(this, "live_voice", "Charon")) ? " ✓" : "");
        new android.app.AlertDialog.Builder(this).setTitle("Gemini-Stimme (tippen = Hörprobe)").setItems(names, (d, i) -> {
            String voice = LIVE_VOICES[i][0];
            Vault.set(this, "live_voice", voice);
            Toast.makeText(this, "Hörprobe wird geholt …", Toast.LENGTH_SHORT).show();
            new Thread(() -> livePreview(voice)).start();
        }).show();
    }

    /** Short real Gemini Live session that speaks one sample sentence. */
    private void livePreview(String voice) {
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        Object done = new Object();
        long t0 = System.currentTimeMillis();
        long[] first = {0};
        try {
            de.felix.jarvis.shared.GeminiLive g = new de.felix.jarvis.shared.GeminiLive(Vault.secret(this, Vault.GEMINI),
                    "gemini-3.8-live", voice, "Du bist Jarvis, ein ruhiger, souveräner deutscher Assistent. Sprich genau den Satz, den du bekommst, natürlich und ohne Zusatz.",
                    null, new de.felix.jarvis.shared.GeminiLive.Events() {
                @Override public void onReady() {}
                @Override public void onAudio(byte[] b) { if (first[0] == 0) first[0] = System.currentTimeMillis(); pcm.write(b, 0, b.length); }
                @Override public void onInterrupted() {}
                @Override public void onTurnComplete() { synchronized (done) { done.notifyAll(); } }
                @Override public void onInputText(String t) {}
                @Override public void onOutputText(String t) {}
                @Override public void onToolCall(String id, String n, org.json.JSONObject a) {}
                @Override public void onClosed(String r) { synchronized (done) { done.notifyAll(); } }
            });
            g.open(8000);
            long ready = System.currentTimeMillis();
            g.sendText("Ist erledigt, Boss. Der Mathe-Leistungsnachweis steht am Dienstag im Kalender. Das Lernen überlasse ich dir.");
            synchronized (done) { done.wait(20000); }
            g.close();
            byte[] a = pcm.toByteArray();
            Vault.set(this, "live_preview", voice + ": Verbindung " + (ready - t0) + " ms, erste Sprache nach " + (first[0] == 0 ? "–" : (first[0] - ready) + " ms"));
            Vault.log(this, Vault.get(this, "live_preview", ""));
            if (a.length > 0) {
                android.media.AudioTrack t = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(24000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(a.length).setTransferMode(android.media.AudioTrack.MODE_STATIC).build();
                t.write(a, 0, a.length);
                t.play();
                android.os.SystemClock.sleep(a.length / 48 + 300);
                t.release();
            }
        } catch (Exception e) {
            Vault.set(this, "live_preview", "Hörprobe fehlgeschlagen: " + e.getMessage());
            Vault.log(this, Vault.get(this, "live_preview", ""));
        }
        refreshAll();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) { super.onActivityResult(req, res, data); render(); }

    private void saveContact(boolean withNumber) {
        java.util.ArrayList<android.content.ContentValues> rows = new java.util.ArrayList<>();
        if (withNumber) {
            android.content.ContentValues ph = new android.content.ContentValues();
            ph.put(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            ph.put(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, CallSession.NUMBER);
            ph.put(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE, android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
            rows.add(ph);
        }
        android.content.ContentValues sip = new android.content.ContentValues();
        sip.put(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE);
        sip.put(android.provider.ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS, CallSession.SIP);
        sip.put(android.provider.ContactsContract.CommonDataKinds.SipAddress.TYPE, android.provider.ContactsContract.CommonDataKinds.SipAddress.TYPE_OTHER);
        rows.add(sip);
        try {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.avatar);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
            android.content.ContentValues photo = new android.content.ContentValues();
            photo.put(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
            photo.put(android.provider.ContactsContract.CommonDataKinds.Photo.PHOTO, png.toByteArray());
            rows.add(photo);
        } catch (Exception ignored) {}
        Intent i = new Intent(android.provider.ContactsContract.Intents.Insert.ACTION)
                .setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, "Jarvis")
                .putExtra(android.provider.ContactsContract.Intents.Insert.NOTES, "Persönlicher Assistent")
                .putParcelableArrayListExtra(android.provider.ContactsContract.Intents.Insert.DATA, rows);
        try { startActivity(i); } catch (Exception e) { Toast.makeText(this, "Keine Kontakte-App gefunden", Toast.LENGTH_LONG).show(); }
    }

    private void pinShortcut() {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        ShortcutInfo si = new ShortcutInfo.Builder(this, "call-jarvis").setShortLabel("Jarvis").setLongLabel("Jarvis anrufen")
                .setIcon(Icon.createWithResource(this, R.drawable.avatar))
                .setIntent(new Intent(this, MainActivity.class).setAction("call")).build();
        sm.setDynamicShortcuts(java.util.Collections.singletonList(si));
        if (sm.isRequestPinShortcutSupported()) sm.requestPinShortcut(si, null);
    }

    private void pickVoice() {
        preview = new TextToSpeech(this, st -> h.post(() -> {
            List<Voice> vs = new ArrayList<>();
            try {
                for (Voice v : preview.getVoices()) if (v.getLocale().getLanguage().equals("de") && !v.isNetworkConnectionRequired()) vs.add(v);
            } catch (Exception ignored) {}
            if (vs.isEmpty()) { Toast.makeText(this, "Keine deutsche Offline-Stimme installiert (Google Sprachausgabe → Sprachdaten).", Toast.LENGTH_LONG).show(); return; }
            vs.sort((a, b) -> a.getName().compareTo(b.getName()));
            String[] names = new String[vs.size()];
            for (int i = 0; i < names.length; i++) names[i] = vs.get(i).getName() + (vs.get(i).getName().equals(Vault.get(this, "voice", "")) ? " ✓" : "");
            new android.app.AlertDialog.Builder(this).setTitle("Stimme").setItems(names, (d, i) -> {
                Vault.set(this, "voice", vs.get(i).getName());
                preview.setVoice(vs.get(i));
                preview.speak("Ist erledigt, Boss. Der Termin steht im Kalender.", TextToSpeech.QUEUE_FLUSH, null, "p");
            }).show();
        }));
    }

    // ------------------------------------------------------------------ helpers

    private void section(String s) {
        TextView t = text(s.toUpperCase(Locale.GERMAN), 12, 0xFF8A94A3, Gravity.START);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(22), 0, dp(6));
        content.addView(t);
    }

    private void check(String label, boolean ok, Runnable fix) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text((ok ? "✓  " : "•  ") + label, 15, ok ? 0xFF81C784 : 0xFFFFB74D, Gravity.START);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (!ok) r.addView(button("Erlauben", v -> fix.run()));
        content.addView(r);
    }

    private Button button(String s, View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(on);
        return b;
    }

    private TextView text(String s, float sp, int c) { return text(s, sp, c, Gravity.CENTER); }

    private TextView text(String s, float sp, int c, int grav) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(c);
        t.setGravity(grav);
        return t;
    }

    private static String ago(long ms) {
        if (ms <= 0) return "nie";
        long m = (System.currentTimeMillis() - ms) / 60000;
        return m < 60 ? "vor " + m + " min" : m < 1440 ? "vor " + m / 60 + " h" : "vor " + m / 1440 + " Tagen";
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
