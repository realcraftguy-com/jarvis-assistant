package de.felix.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads the Boss' own EduPage timeline through the school's web portal, using the session from
 * EduLoginActivity. This is the same data the EduPage app shows (the app is a WebView of it).
 * Not an officially documented API: if EduPage changes the page format, parsing fails loudly
 * (status "Formatfehler") instead of guessing.
 */
public final class EduPage {
    public static final class SessionExpired extends Exception {
        SessionExpired() { super("EduPage-Sitzung abgelaufen – bitte in Jarvis neu anmelden"); }
    }

    public static final class Item {
        public String id, type, published, text, eventTime, author, subject, ineid, replyTo;
        public boolean removed;
        public JSONObject data;
        public List<String[]> attachments = new ArrayList<>(); // {name, url}
        public String url;
        public String raw, typeName, subjectName;
    }

    /** Names of the school's event types and subjects (from the portal's dbi), for classification. */
    public Map<String, String> eventTypes = new HashMap<>(), subjectNames = new HashMap<>();

    private final Context ctx;
    private final String sub;

    public EduPage(Context c) {
        ctx = c.getApplicationContext();
        sub = Cfg.get(ctx, "edu_subdomain");
    }

    public boolean hasSession() { return Secrets.has(ctx, Secrets.EDU_COOKIES); }

    private Map<String, String> headers() throws SessionExpired {
        String cookies = Secrets.get(ctx, Secrets.EDU_COOKIES);
        if (cookies == null) throw new SessionExpired();
        Map<String, String> h = new HashMap<>();
        h.put("Cookie", cookies);
        h.put("Accept-Language", "de-DE,de;q=0.9");
        return h;
    }

    /** Any page of the school's EduPage with the user's session (e.g. "/znamky/"). */
    public String page(String path) throws Exception {
        Http.Resp r = Http.request("GET", "https://" + sub + ".edupage.org" + path, headers(), null, 30000);
        if (r.code == 401 || r.code == 403 || r.body.contains("loginForm") && !r.body.contains("userhome")) throw new SessionExpired();
        if (!r.ok()) throw new Exception("EduPage HTTP " + r.code);
        return r.body;
    }

    /** The parsed userhome(...) object of /user/. */
    public JSONObject userhome() throws Exception {
        Http.Resp r = Http.request("GET", "https://" + sub + ".edupage.org/user/", headers(), null, 30000);
        if (r.code == 401 || r.code == 403) throw new SessionExpired();
        int i = r.body.indexOf("userhome(");
        if (i < 0) {
            if (r.body.contains("login") && (r.body.contains("password") || r.body.contains("Passwort"))) throw new SessionExpired();
            throw new Exception("Formatfehler: userhome nicht gefunden (HTTP " + r.code + ")");
        }
        int start = i + "userhome(".length();
        int end = r.body.indexOf(");", start);
        // the JSON can contain ");" inside strings: find the matching closing brace instead
        end = matchBrace(r.body, start);
        if (end < 0) throw new Exception("Formatfehler: userhome unvollständig");
        JSONObject home = new JSONObject(r.body.substring(start, end + 1));
        java.util.regex.Matcher g = java.util.regex.Pattern.compile("gsechash=\"([0-9a-f]+)\"").matcher(r.body);
        if (g.find()) home.put("_gsechash", g.group(1));
        return home;
    }

    public static final class Lesson {
        public String date, start, end, subject, teacher, room, group;
        public int period, periods;
        public boolean changed, cancelled, event;
    }

