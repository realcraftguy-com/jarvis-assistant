package de.felix.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** German UI: status, school, tasks, questions, reminders, log, chat, settings. Pause/Stop always visible. */
public class MainActivity extends Activity {
    private static final String[] TABS = {"Übersicht", "Schule", "Rückfragen", "Aufgaben", "Erinnerungen", "Chat", "Protokoll", "Einstellungen"};
    private LinearLayout content, tabBar;
    private TextView statusLine;
    private LinearLayout topButtons;
    private int tab = 0;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Db db;
    private boolean editing;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (!editing && (tab == 0 || tab == 6)) render();
            renderTop();
            h.postDelayed(this, 5000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = Db.get(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        int p = Ui.dp(this, 16);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(p, Ui.dp(this, 12), p, 0);
        TextView name = Ui.text(this, "Jarvis", 26, Ui.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusLine = Ui.text(this, "", 14, Ui.MUTED);
        topButtons = Ui.hbox(this);
        top.addView(name);
        top.addView(statusLine);
        top.addView(topButtons);
        root.addView(top);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.VERTICAL);
        tabBar.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 4));
        root.addView(tabBar);

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(p, 0, p, Ui.dp(this, 40));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        JarvisService.start(this);
        String t = getIntent().getStringExtra("tab");
        if (t != null) for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(t)) tab = i;
        render();
    }

    @Override protected void onResume() { super.onResume(); h.post(refresher); }

    @Override protected void onPause() { super.onPause(); h.removeCallbacks(refresher); }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { JarvisService.start(this); render(); }

    // ------------------------------------------------------------------ top

    private void renderTop() {
        boolean stopped = "1".equals(db.kv("stopped", "0"));
        boolean paused = Cfg.paused(this);
        statusLine.setText(stopped ? "Gestoppt – Jarvis läuft nicht." : paused ? "Pausiert – keine automatischen Aktionen."
                : JarvisService.isRunning() ? Brain.get(this).statusLine() : "Dienst startet …");
        statusLine.setTextColor(stopped ? Ui.BAD : paused ? Ui.WARN : Ui.OK);
        topButtons.removeAllViews();
        topButtons.addView(Ui.button(this, paused ? "▶ Fortsetzen" : "⏸ Pause", Ui.WARN, v -> {
            Cfg.setPaused(this, !Cfg.paused(this));
            JarvisService.poke(this);
            renderTop();
        }));
        topButtons.addView(Ui.button(this, stopped ? "Starten" : "■ Stopp", stopped ? Ui.OK : Ui.BAD, v -> {
            if (stopped) {
                db.kvSet("stopped", "0");
                Cfg.setPaused(this, false);
                JarvisService.start(this);
            } else {
                db.kvSet("stopped", "1");
                Cfg.setPaused(this, true);
                stopService(new Intent(this, JarvisService.class));
                db.log("I", "system", "Gestoppt über die App");
            }
            h.postDelayed(this::renderTop, 400);
        }));
        tabBar.removeAllViews();
        LinearLayout tabRow = null;
        for (int i = 0; i < TABS.length; i++) {
            if (i % 3 == 0) { tabRow = Ui.hbox(this); tabBar.addView(tabRow); }
            final int idx = i;
            String label = TABS[i];
            if (i == 2) {
                int n = db.one("SELECT COUNT(*) n FROM questions WHERE status='open'").getAsInteger("n");
                if (n > 0) label += " (" + n + ")";
            }
            TextView t = Ui.text(this, label, 14, i == tab ? Ui.ACCENT : Ui.MUTED);
            t.setTypeface(i == tab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            t.setOnClickListener(v -> { tab = idx; editing = false; render(); renderTop(); });
            tabRow.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private void render() {
        content.removeAllViews();
        renderTop();
        switch (tab) {
            case 0: overview(); break;
            case 1: school(); break;
            case 2: questions(); break;
            case 3: tasks(); break;
            case 4: reminders(); break;
            case 5: chat(); break;
            case 6: log(); break;
            default: settings(); break;
        }
    }

    // ------------------------------------------------------------------ overview

    private void overview() {
        LinearLayout c = Ui.card(this);
        boolean running = JarvisService.isRunning();
        c.addView(Ui.row(this, "Betrieb", running ? (Cfg.paused(this) ? "pausiert" : "läuft") + " · letzter Durchlauf " + Ui.ago(db.kvLong("tick_at", 0))
                : "Dienst läuft nicht", running && !Cfg.paused(this) ? Ui.OK : Ui.WARN));

        String eduState = db.kv("edu_state", new EduPage(this).hasSession() ? "angemeldet, noch nicht geprüft" : "nicht angemeldet");
        long eduOk = db.kvLong("edu_last_ok", 0);
        c.addView(Ui.row(this, "EduPage", eduState + "\nLetzte erfolgreiche Prüfung: " + Ui.ago(eduOk)
                + "\nPrüfintervall: " + Cfg.get(this, "edu_poll_min") + " min", "ok".equals(eduState) ? Ui.OK : Ui.WARN));

        CalendarStore.Cal cal = new CalendarStore(this).target();
        ContentValues cm = db.one("SELECT COUNT(*) n, SUM(sync_state='local') l FROM cal_map WHERE cancelled=0 AND entry_key NOT LIKE 'test:%'");
        int local = cm.getAsInteger("l") == null ? 0 : cm.getAsInteger("l");
        c.addView(Ui.row(this, "Kalender", cal == null ? "kein Google-Kalender gefunden (Berechtigung?)"
                        : cal.name + " (" + cal.accountType + ")\n" + cm.getAsInteger("n") + " Einträge von Jarvis, " + local + " warten auf Google-Sync",
                cal == null ? Ui.BAD : local > 0 ? Ui.WARN : Ui.OK));

        Telegram tg = new Telegram(this);
        int pending = Outbox.pendingCount(this);
        String tgErr = db.kv("tg_poll_error", null);
        c.addView(Ui.row(this, "Telegram (Nachrichten an dich)", !tg.configured() ? "kein Bot-Token eingetragen"
                        : !tg.paired() ? "Bot @" + db.kv("tg_bot_username", "?") + " – noch nicht mit dir gekoppelt"
                        : "gekoppelt mit @" + db.kv("tg_bot_username", "?") + "\nLetzte Nachricht gesendet: " + Ui.ago(db.kvLong("tg_last_send_ok", 0))
                        + "\nWartend: " + pending + (tgErr != null ? "\nEmpfang gestört: " + tgErr : ""),
                tg.paired() && pending == 0 && tgErr == null ? Ui.OK : tg.paired() ? Ui.WARN : Ui.BAD));

        ClaudeBridge cbr = new ClaudeBridge(this);
        long cl = db.kvLong("claude_limited_until", 0);
        String ch = db.kv("claude_health", "");
        c.addView(Ui.row(this, "Gehirn: Claude Opus 5.5 (dein Abo)", !cbr.enabled() ? "ausgeschaltet – Groq antwortet"
                        : ch.isEmpty() ? "Brücke zu Termux noch nicht erreichbar"
                        : (cbr.loggedIn() ? "angemeldet" : "in Termux noch nicht angemeldet")
                        + (cl > System.currentTimeMillis() ? "\nAbo-Limit – Groq übernimmt bis " + Brain.fmt(cl) : "")
                        + "\nZuletzt geantwortet: " + db.kv("brain_last", "–")
                        + (db.kv("claude_latencies", "").isEmpty() ? "" : "\nLetzte Antwortzeit: " + db.kv("claude_latencies", "").split(",")[0] + " ms")
                        + (db.kv("claude_error", null) != null ? "\nFehler: " + db.kv("claude_error", "") : ""),
                cbr.enabled() && cbr.loggedIn() && cl < System.currentTimeMillis() ? Ui.OK : Ui.WARN));
        Llm llm = Brain.get(this).llm;
        long blocked = db.kvLong("llm_blocked_until", 0);
        String lat = db.kv("llm_latencies", "");
        c.addView(Ui.row(this, "KI (Groq, kostenlos)", !llm.configured() ? "kein Schlüssel eingetragen – Schule läuft regelbasiert"
                        : "heute " + llm.usedToday() + " / " + Cfg.get(this, "llm_daily_budget") + " Anfragen"
                        + (blocked > System.currentTimeMillis() ? "\nLimit – wieder ab " + Brain.fmt(blocked) : "")
                        + (lat.isEmpty() ? "" : "\nLetzte Antwortzeit: " + lat.split(",")[0].split("/")[0] + " ms (" + db.kv("llm_last_model", "") + ")"),
                !llm.configured() ? Ui.WARN : blocked > System.currentTimeMillis() ? Ui.WARN : Ui.OK));

        CallLink link = CallLink.get(this);
        c.addView(Ui.row(this, "Anruf-App", !link.paired() ? "nicht gekoppelt"
                        : "gekoppelt (" + db.kv("link_peer_name", "Telefon") + ")\nZuletzt erreichbar: " + Ui.ago(db.kvLong("link_last_seen", 0))
                        + "\nRelay-Nachrichten heute: " + db.kv("ntfy_count", "0") + " / 250",
                link.paired() ? Ui.OK : Ui.WARN));

        Intent bat = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int lvl = bat == null ? -1 : bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int temp = bat == null ? 0 : bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        boolean plugged = bat != null && bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        c.addView(Ui.row(this, "Gerät", "Akku " + lvl + " %" + (plugged ? " (lädt/Netzteil)" : " (nicht angesteckt)") + ", " + temp / 10.0 + " °C"
                + "\nAkku-Optimierung: " + (exempt ? "ausgenommen ✓" : "aktiv – kann Jarvis im Hintergrund bremsen"), exempt ? Ui.OK : Ui.WARN));
        content.addView(Ui.title(this, "Status"));
        content.addView(c);

        LinearLayout btns = Ui.hbox(this);
        btns.addView(Ui.button(this, "EduPage jetzt prüfen", Ui.ACCENT, v -> {
            JarvisService.pokeSchool(this);
            Toast.makeText(this, "Prüfung läuft …", Toast.LENGTH_SHORT).show();
        }));
        btns.addView(Ui.button(this, "Senden wiederholen", Ui.ACCENT, v -> {
            db.getWritableDatabase().execSQL("UPDATE outbox SET next_try=0 WHERE status='pending'");
            JarvisService.poke(this);
        }));
        content.addView(btns);
    }

    // ------------------------------------------------------------------ school

    private void school() {
        content.addView(Ui.title(this, "Kalendereinträge von Jarvis"));
        List<ContentValues> map = db.query("SELECT * FROM cal_map WHERE entry_key NOT LIKE 'test:%' AND start_ms>? ORDER BY start_ms LIMIT 60",
                String.valueOf(System.currentTimeMillis() - 7 * 86_400_000L));
        if (map.isEmpty()) content.addView(Ui.text(this, "Noch keine.", 14, Ui.MUTED));
        for (ContentValues m : map) {
            LinearLayout c = Ui.card(this);
            String state = m.getAsInteger("adopted") == 1 ? "bereits vorhanden (fremder Eintrag, unverändert)"
                    : "synced".equals(m.getAsString("sync_state")) ? "mit Google synchronisiert ✓" : "lokal gespeichert – Google-Sync ausstehend";
            c.addView(Ui.row(this, m.getAsString("title") + (m.getAsInteger("cancelled") == 1 ? " (abgesagt)" : ""),
                    SchoolPipeline.when(m.getAsLong("start_ms"), m.getAsInteger("all_day") == 1) + "\n" + state + "\nQuelle: " + m.getAsString("src_key"),
                    m.getAsInteger("cancelled") == 1 ? Ui.MUTED : "synced".equals(m.getAsString("sync_state")) ? Ui.OK : Ui.WARN));
            if (m.getAsInteger("adopted") == 0) {
                String key = m.getAsString("entry_key");
                c.addView(Ui.button(this, "Termin löschen", Ui.BAD, v -> confirm("Diesen von Jarvis angelegten Termin löschen?", () -> {
                    boolean ok = new CalendarStore(this).deleteOwned(key);
                    db.log("I", "kalender", (ok ? "Gelöscht: " : "Nicht gelöscht: ") + key);
                    render();
                })));
            }
            content.addView(c);
        }
        content.addView(Ui.title(this, "EduPage-Einträge (neueste zuerst)"));
        for (ContentValues s : db.query("SELECT * FROM school_items WHERE status NOT IN ('ignored','past','duplicate') AND src_key NOT LIKE 'test:%' ORDER BY published DESC LIMIT 40")) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.row(this, SchoolPipeline.shorten(s.getAsString("title"), 70),
                    s.getAsString("edu_type") + " · " + s.getAsString("published") + " · " + statusDe(s.getAsString("status")), Ui.ACCENT));
            String key = s.getAsString("src_key");
            c.addView(Ui.button(this, "Erneut verarbeiten", Ui.ACCENT, v -> {
                db.update("school_items", Db.cv("text_hash", "x"), "src_key=?", key);
                JarvisService.pokeSchool(this);
                Toast.makeText(this, "Wird beim nächsten Durchlauf neu verarbeitet", Toast.LENGTH_SHORT).show();
            }));
            content.addView(c);
        }
        long n = db.one("SELECT COUNT(*) n FROM school_items WHERE status='ignored'").getAsLong("n");
        content.addView(Ui.text(this, n + " weitere Einträge als nicht relevant übersprungen (Stundenplan, Noten, Mensa …).", 13, Ui.MUTED));
    }

    private static String statusDe(String s) {
        switch (s == null ? "" : s) {
            case "done": return "erledigt";
            case "notify": return "Nachricht ausstehend";
            case "question": return "Rückfrage offen";
            case "error": return "Fehler – wird erneut versucht";
            case "new": return "neu";
            default: return s;
        }
    }

    // ------------------------------------------------------------------ questions

    private void questions() {
        content.addView(Ui.title(this, "Offene Rückfragen"));
        List<ContentValues> open = db.query("SELECT * FROM questions WHERE status='open' ORDER BY id DESC");
        if (open.isEmpty()) content.addView(Ui.text(this, "Keine offenen Rückfragen.", 14, Ui.MUTED));
        for (ContentValues q : open) {
            LinearLayout c = Ui.card(this);
            long id = q.getAsLong("id");
            c.addView(Ui.text(this, "#" + id + " · " + Brain.fmt(q.getAsLong("created")), 12, Ui.MUTED));
            c.addView(Ui.text(this, q.getAsString("text"), 14, Ui.TEXT));
            EditText in = Ui.input(this, "Antwort, z. B. 22.10. 8 Uhr", "", false);
            in.setOnFocusChangeListener((v, f) -> editing = f);
            c.addView(in);
            LinearLayout b = Ui.hbox(this);
            b.addView(Ui.button(this, "Antworten", Ui.ACCENT, v -> async(() -> Brain.get(this).answerQuestion(id, in.getText().toString()))));
            b.addView(Ui.button(this, "Ignorieren", Ui.WARN, v -> async(() -> Brain.get(this).answerQuestion(id, "ignore"))));
            c.addView(b);
            content.addView(c);
        }
        content.addView(Ui.title(this, "Erledigte Rückfragen"));
        for (ContentValues q : db.query("SELECT * FROM questions WHERE status!='open' ORDER BY id DESC LIMIT 15")) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, SchoolPipeline.shorten(q.getAsString("text"), 140) + "\n→ " + q.getAsString("answer"), 13, Ui.MUTED));
            long id = q.getAsLong("id");
            c.addView(Ui.button(this, "Löschen", Ui.BAD, v -> { db.delete("questions", "id=?", String.valueOf(id)); render(); }));
            content.addView(c);
        }
    }

    // ------------------------------------------------------------------ tasks

    private void tasks() {
        content.addView(Ui.title(this, "Neue Aufgabe"));
        LinearLayout f = Ui.card(this);
        EditText t = Ui.input(this, "Titel", "", false);
        EditText d = Ui.input(this, "Nachricht/Details (optional)", "", false);
        CheckBox auto = new CheckBox(this);
        auto.setText("Jarvis arbeitet selbstständig daran");
        auto.setTextColor(Ui.TEXT);
        t.setOnFocusChangeListener((v, x) -> editing = x);
        f.addView(t);
        f.addView(d);
        f.addView(auto);
        f.addView(Ui.button(this, "Speichern", Ui.ACCENT, v -> {
            if (t.getText().toString().trim().isEmpty()) return;
            db.insert("tasks", Db.cv("title", t.getText().toString().trim(), "detail", d.getText().toString().trim(), "status", "open",
                    "next_run", auto.isChecked() ? System.currentTimeMillis() : null, "created", System.currentTimeMillis(), "updated", System.currentTimeMillis()));
            JarvisService.poke(this);
            editing = false;
            render();
        }));
        content.addView(f);
        content.addView(Ui.title(this, "Aufgaben"));
        List<ContentValues> l = db.query("SELECT * FROM tasks ORDER BY status IN ('done','cancelled'), id DESC LIMIT 50");
        if (l.isEmpty()) content.addView(Ui.text(this, "Keine Aufgaben.", 14, Ui.MUTED));
        for (ContentValues x : l) {
            LinearLayout c = Ui.card(this);
            long id = x.getAsLong("id");
            String st = x.getAsString("status");
            c.addView(Ui.row(this, "#" + id + " " + x.getAsString("title"), "Status: " + st
                    + (x.getAsLong("next_run") != null ? " · nächster Schritt " + Brain.fmt(x.getAsLong("next_run")) : "")
                    + (x.getAsString("steps") != null ? "\nSchritte: " + x.getAsString("steps") : "")
                    + (x.getAsString("result") != null ? "\nErgebnis: " + x.getAsString("result") : ""),
                    "done".equals(st) ? Ui.OK : "blocked".equals(st) ? Ui.BAD : Ui.ACCENT));
            LinearLayout b = Ui.hbox(this);
            b.addView(Ui.button(this, "Erledigt", Ui.OK, v -> { db.update("tasks", Db.cv("status", "done", "next_run", null), "id=?", String.valueOf(id)); render(); }));
            b.addView(Ui.button(this, "Jarvis: weiterarbeiten", Ui.ACCENT, v -> {
                db.update("tasks", Db.cv("status", "open", "next_run", System.currentTimeMillis()), "id=?", String.valueOf(id));
                JarvisService.poke(this);
                render();
            }));
            b.addView(Ui.button(this, "Löschen", Ui.BAD, v -> confirm("Aufgabe löschen?", () -> { db.delete("tasks", "id=?", String.valueOf(id)); render(); })));
            c.addView(b);
            content.addView(c);
        }
    }

    // ------------------------------------------------------------------ reminders

    private void reminders() {
        content.addView(Ui.title(this, "Neue Erinnerung"));
        LinearLayout f = Ui.card(this);
        EditText t = Ui.input(this, "Woran erinnern?", "", false);
        EditText w = Ui.input(this, "Wann? TT.MM.JJJJ HH:MM", LocalDateTime.now(Cfg.ZONE).plusHours(1).withMinute(0)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")), false);
        t.setOnFocusChangeListener((v, x) -> editing = x);
        f.addView(t);
        f.addView(w);
        f.addView(Ui.button(this, "Speichern", Ui.ACCENT, v -> {
            try {
                long due = LocalDateTime.parse(w.getText().toString().trim(), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                        .atZone(Cfg.ZONE).toInstant().toEpochMilli();
                db.insert("reminders", Db.cv("text", t.getText().toString().trim(), "due", due, "status", "pending", "created", System.currentTimeMillis()));
                editing = false;
                render();
            } catch (Exception e) {
                Toast.makeText(this, "Zeitformat: TT.MM.JJJJ HH:MM", Toast.LENGTH_SHORT).show();
            }
        }));
        content.addView(f);
        content.addView(Ui.title(this, "Ausstehend"));
        for (ContentValues r : db.query("SELECT * FROM reminders WHERE status='pending' ORDER BY due")) {
            LinearLayout c = Ui.card(this);
            long id = r.getAsLong("id");
            String text = r.getAsString("text");
            c.addView(Ui.row(this, text.startsWith("CALL:") ? "Anruf: " + text.substring(5) : text, Brain.fmt(r.getAsLong("due"))
                    + (r.getAsString("repeat") != null ? " · " + r.getAsString("repeat") : ""), Ui.ACCENT));
            c.addView(Ui.button(this, "Löschen", Ui.BAD, v -> { db.delete("reminders", "id=?", String.valueOf(id)); render(); }));
            content.addView(c);
        }
    }

    // ------------------------------------------------------------------ chat

    private void chat() {
        content.addView(Ui.title(this, "Chat (Einrichtung & Diagnose)"));
        content.addView(Ui.text(this, "Ersetzt Telegram nicht – Nachrichten an dich gehen immer über Telegram.", 13, Ui.MUTED));
        for (ContentValues m : db.query("SELECT * FROM (SELECT * FROM chat WHERE channel='text' ORDER BY id DESC LIMIT 30) ORDER BY id")) {
            boolean me = "user".equals(m.getAsString("role"));
            TextView t = Ui.text(this, (me ? "Du: " : "Jarvis: ") + m.getAsString("content"), 15, me ? Ui.ACCENT : Ui.TEXT);
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            content.addView(t);
        }
        EditText in = Ui.input(this, "Nachricht an Jarvis", "", false);
        in.setOnFocusChangeListener((v, x) -> editing = x);
        content.addView(in);
        content.addView(Ui.button(this, "Senden", Ui.ACCENT, v -> {
            String text = in.getText().toString().trim();
            if (text.isEmpty()) return;
            in.setText("");
            async(() -> Brain.get(this).agent.respond("app", text, null));
        }));
    }

    // ------------------------------------------------------------------ log

    private void log() {
        content.addView(Ui.title(this, "Aktionsprotokoll"));
        for (ContentValues l : db.query("SELECT * FROM log ORDER BY id DESC LIMIT 150")) {
            String lv = l.getAsString("level");
            TextView t = Ui.text(this, Brain.fmt(l.getAsLong("ts")) + "  [" + l.getAsString("area") + "]  " + l.getAsString("msg"), 13,
                    "E".equals(lv) ? Ui.BAD : "W".equals(lv) ? Ui.WARN : Ui.TEXT);
            t.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
            content.addView(t);
        }
    }

    // ------------------------------------------------------------------ settings

    private void settings() {
        editing = true;
        content.addView(Ui.title(this, "Zugänge (verschlüsselt gespeichert)"));
        LinearLayout c = Ui.card(this);
        secretField(c, "Groq-API-Schlüssel", Secrets.GROQ, () -> CallLink.get(this).pushKey());
        secretField(c, "Gemini-API-Schlüssel (Telefon-Stimme, aistudio.google.com)", Secrets.GEMINI, () -> CallLink.get(this).pushKey());
        secretField(c, "Telegram-Bot-Token (von @BotFather)", Secrets.TELEGRAM, () -> async(() -> {
            try { return "Bot @" + new Telegram(this).verify() + " erkannt. Jetzt koppeln."; } catch (Exception e) { return "Token ungültig: " + e.getMessage(); }
        }));
        content.addView(c);

        content.addView(Ui.title(this, "Telegram koppeln"));
        LinearLayout tg = Ui.card(this);
        Telegram t = new Telegram(this);
        tg.addView(Ui.text(this, t.paired() ? "Gekoppelt ✓ – nur dein Telegram-Konto kann Jarvis Befehle geben."
                : "Öffne in Telegram deinen Bot @" + db.kv("tg_bot_username", "…") + " und sende ihm den Code als „/start CODE“.", 14, Ui.TEXT));
        tg.addView(Ui.button(this, t.paired() ? "Neu koppeln" : "Kopplungscode erzeugen", Ui.ACCENT, v -> {
            if (t.paired()) { db.kvSet("tg_owner_user", null); db.kvSet("tg_owner_chat", null); }
            String code = t.newPairingCode();
            info("Telegram-Code", "Sende deinem Bot innerhalb von 30 Minuten:\n\n/start " + code);
        }));
        content.addView(tg);

        content.addView(Ui.title(this, "Schule"));
        LinearLayout ep = Ui.card(this);
        boolean school = Cfg.school(this);
        ep.addView(Ui.row(this, "Schulfunktionen", school ? "an" : "aus", school ? Ui.OK : Ui.MUTED));
        ep.addView(Ui.button(this, school ? "Schulfunktionen ausschalten" : "Schulfunktionen einschalten", Ui.ACCENT, v -> {
            Cfg.set(this, "school_enabled", school ? "0" : "1");
            db.log("I", "einstellungen", "Schulfunktionen " + (school ? "aus" : "an"));
            render();
        }));
        if (school) {
            ep.addView(Ui.text(this, "Schule suchen: Name der Schule (mit Ort) oder Adresse der Schulseite/Schul-App.", 13, Ui.MUTED));
            EditText q = Ui.input(this, "z. B. Gymnasium Musterstadt oder meineschule.de", "", false);
            ep.addView(q);
            ep.addView(Ui.button(this, "Suchen und prüfen", Ui.ACCENT, v -> {
                String query = q.getText().toString().trim();
                if (query.isEmpty()) return;
                Toast.makeText(this, "Jarvis sucht …", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    java.util.List<SchoolFinder.Candidate> found = SchoolFinder.find(query);
                    h.post(() -> chooseSchool(found));
                }).start();
            }));
            String sub = Cfg.get(this, "edu_subdomain");
            if (!sub.isEmpty()) {
                ep.addView(Ui.text(this, "EduPage (" + sub + ".edupage.org): " + (new EduPage(this).hasSession()
                        ? "Sitzung gespeichert (" + Ui.ago(db.kvLong("edu_login_at", 0)) + ")." : "noch nicht angemeldet."), 14, Ui.TEXT));
                ep.addView(Ui.button(this, "Bei EduPage anmelden", Ui.ACCENT, v -> startActivity(new Intent(this, EduLoginActivity.class))));
            }
            for (String page : WebSchool.pages(this)) {
                ep.addView(Ui.text(this, "Beobachtet: " + page, 13, Ui.TEXT));
                LinearLayout pb = Ui.hbox(this);
                pb.addView(Ui.button(this, "Neu anmelden", Ui.ACCENT, v -> startActivity(new Intent(this, WebLoginActivity.class).putExtra("url", page))));
                pb.addView(Ui.button(this, "Entfernen", Ui.MUTED, v -> confirm("Diese Seite nicht mehr beobachten?", () -> { WebSchool.removePage(this, page); render(); })));
                ep.addView(pb);
            }
        }
        content.addView(ep);

        content.addView(Ui.title(this, "Anruf-App auf deinem Handy"));
        LinearLayout ca = Ui.card(this);
        ca.addView(Ui.text(this, CallLink.get(this).paired() ? "Gekoppelt mit " + db.kv("link_peer_name", "Telefon") + "."
                : "1. Anruf-App per Telegram an dich schicken und auf deinem Handy installieren.\n2. Kopplungscode per Telegram senden, in der Anruf-App einfügen.", 14, Ui.TEXT));
        LinearLayout cb = Ui.hbox(this);
        cb.addView(Ui.button(this, "App per Telegram senden", Ui.ACCENT, v -> async(() -> {
            try {
                java.io.File f = new java.io.File(getFilesDir(), "Jarvis-Anruf.apk");
                try (java.io.InputStream in = getAssets().open("jarvis-call.apk"); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                new Telegram(this).sendDocument(f, "Jarvis-Anruf-App für dein Handy. Installieren, öffnen, dann Kopplungscode einfügen.");
                return "Gesendet.";
            } catch (Exception e) { return "Fehlgeschlagen: " + e.getMessage(); }
        })));
        cb.addView(Ui.button(this, "Kopplungscode senden", Ui.ACCENT, v -> async(() -> {
            try {
                String code = CallLink.get(this).newPairingCode();
                Outbox.enqueue(this, "paircode:" + System.currentTimeMillis(), "Kopplungscode für die Anruf-App (1 Stunde gültig) – kopieren und in der App einfügen:", null, null);
                Outbox.enqueue(this, "paircode2:" + System.currentTimeMillis(), code, null, null);
                return "Code per Telegram gesendet.";
            } catch (Exception e) { return "Fehlgeschlagen: " + e.getMessage(); }
        })));
        ca.addView(cb);
        ca.addView(Ui.button(this, "Testanruf an mich", Ui.OK, v -> async(() ->
                Brain.get(this).requestCall("[TEST] Testanruf von Jarvis – bitte annehmen und kurz etwas sagen.", 3, true))));
        ca.addView(Ui.button(this, "Kopplungscode hier anzeigen", Ui.ACCENT, v -> async(() -> {
            try { return CallLink.get(this).newPairingCode(); } catch (Exception e) { return "Fehlgeschlagen: " + e.getMessage(); }
        })));
        content.addView(ca);

        content.addView(Ui.title(this, "E-Mail"));
        LinearLayout ml = Ui.card(this);
        ml.addView(Ui.text(this, "Adresse unten bei „Verhalten“ eintragen, hier das Passwort. Gmail: App-Passwort (Google-Konto → Sicherheit → "
                + "2-Faktor an → App-Passwörter). GMX/web.de: IMAP in den Mail-Einstellungen erlauben. Jarvis liest nur.", 13, Ui.MUTED));
        secretField(ml, "E-Mail-Passwort / App-Passwort", MailWatch.PASSWORD, () -> async(() -> new MailWatch(this).test()));
        String me = db.kv("mail_error", null);
        ml.addView(Ui.text(this, !new MailWatch(this).configured() ? "Nicht eingerichtet." : me != null ? "Fehler: " + me
                : "Zuletzt geprüft: " + Ui.ago(db.kvLong("mail_ok_at", 0)), 13, Ui.TEXT));
        content.addView(ml);

        content.addView(Ui.title(this, "PC"));
        LinearLayout pcc = Ui.card(this);
        PcLink pl = PcLink.get(this);
        pcc.addView(Ui.text(this, pl.paired() ? "Gekoppelt mit „" + db.kv("pc_name", "PC") + "“ – " + (pl.online() ? "an." : "aus/offline.")
                : "Auf dem PC jarvis-pc.jar starten: java -jar jarvis-pc.jar pair CODE, danach java -jar jarvis-pc.jar install.", 14, Ui.TEXT));
        pcc.addView(Ui.button(this, "PC-Kopplungscode anzeigen", Ui.ACCENT, v -> async(() -> {
            try { return PcLink.get(this).newPairingCode(); } catch (Exception e) { return "Fehlgeschlagen: " + e.getMessage(); }
        })));
        content.addView(pcc);

        content.addView(Ui.title(this, "Verhalten"));
        LinearLayout s = Ui.card(this);
        for (String[] fld : Cfg.FIELDS) {
            s.addView(Ui.text(this, fld[2], 13, Ui.MUTED));
            EditText e = Ui.input(this, fld[1], Cfg.get(this, fld[0]), false);
            e.setOnFocusChangeListener((v, focus) -> {
                if (!focus) { Cfg.set(this, fld[0], e.getText().toString().trim()); db.log("I", "einstellungen", fld[2] + " = " + e.getText()); }
            });
            s.addView(e);
        }
        content.addView(s);

        content.addView(Ui.title(this, "Berechtigungen & Dauerbetrieb"));
        LinearLayout p = Ui.card(this);
        p.addView(Ui.row(this, "Kalender", granted(Manifest.permission.WRITE_CALENDAR) ? "erlaubt" : "fehlt", granted(Manifest.permission.WRITE_CALENDAR) ? Ui.OK : Ui.BAD));
        p.addView(Ui.row(this, "Benachrichtigungen", granted(Manifest.permission.POST_NOTIFICATIONS) ? "erlaubt" : "fehlt", granted(Manifest.permission.POST_NOTIFICATIONS) ? Ui.OK : Ui.WARN));
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        p.addView(Ui.row(this, "Akku-Optimierung", exempt ? "ausgenommen" : "aktiv", exempt ? Ui.OK : Ui.WARN));
        p.addView(Ui.button(this, "Akku-Ausnahme anfragen", Ui.ACCENT, v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())))));
        p.addView(Ui.text(this, "Samsung: Einstellungen → Akku → Hintergrund-Nutzungslimits → „Nie in Standby versetzte Apps“ → Jarvis hinzufügen. "
                + "Für Dauerbetrieb das Jarvis-Handy am Netzteil lassen und unter Akku → „Akku schützen“ (Max. 85 %) aktivieren.", 13, Ui.MUTED));
        content.addView(p);

        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            content.addView(Ui.button(this, "Diagnose: EduPage-Rohdaten sichern", Ui.MUTED, v -> async(() -> {
                try {
                    EduPage edu = new EduPage(this);
                    StringBuilder b = new StringBuilder();
                    for (EduPage.Item it : edu.timeline(java.time.LocalDate.now(Cfg.ZONE).minusDays(45).toString())) b.append(it.raw).append("\n");
                    java.nio.file.Files.write(new java.io.File(getFilesDir(), "edupage-raw.jsonl").toPath(), b.toString().getBytes("UTF-8"));
                    return "Gesichert (" + b.length() / 1024 + " KB), Ereignistypen: " + edu.eventTypes.size();
                } catch (Exception e) { return "Fehler: " + e.getMessage(); }
            })));
        }
        content.addView(Ui.title(this, "KI-Modelle"));
        LinearLayout km = Ui.card(this);
        km.addView(Ui.text(this, "Aktuell: " + Cfg.get(this, "llm_model") + "\n" + db.kv("model_bench", "Noch kein Vergleich gemessen."), 13, Ui.MUTED));
        km.addView(Ui.button(this, "Kostenlose Modelle vergleichen (ca. 25 Anfragen)", Ui.ACCENT, v -> async(() -> {
            try { return ModelBench.run(this); } catch (Exception e) { return "Fehlgeschlagen: " + e.getMessage(); }
        })));
        content.addView(km);
        content.addView(Ui.title(this, "Selbsttest"));
        LinearLayout st = Ui.card(this);
        st.addView(Ui.text(this, "Spielt die Schul-Testfälle mit künstlichen Nachrichten im lokalen Kalender „" + CalendarStore.TEST_CAL_NAME
                + "“ durch. Testnachrichten an dich sind mit [TEST] gekennzeichnet.", 13, Ui.MUTED));
        st.addView(Ui.button(this, "Selbsttest starten", Ui.ACCENT, v -> async(() -> SelfTest.run(this))));
        content.addView(st);
    }

    private void secretField(LinearLayout c, String label, String name, Runnable after) {
        c.addView(Ui.text(this, label + (Secrets.has(this, name) ? " – gespeichert ✓" : " – fehlt"), 13, Ui.MUTED));
        LinearLayout r = Ui.hbox(this);
        EditText e = Ui.input(this, "neu eintragen", "", true);
        r.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(Ui.button(this, "Speichern", Ui.ACCENT, v -> {
            String val = e.getText().toString().trim();
            if (val.isEmpty()) return;
            Secrets.put(this, name, val);
            e.setText("");
            db.log("I", "einstellungen", label + " gespeichert");
            if (after != null) after.run();
            JarvisService.poke(this);
            render();
        }));
        c.addView(r);
    }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    // ------------------------------------------------------------------ helpers

    /** Result of the school search: EduPage is read directly, every other page through the built-in browser. */
    private void chooseSchool(java.util.List<SchoolFinder.Candidate> found) {
        java.util.List<SchoolFinder.Candidate> ok = new java.util.ArrayList<>();
        for (SchoolFinder.Candidate c : found) if (c.ok) ok.add(c);
        if (ok.isEmpty()) {
            info("Schule suchen", SchoolFinder.describe(found) + "\n\nTipp: Gib die Adresse direkt ein, die du sonst im Browser für die Schul-App benutzt.");
            return;
        }
        String[] labels = new String[ok.size()];
        for (int i = 0; i < ok.size(); i++) labels[i] = ok.get(i).platform + "\n" + ok.get(i).url + (ok.get(i).note == null ? "" : "\n" + ok.get(i).note);
        new android.app.AlertDialog.Builder(this).setTitle("Gefunden – welche nutzt du?").setItems(labels, (d, i) -> {
            SchoolFinder.Candidate c = ok.get(i);
            if (c.eduSub() != null) {
                Cfg.set(this, "edu_subdomain", c.eduSub());
                db.log("I", "einstellungen", "EduPage-Schule: " + c.eduSub());
                startActivity(new Intent(this, EduLoginActivity.class));
            } else {
                startActivity(new Intent(this, WebLoginActivity.class).putExtra("url", c.url));
            }
            render();
        }).setNegativeButton("Abbrechen", null).show();
    }

    interface Job { String run(); }

    private void async(Job j) {
        Toast.makeText(this, "Einen Moment …", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String r;
            try { r = j.run(); } catch (Exception e) { r = "Fehler: " + e.getMessage(); }
            String res = r;
            h.post(() -> {
                editing = false;
                render();
                if (res != null && !res.isEmpty() && tab != 5) info("Jarvis", res);
            });
        }).start();
    }

    private void info(String title, String msg) {
        TextView t = Ui.text(this, msg, 15, 0xFF111111);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), 0);
        new android.app.AlertDialog.Builder(this).setTitle(title).setView(t)
                .setNeutralButton("Kopieren", (d, w) -> getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Jarvis", msg)))
                .setPositiveButton("OK", null).show();
    }

    private void confirm(String q, Runnable yes) {
        new android.app.AlertDialog.Builder(this).setMessage(q).setPositiveButton("Ja", (d, w) -> yes.run()).setNegativeButton("Abbrechen", null).show();
    }

    static View spacer(android.content.Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(c, 8)));
        return v;
    }

    static int gravityCenter() { return Gravity.CENTER; }
}
