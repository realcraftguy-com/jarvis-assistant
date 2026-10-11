package de.felix.jarvis;

import android.content.Context;

import de.felix.jarvis.shared.Link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyPair;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Link to the PC agent (jarvis-pc.jar on the user's computer): status reports, alerts and a fixed set of actions
 * (shutdown, reboot, suspend, cancel, lock, notify, volume, mute). Same encryption as the call app.
 */
public final class PcLink {
    private static PcLink instance;
    private final Context ctx;
    private final Db db;
    private final Link.Seen seen = new Link.Seen();
    private final Map<String, java.util.concurrent.CompletableFuture<JSONObject>> waiting = new ConcurrentHashMap<>();
    private volatile boolean restart;

    static final String SESSION = "pc_session", PRIVATE = "pc_private";

    public static synchronized PcLink get(Context c) {
        if (instance == null) instance = new PcLink(c.getApplicationContext());
        return instance;
    }

    private PcLink(Context c) {
        ctx = c;
        db = Db.get(c);
    }

    private String server() { return db.kv("link_server", Link.DEFAULT_SERVER); }

    public boolean paired() { return Secrets.has(ctx, SESSION) && db.kv("pc_topic_a", null) != null; }

    /** "PC is on": a heartbeat arrived within the last 40 minutes. */
    public boolean online() { return paired() && System.currentTimeMillis() - db.kvLong("pc_seen_at", 0) < 40 * 60_000L; }

    // ---------------------------------------------------------------- pairing

    public String newPairingCode() throws Exception {
        KeyPair kp = Link.newKeyPair();
        String a = "jp-" + Link.randomId(18), b = "jp-" + Link.randomId(18), token = Link.randomId(16);
        Secrets.put(ctx, PRIVATE, Link.priv(kp));
        Secrets.put(ctx, SESSION, null);
        db.kvSet("pc_topic_a", a);
        db.kvSet("pc_topic_b", b);
        db.kvSet("pc_pair_token", token);
        db.kvSet("pc_pair_expires", System.currentTimeMillis() + 60 * 60_000L);
        db.kvSet("pc_since", "30s");
        restart = true;
        db.log("I", "pc", "Neuer PC-Kopplungscode erzeugt (1 h gültig)");
        JSONObject code = new JSONObject().put("a", a).put("b", b).put("k", Link.pub(kp)).put("t", token).put("s", server());
        return "JARVISPC1." + Link.b64(code.toString().getBytes("UTF-8"));
    }

    // ---------------------------------------------------------------- receiving

    public void runSubscriber(BooleanSupplier alive) throws Exception {
        String topic = db.kv("pc_topic_a", null);
        if (topic == null) { android.os.SystemClock.sleep(15_000); return; }
        restart = false;
        String last = Link.subscribe(server(), topic, db.kv("pc_since", "30s"), new Link.Listener() {
            @Override public void onMessage(String id, String body) {
                db.kvSet("pc_since", id);
                try { handle(topic, body); } catch (Exception e) { db.log("W", "pc", "Nachricht nicht verarbeitet: " + e.getMessage()); }
            }
        }, () -> alive.getAsBoolean() && !restart);
        db.kvSet("pc_since", last);
    }

    private void handle(String topic, String body) throws Exception {
        JSONObject plain = null;
        try { plain = new JSONObject(body); } catch (Exception ignored) {}
        if (plain != null && "hello".equals(plain.optString("t"))) { hello(plain); return; }
        String k = Secrets.get(ctx, SESSION);
        if (k == null) return;
        JSONObject m = Link.open(Link.unb64(k), topic, body);
        if (m == null || !seen.accept(m, 10 * 60_000L)) return;
        db.kvSet("pc_seen_at", System.currentTimeMillis());
        java.util.concurrent.CompletableFuture<JSONObject> f = m.has("rid") ? waiting.remove(m.optString("rid")) : null;
        switch (m.optString("t")) {
            case "pc_status":
                db.kvSet("pc_status", m.getJSONObject("status").toString());
                if ("start".equals(m.optString("reason"))) db.log("I", "pc", "PC ist an (" + m.getJSONObject("status").optString("host") + ")");
                break;
            case "pc_alert": {
                String text = "Dein PC: " + m.optString("text");
                db.log("I", "pc", text);
                String kill = m.optString("kill", "");
                Outbox.enqueue(ctx, "pcalert:" + m.optString("text").replaceAll("\\d", "").hashCode() + ":" + java.time.LocalDate.now(Cfg.ZONE),
                        kill.isEmpty() ? text : text + " Soll ich es beenden?",
                        kill.isEmpty() ? null : Telegram.buttons(java.util.Collections.singletonList(new String[]{"Beenden", "pc:kill:" + kill, "Lassen", "pc:keep"})), null);
                db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "text", "role", "assistant", "content", text));
                break;
            }
            default:
                break;
        }
        if (f != null) f.complete(m);
    }

    private void hello(JSONObject h) throws Exception {
        String token = db.kv("pc_pair_token", null);
        if (token == null || System.currentTimeMillis() > db.kvLong("pc_pair_expires", 0)) return;
        byte[] k = Link.sharedKey(Secrets.get(ctx, PRIVATE), h.getString("pub"), Link.unb64(token));
        if (!Link.proof(k, "hello:" + token).equals(h.optString("proof"))) { db.log("W", "pc", "Ungültige PC-Kopplung verworfen"); return; }
        Secrets.put(ctx, SESSION, Link.b64(k));
        db.kvSet("pc_pair_token", null);
        db.kvSet("pc_name", h.optString("device", "PC"));
        db.log("I", "pc", "PC gekoppelt: " + h.optString("device", "PC"));
        Outbox.enqueue(ctx, "pcpaired:" + System.currentTimeMillis(), "Dein PC „" + h.optString("device", "PC") + "“ ist gekoppelt. Ich behalte ihn im Auge.", null, null);
    }

    // ---------------------------------------------------------------- requests

    private JSONObject request(JSONObject m, int timeoutSec) throws Exception {
        String k = Secrets.get(ctx, SESSION), topic = db.kv("pc_topic_b", null);
        if (k == null || topic == null) throw new IllegalStateException("Kein PC gekoppelt.");
        String rid = Link.randomId(6);
        m.put("rid", rid);
        java.util.concurrent.CompletableFuture<JSONObject> f = new java.util.concurrent.CompletableFuture<>();
        waiting.put(rid, f);
        Link.publish(server(), topic, Link.seal(Link.unb64(k), topic, m));
        try {
            return f.get(timeoutSec, TimeUnit.SECONDS);
        } finally {
            waiting.remove(rid);
        }
    }

    /** Current state for Jarvis (fresh if the PC answers, otherwise the last heartbeat). */
    public String statusText() {
        if (!paired()) return "Kein PC gekoppelt (Jarvis-App → Einstellungen → PC).";
        try { request(Link.msg("pc_status_req"), 12); } catch (Exception ignored) {}
        String raw = db.kv("pc_status", null);
        long seen = db.kvLong("pc_seen_at", 0);
        if (raw == null) return "Vom PC kam noch kein Status.";
        try {
            JSONObject s = new JSONObject(raw);
            StringBuilder b = new StringBuilder();
            boolean on = System.currentTimeMillis() - seen < 40 * 60_000L;
            b.append("PC „").append(s.optString("host")).append("“: ").append(on ? "an" : "vermutlich aus (zuletzt " + Brain.fmt(seen) + ")")
                    .append(", läuft seit ").append(duration(s.optLong("uptime_min"))).append(".\n");
            b.append("CPU-Last ").append(s.optDouble("load1")).append(" bei ").append(s.optInt("cpus")).append(" Kernen, RAM ")
                    .append(s.optInt("ram_used_pct")).append(" % von ").append(s.optInt("ram_total_gb")).append(" GB, Temperatur ")
                    .append(s.optInt("temp_max_c")).append(" °C.\n");
            JSONArray d = s.optJSONArray("disks");
            if (d != null) for (int i = 0; i < d.length(); i++) {
                JSONObject x = d.getJSONObject(i);
                b.append("Speicher ").append(x.optString("path")).append(": ").append(x.optInt("used_pct")).append(" % belegt, ").append(x.optLong("free_gb")).append(" GB frei.\n");
            }
            if (s.has("battery_pct")) b.append("Akku ").append(s.optInt("battery_pct")).append(" % (").append(s.optString("charging")).append(").\n");
            if (s.has("updates")) b.append("Verfügbare Updates: ").append(s.optInt("updates")).append(".\n");
            JSONArray t = s.optJSONArray("top");
            if (t != null && t.length() > 0) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < t.length(); i++) l.add(t.getString(i));
                b.append("Größte Prozesse: ").append(String.join("; ", l)).append(".");
            }
            return b.toString().trim();
        } catch (Exception e) {
            return "PC-Status unlesbar: " + e.getMessage();
        }
    }

    static String duration(long min) {
        return min < 60 ? min + " min" : min < 1440 ? (min / 60) + " h " + (min % 60) + " min" : (min / 1440) + " Tagen";
    }

    /** One of the fixed actions. */
    public String action(String action, String value) {
        if (!paired()) return "Kein PC gekoppelt.";
        if (!action.matches("shutdown|reboot|suspend|cancel|lock|notify|volume|mute|kill|music")) return "Diese Aktion kann ich am PC nicht ausführen.";
        if (!online() && !"cancel".equals(action)) return "Der PC scheint aus zu sein (letztes Lebenszeichen " + Brain.fmt(db.kvLong("pc_seen_at", 0)) + ").";
        try {
            JSONObject r = request(Link.msg("pc_action").put("action", action).put("value", value == null ? "" : value), 15);
            db.log("I", "pc", "Aktion " + action + ": " + r.optString("result"));
            return r.optString("result", "erledigt");
        } catch (java.util.concurrent.TimeoutException e) {
            return "Der PC hat nicht geantwortet – vielleicht ist er aus oder offline.";
        } catch (Exception e) {
            return "Ging nicht: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- routine

    /** Late in the evening the PC is still on: ask once whether Jarvis should shut it down. */
    public void tick() {
        if (!online()) return;
        ZonedDateTime now = Cfg.now();
        String day = now.minusHours(6).toLocalDate().toString(); // the "evening" lasts until 06:00
        if (!Cfg.inQuietHours(ctx, now) || day.equals(db.kv("pc_night_asked", ""))) return;
        db.kvSet("pc_night_asked", day);
        String name = db.kv("pc_name", "PC");
        Outbox.enqueue(ctx, "pcnight:" + day, "Dein " + name + " läuft noch, und es ist " + now.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
                + ". Soll ich ihn ausschalten?", Telegram.buttons(java.util.Collections.singletonList(
                new String[]{"Ausschalten", "pc:shutdown", "Lass an", "pc:keep"})), null);
    }

    public String button(String data) {
        if ("pc:keep".equals(data)) return "Alles klar, ich lasse es so.";
        if (data.startsWith("pc:kill:")) return action("kill", data.substring(8));
        return action(data.substring(3), "");
    }
}
