package de.felix.jarvis;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calendar access through Android's CalendarProvider. Events written into a Google calendar are
 * uploaded by the Google sync adapter; "synced" is only reported once the server id (_sync_id)
 * is present and the row is no longer dirty.
 */
public final class CalendarStore {
    public static final String MARK = "— Verwaltet von Jarvis";
    public static final String TEST_CAL_NAME = "Jarvis – Test (lokal)";

    public static final class Entry {
        public String key, srcKey, kind, title, description, location;
        public boolean allDay;
        public long startMs, endMs;
        public int[] reminders = new int[0];

        public String fingerprint() {
            return title + "|" + startMs + "|" + endMs + "|" + allDay + "|" + (location == null ? "" : location) + "|"
                    + (description == null ? 0 : description.hashCode()) + "|" + java.util.Arrays.toString(reminders);
        }
    }

    public static final class Result {
        public String action;       // created | updated | unchanged | adopted | cancelled | failed
        public long eventId;
        public List<String> changes = new ArrayList<>();
        public String error;
        public String foreignTitle; // for "adopted"
    }

    private final Context ctx;
    private final ContentResolver cr;
    private final Db db;

    public CalendarStore(Context c) {
        ctx = c.getApplicationContext();
        cr = ctx.getContentResolver();
        db = Db.get(ctx);
    }

    // ------------------------------------------------------------- calendars

    public static final class Cal {
        public long id;
        public String name, account, accountType;
    }

    /** The user's primary Google calendar (the user chose it over a separate school calendar). */
    public Cal target() {
        return query(Calendars.ACCOUNT_TYPE + "='com.google' AND " + Calendars.IS_PRIMARY + "=1");
    }

    /** Artificial test entries (key "test:…") only ever go to the local test calendar. */
    public Cal targetFor(String key) {
        if (key != null && key.startsWith("test:")) return query(Calendars._ID + "=" + ensureTestCalendar());
        return target();
    }

    /** Test hook: makes calendar writes fail (simulated outage). */
    public static volatile boolean simulateOutage;

    private Cal query(String sel) {
        try (Cursor c = cr.query(Calendars.CONTENT_URI, new String[]{Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE}, sel, null, null)) {
            if (c != null && c.moveToFirst()) {
                Cal k = new Cal();
                k.id = c.getLong(0); k.name = c.getString(1); k.account = c.getString(2); k.accountType = c.getString(3);
                return k;
            }
        }
        return null;
    }

