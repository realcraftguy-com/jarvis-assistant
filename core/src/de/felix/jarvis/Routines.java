package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONArray;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daily routines: live substitution alerts during the school morning and the optional wake-up call with the briefing.
 */
final class Routines {
    private final Context ctx;
    private final Db db;

    Routines(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
    }

    void tick() {
        ZonedDateTime now = Cfg.now();
        if (Cfg.school(ctx)) try { substitutions(now); } catch (Exception e) { db.log("W", "edupage", "Vertretungs-Abgleich: " + e.getMessage()); }
        wakeCall(now);
        weatherTip(now);
        if (Cfg.school(ctx)) try { packingLists(now); } catch (Exception e) { db.log("W", "packliste", e.toString()); }
    }

    // ------------------------------------------------------------------ weather tip

    /** School days: the morning briefing carries it; otherwise (weekend, holidays, briefing off) a short tip at 08:30. */
    private void weatherTip(ZonedDateTime now) {
        String day = now.toLocalDate().toString();
        if (day.equals(db.kv("weather_tip_day", ""))) return;
        boolean schoolDay = now.getDayOfWeek().getValue() <= 5;
        boolean briefing = "1".equals(Cfg.get(ctx, "briefing_enabled"));
        LocalTime at = schoolDay && !briefing ? LocalTime.of(6, 40) : LocalTime.of(8, 30);
        if (now.toLocalTime().isBefore(at) || now.getHour() >= 12) return;
        if (schoolDay && briefing && !day.equals(db.kv("briefing_morning_day", "__"))) return; // wait: maybe the briefing covers it
        db.kvSet("weather_tip_day", day);
        if (schoolDay && briefing && db.kvLong("briefing_last", 0) > now.toLocalDate().atStartOfDay(Cfg.ZONE).toInstant().toEpochMilli()) return; // briefing went out
        String tip = Tools.weatherNotable(day);
        if (tip == null) return;
        Outbox.enqueue(ctx, "weather:" + day, "Wetter heute: " + tip, null, null);
        db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "text", "role", "assistant", "content", "Wetter heute: " + tip));
    }

    // ------------------------------------------------------------------ packing lists

    private static final String TRIP = "(?i).*(camp|fahrt|ausflug|schullandheim|landheim|exkursion|besuch|wandertag|skikurs|skilager|klassenfahrt|"
            + "museum|theater|kino|zoo|tierpark|sporttag|bundesjugendspiele|projekttag|wallfahrt|übernachtung).*";

    /** Two days before a trip (three for multi-day trips): a checklist; the evening before: "bag packed?". */
    private void packingLists(ZonedDateTime now) throws Exception {
        long ms = System.currentTimeMillis();
        for (ContentValues e : db.query("SELECT entry_key, src_key, title, start_ms, end_ms, all_day FROM cal_map WHERE cancelled=0 AND entry_key NOT LIKE 'test:%' "
                + "AND start_ms>? AND start_ms<?", String.valueOf(ms), String.valueOf(ms + 4 * 86_400_000L))) {
            String title = e.getAsString("title");
            if (title == null || !title.matches(TRIP) || title.matches("(?i).*(leistungsnachweis|schulaufgabe|test|prüfung).*")) continue;
            boolean allDay = e.getAsInteger("all_day") != null && e.getAsInteger("all_day") == 1;
            LocalDate day = allDay ? java.time.Instant.ofEpochMilli(e.getAsLong("start_ms")).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    : java.time.Instant.ofEpochMilli(e.getAsLong("start_ms")).atZone(Cfg.ZONE).toLocalDate();
            long days = Math.max(1, Math.round((e.getAsLong("end_ms") - e.getAsLong("start_ms")) / 86_400_000.0));
            long before = day.toEpochDay() - now.toLocalDate().toEpochDay();
            String key = "pack:" + e.getAsString("entry_key") + ":" + day;
            if (before <= (days > 1 ? 3 : 2) && before >= 1 && db.kv(key, null) == null && now.getHour() >= 15 && !Cfg.inQuietHours(ctx, now)) {
                db.kvSet(key, String.valueOf(ms));
                ContentValues src = db.one("SELECT text, note FROM school_items WHERE src_key=?", e.getAsString("src_key"));
                String facts = "Termin: " + title + ", " + Homework.day(day.toString()) + (days > 1 ? ", " + days + " Tage" : "")
                        + "\nInfos der Schule (Daten): " + (src == null ? "keine" : SchoolPipeline.shorten(src.getAsString("text"), 1500))
                        + "\nWetter: " + Tools.weather(day.toString());
                String list = compose(facts);
                Outbox.enqueue(ctx, key, list, null, null);
                db.insert("chat", Db.cv("ts", ms, "channel", "text", "role", "assistant", "content", list));
                db.log("I", "packliste", "Packliste für " + title);
            }
            String eve = "packeve:" + e.getAsString("entry_key") + ":" + day;
            if (before == 1 && now.getHour() >= 19 && now.getHour() < 21 && db.kv(eve, null) == null && db.kv(key, null) != null) {
                db.kvSet(eve, String.valueOf(ms));
                Outbox.enqueue(ctx, eve, "Morgen ist " + title + ". Tasche schon gepackt? Die Liste von vorgestern steht weiter oben.", null, null);
            }
        }
    }

    private String compose(String facts) {
        String instruction = "Schreib " + Cfg.user() + " eine kurze Packliste für diesen Schultermin: höchstens 12 Punkte als „• …“, zuerst was die Schule ausdrücklich "
                + "verlangt (mit „(laut Schule)“ markieren), dann das Sinnvolle fürs Wetter und die Dauer. Eine Zeile Einleitung, keine Erklärungen. "
                + "Nichts erfinden, was die Schule angeblich verlangt.\n\n" + facts;
        ClaudeBridge cb = new ClaudeBridge(ctx);
        if (cb.enabled()) {
            try { return Agent.clean(cb.ask("pack", Agent.personaStatic("telegram"), Brain.get(ctx).agent.timeOnly(), null, instruction)); } catch (Exception ignored) {}
        }
        try {
            org.json.JSONArray msgs = new org.json.JSONArray().put(new org.json.JSONObject().put("role", "system").put("content", Agent.persona("telegram")))
                    .put(new org.json.JSONObject().put("role", "user").put("content", instruction));
            return Agent.clean(Llm.text(Brain.get(ctx).llm.chat(msgs, null, false, null, 500)));
        } catch (Exception e) {
            return "Bald ist ein Schultermin – denk ans Packen:\n" + facts;
        }
    }

    // ------------------------------------------------------------------ substitutions

    /** Changes for one day that the user has not been told about yet ("2. Stunde Mathe entfällt"). Marks them as told. */
    List<String> newChanges(LocalDate day) throws Exception {
        List<EduPage.Lesson> ls = new EduPage(ctx).timetable(day.toString(), day.toString());
        String key = "tt_told:" + day;
        Set<String> told = new HashSet<>();
        JSONArray a = new JSONArray(db.kv(key, "[]"));
        for (int i = 0; i < a.length(); i++) told.add(a.getString(i));
        List<String> lines = new ArrayList<>();
        boolean isToday = day.equals(LocalDate.now(Cfg.ZONE));
        LocalTime nowT = LocalTime.now(Cfg.ZONE);
        int firstReal = -1;
        String firstStart = null;
        for (EduPage.Lesson l : ls) {
            if (!l.cancelled && firstStart == null) { firstStart = l.start; firstReal = l.period; }
            if (!l.cancelled && !l.changed) continue;
            if (isToday && LocalTime.parse(l.end).isBefore(nowT)) continue; // already over
            String sig = l.period + "|" + l.subject + "|" + l.cancelled + "|" + l.teacher + "|" + l.room + "|" + l.start;
            if (told.contains(sig)) continue;
            told.add(sig);
            String name = SchoolParserFriendly.name(l.subject);
            lines.add(l.cancelled ? l.period + ". Stunde " + name + " entfällt"
                    : l.period + ". Stunde " + name + (l.teacher != null ? " bei " + l.teacher : "") + (l.room != null && !l.room.isEmpty() ? ", Raum " + l.room : "") + " (geändert)");
        }
        // first lessons cancelled: he can start later
        if (!lines.isEmpty() && firstReal > 1 && firstStart != null && !ls.isEmpty() && ls.get(0).cancelled)
            lines.add("Du fängst damit erst um " + firstStart + " an (" + firstReal + ". Stunde).");
        JSONArray out = new JSONArray();
        for (String s : told) out.put(s);
        db.kvSet(key, out.toString());
        return lines;
    }

    /** School mornings 06:15–13:30: check today's timetable every 15 minutes and report new changes at once. */
    private void substitutions(ZonedDateTime now) throws Exception {
        if (now.getDayOfWeek().getValue() > 5 || now.toLocalTime().isBefore(LocalTime.of(6, 15)) || now.toLocalTime().isAfter(LocalTime.of(13, 30))) return;
        if (System.currentTimeMillis() - db.kvLong("tt_live_at", 0) < 15 * 60_000L || !new EduPage(ctx).hasSession()) return;
        db.kvSet("tt_live_at", System.currentTimeMillis());
        List<String> lines = newChanges(now.toLocalDate());
        if (lines.isEmpty()) return;
        String text = "Achtung, heute: " + (lines.size() == 1 ? lines.get(0) + "." : "\n• " + String.join("\n• ", lines));
        Outbox.enqueue(ctx, "ttlive:" + now.toLocalDate() + ":" + text.hashCode(), text, null, null);
        db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "text", "role", "assistant", "content", text));
        db.log("I", "edupage", "Stundenplan-Änderung heute gemeldet");
    }

    // ------------------------------------------------------------------ wake-up call

    static String wakeReason() { return "Weckanruf: Guten Morgen! Wecke " + Cfg.user() + " gut gelaunt und sag ihm in drei, vier kurzen Sätzen das Wichtigste für heute "
            + "(Änderungen im Stundenplan, Prüfungen, Hausaufgaben, Wetter, falls relevant)"; }

    /** "06:40" = on school days at that time (only if there are lessons), "" = off. One retry after 6 minutes if missed. */
    private void wakeCall(ZonedDateTime now) {
        String t = Cfg.get(ctx, "wake_call");
        if (t == null || !t.matches("\\d{1,2}:\\d{2}")) return;
        String today = now.toLocalDate().toString();
        LocalTime at = LocalTime.parse(t.length() == 4 ? "0" + t : t);
        if (now.toLocalTime().isBefore(at) || now.toLocalTime().isAfter(at.plusMinutes(45))) return;
        String state = db.kv("wake_state", "");
        if (state.startsWith(today + "|done")) return;
        if (!state.startsWith(today)) {
            if (now.getDayOfWeek().getValue() > 5 || (Cfg.school(ctx) && !Brain.get(ctx).agent.timetable(today, today).contains("Stunde"))) { db.kvSet("wake_state", today + "|done"); return; }
            if (CallLink.get(ctx).inCall()) return;
            String r = Brain.get(ctx).requestCall(wakeReason(), 3, true, false);
            db.kvSet("wake_state", today + "|1|" + System.currentTimeMillis());
            db.log("I", "wecker", "Weckanruf: " + r);
            return;
        }
        String[] p = state.split("\\|");
        if (p.length >= 3 && "1".equals(p[1]) && System.currentTimeMillis() - Long.parseLong(p[2]) > 6 * 60_000L) {
            ContentValues c = db.one("SELECT COUNT(*) n FROM calls WHERE direction='out' AND ts>=? AND outcome IN ('answered','connected')", p[2]);
            if (c != null && c.getAsInteger("n") > 0) { db.kvSet("wake_state", today + "|done"); return; }
            if (CallLink.get(ctx).inCall()) return;
            db.kvSet("wake_state", today + "|done");
            db.log("I", "wecker", "Zweiter Weckversuch: " + Brain.get(ctx).requestCall(wakeReason(), 3, true, true));
        }
    }

    static String wakeSet(Context c, String time) {
        if (time == null || time.trim().isEmpty() || time.toLowerCase().matches(".*(aus|off|nein|stop).*")) {
            Cfg.set(c, "wake_call", "");
            return "Weckanruf ist aus.";
        }
        String t = time.trim().replace('.', ':');
        if (t.matches("\\d{1,2}")) t = t + ":00";
        LocalTime lt = LocalTime.parse(t.length() == 4 ? "0" + t : t);
        Cfg.set(c, "wake_call", lt.format(DateTimeFormatter.ofPattern("HH:mm")));
        return "Weckanruf an Schultagen um " + lt.format(DateTimeFormatter.ofPattern("H:mm")) + " Uhr – mit Briefing. Gehst du nicht ran, versuche ich es nach 6 Minuten noch einmal.";
    }
}
