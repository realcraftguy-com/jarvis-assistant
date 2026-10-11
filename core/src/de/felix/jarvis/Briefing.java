package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * "Was ist passiert, was muss ich wissen?" – collects the facts (school, timetable, calendar, open items,
 * weather, problems) and lets the brain turn them into a short briefing. Used on request (tool "briefing")
 * and automatically: school-day mornings and Sunday evening.
 */
final class Briefing {
    private final Context ctx;
    private final Db db;

    Briefing(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
    }

    /** Raw facts since the given time, newest school news first. Plain text for the language model. */
    String facts(long sinceMs, boolean weekAhead) {
        ZonedDateTime now = Cfg.now();
        LocalDate today = now.toLocalDate();
        // the school day that matters: today before 14:00 on weekdays, otherwise the next school day
        LocalDate day = today;
        if (now.getHour() >= 14 || day.getDayOfWeek().getValue() > 5) day = day.plusDays(1);
        while (day.getDayOfWeek().getValue() > 5) day = day.plusDays(1);
        Agent agent = Brain.get(ctx).agent;
        StringBuilder b = new StringBuilder();

        boolean school = Cfg.school(ctx);
        if (school) {
        b.append("== Neu aus EduPage seit ").append(sinceMs > 0 ? Brain.fmt(sinceMs) : "Beginn").append(" ==\n");
        List<ContentValues> news = db.query("SELECT title, edu_type, status, note FROM school_items WHERE updated>? AND src_key NOT LIKE 'test:%' "
                + "AND status IN ('done','notify','question') ORDER BY updated DESC LIMIT 12", String.valueOf(sinceMs));
        if (news.isEmpty()) b.append("nichts Neues\n");
        for (ContentValues n : news) {
            String note = n.getAsString("note");
            if (note == null) {
                b.append("- ").append(n.getAsString("title")).append("question".equals(n.getAsString("status")) ? " (Rückfrage offen)" : "").append("\n");
                continue;
            }
            // the resulting calendar change with its date, not just the EduPage headline
            for (String line : note.split("\n")) {
                String[] p = line.split("\\|", -1);
                try {
                    switch (p[0]) {
                        case "created": case "updated":
                            b.append("- ").append("created".equals(p[0]) ? "neu eingetragen: " : "geändert: ").append(p[2]).append(", ")
                                    .append(SchoolPipeline.shortWhen(Long.parseLong(p[3]), "1".equals(p[4])))
                                    .append(p.length > 9 && !p[9].isEmpty() ? " (Thema: " + p[9] + ")" : "").append("\n");
                            break;
                        case "moved":
                            b.append("- verschoben: ").append(p[1]).append(" auf ").append(SchoolPipeline.shortWhen(Long.parseLong(p[3]), "1".equals(p[4]))).append("\n");
                            break;
                        case "cancel":
                            b.append("- abgesagt: ").append(p[1]).append(", ").append(SchoolPipeline.shortWhen(Long.parseLong(p[2]), "1".equals(p[3]))).append("\n");
                            break;
                        case "info":
                            b.append("- Info von ").append(p[1]).append(": ").append(p[2]).append("\n");
                            break;
                        default:
                            break;
                    }
                } catch (Exception ignored) {}
            }
        }

        b.append("\n== Alle eingetragenen Schultermine der nächsten ").append(weekAhead ? 9 : 7).append(" Tage (vollständig) ==\n");
        try { b.append(agent.run("school_overview", new JSONObject().put("days", weekAhead ? 9 : 7))).append("\n"); } catch (Exception ignored) {}

        b.append("\n== Stundenplan ").append(day.format(DateTimeFormatter.ofPattern("EEEE d.M.", Locale.GERMAN))).append(" ==\n");
        b.append(agent.timetable(day.toString(), weekAhead ? day.plusDays(4).toString() : day.toString())).append("\n");
        }

        b.append("\n== Kalender heute und morgen (alle Kalender) ==\n");
        try { b.append(agent.run("calendar_events", new JSONObject().put("from_date", today.toString()).put("to_date", today.plusDays(1).toString()))).append("\n"); }
        catch (Exception ignored) {}

        if (school) b.append("\n== Offene Hausaufgaben (nächste 3 Tage) ==\n").append(new Homework(ctx).openSummary(3));

        b.append("\n== E-Mails (letzte 24 h, ohne Werbung) ==\n").append(new MailWatch(ctx).recent(24, true));
        if (PcLink.get(ctx).paired()) b.append("\n== PC ==\n").append(PcLink.get(ctx).online() ? "an" : "aus").append("\n");

        b.append("\n== Offen ==\n");
        for (ContentValues q : db.query("SELECT id, text FROM questions WHERE status='open' AND COALESCE(src_key,'') NOT LIKE 'test:%' ORDER BY id DESC LIMIT 5"))
            b.append("- Rückfrage #").append(q.getAsLong("id")).append(": ").append(SchoolPipeline.shorten(q.getAsString("text"), 140)).append("\n");
        for (ContentValues r : db.query("SELECT text, due FROM reminders WHERE status='pending' AND due<? ORDER BY due LIMIT 5",
                String.valueOf(System.currentTimeMillis() + (weekAhead ? 7 : 2) * 86_400_000L)))
            b.append("- Erinnerung ").append(Brain.fmt(r.getAsLong("due"))).append(": ").append(r.getAsString("text").replace("CALL:", "Anruf: ")).append("\n");
        for (ContentValues t : db.query("SELECT title, status, due FROM tasks WHERE status IN ('open','waiting','blocked') ORDER BY id DESC LIMIT 5"))
            b.append("- Aufgabe [").append(t.getAsString("status")).append("]: ").append(t.getAsString("title"))
                    .append(t.getAsLong("due") != null ? ", fällig " + Brain.fmt(t.getAsLong("due")) : "").append("\n");

        b.append("\n== Wetter ==\n").append(Tools.weather(day.toString())).append("\n");

        b.append("\n== Probleme bei Jarvis ==\n").append(problems());
        return b.toString();
    }

