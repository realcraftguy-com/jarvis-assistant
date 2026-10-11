package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Durable state. Everything Jarvis must not forget across restarts lives here. */
public final class Db extends SQLiteOpenHelper {
    private static Db instance;

    /** The open database or null (for static helpers that have no Context). */
    static Db peek() { return instance; }

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "jarvis.db", null, 2);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY, ts INTEGER, level TEXT, area TEXT, msg TEXT)");
        db.execSQL("CREATE TABLE school_items(src_key TEXT PRIMARY KEY, edu_id TEXT, edu_type TEXT, published TEXT,"
                + " title TEXT, text TEXT, text_hash TEXT, status TEXT, analysis TEXT, note TEXT, updated INTEGER, removed INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE cal_map(entry_key TEXT PRIMARY KEY, src_key TEXT, kind TEXT, event_id INTEGER, cal_id INTEGER,"
                + " title TEXT, start_ms INTEGER, end_ms INTEGER, all_day INTEGER, location TEXT, sync_state TEXT,"
                + " cancelled INTEGER DEFAULT 0, adopted INTEGER DEFAULT 0, fingerprint TEXT, created INTEGER, updated INTEGER)");
        db.execSQL("CREATE TABLE outbox(id INTEGER PRIMARY KEY, dedupe TEXT UNIQUE, channel TEXT, text TEXT, markup TEXT,"
                + " question_id INTEGER, status TEXT, attempts INTEGER DEFAULT 0, next_try INTEGER, created INTEGER,"
                + " sent_at INTEGER, remote_id TEXT, last_error TEXT)");
        db.execSQL("CREATE TABLE questions(id INTEGER PRIMARY KEY, kind TEXT, src_key TEXT, text TEXT, options TEXT,"
                + " status TEXT, answer TEXT, context TEXT, created INTEGER, answered INTEGER, remote_id TEXT)");
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY, title TEXT, detail TEXT, status TEXT, due INTEGER,"
                + " next_run INTEGER, steps TEXT, result TEXT, attempts INTEGER DEFAULT 0, created INTEGER, updated INTEGER)");
        db.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY, text TEXT, due INTEGER, status TEXT, created INTEGER, repeat TEXT)");
        db.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY, ts INTEGER, channel TEXT, role TEXT, content TEXT)");
        db.execSQL("CREATE TABLE llm_usage(day TEXT, provider TEXT, requests INTEGER DEFAULT 0, tokens INTEGER DEFAULT 0,"
                + " errors INTEGER DEFAULT 0, last_error TEXT, last_ms INTEGER, PRIMARY KEY(day, provider))");
        db.execSQL("CREATE TABLE calls(id INTEGER PRIMARY KEY, ts INTEGER, direction TEXT, reason TEXT, outcome TEXT, duration INTEGER)");
        db.execSQL("CREATE INDEX log_ts ON log(ts)");
        db.execSQL("CREATE INDEX outbox_status ON outbox(status, next_try)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            try { db.execSQL("ALTER TABLE llm_usage ADD COLUMN last_ms INTEGER"); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------- key/value

    public String kv(String k, String def) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM kv WHERE k=?", new String[]{k})) {
            return c.moveToFirst() && c.getString(0) != null ? c.getString(0) : def;
        }
    }

    public long kvLong(String k, long def) {
        try { return Long.parseLong(kv(k, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    public void kvSet(String k, Object v) {
        if (v == null) { getWritableDatabase().delete("kv", "k=?", new String[]{k}); return; }
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", String.valueOf(v));
        getWritableDatabase().insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ------------------------------------------------------------- log

    public void log(String level, String area, String msg) {
        ContentValues cv = new ContentValues();
        cv.put("ts", System.currentTimeMillis());
        cv.put("level", level);
        cv.put("area", area);
        cv.put("msg", msg);
        getWritableDatabase().insert("log", null, cv);
        android.util.Log.println("E".equals(level) ? android.util.Log.ERROR : android.util.Log.INFO, "Jarvis/" + area, msg);
    }

    public void pruneLog() {
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        getWritableDatabase().delete("log", "ts<?", new String[]{String.valueOf(cutoff)});
        getWritableDatabase().delete("chat", "ts<?", new String[]{String.valueOf(cutoff)});
    }

    // ------------------------------------------------------------- generic helpers

    public List<ContentValues> query(String sql, String... args) {
        List<ContentValues> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                ContentValues cv = new ContentValues();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: cv.put(c.getColumnName(i), c.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: cv.put(c.getColumnName(i), c.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_NULL: cv.putNull(c.getColumnName(i)); break;
                        default: cv.put(c.getColumnName(i), c.getString(i));
                    }
                }
                out.add(cv);
            }
        }
        return out;
    }

    public ContentValues one(String sql, String... args) {
        List<ContentValues> l = query(sql, args);
        return l.isEmpty() ? null : l.get(0);
    }

    public long insert(String table, ContentValues cv) {
        return getWritableDatabase().insertOrThrow(table, null, cv);
    }

    public int update(String table, ContentValues cv, String where, String... args) {
        return getWritableDatabase().update(table, cv, where, args);
    }

    public int delete(String table, String where, String... args) {
        return getWritableDatabase().delete(table, where, args);
    }

    public static ContentValues cv(Object... kv) {
        ContentValues c = new ContentValues();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v == null) c.putNull(k);
            else if (v instanceof Integer) c.put(k, (Integer) v);
            else if (v instanceof Long) c.put(k, (Long) v);
            else if (v instanceof Boolean) c.put(k, ((Boolean) v) ? 1 : 0);
            else if (v instanceof Double) c.put(k, (Double) v);
            else c.put(k, String.valueOf(v));
        }
        return c;
    }
}
