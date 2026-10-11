package de.felix.jarvis;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Instances;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Watches the user's calendars (not only school) for appointments that someone else cancelled or moved.
 * Soon (within 48 h): Jarvis calls and offers to use the freed time; later: a Telegram note.
 * Changes the user made himself (his own events, declined invitations) and Jarvis' own entries are ignored.
 */
final class CalendarWatch {
    private static final long WINDOW = 8 * 86_400_000L, SOON = 48 * 3600_000L;
    private final Context ctx;
    private final Db db;

    CalendarWatch(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
    }

    /** Calendars never touched or watched (e.g. the family birthday calendar), comma-separated ids. */
    static boolean excluded(Context c, long calId, String calName) {
        if (calName != null && calName.contains("Jarvis – Test")) return !"1".equals(Db.get(c).kv("calwatch_test", "0"));
        for (String id : Db.get(c).kv("cal_exclude", "6").split(",")) if (id.trim().equals(String.valueOf(calId))) return true;
        return false;
    }

    static final class Item {
        long eventId, begin, end;
        String title, cal;
        boolean own;

        JSONArray json() { return new JSONArray().put(eventId).put(begin).put(end).put(title).put(cal).put(own ? 1 : 0); }

        static Item of(JSONArray a) {
            Item i = new Item();
            i.eventId = a.optLong(0); i.begin = a.optLong(1); i.end = a.optLong(2); i.title = a.optString(3); i.cal = a.optString(4); i.own = a.optInt(5) == 1;
            return i;
        }
    }