    /** Local, clearly labelled calendar for artificial test cases (never synced anywhere). */
    public long ensureTestCalendar() {
        try (Cursor c = cr.query(Calendars.CONTENT_URI, new String[]{Calendars._ID},
                Calendars.ACCOUNT_TYPE + "=? AND " + Calendars.CALENDAR_DISPLAY_NAME + "=?",
                new String[]{CalendarContract.ACCOUNT_TYPE_LOCAL, TEST_CAL_NAME}, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        Uri u = Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, "Jarvis Test")
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
        ContentValues v = new ContentValues();
        v.put(Calendars.ACCOUNT_NAME, "Jarvis Test");
        v.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        v.put(Calendars.NAME, TEST_CAL_NAME);
        v.put(Calendars.CALENDAR_DISPLAY_NAME, TEST_CAL_NAME);
        v.put(Calendars.CALENDAR_COLOR, 0xFFFF8800);
        v.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
        v.put(Calendars.OWNER_ACCOUNT, "Jarvis Test");
        v.put(Calendars.VISIBLE, 1);
        v.put(Calendars.SYNC_EVENTS, 1);
        v.put(Calendars.CALENDAR_TIME_ZONE, Cfg.ZONE.getId());
        return ContentUris.parseId(cr.insert(u, v));
    }

    // ------------------------------------------------------------- write

    /** Create or update the event mapped to entry.key. Never touches events Jarvis does not own. */
    public Result upsert(Entry e) {
        Result r = new Result();
        Cal cal = targetFor(e.key);
        if (cal == null) { r.action = "failed"; r.error = "kein Google-Kalender gefunden"; return r; }
        if (simulateOutage) { r.action = "failed"; r.error = "Kalender nicht verfügbar (simuliert)"; return r; }
        ContentValues map = db.one("SELECT * FROM cal_map WHERE entry_key=?", e.key);
        try {
            if (map != null && map.getAsLong("event_id") != null && exists(map.getAsLong("event_id"))) {
                long id = map.getAsLong("event_id");
                if (map.getAsInteger("adopted") == 1) {
                    r.action = "unchanged"; r.eventId = id; return r; // foreign event: never modified
                }
                if (e.fingerprint().equals(map.getAsString("fingerprint")) && map.getAsInteger("cancelled") == 0) {
                    r.action = "unchanged"; r.eventId = id; return r;
                }
                r.changes = diff(map, e);
                cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), values(e, cal.id), null, null);
                replaceReminders(id, e.reminders);
                db.update("cal_map", Db.cv("title", e.title, "start_ms", e.startMs, "end_ms", e.endMs, "all_day", e.allDay,
                        "location", e.location, "fingerprint", e.fingerprint(), "cancelled", 0, "sync_state", "local",
                        "updated", System.currentTimeMillis()), "entry_key=?", e.key);
                r.action = "updated"; r.eventId = id;
            } else {
                // before creating: is there already a matching event the user (or someone) entered?
                long[] foreign = findMatching(cal.id, e);
                if (foreign != null) {
                    saveMap(e, foreign[0], cal.id, true);
                    r.action = "adopted"; r.eventId = foreign[0]; r.foreignTitle = title(foreign[0]);
                    return r;
                }
                Uri u = cr.insert(Events.CONTENT_URI, values(e, cal.id));
                if (u == null) throw new IllegalStateException("Kalender hat das Einfügen abgelehnt");
                long id = ContentUris.parseId(u);
                replaceReminders(id, e.reminders);
                saveMap(e, id, cal.id, false);
                r.action = "created"; r.eventId = id;
            }
            requestSync(cal);
        } catch (Exception ex) {
            r.action = "failed"; r.error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        return r;
    }

