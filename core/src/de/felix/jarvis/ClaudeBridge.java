package de.felix.jarvis;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jarvis' main brain: Claude (Opus) through the Boss' own subscription, via the unmodified Claude Code CLI that
 * runs in Termux on this phone (the Boss signed in there himself; Jarvis never sees those credentials).
 * If the subscription limit is reached or Claude is unreachable, the caller falls back to Groq.
 */
public final class ClaudeBridge {
    static final String URL = "http://127.0.0.1:8789";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private final Context ctx;
    private final Db db;

    public ClaudeBridge(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
    }

    public static final class Limited extends Exception {
        public final long until;
        Limited(String m, long until) { super(m); this.until = until; }
    }

    public boolean enabled() { return !"nur-api".equals(Cfg.brainMode(ctx)); }

    private static String secret(Context c, String name) {
        String s = Secrets.get(c, name);
        if (s == null) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            s = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING);
            Secrets.put(c, name, s);
        }
        return s;
    }

    private Map<String, String> auth() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + secret(ctx, Secrets.BRIDGE));
        return h;
    }

    /** Health incl. login state; starts the bridge in Termux if it is not running. */
    public JSONObject health(boolean start) {
        McpServer.start(ctx);
        secret(ctx, Secrets.MCP);
        try {
            Http.Resp r = Http.request("GET", URL + "/health", auth(), null, 45000);
            if (r.code == 200) {
                // the long-running Claude sessions load the tool list once: after an update with new tools, start fresh
                String tools = String.valueOf(Brain.get(ctx).agent.toolsFor("claude").toString().hashCode());
                if (start && !tools.equals(db.kv("claude_tools_hash", ""))) {
                    db.kvSet("claude_tools_hash", tools);
                    db.log("I", "claude", "Neue Werkzeuge – Claude-Sitzungen werden neu gestartet");
                    restart();
                    android.os.SystemClock.sleep(6000);
                    return health(false);
                }
                JSONObject o = new JSONObject(r.body);
                db.kvSet("claude_health", o.toString());
                db.kvSet("claude_health_at", System.currentTimeMillis());
                JSONObject workers = o.optJSONObject("workers");
                if (loggedIn() && (workers == null || !workers.optBoolean("chat"))) warm();
                return o;
            }
            if (r.code == 401 && start) restart(); // bridge from an older secret
        } catch (Exception e) {
            if (start) {
                restart();
                // give the bridge a moment to come up, then ask once more
                android.os.SystemClock.sleep(6000);
                return health(false);
            }
        }
        return null;
    }

    public boolean loggedIn() {
        String h = db.kv("claude_health", "");
        return h.contains("\"loggedIn\":true") || h.contains("\"logged_in\":true") || h.contains("claude.ai");
    }

    /** Starts the long-running chat session in advance, so the first message does not wait ~15 s for Claude Code. */
    public void warm() {
        try {
            JSONObject req = new JSONObject().put("channel", "chat").put("model", Cfg.get(ctx, "claude_model"))
                    .put("system", Agent.personaStatic("telegram") + Agent.claudeRules());
            Http.postJson(URL + "/warm", auth(), req.toString(), 20000);
        } catch (Exception ignored) {}
    }

    /** Starts (or restarts) the bridge through Termux' RUN_COMMAND service. */
    public void restart() {
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService");
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c",
                    // only an already running bridge (its command line starts with "python"), never this shell itself
                    "pkill -f '^python.*[j]arvis-bridge'; termux-wake-lock 2>/dev/null; echo \"$(date) start\" >> " + TERMUX_HOME + "/bridge.log; "
                            + "exec python " + TERMUX_HOME + "/jarvis-bridge.py \"$0\" \"$1\" >> " + TERMUX_HOME + "/bridge.log 2>&1",
                    secret(ctx, Secrets.BRIDGE), secret(ctx, Secrets.MCP)});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            // Termux reports the end of the command (only if the bridge stops – normally it keeps running)
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", android.app.PendingIntent.getBroadcast(ctx, 7,
                    new Intent(ctx, TermuxResult.class), android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT));
            ctx.startForegroundService(i);
            db.kvSet("claude_bridge_started", System.currentTimeMillis());
            db.log("I", "claude", "Claude-Brücke in Termux gestartet");
        } catch (Exception e) {
            db.kvSet("claude_error", "Termux nicht startbar: " + e.getMessage());
            db.log("W", "claude", "Termux nicht startbar: " + e.getMessage());
        }
    }

    /** One answer from Claude; tools are executed by Claude through Jarvis' MCP server. */
    /**
     * channel: one long-running Claude session per channel; system must stay stable (else the session restarts);
     * context: per-message facts (time etc.); history: only used if the session is new; prompt: the user's message.
     */
    public String ask(String channel, String system, String context, String history, String prompt) throws Exception {
        long until = db.kvLong("claude_limited_until", 0);
        // the reset time in the limit message is only an estimate: try Claude again every 20 minutes
        if (System.currentTimeMillis() < until) {
            if (System.currentTimeMillis() - db.kvLong("claude_limit_probe", 0) < 20 * 60_000L) throw new Limited("Claude-Limit erreicht", until);
            db.kvSet("claude_limit_probe", System.currentTimeMillis());
        }
        McpServer.start(ctx);
        JSONObject req = new JSONObject().put("channel", channel).put("system", system).put("context", context)
                .put("history", history == null ? "" : history).put("prompt", prompt).put("model", Cfg.get(ctx, "claude_model"));
        long t0 = System.currentTimeMillis();
        Http.Resp r;
        try {
            r = Http.postJson(URL + "/ask", auth(), req.toString(), 160000);
        } catch (java.io.IOException e) {
            restart();
            throw new Exception("Claude-Brücke nicht erreichbar");
        }
        if (r.code == 401) { restart(); throw new Exception("Claude-Brücke neu gestartet"); }
        JSONObject o = new JSONObject(r.body);
        String result = o.optString("result", "");
        if (o.optBoolean("is_error") || r.code != 200) {
            String low = result.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("limit") || low.contains("rate_limit") || low.contains("rate limit") || low.contains("quota")) {
                long u = resetTime(result);
                db.kvSet("claude_limited_until", u);
                db.kvSet("claude_limit_probe", System.currentTimeMillis());
                db.log("W", "claude", "Abo-Limit erreicht – Groq übernimmt (laut Meldung bis " + Brain.fmt(u) + ", ich prüfe alle 20 Min.): "
                        + SchoolPipeline.shorten(result, 200));
                throw new Limited("Claude-Limit erreicht", u);
            }
            if (low.contains("log in") || low.contains("login") || low.contains("authent")) {
                db.kvSet("claude_error", "nicht angemeldet");
                throw new Exception("Claude nicht angemeldet");
            }
            db.kvSet("claude_error", SchoolPipeline.shorten(result, 160));
            throw new Exception("Claude: " + SchoolPipeline.shorten(result, 160));
        }
        long ms = System.currentTimeMillis() - t0;
        if (db.kvLong("claude_limited_until", 0) > 0) { db.kvSet("claude_limited_until", null); db.log("I", "claude", "Claude antwortet wieder"); }
        db.kvSet("claude_last_ms", ms);
        db.kvSet("claude_last_ok", System.currentTimeMillis());
        db.kvSet("claude_error", null);
        String prev = db.kv("claude_latencies", "");
        db.kvSet("claude_latencies", ms + (prev.isEmpty() ? "" : "," + (prev.length() > 300 ? prev.substring(0, 300) : prev)));
        return result.trim();
    }

    /** "… resets 3pm" / "… resets at 15:00" -> epoch ms; otherwise one hour. */
    static long resetTime(String msg) {
        Matcher m = Pattern.compile("(?i)reset[s]?\\s*(?:at\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(msg);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if ("pm".equalsIgnoreCase(m.group(3)) && h < 12) h += 12;
            if ("am".equalsIgnoreCase(m.group(3)) && h == 12) h = 0;
            java.time.ZonedDateTime t = Cfg.now().withHour(h % 24).withMinute(min).withSecond(0);
            if (t.isBefore(Cfg.now())) t = t.plusDays(1);
            return t.toInstant().toEpochMilli();
        }
        return System.currentTimeMillis() + 3600_000L;
    }
}
