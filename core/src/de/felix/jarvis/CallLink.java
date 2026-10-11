package de.felix.jarvis;

import android.content.Context;

import de.felix.jarvis.shared.Link;

import org.json.JSONObject;

import java.security.KeyPair;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Jarvis' side of the call app link (user-approved replacement for real phone calls, 2026-10-10).
 * Audio never leaves the user's phone: the call app does speech recognition (Groq Whisper) and speech
 * output; this side receives the recognised text, thinks, and streams the answer back sentence by sentence.
 */
public final class CallLink {
    private static CallLink instance;

    public static synchronized CallLink get(Context c) {
        if (instance == null) instance = new CallLink(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Db db;
    private final Link.Seen seen = new Link.Seen();
    private final Link.Assembler assembler = new Link.Assembler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String activeCall;      // call id of the running conversation
    private volatile long ringStarted;       // outgoing ring waiting for an answer
    private volatile String ringCall, ringReason;
    private volatile boolean ringAcked;
    private volatile long callStarted;

    private CallLink(Context c) {
        ctx = c;
        db = Db.get(c);
    }

    private String server() { return db.kv("link_server", Link.DEFAULT_SERVER); }

    public boolean paired() { return Secrets.has(ctx, Secrets.LINK_SESSION) && db.kv("link_topic_a", null) != null; }

    private byte[] key() {
        String k = Secrets.get(ctx, Secrets.LINK_SESSION);
        return k == null ? null : Link.unb64(k);
    }

    // ---------------------------------------------------------------- pairing

    /** A one-time code for the call app (pasted there). Re-pairing replaces the old phone. */
    public String newPairingCode() throws Exception {
        KeyPair kp = Link.newKeyPair();
        String a = "jv-" + Link.randomId(18), b = "jv-" + Link.randomId(18), token = Link.randomId(16);
        Secrets.put(ctx, Secrets.LINK_PRIVATE, Link.priv(kp));
        Secrets.put(ctx, Secrets.LINK_SESSION, null);
        db.kvSet("link_topic_a", a);
        db.kvSet("link_topic_b", b);
        db.kvSet("link_pair_token", token);
        db.kvSet("link_pair_expires", System.currentTimeMillis() + 60 * 60_000L);
        db.kvSet("link_since", "30s");
        JSONObject code = new JSONObject().put("a", a).put("b", b).put("k", Link.pub(kp)).put("t", token).put("s", server());
        db.log("I", "anruf-app", "Neuer Kopplungscode erzeugt (1 h gültig)");
        restartSubscriber();
        return "JARVIS1." + Link.b64(code.toString().getBytes("UTF-8"));
    }

    private volatile boolean restart;

    private void restartSubscriber() { restart = true; }

    // ---------------------------------------------------------------- receiving

    public void runSubscriber(BooleanSupplier alive) throws Exception {
        String topic = db.kv("link_topic_a", null);
        if (topic == null) { android.os.SystemClock.sleep(10_000); return; }
        restart = false;
        String since = db.kv("link_since", "30s");
        String last = Link.subscribe(server(), topic, since, new Link.Listener() {
            @Override public void onOpen() { db.kvSet("link_connected_at", System.currentTimeMillis()); }

            @Override public void onMessage(String id, String body) {
                db.kvSet("link_since", id);
                try { handle(topic, body); } catch (Exception e) { db.log("W", "anruf-app", "Nachricht nicht verarbeitet: " + e.getMessage()); }
            }
        }, () -> alive.getAsBoolean() && !restart);
        db.kvSet("link_since", last);
    }

    private void handle(String topic, String body) throws Exception {
        JSONObject plain = null;
        try { plain = new JSONObject(body); } catch (Exception ignored) {}
        if (plain != null && "hello".equals(plain.optString("t"))) { pairHello(plain); return; }
        byte[] k = key();
        if (k == null) return;
        JSONObject m = Link.open(k, topic, body);
        if (m == null || !seen.accept(m, 5 * 60_000L)) return;
        db.kvSet("link_last_seen", System.currentTimeMillis());
        String call = m.optString("call", "");
        switch (m.getString("t")) {
            case "ping":
                db.kvSet("link_peer_status", m.optString("status", "ok"));
                break;
            case "call_start": // the Boss calls Jarvis
                activeCall = call;
                callStarted = System.currentTimeMillis();
                Agent.callStartedAt = callStarted;
                db.insert("calls", Db.cv("ts", callStarted, "direction", "in", "reason", "Anruf vom Boss", "outcome", "connected"));
                db.log("I", "anruf-app", "Eingehender Anruf vom Boss" + (m.optBoolean("live") ? " (Gemini Live)" : ""));
                if (m.optBoolean("live") && Secrets.has(ctx, Secrets.GEMINI)) worker.submit(() -> sendLiveConfig(call, null));
                else say(call, greeting(), true);
                break;
            case "live_failed": // phone could not reach Gemini: classic voice chain takes over
                db.log("W", "anruf-app", "Gemini Live nicht verfügbar (" + m.optString("why") + ") – Ersatzstimme");
                if (call.equals(activeCall)) {
                    String reason = m.optString("reason", "");
                    if (reason.isEmpty()) say(call, greeting(), true); else worker.submit(() -> openingTurn(call, reason));
                }
                break;
            case "tool": // Gemini on the phone wants Jarvis to do something
                if (!call.equals(activeCall)) break;
                worker.submit(() -> {
                    String result;
                    try {
                        result = Brain.get(ctx).agent.run(m.getString("name"), new JSONObject(m.optString("args", "{}")).put("_quick", true));
                    } catch (Exception e) {
                        result = "Fehler: " + e.getMessage();
                    }
                    db.log("I", "werkzeug", "(Telefon) " + m.optString("name") + " → " + SchoolPipeline.shorten(result, 100));
                    try {
                        sendLarge(Link.msg("tool_result").put("call", call).put("tid", m.getString("tid")), result);
                    } catch (Exception e) {
                        db.log("W", "anruf-app", "Werkzeugergebnis nicht gesendet: " + e.getMessage());
                    }
                });
                break;
            case "transcript": { // what was said in a Gemini Live call -> Jarvis' memory
                String packed = assembler.add(m.optString("group"), m.optInt("i"), m.optInt("n"), m.optString("chunk"));
                if (packed != null) {
                    String t = Link.unpack(packed);
                    for (String line : t.split("\n")) {
                        if (line.startsWith("F: ")) db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "call", "role", "user", "content", line.substring(3)));
                        else if (line.startsWith("J: ")) db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "call", "role", "assistant", "content", line.substring(3)));
                    }
                    db.log("I", "anruf-app", "Gesprächsprotokoll übernommen (" + t.split("\n").length + " Zeilen)");
                }
                break;
            }
            case "crash":
                db.log("E", "anruf-app", "Absturz der Anruf-App: " + m.optString("trace").replace("\n", " ⏎ "));
                db.kvSet("call_app_crash", m.optString("trace"));
                break;
            case "need_keys":
                db.log("I", "anruf-app", "Anruf-App hat die Schlüssel angefordert – gesendet");
                pushKey();
                break;
            case "stats":
                db.kvSet("live_stats", m.optString("text"));
                break;
            case "ringing":
                if (call.equals(ringCall)) ringAcked = true;
                break;
            case "answered":
                if (call.equals(ringCall)) {
                    activeCall = call;
                    callStarted = System.currentTimeMillis();
                    db.getWritableDatabase().execSQL("UPDATE calls SET outcome='answered' WHERE id=(SELECT MAX(id) FROM calls WHERE direction='out')");
                    String reason = ringReason;
                    ringCall = null;
                    if (m.optBoolean("live") && m.optBoolean("has_config")) db.log("I", "anruf-app", "Gemini startet mit vorbereiteter Konfiguration");
                    else if (m.optBoolean("live") && Secrets.has(ctx, Secrets.GEMINI)) worker.submit(() -> sendLiveConfig(call, reason));
                    else worker.submit(() -> openingTurn(call, reason));
                }
                break;
            case "declined":
                if (call.equals(ringCall)) missed("abgelehnt");
                break;
            case "utterance":
                if (!call.equals(activeCall)) break;
                String text = m.optString("text", "").trim();
                if (!text.isEmpty()) worker.submit(() -> turn(call, text));
                break;
            case "hangup":
                if (call.equals(activeCall)) {
                    long dur = (System.currentTimeMillis() - callStarted) / 1000;
                    db.getWritableDatabase().execSQL("UPDATE calls SET duration=? WHERE id=(SELECT MAX(id) FROM calls)", new Object[]{dur});
                    db.log("I", "anruf-app", "Gespräch beendet (" + dur + " s)");
                    activeCall = null;
                }
                if (call.equals(ringCall)) missed("aufgelegt");
                break;
            default:
                break;
        }
    }

    private void pairHello(JSONObject h) throws Exception {
        String token = db.kv("link_pair_token", null);
        if (token == null || System.currentTimeMillis() > db.kvLong("link_pair_expires", 0)) return;
        byte[] k = Link.sharedKey(Secrets.get(ctx, Secrets.LINK_PRIVATE), h.getString("pub"), Link.unb64(token));
        if (!Link.proof(k, "hello:" + token).equals(h.optString("proof"))) {
            db.log("W", "anruf-app", "Ungültige Kopplungsanfrage verworfen");
            return;
        }
        Secrets.put(ctx, Secrets.LINK_SESSION, Link.b64(k));
        db.kvSet("link_pair_token", null);
        db.kvSet("link_paired_at", System.currentTimeMillis());
        db.kvSet("link_peer_name", h.optString("device", "Telefon"));
        db.log("I", "anruf-app", "Anruf-App gekoppelt: " + h.optString("device", "Telefon"));
        JSONObject w = Link.msg("welcome").put("groq", Secrets.get(ctx, Secrets.GROQ) == null ? "" : Secrets.get(ctx, Secrets.GROQ))
                .put("gemini", Secrets.get(ctx, Secrets.GEMINI) == null ? "" : Secrets.get(ctx, Secrets.GEMINI));
        send(w);
        Outbox.enqueue(ctx, "linkpaired:" + db.kvLong("link_paired_at", 0), "Die Anruf-App auf deinem " + h.optString("device", "Telefon")
                + " ist gekoppelt. Du kannst mich jetzt dort anrufen.", null, null);
    }

    /** Re-sends the speech key after it was changed in the settings. */
    public void pushKey() {
        if (!paired()) return;
        try {
            send(Link.msg("welcome").put("groq", nz(Secrets.get(ctx, Secrets.GROQ))).put("gemini", nz(Secrets.get(ctx, Secrets.GEMINI))));
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- conversation

    /** Everything the voice model on the phone needs: who Jarvis is, what is going on, which tools exist. */
    String liveSystem(String reason) {
        Agent a = Brain.get(ctx).agent;
        StringBuilder b = new StringBuilder(Agent.persona("call"));
        b.append("\n\nDu sprichst direkt mit " + Cfg.user() + " am Telefon (Deutsch, natürliche Sprechweise, kurze Sätze, du darfst ihn unterbrechen lassen). ")
                .append("Für Aktionen und aktuelle Daten nutzt du die Werkzeuge; was unten steht, weißt du schon.\n");
        try {
            java.time.LocalDate d = java.time.LocalDate.now(Cfg.ZONE);
            if (Cfg.school(ctx)) {
            b.append("\nSchultermine (nächste 21 Tage):\n").append(a.run("school_overview", new JSONObject().put("days", 21)));
            // timetable of today and the next school day, so the voice model answers without a tool call
            java.time.LocalDate next = d.plusDays(1);
            while (next.getDayOfWeek().getValue() > 5) next = next.plusDays(1);
            b.append("\n\nStundenplan heute und nächster Schultag (").append(next).append("):\n").append(a.timetable(d.toString(), next.toString()));
            }
            b.append("\n\nKalender heute und morgen:\n").append(a.run("calendar_events", new JSONObject().put("from_date", d.toString()).put("to_date", d.plusDays(1).toString())));
        } catch (Exception ignored) {}
        if (Cfg.school(ctx)) b.append("\n\nOffene Hausaufgaben (nächste 3 Tage):\n").append(new Homework(ctx).openSummary(3));
        b.append(a.contextForLive());
        if (reason != null) b.append("\n\nDU rufst " + Cfg.user() + " gerade an. Grund: ").append(reason).append(". Er hat abgenommen – eröffne kurz und nenne den Grund.");
        return b.toString();
    }

    private void sendLiveConfig(String call, String reason) {
        long t0 = System.currentTimeMillis();
        try {
            JSONObject cfg = new JSONObject().put("system", liveSystem(reason))
                    .put("tools", de.felix.jarvis.shared.GeminiLive.declarations(Brain.get(ctx).agent.toolsFor("call")))
                    .put("model", Cfg.get(ctx, "live_model")).put("voice", Cfg.get(ctx, "live_voice"))
                    .put("opening", reason == null ? "" + Cfg.user() + " ruft dich an und hat gerade verbunden. Begrüße ihn kurz und locker."
                            : "" + Cfg.user() + " hat abgenommen. Eröffne jetzt.");
            sendLarge(Link.msg("live_config").put("call", call), cfg.toString());
            db.kvSet("live_config_ms", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            db.log("W", "anruf-app", "Live-Konfiguration nicht gesendet: " + e.getMessage());
            if (reason == null) say(call, greeting(), true); else openingTurn(call, reason);
        }
    }

    /** Sends a long text as one or more compressed parts. */
    private void sendLarge(JSONObject head, String payload) throws Exception {
        java.util.List<String> parts = Link.parts(Link.pack(payload), 2600);
        String group = Link.randomId(6);
        for (int i = 0; i < parts.size(); i++) {
            JSONObject m = i == 0 ? head : Link.msg(head.getString("t")).put("call", head.optString("call"));
            for (java.util.Iterator<String> k = head.keys(); k.hasNext(); ) { String key = k.next(); if (!m.has(key)) m.put(key, head.get(key)); }
            send(m.put("group", group).put("i", i).put("n", parts.size()).put("chunk", parts.get(i)));
        }
    }

    static String nz(String s) { return s == null ? "" : s; }

    private String greeting() {
        int h = Cfg.now().getHour();
        String[] g = h < 10 ? new String[]{"Morgen, Boss. Was gibt's?", "Jarvis hier. Was kann ich tun?"}
                : h < 18 ? new String[]{"Jarvis hier. Was gibt's?", "Ja, Boss?"}
                : new String[]{"Jarvis hier. Was liegt an?", "Ja, Boss? Was gibt's?"};
        return g[(int) (System.currentTimeMillis() / 1000 % g.length)];
    }

    private void openingTurn(String call, String reason) {
        Agent.callStartedAt = System.currentTimeMillis();
        if (!Brain.get(ctx).llm.usable() && !new ClaudeBridge(ctx).enabled()) { say(call, reason, true); return; }
        try {
            String opener = Brain.get(ctx).agent.respond("call", null, null,
                    "Du rufst " + Cfg.user() + " gerade an, er hat abgenommen. Grund des Anrufs: " + reason + ". Eröffne kurz und nenne den Grund.");
            say(call, opener, true);
        } catch (Exception e) {
            say(call, "Boss, kurz zur Info: " + reason, true);
        }
    }

    private void turn(String call, String text) {
        final StringBuilder buf = new StringBuilder();
        final boolean[] firstSent = {false};
        long t0 = System.currentTimeMillis();
        // commands are answered only after the action is verified (nothing is spoken that might be retracted)
        boolean command = text.toLowerCase(java.util.Locale.GERMAN).matches("(?s).*\\b(erinner|trag|notier|speicher|setz|lösch|leg|plan|erstell|schreib|ruf|merk|änder|verschieb|sag ab)\\w*.*");
        try {
            if (command) {
                say(call, Brain.get(ctx).agent.respond("call", text, null), true);
                db.kvSet("call_turn_ms", System.currentTimeMillis() - t0);
                return;
            }
            String full = Brain.get(ctx).agent.respond("call", text, delta -> {
                buf.append(delta);
                // send the first sentence as soon as it is complete, the rest in one go
                if (!firstSent[0]) {
                    int end = sentenceEnd(buf);
                    if (end > 12) {
                        say(call, buf.substring(0, end + 1).trim(), false);
                        db.kvSet("call_first_sentence_ms", System.currentTimeMillis() - t0);
                        buf.delete(0, end + 1);
                        firstSent[0] = true;
                    }
                }
            });
            String rest = firstSent[0] ? buf.toString().trim() : full;
            say(call, rest, true);
            db.kvSet("call_turn_ms", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            say(call, "Da hakt gerade etwas bei mir. Versuch es bitte nochmal.", true);
        }
    }

    private static int sentenceEnd(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < s.length() && s.charAt(i + 1) == ' ' && !(i > 0 && Character.isDigit(s.charAt(i - 1)))) return i;
        }
        return -1;
    }

    private void say(String call, String text, boolean last) {
        try {
            send(Link.msg("say").put("call", call).put("text", text).put("final", last));
        } catch (Exception e) {
            db.log("W", "anruf-app", "Antwort nicht gesendet: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- outgoing calls

    void ring(String reason) {
        try {
            ringCall = Link.randomId(9);
            ringReason = reason;
            ringStarted = System.currentTimeMillis();
            ringAcked = false;
            send(Link.msg("ring").put("call", ringCall).put("reason", reason));
            db.log("I", "anruf-app", "Rufe den Boss an: " + reason);
            // build the voice setup while it rings, so Gemini can start the moment the user picks up
            String c = ringCall;
            if (Secrets.has(ctx, Secrets.GEMINI)) worker.submit(() -> sendLiveConfig(c, reason));
        } catch (Exception e) {
            missed("Anruf-App nicht erreichbar (" + e.getMessage() + ")");
        }
    }

    private void missed(String why) {
        String reason = ringReason;
        String call = ringCall;
        ringCall = null;
        if (reason == null) return;
        db.getWritableDatabase().execSQL("UPDATE calls SET outcome=? WHERE id=(SELECT MAX(id) FROM calls WHERE direction='out')", new Object[]{why});
        db.log("I", "anruf-app", "Anruf nicht angenommen (" + why + ") – schreibe stattdessen");
        try { if (call != null) send(Link.msg("cancel").put("call", call)); } catch (Exception ignored) {}
        if (reason.startsWith("Weckanruf")) return; // the morning briefing follows in writing anyway, the retry is handled by Routines
        Outbox.enqueue(ctx, "missed:" + call, "Ich habe dich nicht erreicht (" + why + "). Worum es geht: " + reason, null, null);
    }

    /** Called from the main loop: ring timeouts and daily keep-alive bookkeeping. */
    public void maintenance() {
        if (ringCall != null) {
            long age = System.currentTimeMillis() - ringStarted;
            if (!ringAcked && age > 20_000) missed("Anruf-App hat nicht reagiert");
            else if (age > 50_000) missed("nicht abgenommen");
        }
    }

    private void send(JSONObject m) throws Exception {
        byte[] k = key();
        String topic = db.kv("link_topic_b", null);
        if (k == null || topic == null) throw new IllegalStateException("nicht gekoppelt");
        Link.publish(server(), topic, Link.seal(k, topic, m));
        String day = LocalDate.now(Cfg.ZONE).toString();
        if (!day.equals(db.kv("ntfy_day", ""))) { db.kvSet("ntfy_day", day); db.kvSet("ntfy_count", 0); }
        db.kvSet("ntfy_count", db.kvLong("ntfy_count", 0) + 1);
    }

    public boolean inCall() { return activeCall != null; }
}
