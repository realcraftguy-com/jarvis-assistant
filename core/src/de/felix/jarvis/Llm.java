package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Groq (free tier) via the OpenAI-compatible chat API. No paid fallback exists.
 * Counts requests per day, honours rate-limit replies, and records latency for the status screen.
 */
public final class Llm {
    public static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    public static final class Unavailable extends Exception {
        public final long retryAtMs;
        Unavailable(String m, long retryAt) { super(m); retryAtMs = retryAt; }
    }

    public interface Stream { void onText(String delta); }

    private final Context ctx;
    private final Db db;

    public Llm(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
    }

    public boolean configured() { return Secrets.has(ctx, Secrets.GROQ); }

    /** Groq may answer (key present and the brain mode is not "nur-claude"). Speech-to-text uses the key regardless. */
    public boolean usable() { return configured() && !"nur-claude".equals(Cfg.brainMode(ctx)); }

    /** Speech to text (Groq Whisper, free tier), German. */
    public String transcribe(byte[] audio, String filename) throws Exception {
        // Telegram calls voice messages ".oga"; Whisper only accepts the usual extensions (ogg, opus, mp3, m4a, wav …)
        filename = filename.replaceAll("(?i)\\.oga$", ".ogg");
        if (!filename.contains(".")) filename += ".ogg";
        String boundary = "----jarvis" + System.nanoTime();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(bo, true, "UTF-8");
        for (String[] f : new String[][]{{"model", "whisper-large-v3-turbo"}, {"language", "de"}, {"response_format", "json"}})
            ps.print("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n" + f[1] + "\r\n");
        ps.print("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        ps.flush();
        bo.write(audio);
        ps.print("\r\n--" + boundary + "--\r\n");
        ps.flush();
        java.util.HashMap<String, String> h = new java.util.HashMap<>();
        h.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        h.put("Authorization", "Bearer " + Secrets.get(ctx, Secrets.GROQ));
        Http.Resp r = Http.request("POST", "https://api.groq.com/openai/v1/audio/transcriptions", h, bo.toByteArray(), 60000);
        if (!r.ok()) throw new Exception("Whisper HTTP " + r.code + " " + SchoolPipeline.shorten(r.body, 160));
        return new JSONObject(r.body).optString("text", "").trim();
    }

    private static String day() { return LocalDate.now(Cfg.ZONE).toString(); }

    public int usedToday() {
        ContentValues v = db.one("SELECT requests FROM llm_usage WHERE day=? AND provider='groq'", day());
        return v == null ? 0 : v.getAsInteger("requests");
    }

    /**
     * One chat completion. messages/tools in OpenAI format. Returns the assistant message object
     * (content and/or tool_calls). With a stream callback, text deltas are delivered as they arrive.
     */
    public JSONObject chat(JSONArray messages, JSONArray tools, boolean jsonMode, Stream stream, int maxTokens) throws Exception {
        if (!configured()) throw new Unavailable("kein Groq-Schlüssel eingetragen", 0);
        if (usedToday() >= Cfg.getInt(ctx, "llm_daily_budget")) throw new Unavailable("Tagesbudget für KI-Anfragen aufgebraucht", nextMidnight());

        // Groq limits apply per model: if one is rate-limited, the other one (own quota) answers
        String[] models = {Cfg.get(ctx, "llm_model"), Cfg.get(ctx, "llm_fallback_model")};
        Exception last = null;
        long soonest = Long.MAX_VALUE;
        for (String model : models) {
            long blocked = db.kvLong("llm_blocked_until:" + model, 0);
            if (System.currentTimeMillis() < blocked) { soonest = Math.min(soonest, blocked); continue; }
            try {
                return once(model, messages, tools, jsonMode, stream, maxTokens);
            } catch (Unavailable u) {
                if (u.retryAtMs == Long.MAX_VALUE) throw u; // invalid key
                soonest = Math.min(soonest, u.retryAtMs);
                last = u;
                db.log("W", "ki", "Modell " + model + ": " + u.getMessage() + " – versuche Ersatzmodell");
            } catch (Exception e) {
                last = e;
                db.log("W", "ki", "Modell " + model + " fehlgeschlagen: " + e.getMessage());
            }
        }
        if (last == null || last instanceof Unavailable) {
            db.kvSet("llm_blocked_until", soonest == Long.MAX_VALUE ? 0 : soonest);
            throw new Unavailable("Groq-Limit erreicht", soonest == Long.MAX_VALUE ? System.currentTimeMillis() + 60_000 : soonest);
        }
        throw last;
    }

    /** One request to a specific model (used by the model comparison). */
    public JSONObject chatModel(String model, JSONArray messages, JSONArray tools, int maxTokens) throws Exception {
        return once(model, messages, tools, false, null, maxTokens);
    }

    public java.util.List<String> models() throws Exception {
        Http.Resp r = Http.request("GET", "https://api.groq.com/openai/v1/models",
                java.util.Collections.singletonMap("Authorization", "Bearer " + Secrets.get(ctx, Secrets.GROQ)), null, 15000);
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray d = new JSONObject(r.body).getJSONArray("data");
        for (int i = 0; i < d.length(); i++) out.add(d.getJSONObject(i).getString("id"));
        return out;
    }

    private JSONObject once(String model, JSONArray messages, JSONArray tools, boolean jsonMode, Stream stream, int maxTokens) throws Exception {
        JSONObject body = new JSONObject().put("model", model).put("messages", messages)
                .put("temperature", 0.4).put("max_completion_tokens", maxTokens);
        if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low").put("include_reasoning", false);
        if (model.startsWith("qwen/")) body.put("reasoning_effort", "none");
        if (tools != null && tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto");
        if (jsonMode) body.put("response_format", new JSONObject().put("type", "json_object"));
        if (stream != null) body.put("stream", true);

        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + Secrets.get(ctx, Secrets.GROQ));
        c.setRequestProperty("Content-Type", "application/json");
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(b); }
        int code = c.getResponseCode();
        count(code < 400, code < 400 ? null : "HTTP " + code);
        String lt = c.getHeaderField("x-ratelimit-limit-tokens"), lr = c.getHeaderField("x-ratelimit-limit-requests");
        if (lt != null || lr != null) db.kvSet("llm_limits:" + model, "Anfragen/Tag " + lr + ", Token/Minute " + lt
                + ", übrig heute " + c.getHeaderField("x-ratelimit-remaining-requests") + ", übrig diese Minute " + c.getHeaderField("x-ratelimit-remaining-tokens"));
        if (code == 429) {
            String ra = c.getHeaderField("retry-after");
            long wait = 60_000;
            try { if (ra != null) wait = (long) (Double.parseDouble(ra) * 1000); } catch (Exception ignored) {}
            String err = Http.readAll(c.getErrorStream());
            boolean daily = err.contains("per day") || err.contains("RPD") || err.contains("TPD");
            long until = System.currentTimeMillis() + (daily ? Math.max(wait, 15 * 60_000L) : Math.max(wait, 5_000L));
            db.kvSet("llm_blocked_until:" + model, until);
            throw new Unavailable(daily ? "Groq-Tageslimit erreicht" : "Groq-Minutenlimit erreicht", until);
        }
        if (code == 401) throw new Unavailable("Groq-Schlüssel ungültig", Long.MAX_VALUE);
        if (code >= 400) {
            String err = c.getErrorStream() == null ? "" : Http.readAll(c.getErrorStream());
            throw new Exception("HTTP " + code + " " + err.substring(0, Math.min(200, err.length())));
        }
        JSONObject msg;
        long firstToken = -1;
        if (stream == null) {
            JSONObject resp = new JSONObject(Http.readAll(c.getInputStream()));
            msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONObject usage = resp.optJSONObject("usage");
            if (usage != null) { addTokens(usage.optInt("total_tokens")); db.kvSet("llm_last_prompt_tokens", usage.optInt("prompt_tokens")); }
        } else {
            // server-sent events: accumulate content and tool calls
            StringBuilder content = new StringBuilder();
            Map<Integer, JSONObject> calls = new HashMap<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                JSONObject ev = new JSONObject(data);
                JSONArray ch = ev.optJSONArray("choices");
                if (ev.has("x_groq") && ev.getJSONObject("x_groq").has("usage")) addTokens(ev.getJSONObject("x_groq").getJSONObject("usage").optInt("total_tokens"));
                if (ch == null || ch.length() == 0) continue;
                JSONObject delta = ch.getJSONObject(0).optJSONObject("delta");
                if (delta == null) continue;
                String piece = delta.optString("content", "");
                if (!piece.isEmpty() && !delta.isNull("content")) {
                    if (firstToken < 0) firstToken = System.currentTimeMillis() - t0;
                    content.append(piece);
                    stream.onText(piece);
                }
                JSONArray tc = delta.optJSONArray("tool_calls");
                if (tc != null) for (int i = 0; i < tc.length(); i++) {
                    JSONObject d = tc.getJSONObject(i);
                    int idx = d.optInt("index", 0);
                    JSONObject acc = calls.get(idx);
                    if (acc == null) {
                        acc = new JSONObject().put("id", d.optString("id")).put("type", "function")
                                .put("function", new JSONObject().put("name", "").put("arguments", ""));
                        calls.put(idx, acc);
                    }
                    JSONObject f = d.optJSONObject("function");
                    if (f != null) {
                        JSONObject af = acc.getJSONObject("function");
                        if (f.has("name") && !f.isNull("name")) af.put("name", af.getString("name") + f.getString("name"));
                        if (f.has("arguments") && !f.isNull("arguments")) af.put("arguments", af.getString("arguments") + f.getString("arguments"));
                    }
                }
            }
            r.close();
            msg = new JSONObject().put("role", "assistant").put("content", content.toString());
            if (!calls.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (int i = 0; i < calls.size(); i++) if (calls.get(i) != null) arr.put(calls.get(i));
                msg.put("tool_calls", arr);
            }
        }
        long total = System.currentTimeMillis() - t0;
        db.kvSet("llm_last_ms", total);
        if (firstToken >= 0) db.kvSet("llm_last_first_token_ms", firstToken);
        db.kvSet("llm_last_model", model);
        db.kvSet("llm_last_ok", System.currentTimeMillis());
        recordLatency(total, firstToken);
        return msg;
    }

    private void count(boolean ok, String err) {
        String d = day();
        db.getWritableDatabase().execSQL("INSERT OR IGNORE INTO llm_usage(day, provider) VALUES(?, 'groq')", new Object[]{d});
        db.getWritableDatabase().execSQL("UPDATE llm_usage SET requests=requests+1, errors=errors+?, last_error=COALESCE(?, last_error) WHERE day=? AND provider='groq'",
                new Object[]{ok ? 0 : 1, err, d});
    }

    private void addTokens(int n) {
        db.getWritableDatabase().execSQL("UPDATE llm_usage SET tokens=tokens+? WHERE day=? AND provider='groq'", new Object[]{n, day()});
    }

    private void recordLatency(long total, long first) {
        // keep the last 50 measurements for the status page
        String prev = db.kv("llm_latencies", "");
        String add = total + (first >= 0 ? "/" + first : "");
        String[] parts = prev.isEmpty() ? new String[0] : prev.split(",");
        StringBuilder b = new StringBuilder(add);
        for (int i = 0; i < Math.min(parts.length, 49); i++) b.append(",").append(parts[i]);
        db.kvSet("llm_latencies", b.toString());
    }

    private static long nextMidnight() {
        return LocalDate.now(Cfg.ZONE).plusDays(1).atStartOfDay(Cfg.ZONE).toInstant().toEpochMilli();
    }

    public static String text(JSONObject msg) {
        String c = msg.optString("content", "");
        return msg.isNull("content") ? "" : c.trim();
    }
}
