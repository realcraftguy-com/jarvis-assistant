package de.felix.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * General web mode for any school platform: pages the user logged into in the built-in browser and chose to watch
 * are rendered in an invisible browser (same login cookies), new text blocks go through the normal school
 * pipeline (AI reading, calendar, questions, notes). The first reading of a page only remembers what is there.
 */
final class WebSchool {
    private final Context ctx;
    private final Db db;

    WebSchool(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
    }

    static List<String> pages(Context c) {
        List<String> l = new ArrayList<>();
        String v = Cfg.get(c, "web_pages");
        if (v != null) for (String s : v.split("\\s*\\n\\s*")) if (s.startsWith("http")) l.add(s.trim());
        return l;
    }

    static void addPage(Context c, String url) {
        Set<String> l = new LinkedHashSet<>(pages(c));
        l.add(url);
        Cfg.set(c, "web_pages", String.join("\n", l));
        Db.get(c).kvSet("web_seen:" + url.hashCode(), null); // fresh baseline
    }

    static void removePage(Context c, String url) {
        Set<String> l = new LinkedHashSet<>(pages(c));
        l.remove(url);
        Cfg.set(c, "web_pages", String.join("\n", l));
        Db.get(c).kvSet("web_seen:" + url.hashCode(), null);
    }

    // ------------------------------------------------------------------ rendering

    /** Visible text of a page after its scripts ran (the user's login cookies apply). Must not be called on the main thread. */
    static String render(Context c, String url, int waitMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("main thread");
        Handler main = new Handler(Looper.getMainLooper());
        String[] out = {null};
        WebView[] view = {null};
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                WebView w = new WebView(c.getApplicationContext());
                view[0] = w;
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                w.measure(1080, 2400);
                w.layout(0, 0, 1080, 2400);
                w.setWebViewClient(new WebViewClient() {
                    boolean fired;

                    @Override public void onPageFinished(WebView v, String u) {
                        if (fired) return;
                        fired = true;
                        // single-page apps keep loading after "finished": give them a moment
                        main.postDelayed(() -> v.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()", r -> {
                            out[0] = r;
                            done.countDown();
                        }), waitMs);
                    }
                });
                w.loadUrl(url);
            } catch (Throwable t) {
                done.countDown();
            }
        });
        boolean ok = done.await(waitMs + 30_000L, TimeUnit.MILLISECONDS);
        main.post(() -> { if (view[0] != null) view[0].destroy(); });
        if (!ok || out[0] == null) throw new Exception("Seite lädt nicht");
        // evaluateJavascript returns a JSON string literal
        return new JSONArray("[" + out[0] + "]").optString(0, "");
    }

    /** "klappt" check after choosing a page: readable text and no login form. */
    static String test(Context c, String url) {
        try {
            String t = render(c, url, 4000);
            if (looksLikeLogin(t)) return "Die Seite zeigt ein Anmeldeformular – bitte erst anmelden und dann die Seite mit den Neuigkeiten/Terminen öffnen.";
            int blocks = blocks(t).size();
            return blocks == 0 ? "Auf der Seite ist kein Text lesbar." : "Klappt: " + blocks + " Abschnitte gelesen. Jarvis meldet ab jetzt, was neu dazukommt.";
        } catch (Exception e) {
            return "Seite nicht lesbar: " + e.getMessage();
        }
    }

    static boolean looksLikeLogin(String t) {
        String l = t.toLowerCase();
        return t.length() < 1500 && (l.contains("passwort") || l.contains("password")) && (l.contains("anmelden") || l.contains("login") || l.contains("einloggen"));
    }

    /** Paragraph-like text blocks, short noise removed. */
    static List<String> blocks(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                if (cur.length() > 0) { add(out, cur.toString()); cur.setLength(0); }
                continue;
            }
            if (cur.length() > 0) cur.append("\n");
            cur.append(s);
            if (cur.length() > 1200) { add(out, cur.toString()); cur.setLength(0); }
        }
        if (cur.length() > 0) add(out, cur.toString());
        return out;
    }

    private static void add(List<String> out, String b) {
        if (b.length() >= 25 && b.split("\\s+").length >= 4) out.add(b);
    }

    // ------------------------------------------------------------------ watching

    void tick() {
        List<String> ps = pages(ctx);
        if (ps.isEmpty()) return;
        long due = db.kvLong("web_last_try", 0) + Cfg.getInt(ctx, "edu_poll_min") * 60_000L;
        if (System.currentTimeMillis() < due) return;
        db.kvSet("web_last_try", System.currentTimeMillis());
        for (String url : ps) {
            try { check(url); } catch (Exception e) { db.log("W", "schule-web", host(url) + ": " + e.getMessage()); }
        }
    }

    private void check(String url) throws Exception {
        String text = render(ctx, url, 5000);
        String key = "web_seen:" + url.hashCode();
        if (looksLikeLogin(text)) {
            if (!"login".equals(db.kv(key + ":state", ""))) {
                db.kvSet(key + ":state", "login");
                Outbox.enqueue(ctx, "weblogin:" + url.hashCode() + ":" + LocalDate.now(Cfg.ZONE), "Die Anmeldung bei " + host(url)
                        + " ist abgelaufen. Bitte in der Jarvis-App unter Einstellungen → Schule neu anmelden.", null, null);
            }
            return;
        }
        db.kvSet(key + ":state", "ok");
        Set<String> seen = new HashSet<>();
        String raw = db.kv(key, null);
        if (raw != null) { JSONArray a = new JSONArray(raw); for (int i = 0; i < a.length(); i++) seen.add(a.getString(i)); }
        List<String> bl = blocks(text);
        JSONArray now = new JSONArray();
        int fresh = 0;
        SchoolPipeline sp = new SchoolPipeline(ctx);
        for (String b : bl) {
            String h = SchoolPipeline.sha(b);
            now.put(h);
            if (raw == null || seen.contains(h)) continue;
            SchoolPipeline.Source s = new SchoolPipeline.Source();
            s.key = "web:" + Integer.toHexString(url.hashCode()) + ":" + h.substring(0, 16);
            s.eduId = s.key;
            s.type = "sprava"; // treated like a message from school
            s.text = b;
            s.published = LocalDate.now(Cfg.ZONE).toString() + " 00:00:00";
            s.author = host(url);
            s.url = url;
            s.raw = "{}";
            if (sp.process(s)) fresh++;
        }
        db.kvSet(key, now.toString());
        db.kvSet("web_last_ok", System.currentTimeMillis());
        db.log("I", "schule-web", host(url) + ": " + bl.size() + " Abschnitte" + (raw == null ? " (erstes Lesen, nur gemerkt)" : ", " + fresh + " neu verarbeitet"));
    }

    static String host(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Exception e) { return url; }
    }
}
