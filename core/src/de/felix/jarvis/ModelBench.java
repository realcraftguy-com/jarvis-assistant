package de.felix.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Measures the free Groq models on this device: German chat quality, tool calls, phone-style answers, latency. */
final class ModelBench {
    private ModelBench() {}

    static final String[] PREFERRED = {"openai/gpt-oss-120b", "openai/gpt-oss-20b", "moonshotai/kimi-k2-instruct-0905",
            "moonshotai/kimi-k2-instruct", "llama-3.3-70b-versatile", "meta-llama/llama-4-maverick-17b-128e-instruct",
            "qwen/qwen3.8-27b", "qwen/qwen3-32b"};

    static String run(Context c) throws Exception {
        Brain b = Brain.get(c);
        List<String> available = b.llm.models();
        List<String> models = new ArrayList<>();
        String only = Db.get(c).kv("bench_only", null);
        for (String m : PREFERRED) if (available.contains(m) && (only == null || only.equals(m))) models.add(m);
        JSONArray results = new JSONArray();
        StringBuilder sum = new StringBuilder("Verfügbar bei Groq: " + available.size() + " Modelle. Getestet:\n");
        String tomorrow = LocalDate.now(Cfg.ZONE).plusDays(1).toString();
        for (String m : models) {
            JSONObject r = new JSONObject().put("model", m);
            int pass = 0;
            long total = 0;
            // 1. natural German chat
            try {
                long t0 = System.currentTimeMillis();
                JSONObject a = b.llm.chatModel(m, msgs(Agent.persona("telegram"), "Hey Jarvis, was geht? Ich hab morgen Mathe und null Bock."), null, 300);
                long ms = System.currentTimeMillis() - t0;
                String t = Llm.text(a);
                // must not claim an action it did not take (no tool was available in this test)
                boolean claims = t.matches("(?is).*(habe|hab) (ich )?(den|dir|es|das).{0,30}(eingetragen|gespeichert|notiert|vermerkt|erstellt|angelegt).*")
                        || t.matches("(?is).*(eingetragen|vermerkt) (habe|hab).*") || t.matches("(?is).*im kalender (eingetragen|vermerkt).*");
                boolean ok = !t.isEmpty() && t.length() < 600 && !t.contains("**") && !t.matches("(?is).*\\b(need to|the user)\\b.*")
                        && !t.contains("Als KI") && !claims;
                if (claims) r.put("chat_claims_action", true);
                r.put("chat_ms", ms).put("chat_ok", ok).put("chat", t);
                total += ms;
                if (ok) pass++;
            } catch (Exception e) { r.put("chat_error", e.getMessage()); }
            // 2. tool call with correct arguments
            try {
                long t0 = System.currentTimeMillis();
                JSONObject a = b.llm.chatModel(m, msgs(Agent.persona("telegram"), "Erinnere mich morgen um 7 Uhr an meine Sportsachen."), b.agent.toolsFor("telegram"), 300);
                long ms = System.currentTimeMillis() - t0;
                JSONArray tc = a.optJSONArray("tool_calls");
                String args = tc != null && tc.length() > 0 ? tc.getJSONObject(0).getJSONObject("function").optString("arguments") : "";
                String name = tc != null && tc.length() > 0 ? tc.getJSONObject(0).getJSONObject("function").optString("name") : "";
                boolean ok = name.equals("reminder_add") && args.contains(tomorrow + "T07:00") && !args.contains("daily") && !args.contains("weekly");
                r.put("tool_ms", ms).put("tool_ok", ok).put("tool", name + " " + args + (name.isEmpty() ? " TEXT: " + Llm.text(a) : ""));
                total += ms;
                if (ok) pass++;
            } catch (Exception e) { r.put("tool_error", e.getMessage()); }
            // 3. phone style: short, spoken, numbers written out
            try {
                long t0 = System.currentTimeMillis();
                JSONObject a = b.llm.chatModel(m, msgs(Agent.persona("call"), "Wie spät ist es eigentlich gerade?"), null, 200);
                long ms = System.currentTimeMillis() - t0;
                String t = Llm.text(a);
                java.time.ZonedDateTime now = Cfg.now();
                String spoken = Agent.spokenTime(now.getHour(), now.getMinute()).replace(" nachts", "").replace(" morgens", "").replace(" mittags", "")
                        .replace(" nachmittags", "").replace(" abends", "");
                boolean ok = !t.isEmpty() && t.length() < 160 && !t.matches("(?s).*\\d{1,2}:\\d{2}.*") && !t.contains("*") && t.toLowerCase().contains(spoken.toLowerCase());
                r.put("call_ms", ms).put("call_ok", ok).put("call", t);
                total += ms;
                if (ok) pass++;
            } catch (Exception e) { r.put("call_error", e.getMessage()); }
            r.put("pass", pass).put("avg_ms", total / 3);
            r.put("available", new JSONArray(available));
            results.put(r);
            sum.append("• ").append(m).append(": ").append(pass).append("/3 bestanden, Ø ").append(total / 3).append(" ms\n");
        }
        java.nio.file.Files.write(new java.io.File(c.getFilesDir(), "model-bench.json").toPath(), results.toString(2).getBytes("UTF-8"));
        Db.get(c).kvSet("model_bench", sum.toString());
        Db.get(c).log("I", "ki", "Modellvergleich: " + sum.toString().replace("\n", " "));
        return sum.toString();
    }

    private static JSONArray msgs(String system, String user) throws Exception {
        return new JSONArray().put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user));
    }
}
