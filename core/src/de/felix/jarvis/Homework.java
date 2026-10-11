package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Homework tracker and study plans.
 * Homework comes from EduPage automatically (subject + due date) or from the user ("Mathe S. 45 Nr. 3 bis Donnerstag").
 * The evening before it is due, Jarvis asks whether it is done (with buttons). Before a test, Jarvis lays out a short
 * study plan as reminders and offers to quiz the user the day before.
 */
final class Homework {
    private static volatile boolean ready;
    private final Context ctx;
    private final Db db;

    Homework(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
        if (!ready) {
            db.getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS homework(id INTEGER PRIMARY KEY, src_key TEXT UNIQUE, subject TEXT, title TEXT,"
                    + " detail TEXT, due TEXT, status TEXT, created INTEGER, done_at INTEGER)");
            ready = true;
        }
    }

    // ------------------------------------------------------------------ data

    /** Takes over homework EduPage published (the raw timeline entry is kept in school_items.analysis). */
    void syncFromSchool() {
        String today = LocalDate.now(Cfg.ZONE).toString();
        for (ContentValues s : db.query("SELECT src_key, analysis, removed FROM school_items WHERE edu_type IN ('homework','etesthw') AND src_key NOT LIKE 'test:%'")) {
            try {
                JSONObject raw = new JSONObject(s.getAsString("analysis"));
                Object d = raw.opt("data");
                JSONObject data = d instanceof JSONObject ? (JSONObject) d : new JSONObject(String.valueOf(d));
                String due = data.optString("date", raw.optString("cas_udalosti", "")).replaceAll(" .*", "");
                String title = data.optString("nazov", "").trim();
                if (due.isEmpty() || title.isEmpty()) continue;
                String meno = raw.optString("user_meno", "");
                String subject = SchoolParserFriendly.name(meno.contains("·") ? meno.substring(meno.indexOf('·') + 1).trim() : meno);
                String key = s.getAsString("src_key");
                ContentValues have = db.one("SELECT id, status, due, title FROM homework WHERE src_key=?", key);
                boolean removed = s.getAsInteger("removed") != null && s.getAsInteger("removed") == 1;
                if (have == null) {
                    if (removed || due.compareTo(today) < 0) continue;
                    db.insert("homework", Db.cv("src_key", key, "subject", subject, "title", title, "detail", data.optString("popis", ""),
                            "due", due, "status", "open", "created", System.currentTimeMillis()));
                } else if (removed && "open".equals(have.getAsString("status"))) {
                    db.update("homework", Db.cv("status", "removed"), "id=?", have.getAsString("id"));
                } else if (!due.equals(have.getAsString("due")) || !title.equals(have.getAsString("title"))) {
                    db.update("homework", Db.cv("due", due, "title", title), "id=?", have.getAsString("id"));
                }
            } catch (Exception ignored) {}
        }
    }

    String add(String subject, String title, String due) {
        LocalDate.parse(due);
        long id = db.insert("homework", Db.cv("src_key", "user:" + System.currentTimeMillis(), "subject", subject == null ? "" : subject, "title", title,
                "detail", "", "due", due, "status", "open", "created", System.currentTimeMillis()));
        return "Notiert (#" + id + "): " + (subject == null || subject.isEmpty() ? "" : subject + " – ") + title + ", fällig " + day(due) + ". Am Abend vorher frage ich nach.";
    }

    String list() {
        syncFromSchool();
        List<ContentValues> l = db.query("SELECT id, subject, title, due FROM homework WHERE status='open' AND due>=? ORDER BY due, subject",
                LocalDate.now(Cfg.ZONE).toString());
        if (l.isEmpty()) return "Keine offenen Hausaufgaben eingetragen.";
        StringBuilder b = new StringBuilder();
        for (ContentValues h : l) b.append("#").append(h.getAsLong("id")).append(" ").append(day(h.getAsString("due"))).append(": ")
                .append(h.getAsString("subject").isEmpty() ? "" : h.getAsString("subject") + " – ").append(h.getAsString("title")).append("\n");
        return b.toString().trim();
    }

