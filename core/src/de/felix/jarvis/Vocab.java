package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Vocabulary trainer (Leitner boxes): words come from chat or a photo of the list, Jarvis quizzes a few every day
 * and asks the ones that did not stick more often.
 */
final class Vocab {
    private static final int[] DAYS = {0, 1, 2, 4, 7, 14, 30};
    private static volatile boolean ready;
    private final Context ctx;
    private final Db db;

    Vocab(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
        if (!ready) {
            db.getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS vocab(id INTEGER PRIMARY KEY, list TEXT, front TEXT, back TEXT, box INTEGER DEFAULT 0,"
                    + " due TEXT, right INTEGER DEFAULT 0, wrong INTEGER DEFAULT 0, created INTEGER)");
            ready = true;
        }
    }

    private static String today() { return LocalDate.now(Cfg.ZONE).toString(); }

    /** "skyscraper = Wolkenkratzer" per line (also "-", ":", ";" or tab). */
    String add(String list, String pairs) {
        int n = 0, dup = 0;
        for (String line : pairs.split("\\n|(?<=[a-zäöüß)])\\s*;\\s*(?=\\S)")) {
            String l = line.replaceAll("^\\s*[-•*\\d.)]+\\s+", "").trim();
            String[] p = l.split("\\s*(=|\\t| – | - |:)\\s*", 2);
            if (p.length < 2 || p[0].isEmpty() || p[1].isEmpty()) continue;
            if (db.one("SELECT id FROM vocab WHERE list=? AND front=?", list, p[0]) != null) { dup++; continue; }
            db.insert("vocab", Db.cv("list", list, "front", p[0], "back", p[1], "box", 0, "due", today(), "created", System.currentTimeMillis()));
            n++;
        }
        return n == 0 ? "Keine Vokabelpaare erkannt – Format: „englisch = deutsch“, eins pro Zeile." + (dup > 0 ? " (" + dup + " schon vorhanden)" : "")
                : n + " Vokabeln in „" + list + "“ gespeichert" + (dup > 0 ? ", " + dup + " waren schon drin" : "") + ". Heute schon abfragbar.";
    }

    int dueCount() {
        ContentValues c = db.one("SELECT COUNT(*) n FROM vocab WHERE due<=?", today());
        return c == null ? 0 : c.getAsInteger("n");
    }

    /** The next due word (weakest box first). */
    String next(String list) {
        List<ContentValues> l = db.query("SELECT id, list, front FROM vocab WHERE due<=?" + (list == null || list.isEmpty() ? "" : " AND list=?")
                + " ORDER BY box, wrong DESC, RANDOM() LIMIT 1", list == null || list.isEmpty() ? new String[]{today()} : new String[]{today(), list});
        if (l.isEmpty()) {
            ContentValues c = db.one("SELECT COUNT(*) n FROM vocab");
            return c == null || c.getAsInteger("n") == 0 ? "Noch keine Vokabeln gespeichert. Schick mir die Liste (Text oder Foto)." : "Für heute ist nichts mehr fällig. Stark.";
        }
        ContentValues v = l.get(0);
        return "#" + v.getAsLong("id") + " „" + v.getAsString("front") + "“ (Liste " + v.getAsString("list") + ", noch " + dueCount() + " fällig) – frag nach der Übersetzung, verrate sie nicht.";
    }

    /** ok: the model judged a synonym as correct. */
    String check(long id, String answer, boolean ok) {
        ContentValues v = db.one("SELECT * FROM vocab WHERE id=?", String.valueOf(id));
        if (v == null) return "Vokabel #" + id + " gibt es nicht.";
        boolean right = ok || matches(answer, v.getAsString("back"));
        int box = v.getAsInteger("box");
        box = right ? Math.min(box + 1, DAYS.length - 1) : 0;
        db.update("vocab", Db.cv("box", box, "due", LocalDate.now(Cfg.ZONE).plusDays(right ? DAYS[box] : 0).toString(),
                right ? "right" : "wrong", v.getAsInteger(right ? "right" : "wrong") + 1), "id=?", String.valueOf(id));
        return (right ? "richtig" : "falsch") + " – Lösung: „" + v.getAsString("back") + "“. Noch " + dueCount() + " fällig."
                + (right ? "" : " (Kommt heute noch einmal dran.)");
    }

    static String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.GERMAN), Normalizer.Form.NFC).replaceAll("\\(.*?\\)", " ")
                .replaceAll("^(to|the|a|an|der|die|das|ein|eine|sich)\\s+", "").replaceAll("[^a-zäöüß0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return n;
    }

    static boolean matches(String answer, String solution) {
        String a = norm(answer);
        if (a.isEmpty()) return false;
        for (String s : solution.split("[,;/]")) {
            String b = norm(s);
            if (a.equals(b) || (b.length() > 4 && lev(a, b) <= 1)) return true;
        }
        return false;
    }

    static int lev(String a, String b) {
        int[] p = new int[b.length() + 1], c = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            c[0] = i;
            for (int j = 1; j <= b.length(); j++) c[j] = Math.min(Math.min(c[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p; p = c; c = t;
        }
        return p[b.length()];
    }

    String lists() {
        StringBuilder b = new StringBuilder();
        for (ContentValues c : db.query("SELECT list, COUNT(*) n, SUM(box>=4) sicher, SUM(due<=?) faellig FROM vocab GROUP BY list ORDER BY MAX(created) DESC", today()))
            b.append("- ").append(c.getAsString("list")).append(": ").append(c.getAsInteger("n")).append(" Wörter, ").append(c.getAsInteger("sicher"))
                    .append(" sitzen sicher, ").append(c.getAsInteger("faellig")).append(" heute fällig\n");
        return b.length() == 0 ? "Noch keine Vokabellisten." : b.toString().trim();
    }

    /** Afternoon nudge if words are due (once a day). */
    void tick() {
        ZonedDateTime now = Cfg.now();
        String t = Cfg.get(ctx, "vocab_time");
        if (t == null || !t.matches("\\d{1,2}:\\d{2}")) return;
        if (now.format(DateTimeFormatter.ofPattern("HH:mm")).compareTo(t.length() == 4 ? "0" + t : t) < 0 || now.getHour() >= 21) return;
        if (today().equals(db.kv("vocab_nudge_day", ""))) return;
        db.kvSet("vocab_nudge_day", today());
        int n = dueCount();
        if (n == 0) return;
        String text = "Kurze Vokabelrunde? " + n + (n == 1 ? " Wort ist" : " Wörter sind") + " fällig – dauert keine 3 Minuten. Schreib „Vokabeln“.";
        Outbox.enqueue(ctx, "vocab:" + today(), text, null, null);
        db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "text", "role", "assistant", "content", text));
    }
}