    /** The student's own lessons for [from, to], names resolved, sorted. */
    public List<Lesson> timetable(String from, String to) throws Exception {
        JSONObject home = userhome();
        JSONObject tt = timetableRaw(home, from, to);
        JSONObject dbi = home.optJSONObject("dbi");
        Map<String, String> subj = names(dbi == null ? null : dbi.opt("subjects"));
        JSONObject teachers = dbi == null ? null : dbi.optJSONObject("teachers");
        JSONObject rooms = dbi == null ? null : dbi.optJSONObject("classrooms");
        List<Lesson> out = new ArrayList<>();
        JSONArray items = tt.optJSONArray("ttitems");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            Lesson l = new Lesson();
            l.date = it.optString("date");
            l.start = it.optString("starttime");
            l.end = it.optString("endtime");
            l.period = parseIntSafe(it.optString("uniperiod"));
            l.periods = it.optInt("durationperiods", 1);
            String type = it.optString("type");
            l.cancelled = it.optBoolean("removed") || "absent".equals(type);
            l.event = "event".equals(type) || "out".equals(type);
            l.changed = it.optBoolean("changed");
            String sid = it.optString("subjectid", "");
            l.subject = subj.containsKey(sid) ? subj.get(sid) : it.optString("name", l.event ? "Veranstaltung" : "?");
            JSONArray tids = it.optJSONArray("teacherids");
            if (tids != null && tids.length() > 0 && teachers != null && teachers.optJSONObject(tids.optString(0)) != null) {
                JSONObject t = teachers.getJSONObject(tids.optString(0));
                String g = t.optString("gender");
                l.teacher = ("F".equals(g) ? "Frau " : "M".equals(g) ? "Herr " : "") + t.optString("lastname");
            }
            JSONArray rids = it.optJSONArray("classroomids");
            if (rids != null && rids.length() > 0 && rooms != null && rooms.optJSONObject(rids.optString(0)) != null) l.room = rooms.getJSONObject(rids.optString(0)).optString("short");
            JSONArray groups = it.optJSONArray("groupnames");
            l.group = groups != null && groups.length() > 0 ? groups.optString(0) : "";
            out.add(l);
        }
        out.sort((a, b) -> (a.date + a.start).compareTo(b.date + b.start));
        return out;
    }

    static int parseIntSafe(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

    /** Raw timetable items of the logged-in student for [from, to] (yyyy-MM-dd), incl. cancellations/substitutions. */
    public JSONObject timetableRaw(JSONObject home, String from, String to) throws Exception {
        String userid = home.optString("userid", "");
        String id = userid.replaceAll("[^0-9-]", "");
        JSONObject dp = home.optJSONObject("dp");
        Object year = dp != null ? dp.opt("year") : null;
        JSONObject args = new JSONObject().put("year", year).put("datefrom", from).put("dateto", to).put("table", "students").put("id", id)
                .put("showColors", true).put("showIgroupsInClasses", true).put("showOrig", true).put("log_module", "CurrentTTView");
        JSONObject req = new JSONObject().put("__args", new JSONArray().put(JSONObject.NULL).put(args)).put("__gsh", home.optString("_gsechash"));
        Map<String, String> h = headers();
        h.put("Content-Type", "application/json; charset=utf-8");
        Http.Resp r = Http.request("POST", "https://" + sub + ".edupage.org/timetable/server/currenttt.js?__func=curentttGetData", h,
                req.toString().getBytes("UTF-8"), 30000);
        if (!r.body.trim().startsWith("{")) throw new Exception("Stundenplan: unerwartete Antwort (HTTP " + r.code + ")");
        JSONObject o = new JSONObject(r.body).optJSONObject("r");
        if (o == null) throw new Exception("Stundenplan: leere Antwort");
        if (o.has("error") && !o.isNull("error")) throw new Exception("Stundenplan: " + o.opt("error"));
        o.put("_userid", userid).put("_year", year);
        return o;
    }

    private static int matchBrace(String s, int start) {
        int depth = 0;
        boolean str = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') str = false;
                continue;
            }
            if (c == '"') str = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** Timeline items since the given date (YYYY-MM-DD) via the timeline endpoint, plus the userhome items. */
    public List<Item> timeline(String dateFrom) throws Exception {
        JSONObject home = userhome();
        Map<String, String> subjects = subjects(home);
        subjectNames = subjects;
        JSONObject dbi = home.optJSONObject("dbi");
        if (dbi != null) {
            StringBuilder keys = new StringBuilder();
            for (Iterator<String> k = dbi.keys(); k.hasNext(); ) keys.append(k.next()).append(' ');
            Db.get(ctx).kvSet("edu_dbi_keys", keys.toString());
            Object et = dbi.opt("event_types");
            if (et != null) Db.get(ctx).kvSet("edu_event_types_raw", et.toString().substring(0, Math.min(20000, et.toString().length())));
            eventTypes = names(et);
            // bell times of the school (period -> start/end) for "3. Stunde" and EduPage period numbers
            Object per = dbi.opt("periods");
            java.util.TreeMap<Integer, String[]> pm = new java.util.TreeMap<>();
            if (per instanceof JSONObject) {
                for (Iterator<String> k = ((JSONObject) per).keys(); k.hasNext(); ) addPeriod(pm, ((JSONObject) per).optJSONObject(k.next()));
            } else if (per instanceof JSONArray) {
                for (int i = 0; i < ((JSONArray) per).length(); i++) addPeriod(pm, ((JSONArray) per).optJSONObject(i));
            }
            if (!pm.isEmpty()) {
                String[][] arr = new String[pm.lastKey()][];
                for (java.util.Map.Entry<Integer, String[]> e : pm.entrySet()) if (e.getKey() >= 1) arr[e.getKey() - 1] = e.getValue();
                de.felix.jarvis.shared.BellTimes.set(arr);
                Db.get(ctx).kvSet("edu_periods", pm.size());
            }
        }
        Map<String, Item> byId = new HashMap<>();
        JSONArray items = home.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) add(byId, items.getJSONObject(i), subjects);

        try {
            Map<String, String> h = headers();
            h.put("Content-Type", "application/x-www-form-urlencoded");
            Http.Resp r = Http.request("POST", "https://" + sub + ".edupage.org/timeline/?module=todo&filterTab=messages&akcia=getData",
                    h, ("datefrom=" + Http.enc(dateFrom)).getBytes("UTF-8"), 30000);
            if (r.ok() && r.body.trim().startsWith("{")) {
                JSONArray more = new JSONObject(r.body).optJSONArray("timelineItems");
                if (more != null) for (int i = 0; i < more.length(); i++) add(byId, more.getJSONObject(i), subjects);
            }
        } catch (SessionExpired e) {
            throw e;
        } catch (Exception e) {
            Db.get(ctx).log("W", "edupage", "Verlauf nicht lesbar (nur aktuelle Einträge): " + e.getMessage());
        }
        return new ArrayList<>(byId.values());
    }

    private void add(Map<String, Item> out, JSONObject o, Map<String, String> subjects) {
        String id = o.optString("timelineid", "");
        if (id.isEmpty()) return;
        Item it = new Item();
        it.id = id;
        it.raw = o.toString();
        it.type = o.optString("typ");
        it.published = o.optString("cas_pridania", o.optString("timestamp"));
        it.eventTime = o.optString("cas_udalosti", null);
        it.author = o.optString("vlastnik_meno", null);
        it.removed = "1".equals(o.optString("removed"));
        it.ineid = o.optString("ineid", null);
        it.replyTo = o.optString("reakcia_na", null);
        it.text = o.optString("text", "");
        try {
            Object d = o.opt("data");
            it.data = d instanceof JSONObject ? (JSONObject) d : (d instanceof String && ((String) d).startsWith("{") ? new JSONObject((String) d) : new JSONObject());
        } catch (Exception e) {
            it.data = new JSONObject();
        }
        String body = it.data.optString("messageContent", "");
        if (!body.trim().isEmpty() && body.length() > it.text.length()) it.text = body;
        if (it.text.isEmpty()) it.text = it.data.optString("nazov", "");
        String sid = it.data.optString("predmetid", it.data.optString("subjectid", ""));
        if (!sid.isEmpty() && subjects.containsKey(sid)) it.subject = subjects.get(sid);
        it.typeName = eventTypes.get(it.data.optString("typ", ""));
        JSONObject att = it.data.optJSONObject("attachements");
        if (att != null) {
            for (Iterator<String> k = att.keys(); k.hasNext(); ) {
                String url = k.next();
                it.attachments.add(new String[]{att.optString(url, url), url.startsWith("http") ? url : "https://" + sub + ".edupage.org" + url});
            }
        }
        it.url = "https://" + sub + ".edupage.org/timeline/event.php?akcia=show&eventid=" + id;
        Item prev = out.get(id);
        if (prev == null || it.text.length() >= prev.text.length()) out.put(id, it);
    }

    private static void addPeriod(java.util.TreeMap<Integer, String[]> pm, JSONObject p) {
        if (p == null) return;
        try {
            int n = Integer.parseInt(p.optString("period", p.optString("id", p.optString("name", ""))).trim());
            String a = p.optString("starttime", ""), b = p.optString("endtime", "");
            if (a.matches("\\d{1,2}:\\d{2}") && b.matches("\\d{1,2}:\\d{2}")) pm.put(n, new String[]{a.length() == 4 ? "0" + a : a, b.length() == 4 ? "0" + b : b});
        } catch (NumberFormatException ignored) {}
    }

    /** {id: {name: …}} or [{id, name}] -> id -> name */
    static Map<String, String> names(Object o) {
        Map<String, String> m = new HashMap<>();
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (Iterator<String> k = j.keys(); k.hasNext(); ) {
                String id = k.next();
                JSONObject e = j.optJSONObject(id);
                if (e != null) m.put(id, e.optString("name", e.optString("nazov", e.optString("short", ""))));
                else m.put(id, j.optString(id));
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null) m.put(e.optString("id", String.valueOf(i)), e.optString("name", e.optString("nazov", "")));
            }
        }
        return m;
    }

    private static Map<String, String> subjects(JSONObject home) {
        Map<String, String> m = new HashMap<>();
        JSONObject dbi = home.optJSONObject("dbi");
        JSONObject subj = dbi == null ? null : dbi.optJSONObject("subjects");
        if (subj == null) return m;
        for (Iterator<String> k = subj.keys(); k.hasNext(); ) {
            String id = k.next();
            JSONObject s = subj.optJSONObject(id);
            if (s != null) m.put(id, s.optString("name", s.optString("short", "")));
        }
        return m;
    }

    /** Text of a plain-text/HTML attachment (PDFs cannot be read without an extra library – reported as such). */
    public String attachmentText(String url) {
        try {
            Http.Resp r = Http.request("GET", url, headers(), null, 30000);
            String type = r.header("Content-Type");
            if (r.ok() && type != null && (type.startsWith("text/") || type.contains("html"))) {
                return r.body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
