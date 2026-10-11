package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

/**
 * Telegram Bot API: the written channel to the Boss (user-approved replacement for WhatsApp, 2026-10-10).
 * Only the paired Telegram user id may give commands; display names are never trusted.
 */
public final class Telegram {
    private static final String API = "https://api.telegram.org/bot";
    private final Context ctx;
    private final Db db;

    public Telegram(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
    }

    private String token() { return Secrets.get(ctx, Secrets.TELEGRAM); }

    public boolean configured() { return Secrets.has(ctx, Secrets.TELEGRAM); }

    public boolean paired() { return db.kv("tg_owner_user", null) != null; }

    private JSONObject call(String method, JSONObject params, int timeoutMs) throws Exception {
        String t = token();
        if (t == null) throw new IllegalStateException("kein Telegram-Token");
        Http.Resp r = Http.postJson(API + t + "/" + method, null, params.toString(), timeoutMs);
        JSONObject o;
        try { o = new JSONObject(r.body); } catch (Exception e) { throw new Exception("Telegram HTTP " + r.code); }
        if (!o.optBoolean("ok")) {
            int retry = o.optJSONObject("parameters") != null ? o.optJSONObject("parameters").optInt("retry_after", 0) : 0;
            throw new TgError(r.code, o.optString("description", "Fehler"), retry);
        }
        return o;
    }

    public static final class TgError extends Exception {
        public final int code, retryAfter;
        TgError(int code, String msg, int retryAfter) { super("Telegram " + code + ": " + msg); this.code = code; this.retryAfter = retryAfter; }
    }

    /** Verifies the token and remembers the bot's username. */
    public String verify() throws Exception {
        JSONObject me = call("getMe", new JSONObject(), 15000).getJSONObject("result");
        db.kvSet("tg_bot_username", me.optString("username"));
        return me.optString("username");
    }

    public String newPairingCode() {
        String code = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
        db.kvSet("tg_pair_code", code);
        db.kvSet("tg_pair_expires", System.currentTimeMillis() + 30 * 60 * 1000);
        return code;
    }

    // ---------------------------------------------------------------- sending

    /** Returns the Telegram message id. */
    /** Test hook: simulated network outage for sending. */
    public static volatile boolean simulateOutage;

    public long send(String text, String markupJson, Long replyTo) throws Exception {
        if (simulateOutage) throw new java.io.IOException("Netzwerk nicht erreichbar (simuliert)");
        String chat = db.kv("tg_owner_chat", null);
        if (chat == null) throw new IllegalStateException("Telegram noch nicht gekoppelt");
        JSONObject p = new JSONObject().put("chat_id", Long.parseLong(chat)).put("text", text)
                .put("link_preview_options", new JSONObject().put("is_disabled", true));
        if (markupJson != null) p.put("reply_markup", new JSONObject(markupJson));
        if (replyTo != null) p.put("reply_parameters", new JSONObject().put("message_id", replyTo).put("allow_sending_without_reply", true));
        return call("sendMessage", p, 20000).getJSONObject("result").getLong("message_id");
    }

    public void sendChatAction(String action) {
        try {
            String chat = db.kv("tg_owner_chat", null);
            if (chat != null) call("sendChatAction", new JSONObject().put("chat_id", Long.parseLong(chat)).put("action", action), 10000);
        } catch (Exception ignored) {}
    }

    public void clearButtons(long messageId) {
        try {
            String chat = db.kv("tg_owner_chat", null);
            call("editMessageReplyMarkup", new JSONObject().put("chat_id", Long.parseLong(chat)).put("message_id", messageId)
                    .put("reply_markup", new JSONObject().put("inline_keyboard", new JSONArray())), 10000);
        } catch (Exception ignored) {}
    }

