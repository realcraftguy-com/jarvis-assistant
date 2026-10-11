package de.felix.jarvis;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.provider.CalendarContract;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays the required school scenarios with artificial EduPage messages. Calendar entries go only to
 * the local calendar "Jarvis – Test (lokal)", messages to the Boss carry "[TEST]".
 */
public final class SelfTest {
    static volatile boolean running;

    private final Context ctx;
    private final Db db;
    private final List<String> lines = new ArrayList<>();
    private int pass, fail;
    private final String run;

    private SelfTest(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
        run = "test:" + Long.toString(System.currentTimeMillis() / 1000, 36);
    }

    public static synchronized String run(Context c) {
        running = true;
        try {
            return new SelfTest(c).all();
        } finally {
            running = false;
            CalendarStore.simulateOutage = false;
            Telegram.simulateOutage = false;
        }
    }

    private void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        lines.add((ok ? "✓ " : "✗ ") + name + (ok ? "" : " – " + detail));
    }

    private SchoolPipeline.Source src(String n, String type, String text, LocalDate published) {
        SchoolPipeline.Source s = new SchoolPipeline.Source();
        s.key = run + ":" + n;
        s.eduId = n;
        s.type = type;
        s.text = text;
        s.published = published + " 07:30:00";
        s.author = "Testlehrer";
        return s;
    }

    private ContentValues map(String key) { return db.one("SELECT * FROM cal_map WHERE entry_key=?", key); }

    private int count(String sql, String... a) { return db.one(sql, a).getAsInteger("n"); }

    private String title(long eventId) {
        try (Cursor c = ctx.getContentResolver().query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                new String[]{CalendarContract.Events.TITLE, CalendarContract.Events.CALENDAR_ID}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static String dm(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("d.M.")); }

    private String all() {
        CalendarStore cs = new CalendarStore(ctx);
        long testCal = cs.ensureTestCalendar();
        SchoolPipeline p = new SchoolPipeline(ctx);
        LocalDate today = LocalDate.now(Cfg.ZONE);
        LocalDate d1 = today.plusDays(9), d2 = today.plusDays(12), d3 = today.plusDays(16), d4 = today.plusDays(6);
        // weekday names must match the dates, otherwise the parser (correctly) asks back
        long started = System.currentTimeMillis();

        // 1. new school message -> correct calendar entry -> message
        SchoolPipeline.Source s1 = src("1", "sprava", "Am " + dm(d1) + " schreiben wir in Mathe einen Leistungsnachweis über lineare Funktionen. Bitte Geodreieck mitbringen.", today);
        boolean did = p.process(s1);
        ContentValues m1 = map(s1.key + ":event");
        check("1 Neue Nachricht → Termin", did && m1 != null && m1.getAsLong("cal_id") == testCal
                        && d1.equals(java.time.Instant.ofEpochMilli(m1.getAsLong("start_ms")).atZone(ZoneOffset.UTC).toLocalDate())
                        && m1.getAsString("title").contains("Mathe"),
                m1 == null ? "kein Kalendereintrag" : m1.getAsString("title") + " " + m1.getAsLong("start_ms"));
        p.flushNotes();
        ContentValues msg1 = db.one("SELECT text FROM outbox WHERE created>=? AND text LIKE '[TEST]%Mathe%' ORDER BY id DESC LIMIT 1", String.valueOf(started));
        check("1 Bestätigung in der Warteschlange", msg1 != null && msg1.getAsString("text").contains("eingetragen"), msg1 == null ? "keine Nachricht" : msg1.getAsString("text"));

        // 2. processing the same again -> no duplicate entry, no second message
        int mapBefore = count("SELECT COUNT(*) n FROM cal_map WHERE src_key LIKE ?", run + "%");
        int outBefore = count("SELECT COUNT(*) n FROM outbox");
        boolean again = new SchoolPipeline(ctx).process(s1); // fresh instance = like after a restart
        p.flushNotes();
        check("2 Erneute Verarbeitung ohne Duplikat", !again && mapBefore == count("SELECT COUNT(*) n FROM cal_map WHERE src_key LIKE ?", run + "%")
                && outBefore == count("SELECT COUNT(*) n FROM outbox"), "zweiter Eintrag oder zweite Nachricht");

        // 3a. moved
        SchoolPipeline.Source s3 = src("3", "sprava", "Der Leistungsnachweis in Mathe wird vom " + dm(d1) + " auf den " + dm(d2) + " verschoben.", today);
        p.process(s3);
        ContentValues m1b = map(s1.key + ":event");
        check("3a Verschiebung aktualisiert denselben Termin", m1b != null && d2.equals(java.time.Instant.ofEpochMilli(m1b.getAsLong("start_ms")).atZone(ZoneOffset.UTC).toLocalDate())
                && m1b.getAsLong("event_id").equals(m1.getAsLong("event_id")) && map(s3.key + ":event") == null,
                m1b == null ? "fehlt" : "start " + m1b.getAsLong("start_ms"));
        // 3b. cancelled
        SchoolPipeline.Source s4 = src("4", "sprava", "Der Leistungsnachweis in Mathe am " + dm(d2) + " entfällt.", today);
        p.process(s4);
        ContentValues m1c = map(s1.key + ":event");
        String t1 = m1c == null ? null : title(m1c.getAsLong("event_id"));
        check("3b Absage markiert den Termin", m1c != null && m1c.getAsInteger("cancelled") == 1 && t1 != null && t1.startsWith("ABGESAGT"), "Titel: " + t1);
        p.flushNotes();

        // 4. unclear date -> question, no invented entry; answer -> entry
        SchoolPipeline.Source s5 = src("5", "sprava", "Der Englisch-Test findet nächste Woche statt.", today);
        p.process(s5);
        ContentValues q = db.one("SELECT * FROM questions WHERE src_key=? AND status='open'", s5.key);
        check("4 Unklares Datum → Rückfrage, kein Termin", q != null && map(s5.key + ":event") == null, q == null ? "keine Rückfrage" : "Termin trotzdem angelegt");
        if (q != null) {
            String reply = Brain.get(ctx).answerQuestion(q.getAsLong("id"), dm(d3) + " 8:00 Uhr");
            ContentValues m5 = map(s5.key + ":event");
            check("4 Antwort auf Rückfrage → Termin", m5 != null && m5.getAsInteger("all_day") == 0, reply);
        }

        // 5. separate event and deadline
        SchoolPipeline.Source s6 = src("6", "sprava", "Am " + dm(d3) + " machen wir einen Ausflug in den Tierpark Hellabrunn. Bitte den unterschriebenen Zettel bis zum "
                + dm(d4) + " abgeben.", today);
        p.process(s6);
        ContentValues ev = map(s6.key + ":event"), dl = map(s6.key + ":deadline:" + d4);
        check("5 Veranstaltung und Abgabefrist getrennt", ev != null && dl != null
                        && d3.equals(java.time.Instant.ofEpochMilli(ev.getAsLong("start_ms")).atZone(ZoneOffset.UTC).toLocalDate())
                        && dl.getAsString("title").contains("Zettelabgabe"),
                "event=" + (ev == null ? "-" : ev.getAsString("title")) + " frist=" + (dl == null ? "-" : dl.getAsString("title")));
        p.flushNotes();

        // 6a. calendar outage: nothing half-done, retried later without duplicate
        CalendarStore.simulateOutage = true;
        SchoolPipeline.Source s7 = src("7", "sprava", "Am " + dm(d2) + " ist Elternabend um 19:00 Uhr in der Aula.", today);
        p.process(s7);
        boolean errorState = "error".equals(db.one("SELECT status FROM school_items WHERE src_key=?", s7.key).getAsString("status"));
        CalendarStore.simulateOutage = false;
        p.process(s7);
        p.process(s7);
        check("6a Kalender-Ausfall → später genau ein Termin", errorState && count("SELECT COUNT(*) n FROM cal_map WHERE src_key=?", s7.key) == 1,
                "status nach Ausfall: " + errorState);
        p.flushNotes();

        // 6b. messaging outage: calendar untouched, only the message is retried
        boolean tgReady = new Telegram(ctx).paired();
        Telegram.simulateOutage = true;
        long beforeEvent = map(s7.key + ":event").getAsLong("event_id");
        Outbox.enqueue(ctx, run + ":outage", "[TEST] Nachricht während simuliertem Netzausfall", null, null);
        Outbox.flush(ctx);
        ContentValues ob = db.one("SELECT * FROM outbox WHERE dedupe=?", run + ":outage");
        boolean pendingAfterFail = ob != null && "pending".equals(ob.getAsString("status")) && (ob.getAsInteger("attempts") >= 1 || !tgReady);
        Telegram.simulateOutage = false;
        if (tgReady) {
            db.getWritableDatabase().execSQL("UPDATE outbox SET next_try=0 WHERE status='pending'");
            Outbox.flush(ctx);
        }
        ContentValues ob2 = db.one("SELECT * FROM outbox WHERE dedupe=?", run + ":outage");
        check("6b Telegram-Ausfall → nur Nachricht wiederholt", pendingAfterFail && map(s7.key + ":event").getAsLong("event_id") == beforeEvent
                        && (!tgReady || "sent".equals(ob2.getAsString("status"))),
                "pending=" + pendingAfterFail + " status=" + (ob2 == null ? "-" : ob2.getAsString("status")) + (tgReady ? "" : " (Telegram nicht gekoppelt)"));

        // 6c. AI outage: rules still process the item
        long savedBlock = db.kvLong("llm_blocked_until", 0);
        db.kvSet("llm_blocked_until", System.currentTimeMillis() + 60_000);
        SchoolPipeline.Source s8 = src("8", "sprava", "Schulaufgabe in Deutsch am " + dm(d3) + " in der 2. Stunde.", today);
        p.process(s8);
        db.kvSet("llm_blocked_until", savedBlock);
        ContentValues m8 = map(s8.key + ":event");
        check("6c KI-Ausfall → Schulablauf läuft regelbasiert weiter", m8 != null && m8.getAsInteger("all_day") == 0, m8 == null ? "kein Termin" : "");
        p.flushNotes();

        String summary = "Selbsttest " + run.substring(5) + ": " + pass + " bestanden, " + fail + " fehlgeschlagen\n" + String.join("\n", lines);
        db.kvSet("selftest_report", summary);
        db.kvSet("selftest_at", System.currentTimeMillis());
        db.log(fail == 0 ? "I" : "E", "selbsttest", summary.replace("\n", " | "));
        Outbox.enqueue(ctx, run + ":report", "[TEST] " + summary, null, null);
        return summary;
    }
}