    /** Timed appointments from now on, keyed "eventId@begin". */
    Map<String, Item> scan(long from) {
        Map<String, Item> out = new HashMap<>();
        Uri.Builder b = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, from + WINDOW);
        try (Cursor c = ctx.getContentResolver().query(b.build(), new String[]{Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE,
                        Instances.CALENDAR_ID, Instances.CALENDAR_DISPLAY_NAME, Instances.ORGANIZER, Instances.OWNER_ACCOUNT,
                        Instances.CALENDAR_ACCESS_LEVEL, Instances.STATUS, Instances.SELF_ATTENDEE_STATUS, Instances.DESCRIPTION, Instances.ALL_DAY},
                Instances.VISIBLE + "=1", null, Instances.BEGIN)) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(12) == 1) continue;                                                    // all-day: birthdays, holidays
                if (excluded(ctx, c.getLong(4), c.getString(5))) continue;
                if (c.getString(11) != null && c.getString(11).contains(CalendarStore.MARK)) continue; // Jarvis' own entries
                if (c.getInt(9) == CalendarContract.Events.STATUS_CANCELED) continue;
                if (c.getInt(10) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;
                Item i = new Item();
                i.eventId = c.getLong(0); i.begin = c.getLong(1); i.end = c.getLong(2);
                i.title = c.getString(3) == null ? "(ohne Titel)" : c.getString(3);
                i.cal = c.getString(5);
                String org = c.getString(6), owner = c.getString(7);
                // the user's own appointment in his own calendar: if it disappears, he did that himself
                i.own = c.getInt(8) >= CalendarContract.Calendars.CAL_ACCESS_OWNER && (org == null || org.equalsIgnoreCase(owner));
                if (i.title.startsWith("[TEST]") && "1".equals(db.kv("calwatch_test", "0"))) i.own = false; // test: pretend someone else owns it
                out.put(i.eventId + "@" + i.begin, i);
            }
        }
        return out;
    }

    /** Called from Brain.tick(); compares with the last snapshot every two minutes. */
    void tick() {
        long now = System.currentTimeMillis();
        if (now - db.kvLong("calwatch_at", 0) < 120_000) return;
        db.kvSet("calwatch_at", now);
        Map<String, Item> cur;
        try { cur = scan(now); } catch (Exception e) { return; } // no calendar permission etc.
        String raw = db.kv("calwatch_snap", null);
        Map<String, Item> old = new HashMap<>();
        if (raw != null) {
            try {
                JSONObject o = new JSONObject(raw);
                for (Iterator<String> k = o.keys(); k.hasNext(); ) { String key = k.next(); old.put(key, Item.of(o.getJSONArray(key))); }
            } catch (Exception ignored) {}
        }
        // a calendar account that is resyncing can look empty for a moment – never report that as mass cancellation
        if (cur.isEmpty() && old.size() > 3) return;
        save(cur);
        if (raw == null) return;

        List<String[]> found = new ArrayList<>(); // kind, key, text, soonFlag
        for (Map.Entry<String, Item> e : old.entrySet()) {
            if (cur.containsKey(e.getKey())) continue;
            Item was = e.getValue();
            if (was.begin < now + 5 * 60_000L || was.begin > now + WINDOW - 86_400_000L) continue; // passed or at the window edge
            if (was.own) { db.log("I", "kalender", "Eigener Termin geändert/gelöscht (von dir): " + was.title); continue; }
            if (declinedByFelix(was.eventId)) continue;
            Item moved = null;
            for (Item i : cur.values()) if (i.eventId == was.eventId && !old.containsKey(i.eventId + "@" + i.begin)) { moved = i; break; }
            if (moved != null) {
                found.add(new String[]{"moved", e.getKey(), "„" + was.title + "“ wurde verschoben: von " + span(was.begin, was.end) + " auf " + span(moved.begin, moved.end) + ".",
                        Math.min(was.begin, moved.begin) - now < SOON ? "1" : "0"});
            } else {
                found.add(new String[]{"cancel", e.getKey(), "Dein Termin „" + was.title + "“ " + span(was.begin, was.end) + " wurde gerade abgesagt (um "
                        + Brain.fmt(now).replaceAll(".* ", "") + " Uhr gemerkt). Die Zeit ist jetzt frei.", was.begin - now < SOON ? "1" : "0"});
            }
        }
        if (found.size() > 4) {
            db.log("W", "kalender", found.size() + " Termine gleichzeitig verschwunden – sieht nach Kalender-Sync aus, keine Meldung");
            return;
        }
        for (String[] f : found) {
            String key = "cal:" + f[0] + ":" + f[1];
            if (db.kv("calwatch_done:" + f[1], null) != null) continue;
            db.kvSet("calwatch_done:" + f[1], String.valueOf(now));
            String offer = "cancel".equals(f[0]) ? " Soll ich dir die Zeit für etwas anderes blocken?" : "";
            db.log("I", "kalender", f[2]);
            Outbox.enqueue(ctx, key, f[2] + offer, null, null);
            db.insert("chat", Db.cv("ts", now, "channel", "text", "role", "assistant", "content", f[2] + offer));
            if ("1".equals(f[3])) Brain.get(ctx).urgent(key, f[2] + offer);
        }
    }

    private void save(Map<String, Item> cur) {
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, Item> e : cur.entrySet()) o.put(e.getKey(), e.getValue().json()); } catch (Exception ignored) {}
        db.kvSet("calwatch_snap", o.toString());
    }

    /** the user said no to an invitation himself (the event still exists, only his answer changed). */
    private boolean declinedByFelix(long eventId) {
        try (Cursor c = ctx.getContentResolver().query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                new String[]{CalendarContract.Events.SELF_ATTENDEE_STATUS, CalendarContract.Events.DELETED, CalendarContract.Events.STATUS}, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(1) == 0 && c.getInt(2) != CalendarContract.Events.STATUS_CANCELED
                    && c.getInt(0) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED;
        } catch (Exception e) {
            return false;
        }
    }

    /** "morgen 12:00–13:00", "heute 18:00–19:00", "Mo 13.10. 9:00–10:30". */
    static String span(long begin, long end) {
        ZonedDateTime b = Instant.ofEpochMilli(begin).atZone(Cfg.ZONE), e = Instant.ofEpochMilli(end).atZone(Cfg.ZONE);
        LocalDate today = LocalDate.now(Cfg.ZONE);
        long d = b.toLocalDate().toEpochDay() - today.toEpochDay();
        String day = d == 0 ? "heute" : d == 1 ? "morgen" : d == 2 ? "übermorgen" : b.format(DateTimeFormatter.ofPattern("EE d.M.", Locale.GERMAN));
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("H:mm");
        return day + " " + b.format(hm) + "–" + e.format(hm);
    }
}