    /** Marks a Jarvis-owned event as cancelled (kept visible, clearly labelled, reminders removed). */
    public Result cancel(String entryKey, String reason) {
        Result r = new Result();
        ContentValues map = db.one("SELECT * FROM cal_map WHERE entry_key=?", entryKey);
        if (map == null || map.getAsLong("event_id") == null || !exists(map.getAsLong("event_id"))) {
            r.action = "failed"; r.error = "kein zugeordneter Termin"; return r;
        }
        long id = map.getAsLong("event_id");
        r.eventId = id;
        if (map.getAsInteger("adopted") == 1) { r.action = "unchanged"; r.error = "fremder Termin – nicht verändert"; return r; }
        if (map.getAsInteger("cancelled") == 1) { r.action = "unchanged"; return r; }
        String t = title(id);
        ContentValues v = new ContentValues();
        v.put(Events.TITLE, "ABGESAGT – " + (t == null ? "" : t.replaceFirst("^ABGESAGT – ", "")));
        v.put(Events.DESCRIPTION, "Laut EduPage abgesagt" + (reason != null ? ": " + reason : "") + "\n\n" + nz(description(id)));
        v.put(Events.AVAILABILITY, Events.AVAILABILITY_FREE);
        cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null);
        replaceReminders(id, new int[0]);
        db.update("cal_map", Db.cv("cancelled", 1, "sync_state", "local", "updated", System.currentTimeMillis()), "entry_key=?", entryKey);
        requestSync(targetFor(entryKey));
        r.action = "cancelled";
        return r;
    }

    /** Deletes an event only if Jarvis created it (used for test cleanup and explicit user requests). */
    public boolean deleteOwned(String entryKey) {
        ContentValues map = db.one("SELECT * FROM cal_map WHERE entry_key=?", entryKey);
        if (map == null || map.getAsInteger("adopted") == 1) return false;
        Long id = map.getAsLong("event_id");
        if (id != null && exists(id)) {
            String d = description(id);
            if (d == null || !d.contains(MARK)) return false; // safety: must carry our marker
            cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null);
        }
        db.delete("cal_map", "entry_key=?", entryKey);
        return true;
    }

    // ------------------------------------------------------------- sync state

    /** "synced" | "local" | "missing" */
    public String syncState(long eventId) {
        try (Cursor c = cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                new String[]{Events._SYNC_ID, Events.DIRTY, Events.DELETED, Events.CALENDAR_ID}, null, null, null)) {
            if (c == null || !c.moveToFirst()) return "missing";
            if (c.getInt(2) == 1) return "missing";
            long calId = c.getLong(3);
            Cal t = query(Calendars._ID + "=" + calId);
            if (t != null && CalendarContract.ACCOUNT_TYPE_LOCAL.equals(t.accountType)) return "local-only";
            return c.getString(0) != null && c.getInt(1) == 0 ? "synced" : "local";
        }
    }

    /** Re-checks entries that are not yet confirmed by Google. Returns keys that became synced. */
    public List<String> refreshSyncStates() {
        List<String> nowSynced = new ArrayList<>();
        for (ContentValues m : db.query("SELECT entry_key, event_id FROM cal_map WHERE sync_state='local'")) {
            String s = syncState(m.getAsLong("event_id"));
            if ("synced".equals(s)) {
                db.update("cal_map", Db.cv("sync_state", "synced"), "entry_key=?", m.getAsString("entry_key"));
                nowSynced.add(m.getAsString("entry_key"));
            } else if ("missing".equals(s)) {
                db.update("cal_map", Db.cv("sync_state", "missing"), "entry_key=?", m.getAsString("entry_key"));
            }
        }
        return nowSynced;
    }

    public void requestSync(Cal cal) {
        if (cal == null || !"com.google".equals(cal.accountType)) return;
        try {
            Bundle b = new Bundle();
            b.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            b.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(new Account(cal.account, cal.accountType), CalendarContract.AUTHORITY, b);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------- read

    public static final class Ev {
        public long id, start, end, calId;
        public boolean allDay;
        public String title, location, calName;
        public boolean jarvis;
    }

    /** Instances in [fromMs, toMs) over all visible calendars (read-only, for questions like "Was steht morgen an?"). */
    public List<Ev> range(long fromMs, long toMs) {
        List<Ev> out = new ArrayList<>();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, fromMs);
        ContentUris.appendId(b, toMs);
        try (Cursor c = cr.query(b.build(), new String[]{CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.CALENDAR_ID},
                CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN)) {
            while (c != null && c.moveToNext()) {
                Ev e = new Ev();
                e.id = c.getLong(0); e.start = c.getLong(1); e.end = c.getLong(2); e.allDay = c.getInt(3) == 1;
                e.title = c.getString(4); e.location = c.getString(5); e.calName = c.getString(6);
                e.jarvis = c.getString(7) != null && c.getString(7).contains(MARK);
                e.calId = c.getLong(8);
                out.add(e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------- internals

    private ContentValues values(Entry e, long calId) {
        ContentValues v = new ContentValues();
        v.put(Events.CALENDAR_ID, calId);
        v.put(Events.TITLE, e.title);
        v.put(Events.DESCRIPTION, e.description);
        v.put(Events.EVENT_LOCATION, e.location);
        v.put(Events.DTSTART, e.startMs);
        v.put(Events.DTEND, e.endMs);
        v.put(Events.ALL_DAY, e.allDay ? 1 : 0);
        v.put(Events.EVENT_TIMEZONE, e.allDay ? "UTC" : Cfg.ZONE.getId());
        v.put(Events.HAS_ALARM, e.reminders.length > 0 ? 1 : 0);
        v.put(Events.AVAILABILITY, e.allDay ? Events.AVAILABILITY_FREE : Events.AVAILABILITY_BUSY);
        v.put(Events.CUSTOM_APP_PACKAGE, ctx.getPackageName());
        v.put(Events.CUSTOM_APP_URI, "jarvis://school/" + Uri.encode(e.key));
        return v;
    }

    private void replaceReminders(long eventId, int[] minutes) {
        cr.delete(Reminders.CONTENT_URI, Reminders.EVENT_ID + "=?", new String[]{String.valueOf(eventId)});
        for (int m : minutes) {
            ContentValues v = new ContentValues();
            v.put(Reminders.EVENT_ID, eventId);
            v.put(Reminders.MINUTES, m);
            v.put(Reminders.METHOD, Reminders.METHOD_ALERT);
            cr.insert(Reminders.CONTENT_URI, v);
        }
    }

    private void saveMap(Entry e, long eventId, long calId, boolean adopted) {
        ContentValues v = Db.cv("entry_key", e.key, "src_key", e.srcKey, "kind", e.kind, "event_id", eventId, "cal_id", calId,
                "title", e.title, "start_ms", e.startMs, "end_ms", e.endMs, "all_day", e.allDay, "location", e.location,
                "sync_state", "local", "cancelled", 0, "adopted", adopted, "fingerprint", e.fingerprint(),
                "created", System.currentTimeMillis(), "updated", System.currentTimeMillis());
        db.getWritableDatabase().insertWithOnConflict("cal_map", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }

    private boolean exists(long id) {
        try (Cursor c = cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), new String[]{Events.DELETED}, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(0) == 0;
        }
    }

    private String title(long id) { return col(id, Events.TITLE); }

    private String description(long id) { return col(id, Events.DESCRIPTION); }

    private String col(long id, String col) {
        try (Cursor c = cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), new String[]{col}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** A not-Jarvis event on the same day whose title shares the subject and the kind of appointment. */
    private long[] findMatching(long calId, Entry e) {
        LocalDate day = e.allDay ? Instant.ofEpochMilli(e.startMs).atZone(ZoneOffset.UTC).toLocalDate()
                : Instant.ofEpochMilli(e.startMs).atZone(Cfg.ZONE).toLocalDate();
        long from = day.atStartOfDay(Cfg.ZONE).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(Cfg.ZONE).toInstant().toEpochMilli();
        List<String> want = tokens(e.title);
        for (Ev ev : range(from, to)) {
            if (ev.jarvis) continue;
            List<String> have = tokens(ev.title);
            int common = 0;
            for (String w : want) if (have.contains(w)) common++;
            // need at least two shared significant words (e.g. "mathe" + "schulaufgabe"), or the full title
            if (common >= 2 || (!want.isEmpty() && common == want.size())) {
                // only events from the target calendar can be "the same" entry
                if (calOf(ev.id) == calId) return new long[]{ev.id};
            }
        }
        return null;
    }

    private long calOf(long eventId) {
        try (Cursor c = cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), new String[]{Events.CALENDAR_ID}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    static List<String> tokens(String t) {
        List<String> out = new ArrayList<>();
        if (t == null) return out;
        String norm = t.toLowerCase(Locale.GERMAN).replace("mathematik", "mathe").replace("leistungsnachweis", "ln")
                .replace("schulaufgabe", "sa").replace("stegreifaufgabe", "ex").replaceAll("[^a-zäöüß0-9 ]", " ");
        for (String w : norm.split("\\s+")) {
            if (w.length() >= 2 && !w.matches("(der|die|das|und|in|im|am|zum|zur|mit|für|von|test)")) out.add(w);
        }
        return out;
    }

    private static List<String> diff(ContentValues old, Entry e) {
        List<String> c = new ArrayList<>();
        if (old.getAsLong("start_ms") != e.startMs || old.getAsLong("end_ms") != e.endMs || (old.getAsInteger("all_day") == 1) != e.allDay) c.add("Zeit");
        if (!nz(old.getAsString("title")).equals(nz(e.title))) c.add("Titel");
        if (!nz(old.getAsString("location")).equals(nz(e.location))) c.add("Ort");
        if (c.isEmpty()) c.add("Details");
        return c;
    }

    static String nz(String s) { return s == null ? "" : s; }
}
