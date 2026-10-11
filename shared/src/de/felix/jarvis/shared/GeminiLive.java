package de.felix.jarvis.shared;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Base64;

/**
 * Gemini Live API session (native audio): 16 kHz PCM in, 24 kHz PCM out, server-side turn detection with
 * barge-in, transcriptions both ways, function calls, and session resumption across the server's
 * periodic reconnects (goAway). Free tier of the Gemini API (key from aistudio.google.com, no billing).
 */
public final class GeminiLive implements WsClient.Listener {
    public static final String ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    public interface Events {
        void onReady();
        void onAudio(byte[] pcm24k);
        void onInterrupted();
        void onTurnComplete();
        void onInputText(String chunk);
        void onOutputText(String chunk);
        void onToolCall(String id, String name, JSONObject args);
        void onClosed(String reason);
    }

    private final String key, model, voice, system;
    private final JSONArray functions;
    private final Events ev;
    private volatile WsClient ws;
    private volatile String resumeHandle;
    private volatile boolean ready, closing, reconnecting;
    private final Object readyLock = new Object();
    public volatile String lastError;
    /** last protocol events, for diagnostics */
    public final java.util.Deque<String> trace = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private void trace(String t) {
        trace.addLast(System.currentTimeMillis() % 100000 + " " + (t.length() > 300 ? t.substring(0, 300) : t));
        while (trace.size() > 30) trace.pollFirst();
    }

    public GeminiLive(String key, String model, String voice, String system, JSONArray functions, Events ev) {
        this.key = key;
        this.model = model;
        this.voice = voice;
        this.system = system;
        this.functions = functions;
        this.ev = ev;
    }

