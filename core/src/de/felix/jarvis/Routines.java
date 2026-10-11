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