    /** id, or a subject name ("Englisch fertig") – marks the matching open homework done. */
    String done(String which) {
        List<ContentValues> hit = new ArrayList<>();
        if (which.matches("#?\\d+")) {
            ContentValues h = db.one("SELECT id, subject, title FROM homework WHERE id=?", which.replace("#", ""));
            if (h != null) hit.add(h);
        } else {
            String w = which.toLowerCase(Locale.GERMAN);
            for (ContentValues h : db.query("SELECT id, subject, title FROM homework WHERE status='open' ORDER BY due")) {
                if ((h.getAsString("subject") + " " + h.getAsString("title")).toLowerCase(Locale.GERMAN).contains(w) || "alle".equals(w)) hit.add(h);
            }
        }
        if (hit.isEmpty()) return "Keine passende offene Hausaufgabe gefunden.";
        StringBuilder b = new StringBuilder("Abgehakt: ");
        for (ContentValues h : hit) {
            db.update("homework", Db.cv("status", "done", "done_at", System.currentTimeMillis()), "id=?", h.getAsString("id"));
            b.append(h.getAsString("subject").isEmpty() ? h.getAsString("title") : h.getAsString("subject") + " – " + h.getAsString("title")).append("; ");
        }
        return b.substring(0, b.length() - 2) + ".";
    }

    /** Open homework for the briefing/voice context. */
    String openSummary(int days) {
        LocalDate t = LocalDate.now(Cfg.ZONE);
        StringBuilder b = new StringBuilder();
        for (ContentValues h : db.query("SELECT subject, title, due FROM homework WHERE status='open' AND due>=? AND due<=? ORDER BY due",
                t.toString(), t.plusDays(days).toString()))
            b.append("- ").append(day(h.getAsString("due"))).append(": ").append(h.getAsString("subject")).append(" – ").append(h.getAsString("title")).append("\n");
        return b.length() == 0 ? "keine\n" : b.toString();
    }

    // ------------------------------------------------------------------ schedule

    void tick() {
        ZonedDateTime now = Cfg.now();
        if (now.getMinute() % 10 == 0 || db.kv("hw_sync_at", null) == null) {
            syncFromSchool();
            db.kvSet("hw_sync_at", System.currentTimeMillis());
        }
        eveningCheck(now);
        studyPlans();
    }

