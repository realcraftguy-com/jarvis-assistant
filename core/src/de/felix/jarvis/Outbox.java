package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;

import java.util.List;

/**
 * Durable, deduplicated message queue to the Boss.
 * A message is enqueued with a stable key (e.g. "cal:<entry>:created"), so a restart or a
 * re-processed EduPage item never produces a second message, and a failed send is retried
 * without repeating the calendar action that caused it.
 */
public final class Outbox {
    private Outbox() {}

    /** @return true if newly queued, false if the same key was queued before. */
    public static boolean enqueue(Context c, String dedupe, String text, String markup, Long questionId) {
        // Telegram allows 4096 characters per message: split long texts at line breaks
        if (text != null && text.length() > 3900) {
            List<String> parts = new java.util.ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String line : text.split("\n")) {
                while (line.length() > 3800) { parts.add(line.substring(0, 3800)); line = line.substring(3800); }
                if (cur.length() + line.length() + 1 > 3800) { parts.add(cur.toString()); cur.setLength(0); }
                cur.append(cur.length() > 0 ? "\n" : "").append(line);
            }
            if (cur.length() > 0) parts.add(cur.toString());
            boolean any = false;
            for (int i = 0; i < parts.size(); i++) {
                any |= enqueue(c, dedupe + "#" + i, parts.get(i), i == parts.size() - 1 ? markup : null, i == parts.size() - 1 ? questionId : null);
            }
            return any;
        }
        Db db = Db.get(c);
        try {
            db.insert("outbox", Db.cv("dedupe", dedupe, "channel", "telegram", "text", text, "markup", markup,
                    "question_id", questionId, "status", "pending", "attempts", 0, "next_try", 0L,
                    "created", System.currentTimeMillis()));
            JarvisService.poke(c);
            return true;
        } catch (SQLiteConstraintException dup) {
            return false;
        }
    }

    public static int pendingCount(Context c) {
        ContentValues v = Db.get(c).one("SELECT COUNT(*) n FROM outbox WHERE status='pending'");
        return v == null ? 0 : v.getAsInteger("n");
    }

    /** Sends everything that is due. Returns the delay (ms) until the next retry, or -1 if nothing is waiting. */
    public static long flush(Context c) {
        Db db = Db.get(c);
        Telegram tg = new Telegram(c);
        if (!tg.configured() || !tg.paired()) return -1;
        if ("1".equals(db.kv("hold_outbox", "0"))) return -1; // development: review before sending
        long now = System.currentTimeMillis();
        List<ContentValues> due = db.query("SELECT * FROM outbox WHERE status='pending' AND next_try<=? ORDER BY id LIMIT 20", String.valueOf(now));
        for (ContentValues m : due) {
            long id = m.getAsLong("id");
            if (Cfg.paused(c)) break;
            try {
                long mid = tg.send(m.getAsString("text"), m.getAsString("markup"), null);
                db.update("outbox", Db.cv("status", "sent", "sent_at", System.currentTimeMillis(), "remote_id", mid, "last_error", null),
                        "id=?", String.valueOf(id));
                Long q = m.getAsLong("question_id");
                if (q != null) db.update("questions", Db.cv("remote_id", mid), "id=?", String.valueOf(q));
                db.kvSet("tg_last_send_ok", System.currentTimeMillis());
            } catch (Exception e) {
                int attempts = m.getAsInteger("attempts") + 1;
                long backoff = Math.min(30_000L << Math.min(attempts, 6), 30 * 60_000L);
                if (e instanceof Telegram.TgError && ((Telegram.TgError) e).retryAfter > 0) backoff = ((Telegram.TgError) e).retryAfter * 1000L;
                boolean permanent = e instanceof Telegram.TgError && (((Telegram.TgError) e).code == 400 || ((Telegram.TgError) e).code == 403)
                        && attempts > 5;
                db.update("outbox", Db.cv("attempts", attempts, "next_try", System.currentTimeMillis() + backoff,
                        "last_error", e.getMessage(), "status", permanent ? "failed" : "pending"), "id=?", String.valueOf(id));
                db.kvSet("tg_last_error", e.getMessage());
                db.kvSet("tg_last_error_at", System.currentTimeMillis());
                if (attempts == 1 || attempts % 5 == 0) db.log("W", "telegram", "Senden fehlgeschlagen (Versuch " + attempts + "): " + e.getMessage());
                break; // network is probably down; retry later in order
            }
        }
        ContentValues next = db.one("SELECT MIN(next_try) t FROM outbox WHERE status='pending'");
        if (next == null || next.getAsLong("t") == null) return -1;
        return Math.max(1000, next.getAsLong("t") - System.currentTimeMillis());
    }
}