    /** Connects and waits (max timeoutMs) until the server confirmed the setup. */
    public void open(long timeoutMs) throws Exception {
        ready = false;
        // documented way for current AI-Studio keys ("AQ." auth keys): header x-goog-api-key; older keys also work as ?key=
        try {
            ws = WsClient.connect(ENDPOINT, java.util.Collections.singletonMap("x-goog-api-key", key), this);
        } catch (IOException e) {
            if (!String.valueOf(e.getMessage()).contains(" 40")) throw e;
            ws = WsClient.connect(ENDPOINT + "?key=" + java.net.URLEncoder.encode(key, "UTF-8"), this);
        }
        JSONObject gen = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", new JSONObject().put("languageCode", "de-DE").put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig",
                        new JSONObject().put("voiceName", voice))));
        JSONObject setup = new JSONObject().put("model", "models/" + model).put("generationConfig", gen)
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("inputAudioTranscription", new JSONObject()).put("outputAudioTranscription", new JSONObject())
                .put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()))
                .put("sessionResumption", resumeHandle == null ? new JSONObject() : new JSONObject().put("handle", resumeHandle));
        if (functions != null && functions.length() > 0) setup.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", functions)));
        ws.send(new JSONObject().put("setup", setup).toString());
        long until = System.currentTimeMillis() + timeoutMs;
        synchronized (readyLock) {
            while (!ready && ws.isOpen() && System.currentTimeMillis() < until) readyLock.wait(Math.max(1, until - System.currentTimeMillis()));
        }
        if (!ready) {
            String why = lastError != null ? lastError : ws.isOpen() ? "keine Bestätigung" : "Verbindung abgewiesen";
            close();
            throw new IOException("Gemini Live: " + why);
        }
    }

    public boolean isReady() { return ready && ws != null && ws.isOpen(); }

    public void sendAudio(byte[] pcm16k, int len) {
        if (!isReady()) return;
        try {
            String b64 = Base64.getEncoder().encodeToString(len == pcm16k.length ? pcm16k : java.util.Arrays.copyOf(pcm16k, len));
            ws.send("{\"realtimeInput\":{\"audio\":{\"data\":\"" + b64 + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}");
        } catch (IOException e) {
            lastError = e.getMessage();
        }
    }

    /** A text turn (e.g. "the user hat abgenommen"), answered immediately. */
    public void sendText(String text) throws Exception {
        ws.send(new JSONObject().put("clientContent", new JSONObject().put("turnComplete", true)
                .put("turns", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", text)))))).toString());
    }

    public void sendToolResponse(String id, String name, String result) throws Exception {
        trace("-> toolResponse " + id + " " + name);
        ws.send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", new JSONArray().put(
                new JSONObject().put("id", id).put("name", name).put("response", new JSONObject().put("result", result))))).toString());
    }

    public void close() {
        closing = true;
        ready = false;
        if (ws != null) ws.close();
    }

    // ---------------------------------------------------------------- server messages

    @Override public void onMessage(String text) {
        try {
            handle(new JSONObject(text));
        } catch (Exception e) {
            lastError = "Antwort nicht lesbar: " + e.getMessage();
        }
    }

    private void handle(JSONObject m) throws Exception {
        String keys = m.keys().hasNext() ? m.names().toString() : "{}";
        trace("<- " + (m.has("serverContent") || m.has("setupComplete") ? keys : m.toString()));
        if (m.has("setupComplete")) {
            synchronized (readyLock) { ready = true; readyLock.notifyAll(); }
            if (!reconnecting) ev.onReady();
            reconnecting = false;
            return;
        }
        if (m.has("error")) lastError = m.optJSONObject("error") != null ? m.getJSONObject("error").optString("message") : m.optString("error");
        JSONObject sc = m.optJSONObject("serverContent");
        if (sc != null) {
            if (sc.optBoolean("interrupted")) ev.onInterrupted();
            JSONObject turn = sc.optJSONObject("modelTurn");
            if (turn != null) {
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) for (int i = 0; i < parts.length(); i++) {
                    JSONObject d = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (d != null && d.optString("mimeType").startsWith("audio/")) ev.onAudio(Base64.getDecoder().decode(d.getString("data")));
                }
            }
            JSONObject it = sc.optJSONObject("inputTranscription");
            if (it != null && !it.optString("text").isEmpty()) ev.onInputText(it.getString("text"));
            JSONObject ot = sc.optJSONObject("outputTranscription");
            if (ot != null && !ot.optString("text").isEmpty()) ev.onOutputText(ot.getString("text"));
            if (sc.optBoolean("turnComplete")) ev.onTurnComplete();
        }
        JSONObject tc = m.optJSONObject("toolCall");
        if (tc != null) {
            JSONArray calls = tc.optJSONArray("functionCalls");
            if (calls != null) for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.getJSONObject(i);
                ev.onToolCall(c.optString("id"), c.optString("name"), c.optJSONObject("args") == null ? new JSONObject() : c.getJSONObject("args"));
            }
        }
        JSONObject sr = m.optJSONObject("sessionResumptionUpdate");
        if (sr != null && sr.optBoolean("resumable") && !sr.optString("newHandle").isEmpty()) resumeHandle = sr.getString("newHandle");
        if (m.has("goAway")) reconnect();
    }

    /** The server ends sessions after a while; continue seamlessly with the resumption handle. */
    private void reconnect() {
        if (closing || resumeHandle == null) return;
        reconnecting = true;
        new Thread(() -> {
            try {
                WsClient old = ws;
                open(8000);
                old.close();
            } catch (Exception e) {
                lastError = "Wiederverbinden: " + e.getMessage();
            }
        }, "gemini-resume").start();
    }

    @Override public void onClosed(int code, String reason) {
        trace("closed " + code + " " + reason);
        if (closing) { synchronized (readyLock) { readyLock.notifyAll(); } return; }
        if (code != 1000 && reason != null && !reason.isEmpty()) lastError = "Code " + code + ": " + reason;
        synchronized (readyLock) { readyLock.notifyAll(); }
        if (!closing && !reconnecting) ev.onClosed(lastError == null ? "Verbindung beendet (" + code + ")" : lastError);
    }

    /** OpenAI-style tool definitions -> Gemini function declarations. */
    public static JSONArray declarations(JSONArray openAiTools) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < openAiTools.length(); i++) {
            JSONObject f = openAiTools.getJSONObject(i).getJSONObject("function");
            JSONObject d = new JSONObject().put("name", f.getString("name")).put("description", f.optString("description"));
            JSONObject p = f.optJSONObject("parameters");
            if (p != null && p.optJSONObject("properties") != null && p.getJSONObject("properties").length() > 0) d.put("parameters", upper(p));
            out.put(d);
        }
        return out;
    }

    private static JSONObject upper(JSONObject schema) throws Exception {
        JSONObject r = new JSONObject();
        for (java.util.Iterator<String> k = schema.keys(); k.hasNext(); ) {
            String key = k.next();
            Object v = schema.get(key);
            if (key.equals("type") && v instanceof String) r.put("type", ((String) v).toUpperCase(java.util.Locale.ROOT));
            else if (key.equals("properties")) {
                JSONObject props = new JSONObject();
                for (java.util.Iterator<String> pk = ((JSONObject) v).keys(); pk.hasNext(); ) { String n = pk.next(); props.put(n, upper(((JSONObject) v).getJSONObject(n))); }
                r.put("properties", props);
            } else r.put(key, v);
        }
        return r;
    }
}