    private void eveningCheck(ZonedDateTime now) {
        String today = now.toLocalDate().toString();
        if (now.format(DateTimeFormatter.ofPattern("HH:mm")).compareTo(Cfg.get(ctx, "hw_check")) < 0 || now.getHour() >= 22) return;
        if (today.equals(db.kv("hw_check_day", ""))) return;
        db.kvSet("hw_check_day", today);
        LocalDate next = now.toLocalDate().plusDays(1);
        while (next.getDayOfWeek().getValue() > 5) next = next.plusDays(1);
        List<ContentValues> due = db.query("SELECT id, subject, title FROM homework WHERE status='open' AND due>? AND due<=? ORDER BY subject",
                today, next.toString());
        List<ContentValues> big = db.query("SELECT subject, title, due FROM homework WHERE status='open' AND due>? AND due<=? "
                + "AND (title LIKE '%referat%' OR title LIKE '%präsentation%' OR title LIKE '%projekt%' OR title LIKE '%plakat%')", next.toString(), now.toLocalDate().plusDays(4).toString());
        if (due.isEmpty() && big.isEmpty()) return;
        StringBuilder b = new StringBuilder();
        List<String[]> rows = new ArrayList<>();
        if (!due.isEmpty()) {
            b.append("Hausaufgaben für ").append(day(next.toString())).append(":\n");
            for (ContentValues h : due) {
                String label = h.getAsString("subject").isEmpty() ? h.getAsString("title") : h.getAsString("subject");
                b.append("• ").append(h.getAsString("subject").isEmpty() ? "" : h.getAsString("subject") + " – ").append(h.getAsString("title")).append("\n");
                rows.add(new String[]{"✓ " + SchoolPipeline.shorten(label, 24), "hw:" + h.getAsLong("id")});
            }
            if (due.size() > 1) rows.add(new String[]{"✓ Alles erledigt", "hw:day:" + next});
            b.append("Schon erledigt?");
        }
        for (ContentValues h : big) b.append(b.length() > 0 ? "\n\n" : "").append("Denk an: ").append(h.getAsString("subject")).append(" – ")
                .append(h.getAsString("title")).append(" (").append(day(h.getAsString("due"))).append(").");
        Outbox.enqueue(ctx, "hw:" + today, b.toString(), rows.isEmpty() ? null : Telegram.buttons(rows), null);
        db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "text", "role", "assistant", "content", b.toString()));
    }

    /** Button "hw:<id>" or "hw:day:<date>". */
    String button(String data) {
        if (data.startsWith("hw:day:")) {
            String d = data.substring(7);
            int n = db.update("homework", Db.cv("status", "done", "done_at", System.currentTimeMillis()), "status='open' AND due<=?", d);
            return n == 0 ? "War schon alles abgehakt." : "Sauber, alles abgehakt. Dann hast du den Abend frei.";
        }
        return done(data.substring(3));
    }

    // ------------------------------------------------------------------ study plans

    private static final String EXAM = "(?i).*(leistungsnachweis|schulaufgabe|stegreif|kurzarbeit|klassenarbeit|test|prüfung|ex\\b).*";

    /** 2–7 days before a test: three short study blocks as reminders, the day before an offer to quiz him. */
    private void studyPlans() {
        if (!"1".equals(Cfg.get(ctx, "study_plan"))) return;
        long now = System.currentTimeMillis();
        for (ContentValues e : db.query("SELECT entry_key, title, start_ms, event_id FROM cal_map WHERE cancelled=0 AND entry_key NOT LIKE 'test:%' AND start_ms>? AND start_ms<?",
                String.valueOf(now + 30 * 3600_000L), String.valueOf(now + 7 * 86_400_000L))) {
            String title = e.getAsString("title");
            if (title == null || !title.matches(EXAM)) continue;
            String key = "study:" + e.getAsString("entry_key");
            String prev = db.kv(key, null);
            long start = e.getAsLong("start_ms");
            if (prev != null && prev.startsWith(start + "|")) continue;
            if (prev != null) for (String id : prev.split("\\|", 2)[1].split(",")) if (!id.isEmpty()) db.delete("reminders", "id=? AND status='pending'", id); // test moved
            LocalDate examDay = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneOffset.UTC).toLocalDate();
            String topic = Brain.get(ctx).agent.topic(e.getAsLong("event_id"));
            String what = title.replace(" – ", " ") + (topic == null ? "" : " (" + topic + ")");
            String[][] steps = {
                    {"3", "Lernblock 1 für " + what + ": Hefteinträge und Buchseiten durchgehen, markieren, was unklar ist (20 Min)."},
                    {"2", "Lernblock 2 für " + what + ": Aufgaben üben, vor allem das Unklare von gestern (30 Min)."},
                    {"1", "Letzter Block für " + what + ": kurz wiederholen. Schreib mir „frag mich ab“, dann frage ich dich zum Stoff ab."}};
            List<String> ids = new ArrayList<>();
            StringBuilder plan = new StringBuilder("Lernplan für " + what + " am " + day(examDay.toString()) + ":\n");
            for (String[] s : steps) {
                LocalDate d = examDay.minusDays(Integer.parseInt(s[0]));
                long due = d.atTime(16, 30).atZone(Cfg.ZONE).toInstant().toEpochMilli();
                if (due < now) continue;
                ids.add(String.valueOf(db.insert("reminders", Db.cv("text", s[1], "due", due, "status", "pending", "created", now, "repeat", null))));
                plan.append("• ").append(day(d.toString())).append(" 16:30 – ").append(s[1].replaceAll("^[^:]+: ", "")).append("\n");
            }
            db.kvSet(key, start + "|" + String.join(",", ids));
            if (ids.isEmpty()) continue;
            plan.append("Ich erinnere dich jeweils. Passt die Uhrzeit nicht, sag Bescheid.");
            Outbox.enqueue(ctx, key + ":" + start, plan.toString(), null, null);
            db.insert("chat", Db.cv("ts", now, "channel", "text", "role", "assistant", "content", plan.toString()));
            db.log("I", "lernplan", "Lernplan angelegt: " + title);
        }
    }

    static String day(String isoDate) {
        LocalDate d = LocalDate.parse(isoDate), t = LocalDate.now(Cfg.ZONE);
        long n = d.toEpochDay() - t.toEpochDay();
        if (n == 0) return "heute";
        if (n == 1) return "morgen";
        return d.format(DateTimeFormatter.ofPattern("EEEE d.M.", Locale.GERMAN));
    }
}