    /** Sends a file (e.g. the call app APK) to the owner. */
    public void sendDocument(java.io.File f, String caption) throws Exception {
        String chat = db.kv("tg_owner_chat", null);
        if (chat == null) throw new IllegalStateException("Telegram noch nicht gekoppelt");
        String boundary = "----jarvis" + System.nanoTime();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(bo, true, "UTF-8");
        ps.print("--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + chat + "\r\n");
        ps.print("--" + boundary + "\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n" + caption + "\r\n");
        ps.print("--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + f.getName()
                + "\"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n");
        ps.flush();
        bo.write(java.nio.file.Files.readAllBytes(f.toPath()));
        ps.print("\r\n--" + boundary + "--\r\n");
        ps.flush();
        java.util.HashMap<String, String> h = new java.util.HashMap<>();
        h.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        Http.Resp r = Http.request("POST", API + token() + "/sendDocument", h, bo.toByteArray(), 120000);
        if (!r.ok()) throw new Exception("Telegram sendDocument HTTP " + r.code);
    }

    // ---------------------------------------------------------------- receiving

    public interface Handler {
        void onOwnerMessage(String text, long messageId, Long replyToMessageId);
        void onOwnerButton(String data, long messageId, String callbackId);
    }

    /** One long-poll round. Returns false if not configured. */
    public boolean poll(Handler h) throws Exception {
        if (!configured()) return false;
        long offset = db.kvLong("tg_offset", 0);
        JSONObject p = new JSONObject().put("offset", offset).put("timeout", 50)
                .put("allowed_updates", new JSONArray().put("message").put("callback_query"));
        JSONArray res = call("getUpdates", p, 65000).getJSONArray("result");
        for (int i = 0; i < res.length(); i++) {
            JSONObject u = res.getJSONObject(i);
            long id = u.getLong("update_id");
            // persist the offset first: a crash while handling must not replay a command
            db.kvSet("tg_offset", id + 1);
            try {
                handle(u, h);
            } catch (Exception e) {
                db.log("E", "telegram", "Update konnte nicht verarbeitet werden: " + e.getMessage());
            }
        }
        db.kvSet("tg_last_poll", System.currentTimeMillis());
        return true;
    }

    private void handle(JSONObject u, Handler h) throws Exception {
        String owner = db.kv("tg_owner_user", null);
        if (u.has("callback_query")) {
            JSONObject q = u.getJSONObject("callback_query");
            String from = String.valueOf(q.getJSONObject("from").getLong("id"));
            call("answerCallbackQuery", new JSONObject().put("callback_query_id", q.getString("id")), 10000);
            if (owner == null || !owner.equals(from)) { strangerLog(from); return; }
            long mid = q.has("message") ? q.getJSONObject("message").getLong("message_id") : 0;
            h.onOwnerButton(q.optString("data"), mid, q.getString("id"));
            return;
        }
        JSONObject m = u.optJSONObject("message");
        if (m == null || !m.has("from")) return;
        String from = String.valueOf(m.getJSONObject("from").getLong("id"));
        String chatType = m.getJSONObject("chat").optString("type");
        String text = m.optString("text", "").trim();

        if (owner == null) {
            // pairing: "/start 123456" from a private chat within 30 minutes
            String code = db.kv("tg_pair_code", null);
            long exp = db.kvLong("tg_pair_expires", 0);
            String sent = text.replace("/start", "").trim();
            if ("private".equals(chatType) && code != null && System.currentTimeMillis() < exp && sent.equals(code)) {
                db.kvSet("tg_owner_user", from);
                db.kvSet("tg_owner_chat", m.getJSONObject("chat").getLong("id"));
                db.kvSet("tg_pair_code", null);
                db.log("I", "telegram", "Telegram gekoppelt (Nutzer-ID verifiziert per Code)");
                Outbox.enqueue(ctx, "pair:" + from, "Verbindung steht, Boss. Ab jetzt schreibe ich dir hier. "
                        + "Befehle nehme ich nur von diesem Konto an.", null, null);
            } else {
                strangerLog(from);
            }
            return;
        }
        if (!owner.equals(from) || !"private".equals(chatType)) { strangerLog(from); return; }
        Long replyTo = m.has("reply_to_message") ? m.getJSONObject("reply_to_message").getLong("message_id") : null;
        JSONObject voice = m.optJSONObject("voice") != null ? m.optJSONObject("voice") : m.optJSONObject("audio") != null ? m.optJSONObject("audio") : m.optJSONObject("video_note");
        if (text.isEmpty() && voice != null) {
            // voice message: download and transcribe, then handle it like typed text
            sendChatAction("typing");
            try {
                if (voice.optInt("duration") > 300) throw new Exception("länger als 5 Minuten");
                String path = call("getFile", new JSONObject().put("file_id", voice.getString("file_id")), 20000).getJSONObject("result").getString("file_path");
                byte[] audio = Http.bytes("https://api.telegram.org/file/bot" + token() + "/" + path, 60000, 20 * 1024 * 1024);
                text = new Llm(ctx).transcribe(audio, path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path);
                db.log("I", "telegram", "Sprachnachricht verstanden (" + voice.optInt("duration") + " s)");
            } catch (Exception e) {
                db.log("W", "telegram", "Sprachnachricht: " + e.getMessage());
                Outbox.enqueue(ctx, "voicefail:" + m.getLong("message_id"), "Die Sprachnachricht konnte ich gerade nicht verstehen (" + e.getMessage() + "). Schreib's mir kurz?", null, null);
                return;
            }
            if (text.isEmpty()) { Outbox.enqueue(ctx, "voiceempty:" + m.getLong("message_id"), "In der Sprachnachricht war nichts zu verstehen.", null, null); return; }
        }
        if (text.isEmpty()) {
            Outbox.enqueue(ctx, "nontext:" + m.getLong("message_id"), "Ich kann hier Text und Sprachnachrichten lesen, Bilder und Dateien noch nicht.", null, null);
            return;
        }
        h.onOwnerMessage(text, m.getLong("message_id"), replyTo);
    }

    private void strangerLog(String from) {
        // never answer strangers; keep a short log entry without content
        String key = "tg_stranger_" + from;
        if (db.kv(key, null) == null) {
            db.kvSet(key, System.currentTimeMillis());
            db.log("W", "telegram", "Nachricht von nicht autorisiertem Telegram-Konto ignoriert");
        }
    }

    public static String buttons(List<String[]> rows) {
        try {
            JSONArray kb = new JSONArray();
            for (String[] r : rows) {
                JSONArray row = new JSONArray();
                for (int i = 0; i + 1 < r.length; i += 2) row.put(new JSONObject().put("text", r[i]).put("callback_data", r[i + 1]));
                kb.put(row);
            }
            return new JSONObject().put("inline_keyboard", kb).toString();
        } catch (Exception e) {
            return null;
        }
    }

    public ContentValues status() {
        ContentValues s = new ContentValues();
        s.put("configured", configured());
        s.put("paired", paired());
        s.put("bot", db.kv("tg_bot_username", ""));
        s.put("last_poll", db.kvLong("tg_last_poll", 0));
        return s;
    }
}