    /** Things that need the Boss' attention on Jarvis' side. */
    String problems() {
        StringBuilder b = new StringBuilder();
        if (Cfg.school(ctx) && "expired".equals(db.kv("edu_session_state", ""))) b.append("- EduPage-Anmeldung abgelaufen: in der Jarvis-App neu anmelden.\n");
        long ok = db.kvLong("edu_last_ok", 0);
        if (Cfg.school(ctx) && ok > 0 && System.currentTimeMillis() - ok > 6 * 3600_000L) b.append("- EduPage seit ").append(Brain.fmt(ok)).append(" nicht mehr erfolgreich geprüft.\n");
        if (CallLink.get(ctx).paired() && System.currentTimeMillis() - db.kvLong("link_last_seen", 0) > 26 * 3600_000L)
            b.append("- Die Anruf-App auf deinem Handy hat sich über einen Tag nicht gemeldet (Autostart/Akku-Einstellung prüfen).\n");
        if (db.kvLong("claude_limited_until", 0) > System.currentTimeMillis())
            b.append("- Claude-Kontingent aufgebraucht bis ").append(Brain.fmt(db.kvLong("claude_limited_until", 0))).append(", Groq antwortet.\n");
        if (Outbox.pendingCount(ctx) > 3) b.append("- ").append(Outbox.pendingCount(ctx)).append(" Nachrichten hängen im Postausgang.\n");
        return b.length() == 0 ? "keine\n" : b.toString();
    }

    /** A finished briefing text (Claude, else Groq, else the plain facts). */
    String compose(String kind, long sinceMs) {
        boolean week = "week".equals(kind);
        String facts = facts(sinceMs, week);
        String task = week
                ? "Schreib " + Cfg.user() + " eine kurze Wochenvorschau für die kommende Schulwoche: Prüfungen, Fristen, Ausflüge, besondere Tage, was er vorbereiten sollte."
                : "morning".equals(kind)
                ? "Schreib " + Cfg.user() + " ein kurzes Morgen-Briefing für heute: nur was wichtig ist (Prüfungen, Änderungen im Stundenplan, Fristen, Mitbringen, Wetter nur wenn relevant)."
                : "" + Cfg.user() + " fragt, was passiert ist und was er wissen muss. Fass das Wichtige zusammen.";
        String instruction = task + " Höchstens 6 kurze Zeilen, Wichtigstes zuerst, keine Wiederholung von Unwichtigem, nichts erfinden, was nicht in den Fakten steht."
                + " Wenn kaum etwas ansteht, sag das in einem Satz.\n\nFakten:\n" + facts;
        ClaudeBridge cb = new ClaudeBridge(ctx);
        if (cb.enabled()) {
            try {
                return Agent.clean(cb.ask("briefing", Agent.personaStatic("telegram"), Brain.get(ctx).agent.timeOnly(), null, instruction));
            } catch (Exception ignored) {}
        }
        try {
            if (!Brain.get(ctx).llm.usable()) throw new Exception("keine API");
            JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", Agent.persona("telegram")))
                    .put(new JSONObject().put("role", "user").put("content", instruction));
            return Agent.clean(Llm.text(Brain.get(ctx).llm.chat(msgs, null, false, null, 600)));
        } catch (Exception e) {
            return "Kurzer Überblick (ohne KI):\n" + SchoolPipeline.shorten(facts, 1500);
        }
    }

    /** Scheduled briefings: school-day mornings and Sunday evening, each once. */
    void tick() {
        if (!"1".equals(Cfg.get(ctx, "briefing_enabled"))) return;
        ZonedDateTime now = Cfg.now();
        String today = now.toLocalDate().toString();
        int dow = now.getDayOfWeek().getValue();
        String hm = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (dow <= 5 && hm.compareTo(Cfg.get(ctx, "briefing_morning")) >= 0 && now.getHour() < 10 && !today.equals(db.kv("briefing_morning_day", ""))) {
            db.kvSet("briefing_morning_day", today);
            // only on days with lessons (not holidays)
            if (!Cfg.school(ctx) || Brain.get(ctx).agent.timetable(today, today).contains("Stunde")) send("morning");
        }
        if (dow == 7 && hm.compareTo(Cfg.get(ctx, "briefing_week")) >= 0 && now.getHour() < 22 && !today.equals(db.kv("briefing_week_day", ""))) {
            db.kvSet("briefing_week_day", today);
            send("week");
        }
    }

    private void send(String kind) {
        long since = db.kvLong("briefing_last", System.currentTimeMillis() - 86_400_000L);
        String text = compose(kind, since);
        db.kvSet("briefing_last", System.currentTimeMillis());
        Outbox.enqueue(ctx, "briefing:" + kind + ":" + Cfg.now().toLocalDate(), text, null, null);
        db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "text", "role", "assistant", "content", text));
        db.log("I", "briefing", ("morning".equals(kind) ? "Morgen-Briefing" : "Wochenvorschau") + " gesendet");
    }
}
