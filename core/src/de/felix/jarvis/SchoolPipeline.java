package de.felix.jarvis;

import android.content.ContentValues;
import android.content.Context;

import de.felix.jarvis.shared.SchoolParser;
import de.felix.jarvis.shared.SchoolParser.Category;
import de.felix.jarvis.shared.SchoolParser.DateMention;
import de.felix.jarvis.shared.SchoolParser.Result;
import de.felix.jarvis.shared.SchoolParser.Role;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * EduPage item -> understood school event(s) -> calendar -> one short note to the Boss.
 *
 * Crash safety: the calendar mapping (cal_map) is keyed by a stable entry key, so re-processing
 * an item updates instead of duplicating. Notes are stored on the item (status notify) and turned
 * into exactly one deduplicated outbox message, so a failed message is retried without touching
 * the calendar again.
 */
public final class SchoolPipeline {
    static final Set<String> EXAM = new HashSet<>(Arrays.asList("bexam", "sexam", "oexam", "rexam", "pexam", "testing"));
    static final Set<String> EVENT = new HashSet<>(Arrays.asList("event", "schoolevent", "trip", "excursion", "culture",
            "parentsevening", "signin", "ctevent", "freeday", "holiday", "sholiday"));
    static final Set<String> MESSAGE = new HashSet<>(Arrays.asList("sprava", "news", "genotif", "anketa"));
    static final Set<String> HOMEWORK = new HashSet<>(Arrays.asList("homework", "etesthw", "h_homework"));

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN);
    private static final DateTimeFormatter DAYW = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN);

    private final Context ctx;
    private final Db db;
    private final CalendarStore cal;

    public SchoolPipeline(Context c) {
        ctx = c.getApplicationContext();
        db = Db.get(ctx);
        cal = new CalendarStore(ctx);
    }

    /** Input that does not depend on where it came from (EduPage or a test case). */
    public static final class Source {
        public String key, eduId, type, text, published, eventTime, author, subject, url, raw, typeName;
        public JSONObject data;
        public boolean removed;
        public List<String[]> attachments = new ArrayList<>();
    }

    public static Source from(EduPage.Item it) {
        Source s = new Source();
        // calendar events carry a stable id (subId): changes arrive as new timeline items with the same subId
        String sub = it.data == null ? "" : it.data.optString("subId", "");
        s.key = "event".equals(it.type) && !sub.isEmpty() ? "edu:ev:" + sub : "edu:" + it.id;
        s.data = it.data;
        s.typeName = it.typeName;
        s.eduId = it.id; s.type = it.type; s.text = it.text; s.published = it.published; s.eventTime = it.eventTime;
        s.author = it.author; s.subject = it.subject; s.url = it.url; s.removed = it.removed; s.attachments = it.attachments;
        s.raw = it.raw;
        return s;
    }

    /** @return true if something was done for this item (new or changed). */
    public boolean process(Source s) {
        String hash = sha(s.type + "|" + s.text + "|" + s.eventTime + "|" + s.removed);
        ContentValues row = db.one("SELECT * FROM school_items WHERE src_key=?", s.key);
        if (row != null && hash.equals(row.getAsString("text_hash")) && !"error".equals(row.getAsString("status"))) return false;
        boolean changed = row != null;

        String kind = relevance(s);
        if (row == null) {
            db.insert("school_items", Db.cv("src_key", s.key, "edu_id", s.eduId, "edu_type", s.type, "published", s.published,
                    "title", firstLine(s.text), "text", s.text, "text_hash", hash, "status", "new", "updated", now(), "analysis", s.raw));
        } else {
            db.update("school_items", Db.cv("text", s.text, "text_hash", hash, "status", "new", "updated", now(),
                    "removed", s.removed, "analysis", s.raw), "src_key=?", s.key);
        }
        if (kind == null) {
            db.update("school_items", Db.cv("status", "ignored"), "src_key=?", s.key);
            return false;
        }
        if (!"event".equals(s.type) && db.one("SELECT src_key FROM school_items WHERE text=? AND src_key!=? AND status IN ('done','notify','question','past')",
                s.text, s.key) != null) {
            db.update("school_items", Db.cv("status", "duplicate"), "src_key=?", s.key);
            return false;
        }
        try {
            List<String> notes = new ArrayList<>();
            String status = handle(s, changed, notes);
            db.update("school_items", Db.cv("status", status, "note", notes.isEmpty() ? null : String.join("\n", notes)),
                    "src_key=?", s.key);
            return true;
        } catch (Exception e) {
            db.update("school_items", Db.cv("status", "error", "note", null), "src_key=?", s.key);
            db.log("E", "schule", "Verarbeitung fehlgeschlagen (" + s.key + "): " + e);
            return false;
        }
    }

    /** EduPage calendar event types that matter for the Boss (school's own type ids included). */
    static final Set<String> EXAM_TYPES = new HashSet<>(Arrays.asList("testing", "bexam", "exam", "sexam", "oexam", "rexam", "pexam",
            "ct_515F3FCB", "ct_D9176635"));
    static final Set<String> EVENT_TYPES = new HashSet<>(Arrays.asList("trip", "excursion", "culture", "schoolevent", "ctevent",
            "ct_C1F5C192", "ct_D8B4DCF2", "ct_7317F509", "ct_589C9ED7", "ct_2A1D31AB", "ct_782403CF", "ct_4499DE81", "ct_0E38516B",
            "parentsevening", "ct_2FC61B38", "ct_BB49AC6E", "ct_27E343DD", "ttcancel", "ttcancel_students", "freeday", "representation"));

    /** null = not relevant for school organisation. */
    static String relevance(Source s) {
        String t = s.type == null ? "" : s.type;
        if ("event".equals(t) && s.data != null) {
            String dt = s.data.optString("typ", "");
            if (EXAM_TYPES.contains(dt)) return "exam";
            if (EVENT_TYPES.contains(dt)) return "event";
            return null; // lessons with the class teacher, room bookings, teacher meetings …
        }
        String lower = s.text == null ? "" : s.text.toLowerCase(Locale.GERMAN);
        if (EXAM.contains(t)) return "exam";
        if (EVENT.contains(t)) return "event";
        if (MESSAGE.contains(t) || t.equals("test")) return "message";
        if (HOMEWORK.contains(t) && SchoolParser.classify(lower, null) == Category.EXAM) return "message"; // "Test am …" posted as homework
        if (HOMEWORK.contains(t) && lower.matches("(?s).*(referat|präsentation|projekt|abgabe|abgeben|portfolio|plakat).*")) return "homework";
        return null;
    }

    // ---------------------------------------------------------------- core decision

    private String handle(Source s, boolean changed, List<String> notes) throws Exception {
        if ("event".equals(s.type) && s.data != null && s.data.has("typ")) return handleStructured(s, notes);
        LocalDate published = parsePublished(s.published);
        LocalDate today = Cfg.now().toLocalDate();
        // old messages: never ask about them, never announce them
        boolean recent = !published.isBefore(today.minusDays(14));
        boolean fresh = !published.isBefore(today.minusDays(3));
        Result r = SchoolParser.analyze(s.text, published, s.type);
        String subject = s.subject != null ? SchoolParser.friendlySubject(s.subject) : r.subject;

        // structured date from EduPage (exams/events/homework) is grounded data, not a guess
        LocalDate structured = null;
        LocalTime structuredTime = null;
        if (s.eventTime != null && s.eventTime.length() >= 10 && !"message".equals(relevance(s))) {
            structured = LocalDate.parse(s.eventTime.substring(0, 10));
            if (s.eventTime.length() >= 16 && !s.eventTime.substring(11, 16).equals("00:00")) structuredTime = LocalTime.parse(s.eventTime.substring(11, 16));
        }

        // optional AI understanding, only accepted where it agrees with grounded dates
        JSONObject ai = Brain.get(ctx).understandSchoolText(s.text, published, s.type, subject);
        String prep = ai != null ? ai.optString("preparation", "") : "";
        String aiTitle = ai != null ? ai.optString("title", "") : "";
        if (ai != null && !ai.optBoolean("relevant", true) && r.dates.isEmpty() && structured == null) {
            notes.clear();
            return "ignored";
        }

        // ------------------------------------------------ removal in EduPage
        if (s.removed) {
            int n = cancelOwnEntries(s.key, "Eintrag wurde in EduPage entfernt", notes);
            return n > 0 ? "notify" : "ignored";
        }

        // ------------------------------------------------ cancellation / move of an earlier appointment
        if (r.cancelled && !r.moved) {
            ContentValues target = findEarlierEntry(r, subject, s.key.startsWith("test:"));
            if (target != null) {
                CalendarStore.Result cr = cal.cancel(target.getAsString("entry_key"), firstLine(s.text));
                if ("cancelled".equals(cr.action)) {
                    maybeUrgent(target.getAsString("entry_key") + ":cancel", target.getAsLong("start_ms"), target.getAsInteger("all_day") == 1,
                            "Boss, " + target.getAsString("title") + " {wann} ist abgesagt.");
                    notes.add("cancel|" + target.getAsString("title") + "|" + target.getAsLong("start_ms") + "|" + target.getAsInteger("all_day"));
                    return "notify";
                }
            }
            ask(s, "Abgesagt – aber was genau?", "In EduPage steht eine Absage, ich kann sie keinem Termin sicher zuordnen:\n„"
                    + shorten(s.text, 300) + "“\nWelcher Termin ist gemeint? (Ich ändere nichts, bis du es mir sagst.)", null);
            return "question";
        }

        List<CalendarStore.Entry> entries = new ArrayList<>();
        List<String> problems = new ArrayList<>(r.problems);
        DateMention ev = r.firstEvent();

        if (structured != null) {
            if (ev != null && !ev.date.equals(structured) && !r.moved) {
                problems.add("EduPage nennt den " + structured.format(DAY) + ", im Text steht „" + ev.raw + "“");
                ev = null;
            } else if (ev == null) {
                ev = new DateMention();
                ev.date = structured;
                ev.start = structuredTime;
                ev.role = Role.EVENT;
                ev.raw = "EduPage-Datum";
            } else if (ev.start == null && structuredTime != null) {
                ev.start = structuredTime;
            }
        }
        if (r.moved && ev != null) {
            ContentValues earlier = findEarlierEntry(r, subject, s.key.startsWith("test:"));
            if (earlier != null && earlier.getAsInteger("adopted") == 0) {
                CalendarStore.Entry e = buildEvent(s, r, ev, subject, aiTitle, prep, published);
                e.key = earlier.getAsString("entry_key");
                e.srcKey = earlier.getAsString("src_key");
                CalendarStore.Result cr = cal.upsert(e);
                if ("updated".equals(cr.action) || "unchanged".equals(cr.action)) {
                    waitForSync(cr.eventId);
                    maybeUrgent(e.key + ":moved-old", earlier.getAsLong("start_ms"), earlier.getAsInteger("all_day") == 1,
                            "Boss, " + e.title + " von {wann} ist verschoben worden.");
                    maybeUrgent(e.key + ":moved-new", e.startMs, e.allDay, "Boss, " + e.title + " ist auf {wann} verschoben worden.");
                    notes.add("moved|" + e.title + "|" + earlier.getAsLong("start_ms") + "|" + e.startMs + "|" + (e.allDay ? 1 : 0)
                            + "|" + cal.syncState(cr.eventId));
                    db.update("cal_map", Db.cv("src_key", s.key), "entry_key=?", e.key);
                    ev = null; // handled
                }
            }
        }

        // nothing is entered for the past
        if (ev != null && (ev.endDate != null ? ev.endDate : ev.date).isBefore(today)) ev = null;
        if (ev != null && (r.category != Category.DEADLINE && r.category != Category.INFO || structured != null)) {
            entries.add(buildEvent(s, r, ev, subject, aiTitle, prep, published));
        } else if (ev != null && r.category == Category.INFO && ai != null && ai.optBoolean("is_appointment", false)) {
            entries.add(buildEvent(s, r, ev, subject, aiTitle, prep, published));
        }
        int dl = 0;
        for (DateMention d : r.deadlines()) if (!d.date.isBefore(today)) entries.add(buildDeadline(s, r, d, subject, ++dl, published, aiTitle));

        // ------------------------------------------------ write calendar
        for (CalendarStore.Entry e : entries) {
            CalendarStore.Result cr = cal.upsert(e);
            switch (cr.action) {
                case "created":
                case "updated":
                    maybeUrgent(e.key + ":" + cr.action, e.startMs, e.allDay, "DEADLINE".equals(e.kind)
                            ? "Boss, kurzfristige Frist: " + e.title + " schon {wann}." : "Boss, neu in EduPage: " + e.title + " schon {wann}.");
                    String sync = waitForSync(cr.eventId);
                    notes.add(cr.action + "|" + e.kind + "|" + e.title + "|" + e.startMs + "|" + (e.allDay ? 1 : 0) + "|" + sync
                            + "|" + String.join(",", cr.changes) + "|" + (e.reminders.length > 0 ? e.reminders[0] : -1)
                            + "|" + (e.description != null && e.description.contains("Uhrzeit nicht angegeben") ? 1 : 0) + "||" + cr.eventId);
                    break;
                case "adopted":
                    notes.add("adopted|" + e.kind + "|" + e.title + "|" + e.startMs + "|" + (e.allDay ? 1 : 0) + "|" + cr.foreignTitle);
                    break;
                case "unchanged":
                    break;
                default:
                    throw new Exception("Kalender: " + cr.error);
            }
        }

        // ------------------------------------------------ open questions for the unclear parts
        // questions only about current messages whose dates are not all in the past
        boolean allPast = !r.dates.isEmpty();
        for (DateMention d : r.dates) if (d.date == null || !d.date.isBefore(today)) allPast = false;
        if (!recent || allPast) problems.clear();
        if (!problems.isEmpty()) {
            ask(s, "Unklares Datum", "Kurze Rückfrage zu EduPage" + (s.author != null ? " (" + s.author + ")" : "") + ":\n„"
                    + shorten(s.text, 350) + "“\n\n" + String.join("\n", problems)
                    + "\n\nWann genau ist es? Antworte einfach auf diese Nachricht, z. B. „22.10. 8 Uhr“ – oder „ignorieren“.", null);
            return entries.isEmpty() && notes.isEmpty() ? "question" : "notify";
        }

        // ------------------------------------------------ important info without date
        if (entries.isEmpty() && "message".equals(relevance(s)) && fresh) {
            boolean important = ai != null ? ai.optBoolean("important", false) : looksImportant(s.text);
            if (important) {
                String summary = ai != null && !ai.optString("summary").isEmpty() ? ai.optString("summary") : shorten(s.text, 280);
                notes.add("info|" + (s.author == null ? "Schule" : s.author) + "|" + summary.replace("|", "/"));
                return "notify";
            }
            return "ignored";
        }
        if (entries.isEmpty() && notes.isEmpty()) return "past";
        return notes.isEmpty() ? "done" : "notify";
    }

    // ---------------------------------------------------------------- EduPage calendar events (structured)

    private String handleStructured(Source s, List<String> notes) throws Exception {
        JSONObject d = s.data;
        String dt = d.optString("typ");
        boolean exam = EXAM_TYPES.contains(dt);
        String typeName = s.typeName != null && !s.typeName.isEmpty() ? s.typeName : dt;
        String subject = s.subject != null ? SchoolParser.friendlySubject(s.subject) : null;
        String name = html(d.optString("name", "")).trim();
        String details = html(d.optString("details", "")).trim();
        LocalDate today = Cfg.now().toLocalDate();

        java.util.TreeSet<LocalDate> days = new java.util.TreeSet<>();
        JSONObject dates = d.optJSONObject("dates");
        if (dates != null) for (java.util.Iterator<String> k = dates.keys(); k.hasNext(); ) { String x = k.next(); if (dates.optBoolean(x, true)) days.add(LocalDate.parse(x)); }
        String one = d.optString("date", d.optString("dateto", s.eventTime == null ? "" : s.eventTime));
        if (days.isEmpty() && one.length() >= 10) days.add(LocalDate.parse(one.substring(0, 10)));
        if (!d.optString("repeat_to", "").isEmpty() && !days.isEmpty()) {
            for (LocalDate x = days.first(); !x.isAfter(LocalDate.parse(d.getString("repeat_to"))); x = x.plusDays(1)) days.add(x);
        }
        if (days.isEmpty()) {
            ask(s, "Termin ohne Datum", "Der EduPage-Termin „" + name + "“ (" + typeName + ") hat kein lesbares Datum. Wann ist er?", null);
            return "question";
        }
        if (d.optBoolean("cancelled", false)) {
            int n = cancelOwnEntries(s.key, "in EduPage abgesagt", notes);
            return n > 0 ? "notify" : "ignored";
        }
        if (days.last().isBefore(today)) return "past";

        CalendarStore.Entry e = new CalendarStore.Entry();
        e.key = s.key + ":event";
        e.srcKey = s.key;
        e.kind = "EVENT";
        String label = exam ? examLabel(dt, name + " " + details) : eventLabel(dt, typeName, name);
        if (exam) e.title = (subject != null ? subject + " – " : "") + label;
        else if (name.isEmpty()) e.title = label;
        else e.title = name + (label.isEmpty() || name.toLowerCase(Locale.GERMAN).contains(label.toLowerCase(Locale.GERMAN)) ? "" : " – " + label);

        StringBuilder desc = new StringBuilder();
        if (exam && !name.isEmpty()) desc.append("Thema: ").append(name).append("\n");
        if (!details.isEmpty()) desc.append(details).append("\n");
        desc.append("EduPage-Typ: ").append(typeName).append(subject != null ? ", Fach: " + subject : "").append("\n");

        LocalDate first = days.first(), last = days.last();
        boolean allDay = d.optBoolean("allday", false) || days.size() > 1 || exam;
        java.time.LocalTime[] from = null, to = null;
        int period = parseInt(d.optString("period", ""), 0), dur = Math.max(1, d.optInt("durationperiods", 1));
        if (!allDay && period > 0) {
            from = de.felix.jarvis.shared.BellTimes.period(period);
            to = de.felix.jarvis.shared.BellTimes.period(period + dur - 1);
        }
        if (exam && period > 0 && d.has("period")) desc.append("EduPage nennt die ").append(period).append(". Stunde – Uhrzeit nicht sicher, daher ganztägig.\n");
        if (from == null || to == null) {
            e.allDay = true;
            e.startMs = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            e.endMs = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            if (!exam && !d.optBoolean("allday", false) && days.size() == 1) desc.append("Uhrzeit nicht angegeben – ganztägig eingetragen.\n");
            e.reminders = new int[]{Cfg.getInt(ctx, "rem_allday_min")};
        } else {
            e.allDay = false;
            e.startMs = LocalDateTime.of(first, from[0]).atZone(Cfg.ZONE).toInstant().toEpochMilli();
            e.endMs = LocalDateTime.of(last, to[1]).atZone(Cfg.ZONE).toInstant().toEpochMilli();
            e.reminders = new int[]{Cfg.getInt(ctx, "rem_timed_min")};
        }
        appendSource(desc, s, parsePublished(s.published));
        e.description = desc.toString();

        CalendarStore.Result cr = cal.upsert(e);
        switch (cr.action) {
            case "created":
            case "updated":
                String sync = waitForSync(cr.eventId);
                maybeUrgent(e.key + ":" + cr.action, e.startMs, e.allDay, "created".equals(cr.action)
                        ? "Boss, neu in EduPage: " + e.title + " schon {wann}." : "Boss, EduPage hat " + e.title + " für {wann} geändert.");
                notes.add(cr.action + "|EVENT|" + e.title + "|" + e.startMs + "|" + (e.allDay ? 1 : 0) + "|" + sync
                        + "|" + String.join(",", cr.changes) + "|" + e.reminders[0] + "|0|" + (exam ? name : "").replace("|", "/") + "|" + cr.eventId);
                return "notify";
            case "adopted":
                notes.add("adopted|EVENT|" + e.title + "|" + e.startMs + "|" + (e.allDay ? 1 : 0) + "|" + cr.foreignTitle);
                return "notify";
            case "unchanged":
                return "done";
            default:
                throw new Exception("Kalender: " + cr.error);
        }
    }

    static String examLabel(String typ, String text) {
        String l = text.toLowerCase(Locale.GERMAN);
        if (l.contains("schulaufgabe")) return "Schulaufgabe";
        if (l.contains("stegreif") || l.matches("(?s).*\\bex\\b.*")) return "Stegreifaufgabe";
        if (l.contains("leistungsnachweis") || l.matches("(?s).*\\bln\\b.*")) return "Leistungsnachweis";
        switch (typ) {
            case "testing": return "Leistungsnachweis";
            case "bexam": return "Schulaufgabe";
            case "sexam": return "Kurztest";
            case "oexam": return "Mündliche Prüfung";
            case "rexam": return "Referat";
            case "pexam": return "Projekt";
            case "ct_515F3FCB": return "Abschlussprüfung";
            case "ct_D9176635": return "Abgabe Projektmappe";
            default: return "Prüfung";
        }
    }

    static String eventLabel(String typ, String typeName, String name) {
        switch (typ) {
            case "ctevent": case "schoolevent": return name.isEmpty() ? "Schulveranstaltung" : ""; // generic types: the name says it
            case "ttcancel": case "ttcancel_students": return "Unterrichtsfrei";
            case "trip": return name.isEmpty() ? "Wandertag" : ""; // EduPage uses "trip" for camps, bowling, … – the name is clearer
            default: return typeName == null ? "" : typeName;
        }
    }

    static String html(String s) {
        return s.replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("<[^>]+>", " ").replaceAll("[ \\t]+", " ");
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ---------------------------------------------------------------- building entries

    private CalendarStore.Entry buildEvent(Source s, Result r, DateMention d, String subject, String aiTitle, String prep, LocalDate published) {
        CalendarStore.Entry e = new CalendarStore.Entry();
        e.key = s.key + ":event";
        e.srcKey = s.key;
        e.kind = "EVENT";
        String label = r.kindLabel;
        // exams always get the fixed "Fach – Art" title: later moves/cancellations are matched by it
        if (r.category != Category.EXAM && aiTitle != null && aiTitle.length() > 3 && aiTitle.length() < 60) e.title = aiTitle;
        else if (r.category == Category.EXAM) e.title = (subject != null ? subject + " – " : "") + label;
        else e.title = label + (subject != null && r.category != Category.TRIP ? " – " + subject : "") + (r.location != null && r.category != Category.EXAM ? " – " + r.location : "");
        e.location = r.location;
        StringBuilder desc = new StringBuilder();
        if (prep != null && !prep.isEmpty()) desc.append("Vorbereitung: ").append(prep).append("\n\n");
        if (d.start == null) {
            e.allDay = true;
            LocalDate end = d.endDate != null ? d.endDate : d.date;
            e.startMs = d.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            e.endMs = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            desc.append("Uhrzeit nicht angegeben – ganztägig eingetragen.\n");
            e.reminders = new int[]{Cfg.getInt(ctx, "rem_allday_min")};
        } else {
            e.allDay = false;
            e.startMs = LocalDateTime.of(d.date, d.start).atZone(Cfg.ZONE).toInstant().toEpochMilli();
            LocalTime endT = d.end != null ? d.end : d.start.plusMinutes(r.category == Category.EXAM ? 45 : 60);
            LocalDate endD = d.endDate != null ? d.endDate : d.date;
            e.endMs = LocalDateTime.of(endD, endT).atZone(Cfg.ZONE).toInstant().toEpochMilli();
            if (d.end == null) desc.append("Endzeit nicht angegeben (Kalender zeigt ").append(r.category == Category.EXAM ? "eine Schulstunde" : "eine Stunde").append(").\n");
            e.reminders = new int[]{Cfg.getInt(ctx, "rem_timed_min")};
        }
        appendSource(desc, s, published);
        e.description = desc.toString();
        return e;
    }

    private CalendarStore.Entry buildDeadline(Source s, Result r, DateMention d, String subject, int n, LocalDate published, String aiTitle) {
        CalendarStore.Entry e = new CalendarStore.Entry();
        e.key = s.key + ":deadline:" + d.date;
        e.srcKey = s.key;
        e.kind = "DEADLINE";
        String about = r.category != Category.DEADLINE && r.category != Category.INFO ? r.kindLabel
                : aiTitle != null && aiTitle.length() > 3 && aiTitle.length() < 45 ? aiTitle
                : subject != null ? subject : (s.author != null ? s.author : "Schule");
        String what = "Frist".equals(d.roleCue) ? "" : d.roleCue + " – ";
        e.title = "Frist: " + what + about;
        StringBuilder desc = new StringBuilder("Frist laut EduPage-Nachricht: „").append(shorten(sentenceAround(s.text, d.pos), 200)).append("“\n");
        if (d.start == null) {
            e.allDay = true;
            e.startMs = d.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            e.endMs = d.date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            e.reminders = new int[]{Cfg.getInt(ctx, "rem_deadline_min")};
        } else {
            e.allDay = false;
            e.startMs = LocalDateTime.of(d.date, d.start).atZone(Cfg.ZONE).toInstant().toEpochMilli();
            e.endMs = e.startMs + 15 * 60_000L;
            e.reminders = new int[]{Cfg.getInt(ctx, "rem_timed_min")};
        }
        appendSource(desc, s, published);
        e.description = desc.toString();
        return e;
    }

    private void appendSource(StringBuilder desc, Source s, LocalDate published) {
        desc.append("\nQuelle: EduPage");
        if (s.author != null && !s.author.isEmpty()) desc.append(" – ").append(s.author);
        if (published != null) desc.append(", veröffentlicht am ").append(published.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        if (s.eduId != null && !s.key.startsWith("test:")) desc.append("\nEintrag Nr. ").append(s.eduId).append(" im EduPage-Schulportal (").append(Cfg.get(ctx, "edu_subdomain")).append(".edupage.org)");
        if (!s.attachments.isEmpty()) {
            desc.append("\nAnhänge: ");
            for (String[] a : s.attachments) desc.append(a[0]).append("  ");
        }
        desc.append("\n\n").append(CalendarStore.MARK).append(" (").append(s.key).append(")");
    }

    // ---------------------------------------------------------------- helpers

    /** Waits up to 45 s for Google to confirm the event. Returns "synced" or "local". */
    private String waitForSync(long eventId) {
        for (int i = 0; i < 15; i++) {
            String st = cal.syncState(eventId);
            if (!"local".equals(st)) {
                if ("synced".equals(st)) db.update("cal_map", Db.cv("sync_state", "synced"), "event_id=?", String.valueOf(eventId));
                return st;
            }
            android.os.SystemClock.sleep(3000);
        }
        return "local";
    }

    private int cancelOwnEntries(String srcKey, String reason, List<String> notes) {
        int n = 0;
        for (ContentValues m : db.query("SELECT * FROM cal_map WHERE src_key=? AND cancelled=0", srcKey)) {
            CalendarStore.Result r = cal.cancel(m.getAsString("entry_key"), reason);
            if ("cancelled".equals(r.action)) {
                maybeUrgent(m.getAsString("entry_key") + ":cancel", m.getAsLong("start_ms"), m.getAsInteger("all_day") == 1,
                        "Boss, " + m.getAsString("title") + " {wann} ist abgesagt.");
                notes.add("cancel|" + m.getAsString("title") + "|" + m.getAsLong("start_ms") + "|" + m.getAsInteger("all_day"));
                n++;
            }
        }
        return n;
    }

    /** The single upcoming Jarvis entry that the cancel/move message refers to, or null if not unique. */
    private ContentValues findEarlierEntry(Result r, String subject, boolean test) {
        // real messages only match real entries, self-test messages only self-test entries
        List<ContentValues> cands = db.query("SELECT * FROM cal_map WHERE cancelled=0 AND kind='EVENT' AND entry_key " + (test ? "" : "NOT ")
                + "LIKE 'test:%' AND start_ms>?", String.valueOf(System.currentTimeMillis() - 24 * 3600_000L));
        DateMention prev = r.previousDate();
        if (prev == null && r.cancelled) for (DateMention d : r.dates) if (d.problem == null) { prev = d; break; }
        List<ContentValues> hits = new ArrayList<>();
        for (ContentValues c : cands) {
            String title = c.getAsString("title").toLowerCase(Locale.GERMAN);
            boolean subj = subject == null || title.contains(subject.toLowerCase(Locale.GERMAN));
            boolean kind = r.kindLabel == null || title.contains(r.kindLabel.toLowerCase(Locale.GERMAN));
            boolean date = true;
            if (prev != null && prev.date != null) {
                LocalDate d = c.getAsInteger("all_day") == 1
                        ? java.time.Instant.ofEpochMilli(c.getAsLong("start_ms")).atZone(ZoneOffset.UTC).toLocalDate()
                        : java.time.Instant.ofEpochMilli(c.getAsLong("start_ms")).atZone(Cfg.ZONE).toLocalDate();
                date = d.equals(prev.date);
            }
            if (subj && kind && date) hits.add(c);
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** Short-notice changes (today/tomorrow) are worth a call: queued once, decided by Brain's call policy. */
    private void maybeUrgent(String key, long startMs, boolean allDay, String what) {
        if (summaryMode || key.startsWith("test:")) return;
        java.time.LocalDate day = allDay ? java.time.Instant.ofEpochMilli(startMs).atZone(ZoneOffset.UTC).toLocalDate()
                : java.time.Instant.ofEpochMilli(startMs).atZone(Cfg.ZONE).toLocalDate();
        java.time.LocalDate today = Cfg.now().toLocalDate();
        if (day.isBefore(today) || day.isAfter(today.plusDays(1))) return;
        String when = day.equals(today) ? "heute" : "morgen";
        Brain.get(ctx).urgent("edu:" + key + ":" + startMs, what.replace("{wann}", when));
    }

    private void ask(Source s, String kind, String text, String options) {
        Brain.get(ctx).askQuestion("school", s.key, (s.key.startsWith("test:") ? "[TEST] " : "") + text, options, "school:" + s.key + ":" + sha(s.text).substring(0, 10));
    }

    static boolean looksImportant(String text) {
        String l = text.toLowerCase(Locale.GERMAN);
        return l.matches("(?s).*(wichtig|bitte|elternbrief|mitbringen|unterschr|zahlung|euro|€|anmeld|frist|ausfall|entfällt|änderung|achtung|hitzefrei|unterrichtsfrei).*");
    }

    static LocalDate parsePublished(String p) {
        try { return LocalDate.parse(p.substring(0, 10)); } catch (Exception e) { return Cfg.now().toLocalDate(); }
    }

    static String firstLine(String t) {
        if (t == null) return "";
        String l = t.trim().split("\n", 2)[0];
        return l.length() > 80 ? l.substring(0, 80) + "…" : l;
    }

    static String shorten(String t, int n) {
        if (t == null) return "";
        String s = t.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    static String sentenceAround(String text, int pos) {
        int a = Math.max(text.lastIndexOf(". ", Math.max(0, pos - 1)), text.lastIndexOf('\n', Math.max(0, pos - 1)));
        int b = text.indexOf(". ", pos);
        if (b < 0) b = text.length();
        return text.substring(a < 0 ? 0 : a + 1, Math.min(text.length(), b + 1)).trim();
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static long now() { return System.currentTimeMillis(); }

    // ---------------------------------------------------------------- notes -> one message

    /** Turns all pending notes into one natural message. Crash-safe: dedupe key = the item keys + hashes. */
    /** First EduPage run: one overview instead of one message per item. */
    public boolean summaryMode, summarySent;
    public String summaryPrefix;

    public void flushNotes() {
        flushNotes(false);
        flushNotes(true);
    }

    private void flushNotes(boolean test) {
        List<ContentValues> rows = db.query("SELECT src_key, note, text_hash FROM school_items WHERE status='notify' AND src_key "
                + (test ? "" : "NOT ") + "LIKE 'test:%' ORDER BY updated");
        if (rows.isEmpty()) return;
        long newest = 0;
        boolean waiting = false;
        for (ContentValues r : rows) {
            ContentValues u = db.one("SELECT updated FROM school_items WHERE src_key=?", r.getAsString("src_key"));
            if (u != null) newest = Math.max(newest, u.getAsLong("updated"));
            String note = r.getAsString("note");
            if (note != null) for (String n : note.split("\n")) {
                String[] p = n.split("\\|", -1);
                if (p.length > 10 && !p[10].isEmpty() && "local".equals(cal.syncState(Long.parseLong(p[10])))) waiting = true;
            }
        }
        long maxWait = summaryMode && !test ? 10 * 60_000L : 3 * 60_000L;
        if (waiting && !test && System.currentTimeMillis() - newest < maxWait) return;
        List<String> lines = new ArrayList<>();
        List<String[]> overview = new ArrayList<>(); // {startMs, line}
        List<String> infos = new ArrayList<>();
        int unsynced = 0;
        StringBuilder key = new StringBuilder("school-notes");
        for (ContentValues r : rows) {
            key.append(":").append(r.getAsString("src_key")).append("@").append(r.getAsString("text_hash").substring(0, 8));
            String note = r.getAsString("note");
            if (note == null) continue;
            for (String n : note.split("\n")) {
                String[] p = n.split("\\|", -1);
                if (summaryMode && !test && (p[0].equals("created") || p[0].equals("updated") || p[0].equals("adopted"))) {
                    long ms = Long.parseLong(p[3]);
                    String range = "";
                    ContentValues mm = db.one("SELECT end_ms FROM cal_map WHERE title=? AND start_ms=?", p[2], p[3]);
                    if (mm != null && "1".equals(p[4]) && mm.getAsLong("end_ms") - ms > 86_400_000L) range = "–" + shortWhen(mm.getAsLong("end_ms") - 86_400_000L, true);
                    overview.add(new String[]{String.valueOf(ms), "• " + shortWhen(ms, "1".equals(p[4])) + range + ": " + p[2]
                            + (p.length > 9 && !p[9].isEmpty() ? " (" + p[9] + ")" : "")
                            + (p[0].equals("adopted") ? " (stand schon im Kalender)" : ""), p.length > 10 && !p[10].isEmpty() ? p[10] : "0"});
                } else if (summaryMode && !test && p[0].equals("info")) {
                    infos.add("• " + p[1] + ": " + shorten(p[2], 160));
                } else {
                    String l = render(n);
                    if (l != null) lines.add(l);
                }
            }
        }
        String text;
        if (summaryMode && !test) {
            unsynced = 0;
            for (String[] o : overview) if (o.length > 2 && "local".equals(cal.syncState(Long.parseLong(o[2])))) unsynced++;
            overview.sort((a, b) -> Long.compare(Long.parseLong(a[0]), Long.parseLong(b[0])));
            StringBuilder b = new StringBuilder(summaryPrefix == null ? "" : summaryPrefix);
            b.append("Ich habe EduPage durchgesehen und deinen Kalender eingerichtet, Boss.");
            if (!overview.isEmpty()) {
                b.append("\n\nKommende Schultermine:\n");
                for (String[] o : overview) b.append(o[1]).append("\n");
                b.append(unsynced == 0 ? "Alles ist mit Google synchronisiert." : unsynced + " Einträge sind noch nicht von Google bestätigt, ich behalte das im Blick.");
                b.append("\nErinnerungen: ganztägige Termine am Vortag um 17 Uhr, Fristen am Vortag um 7 Uhr.");
            } else {
                b.append(" Es stehen keine neuen Schultermine an.");
            }
            if (!infos.isEmpty()) { b.append("\n\nNeue Infos:\n"); for (String i : infos) b.append(i).append("\n"); }
            if (!lines.isEmpty()) { b.append("\n"); for (String l : lines) b.append(l).append("\n"); }
            b.append("\nAb jetzt melde ich mich nur, wenn sich etwas ändert.");
            text = b.toString().trim();
            summarySent = true;
        } else {
            text = compose(lines);
        }
        if (text != null && test) text = "[TEST] " + text;
        db.getWritableDatabase().beginTransaction();
        try {
            if (text != null) Outbox.enqueue(ctx, sha(key.toString()), text, null, null);
            for (ContentValues r : rows) db.update("school_items", Db.cv("status", "done"), "src_key=?", r.getAsString("src_key"));
            db.getWritableDatabase().setTransactionSuccessful();
        } finally {
            db.getWritableDatabase().endTransaction();
        }
    }

    static String compose(List<String> lines) {
        if (lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        StringBuilder b = new StringBuilder("Neues aus EduPage, Boss:\n");
        for (String l : lines) b.append("• ").append(l).append("\n");
        return b.toString().trim();
    }

    private String render(String note) {
        String[] p = note.split("\\|", -1);
        try {
            switch (p[0]) {
                case "created": case "updated": {
                    if (p.length > 10 && !p[10].isEmpty()) p[5] = cal.syncState(Long.parseLong(p[10]));
                    boolean allDay = "1".equals(p[4]);
                    String when = when(Long.parseLong(p[3]), allDay);
                    String sync = "synced".equals(p[5]) ? "" : " Er ist erst lokal gespeichert, der Abgleich mit Google steht noch aus.";
                    String rem = remText(Integer.parseInt(p[7]), allDay, "DEADLINE".equals(p[1]));
                    String noTime = p.length > 8 && "1".equals(p[8]) ? " Eine Uhrzeit stand nicht dabei, deshalb ganztägig." : "";
                    if ("created".equals(p[0])) {
                        if ("DEADLINE".equals(p[1])) return "Frist notiert: „" + p[2] + "“ am " + when + "." + rem + sync;
                        String topic = p.length > 9 && !p[9].isEmpty() ? " (Thema: " + p[9] + ")" : "";
                        return "Ich habe „" + p[2] + "“" + topic + " am " + when + " in deinen Kalender eingetragen." + noTime + rem + sync;
                    }
                    return "„" + p[2] + "“ wurde geändert (" + p[6] + "), jetzt am " + when + ". Den Kalendereintrag habe ich angepasst." + sync;
                }
                case "moved":
                    return "Die Schule hat „" + p[1] + "“ verschoben: von " + when(Long.parseLong(p[2]), "1".equals(p[4]))
                            + " auf " + when(Long.parseLong(p[3]), "1".equals(p[4])) + ". Der Eintrag ist aktualisiert."
                            + ("synced".equals(p[5]) ? "" : " (Google-Abgleich steht noch aus.)");
                case "cancel":
                    return "„" + p[1] + "“ am " + when(Long.parseLong(p[2]), "1".equals(p[3])) + " ist abgesagt. Ich habe den Termin als ABGESAGT markiert.";
                case "adopted":
                    return "„" + p[2] + "“ am " + when(Long.parseLong(p[3]), "1".equals(p[4])) + " steht schon in deinem Kalender (als „" + p[5] + "“) – ich habe nichts doppelt angelegt.";
                case "info":
                    return "Info von " + p[1] + ": " + p[2];
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String shortWhen(long ms, boolean allDay) {
        java.time.ZonedDateTime z = allDay ? java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC) : java.time.Instant.ofEpochMilli(ms).atZone(Cfg.ZONE);
        String pat = z.getYear() == Cfg.now().getYear() ? "EE dd.MM." : "EE dd.MM.yyyy";
        return z.format(DateTimeFormatter.ofPattern(pat, Locale.GERMAN)) + (allDay ? "" : " " + z.format(DateTimeFormatter.ofPattern("H:mm")));
    }

    static String when(long ms, boolean allDay) {
        java.time.ZonedDateTime z = allDay ? java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
                : java.time.Instant.ofEpochMilli(ms).atZone(Cfg.ZONE);
        String d = z.format(DAYW);
        return allDay ? d : d + " um " + z.format(DateTimeFormatter.ofPattern("H:mm")) + " Uhr";
    }

    static String remText(int minutes, boolean allDay, boolean deadline) {
        if (minutes < 0) return "";
        if (allDay) {
            int h = 24 - minutes / 60, m = (60 - minutes % 60) % 60;
            if (minutes % 60 != 0) h -= 1;
            if (minutes <= 1440) return String.format(Locale.GERMAN, " Erinnerung: am Vortag um %d:%02d Uhr.", h % 24, m);
            return " Erinnerung: " + (minutes / 1440) + " Tage vorher.";
        }
        if (minutes == 1440) return " Die Erinnerung ist auf den Vortag eingestellt.";
        if (minutes % 1440 == 0) return " Erinnerung: " + (minutes / 1440) + " Tage vorher.";
        if (minutes % 60 == 0) return " Erinnerung: " + (minutes / 60) + " Stunden vorher.";
        return " Erinnerung: " + minutes + " Minuten vorher.";
    }
}
