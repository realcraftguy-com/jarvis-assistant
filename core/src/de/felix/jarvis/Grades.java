package de.felix.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grades from EduPage (/znamky/, the same page the EduPage app shows): reports new grades with the new subject
 * average and answers "Wie stehe ich in Mathe?" / "Was brauche ich für eine 2?".
 */
final class Grades {
    private final Context ctx;
    private final Db db;

    Grades(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
    }

    static final class Grade {
        String id, subject, title, value, date;
        double weight;   // 1 = normal
        Double number;   // numeric grade 1–6, null for text/points
        Double classAvg;
    }

    List<Grade> fetch() throws Exception {
        String body = new EduPage(ctx).page("/znamky/");
        int i = body.indexOf(".znamkyStudentViewer(");
        if (i < 0) throw new Exception("Notenseite nicht lesbar");
        JSONObject d = (JSONObject) new JSONTokener(body.substring(i + ".znamkyStudentViewer(".length())).nextValue();
        JSONObject subjects = d.optJSONObject("predmety") == null ? new JSONObject() : d.getJSONObject("predmety");
        // grade events: an object keyed by id, or a plain list (seen when the half-year has no grades yet)
        JSONObject events = new JSONObject();
        Object ev0 = d.optJSONObject("vsetkyUdalosti") == null ? null : d.getJSONObject("vsetkyUdalosti").opt("edupage");
        if (ev0 instanceof JSONObject) events = (JSONObject) ev0;
        else if (ev0 instanceof JSONArray) for (int k = 0; k < ((JSONArray) ev0).length(); k++) {
            JSONObject e = ((JSONArray) ev0).optJSONObject(k);
            if (e != null) events.put(e.optString("UdalostID"), e);
        }
        JSONArray all = d.optJSONArray("vsetkyZnamky");
        List<Grade> out = new ArrayList<>();
        if (all == null) return out;
        for (int k = 0; k < all.length(); k++) {
            JSONObject g = all.getJSONObject(k);
            String eid = g.optString("udalostid");
            JSONObject ev = events.optJSONObject(eid);
            if (eid.isEmpty() || ev == null) continue;
            String sid = ev.optString("PredmetID");
            if (sid.isEmpty() || "vsetky".equals(sid)) continue;
            Grade x = new Grade();
            x.id = eid + ":" + g.optString("znamkaid", g.optString("data"));
            JSONObject s = subjects.optJSONObject(sid);
            x.subject = SchoolParserFriendly.name(s != null ? s.optString("p_meno", sid) : ev.optString("predmet_meno", sid));
            x.title = ev.optString("p_meno", "");
            x.value = g.optString("data", "").split(" \\(")[0].trim();
            x.date = g.optString("datum", "").replaceAll(" .*", "");
            String type = ev.optString("p_typ_udalosti", "1");
            try { x.weight = "2".equals(type) ? 1 : Double.parseDouble(ev.optString("p_vaha", "20")) / 20.0; } catch (Exception e) { x.weight = 1; }
            if (!"2".equals(type)) {
                try {
                    String v = x.value.replace(',', '.').replace("+", "").replace("-", "");
                    double n = Double.parseDouble(v);
                    if (n >= 1 && n <= 6) x.number = n;
                } catch (Exception ignored) {}
            }
            try { if (!ev.isNull("priemer")) x.classAvg = Double.parseDouble(ev.getString("priemer")); } catch (Exception ignored) {}
            out.add(x);
        }
        return out;
    }

    /** Per subject: grades and the weighted average. Optional filter by subject name. */
    String overview(String filter) throws Exception {
        List<Grade> l = fetch();
        Map<String, List<Grade>> by = new LinkedHashMap<>();
        for (Grade g : l) {
            if (filter != null && !filter.isEmpty() && !g.subject.toLowerCase(Locale.GERMAN).contains(filter.toLowerCase(Locale.GERMAN))) continue;
            by.computeIfAbsent(g.subject, k -> new ArrayList<>()).add(g);
        }
        if (by.isEmpty()) return l.isEmpty() ? "In EduPage sind in diesem Halbjahr noch keine Noten eingetragen." : "Keine Noten für „" + filter + "“.";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, List<Grade>> e : by.entrySet()) {
            b.append(e.getKey()).append(": ");
            for (Grade g : e.getValue()) b.append(g.value).append(g.weight != 1 ? " (×" + fmt(g.weight) + ")" : "").append(g.title.isEmpty() ? "" : " " + g.title).append("; ");
            Double avg = average(e.getValue());
            b.append(avg == null ? "kein Schnitt" : "Schnitt " + fmt(avg) + " (Summe Gewichte " + fmt(weights(e.getValue())) + ")").append("\n");
        }
        return b.toString().trim() + "\n(Für „Was brauche ich für …“: neuer Schnitt = (Schnitt·Gewichte + Note·Gewicht) / (Gewichte + Gewicht).)";
    }

    static Double average(List<Grade> l) {
        double s = 0, w = 0;
        for (Grade g : l) if (g.number != null) { s += g.number * g.weight; w += g.weight; }
        return w == 0 ? null : s / w;
    }

    static double weights(List<Grade> l) {
        double w = 0;
        for (Grade g : l) if (g.number != null) w += g.weight;
        return w;
    }

    static String fmt(double d) { return String.format(Locale.GERMAN, "%.2f", d).replaceAll(",?0+$", ""); }

    /** Hourly: new grades -> short note with the new subject average. The first run only remembers what exists. */
    void tick() {
        if (System.currentTimeMillis() - db.kvLong("grades_at", 0) < 60 * 60_000L || !new EduPage(ctx).hasSession()) return;
        db.kvSet("grades_at", System.currentTimeMillis());
        List<Grade> l;
        try { l = fetch(); } catch (Exception e) { db.log("W", "noten", "Noten nicht abrufbar: " + e.getMessage()); return; }
        String raw = db.kv("grades_seen", null);
        Set<String> seen = new HashSet<>();
        if (raw != null) for (String s : raw.split("\n")) seen.add(s);
        StringBuilder all = new StringBuilder();
        List<String> lines = new ArrayList<>();
        for (Grade g : l) {
            all.append(g.id).append("\n");
            if (raw == null || seen.contains(g.id)) continue;
            List<Grade> same = new ArrayList<>();
            for (Grade o : l) if (o.subject.equals(g.subject)) same.add(o);
            Double avg = average(same);
            lines.add(g.subject + (g.title.isEmpty() ? "" : " – " + g.title) + ": " + g.value
                    + (g.classAvg != null ? " (Klassenschnitt " + fmt(g.classAvg) + ")" : "") + (avg != null ? ". Schnitt " + g.subject + " jetzt " + fmt(avg) : ""));
        }
        db.kvSet("grades_seen", all.toString());
        if (lines.isEmpty()) return;
        String text = (lines.size() == 1 ? "Neue Note: " + lines.get(0) + "." : "Neue Noten:\n• " + String.join("\n• ", lines));
        Outbox.enqueue(ctx, "grades:" + text.hashCode(), text, null, null);
        db.insert("chat", Db.cv("ts", System.currentTimeMillis(), "channel", "text", "role", "assistant", "content", text));
        db.log("I", "noten", lines.size() + " neue Note(n) gemeldet");
    }
}
