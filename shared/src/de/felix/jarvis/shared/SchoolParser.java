package de.felix.jarvis.shared;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic German date/time/category extraction for school messages.
 * Pure Java (no Android, no org.json) so it is unit-tested on the PC.
 *
 * Principle: never invent a date. Every date produced here is grounded in the
 * text (or in the message's publication date for "morgen" etc.). Anything that
 * is not unambiguous is reported as a problem so the caller asks the user.
 */
public final class SchoolParser {

    public enum Role { EVENT, DEADLINE, UNKNOWN }

    public enum Category { EXAM, TRIP, EXCURSION, EVENT, DEADLINE, CANCELLATION, INFO }

    public static final class DateMention {
        public LocalDate date;
        public LocalDate endDate;          // for ranges ("12.–16. Oktober")
        public LocalTime start, end;       // optional clock time
        public Role role = Role.UNKNOWN;
        public String roleCue = "";        // e.g. "Abgabe", "Anmeldung" (what is due)
        public int pos, endPos;            // character span in the text
        public String raw;
        public boolean relative;           // derived from "morgen", "am Montag", ...
        public boolean previous;           // the old date of a moved appointment
        public boolean yearInferred;
        public String problem;             // non-null -> must not be used without asking

        @Override public String toString() {
            return role + " " + date + (endDate != null ? ".." + endDate : "")
                    + (start != null ? " " + start + (end != null ? "-" + end : "") : "")
                    + (problem != null ? " !" + problem : "") + " [" + raw + "]";
        }
    }

    public static final class Result {
        public Category category = Category.INFO;
        public String subject;             // "Mathe", "Deutsch", ... or null
        public String kindLabel;           // "Leistungsnachweis", "Ausflug", ...
        public List<DateMention> dates = new ArrayList<>();
        public List<String> problems = new ArrayList<>();
        public boolean cancelled, moved;
        public String location;

        public DateMention previousDate() {
            for (DateMention d : dates) if (d.previous) return d;
            return null;
        }

        public DateMention firstEvent() {
            for (DateMention d : dates) if (d.role == Role.EVENT && d.problem == null) return d;
            return null;
        }

        public List<DateMention> deadlines() {
            List<DateMention> out = new ArrayList<>();
            for (DateMention d : dates) if (d.role == Role.DEADLINE && d.problem == null) out.add(d);
            return out;
        }
    }

    private static final String[] MONTHS = {"januar", "februar", "märz", "april", "mai", "juni", "juli",
            "august", "september", "oktober", "november", "dezember"};
    private static final String MONTH_RE =
            "(jan(?:uar)?|feb(?:ruar)?|mär(?:z)?|maerz|apr(?:il)?|mai|jun(?:i)?|jul(?:i)?|aug(?:ust)?|sep(?:t(?:ember)?)?|okt(?:ober)?|nov(?:ember)?|dez(?:ember)?)\\.?";
    private static final String[] WEEKDAYS = {"montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag"};
    private static final String WD_RE = "(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|mo|di|mi|do|fr|sa|so)";

    // 12.10.2026 | 12.10.26 | 12.10. | 12.10 (with optional weekday and optional range)
    private static final Pattern NUMERIC = Pattern.compile(
            "(?:\\b" + WD_RE + "\\.?,?\\s*(?:den\\s+)?)?\\b(\\d{1,2})\\.\\s?(\\d{1,2})\\.(?:\\s?(\\d{4}|\\d{2})\\b)?"
                    + "(?:\\s*(?:-|–|bis(?:\\s+zum)?)\\s*(?:" + WD_RE + "\\.?,?\\s*)?(\\d{1,2})\\.\\s?(\\d{1,2})\\.(?:\\s?(\\d{4}|\\d{2})\\b)?)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // 12. Oktober 2026 | 12.-16. Oktober | 12. bis 16. Oktober | Montag, 12. Oktober
    private static final Pattern WORDY = Pattern.compile(
            "(?:\\b" + WD_RE + "\\.?,?\\s*(?:den\\s+)?)?\\b(\\d{1,2})\\.?\\s*(?:(?:-|–|bis(?:\\s+zum)?)\\s*(\\d{1,2})\\.?\\s*)?" + MONTH_RE + "(?:\\s+(\\d{4}))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RELATIVE = Pattern.compile(
            "\\b(heute|morgen|übermorgen|uebermorgen)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WEEKDAY_ONLY = Pattern.compile(
            "\\b(?:(nächsten|naechsten|kommenden|diesen|am|ab|bis(?:\\s+zum)?|bis)\\s+)?(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\\b(?!\\s*,?\\s*(?:den\\s+)?\\d)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VAGUE = Pattern.compile(
            "\\b(nächste woche|naechste woche|kommende woche|in (?:zwei|drei|vier|\\d) wochen|demnächst|demnaechst|nach den ferien|vor den ferien|ende (?:des monats|oktober|november|dezember|januar|februar|märz|april|mai|juni|juli))\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIME_RANGE = Pattern.compile(
            "\\b(?:von\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(?:uhr\\s*)?(?:-|–|bis)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*uhr\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_SINGLE = Pattern.compile(
            "\\b(?:um|ab|treffpunkt(?:\\s+ist)?(?:\\s+um)?)?\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*uhr\\b|\\b(\\d{1,2}):(\\d{2})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD = Pattern.compile(
            "\\b(\\d{1,2})\\.\\s*(?:(?:-|–|und|bis)\\s*(\\d{1,2})\\.\\s*)?(?:schul)?stunde", Pattern.CASE_INSENSITIVE);

    private static final String[] DEADLINE_CUES = {"abgabe", "abgeben", "abzugeben", "anmeldung", "anmelden",
            "rückgabe", "zurückgeben", "zurückzugeben", "zurück", "einsendeschluss", "frist", "spätestens", "einzureichen",
            "einreichen", "bezahlen", "zu bezahlen", "überweisen", "einsammeln", "mitbringen bis", "unterschrieben"};
    private static final String[] EVENT_CUES = {"findet", "statt", "fahren", "ausflug", "klassenfahrt", "schullandheim",
            "exkursion", "wandertag", "leistungsnachweis", "schulaufgabe", "stegreif", "kurzarbeit", "test", "klassenarbeit",
            "klausur", "prüfung", "theater", "elternabend", "projekttag", "schulfest", "besuch", "treffpunkt", "veranstaltung",
            "wir schreiben", "geschrieben", "abfahrt", "rückkehr", "konzert", "museum", "lauf", "sportfest", "bundesjugendspiele"};

    private static final Object[][] SUBJECTS = {
            {"Mathe", "mathematik", "mathe", "\\bm\\b"}, {"Deutsch", "deutsch", "\\bd\\b"}, {"Englisch", "englisch", "\\be\\b"},
            {"Französisch", "französisch", "franz"}, {"Latein", "latein"}, {"Spanisch", "spanisch"},
            {"Physik", "physik", "\\bph\\b"}, {"Chemie", "chemie", "\\bch\\b"}, {"Biologie", "biologie", "\\bbio\\b"},
            {"Natur und Technik", "natur und technik", "\\bnt\\b"}, {"Geschichte", "geschichte"},
            {"Geographie", "geographie", "erdkunde"}, {"Politik", "politik und gesellschaft", "politik", "sozialkunde"},
            {"GPG", "geschichte politik geographie", "\\bgpg\\b"}, {"Informatik", "informatik", "\\binf\\b"},
            {"Wirtschaft", "wirtschaft", "\\bwr\\b", "\\bbwr\\b"}, {"Kunst", "kunst"}, {"Musik", "musik"},
            {"Sport", "sport", "\\bs[mw]?\\b"}, {"Religion", "religion", "\\brum?\\b", "ethik"}, {"IT", "\\bit\\b"}};

    private SchoolParser() {}

    // ------------------------------------------------------------------ public API

    public static Result analyze(String text, LocalDate published, String eduType) {
        Result r = new Result();
        String t = text == null ? "" : text;
        String lower = t.toLowerCase(Locale.GERMAN);

        r.category = classify(lower, eduType);
        r.kindLabel = kindLabel(lower, eduType, r.category);
        r.subject = subjectOf(lower);
        r.cancelled = containsAny(lower, "entfällt", "fällt aus", "abgesagt", "findet nicht statt", "wird nicht stattfinden", "storniert");
        r.moved = containsAny(lower, "verschoben", "verlegt", "neuer termin", "stattdessen am", "statt am");
        if (r.cancelled && !r.moved) r.category = Category.CANCELLATION;
        r.location = location(t);

        r.dates = extractDates(t, published);
        Matcher vague = VAGUE.matcher(t);
        while (vague.find()) r.problems.add("„" + vague.group(1) + "“ ist kein eindeutiges Datum");

        if (r.moved) {
            DateMention target = null;
            for (DateMention d : r.dates) {
                String before = lower.substring(Math.max(0, d.pos - 25), d.pos);
                if (d.role != Role.DEADLINE && before.matches("(?s).*\\b(auf|auf den|neuer termin:?|neuer termin ist( der| am)?|stattdessen am|jetzt am|nun am)\\s*(den\\s*)?$")) target = d;
            }
            if (target != null) {
                for (DateMention d : r.dates) {
                    if (d == target) d.role = Role.EVENT;
                    else if (d.role != Role.DEADLINE) { d.previous = true; d.role = Role.UNKNOWN; if (d.problem == null) d.problem = "alter Termin"; }
                }
            }
        }
        if (containsAny(lower, "wird noch festgelegt", "noch nicht fest", "voraussichtlich", "eventuell", "evtl.", "unter vorbehalt", "wird noch bekannt")) {
            r.problems.add("Termin ist laut Text noch nicht fest");
            for (DateMention d : r.dates) if (d.role != Role.DEADLINE && d.problem == null) d.problem = "Termin noch nicht fest";
        }
        for (int i = 0; i + 1 < r.dates.size(); i++) {
            DateMention x = r.dates.get(i), y = r.dates.get(i + 1);
            String between = lower.substring(Math.min(x.endPos, y.pos), y.pos);
            if (between.matches("\\s*,?\\s*oder\\s*")) {
                r.problems.add("„" + x.raw + " oder " + y.raw + "“ – Termin nicht eindeutig");
                x.problem = y.problem = "Alternative Termine";
            }
        }
        // Only one date in a clear exam/trip/event message -> that is the event
        boolean anyEvent = false;
        for (DateMention d : r.dates) anyEvent |= d.role == Role.EVENT;
        if (!anyEvent) {
            List<DateMention> unknown = new ArrayList<>();
            for (DateMention d : r.dates) if (d.role == Role.UNKNOWN && d.problem == null) unknown.add(d);
            if (unknown.size() == 1 && r.category != Category.INFO && r.category != Category.DEADLINE) {
                unknown.get(0).role = Role.EVENT;
            } else if (unknown.size() > 1 && r.category != Category.INFO) {
                r.problems.add("mehrere Daten ohne klare Zuordnung (" + joinRaw(unknown) + ")");
            }
        }
        if (r.category == Category.EXAM) {
            List<DateMention> cand = new ArrayList<>();
            for (DateMention d : r.dates) if (d.role != Role.DEADLINE && d.problem == null) cand.add(d);
            if (cand.size() > 1 && !r.moved) {
                r.problems.add("mehrere mögliche Prüfungstermine (" + joinRaw(cand) + ")");
                for (DateMention d : cand) d.problem = "mehrere mögliche Termine";
            }
        }
        for (DateMention d : r.dates) if (d.problem != null && !d.previous && !d.problem.equals("mehrere mögliche Termine") && !d.problem.equals("Alternative Termine") && !d.problem.equals("Termin noch nicht fest")) r.problems.add(d.raw + ": " + d.problem);
        // A deadline-only message keeps category DEADLINE
        if (r.firstEvent() == null && !r.deadlines().isEmpty() && r.category != Category.CANCELLATION) {
            r.category = Category.DEADLINE;
        }
        return r;
    }

    /** All date mentions with role, time and problems. */
    public static List<DateMention> extractDates(String text, LocalDate published) {
        List<DateMention> out = new ArrayList<>();
        String lower = text.toLowerCase(Locale.GERMAN);

        Matcher m = WORDY.matcher(text);
        while (m.find()) {
            int day = Integer.parseInt(m.group(2));
            Integer day2 = m.group(3) != null ? Integer.parseInt(m.group(3)) : null;
            int month = monthIndex(m.group(4));
            if (month < 0) continue;
            Integer year = m.group(5) != null ? Integer.parseInt(m.group(5)) : null;
            DateMention d = new DateMention();
            d.pos = m.start(); d.endPos = m.end(); d.raw = m.group().trim();
            if (day2 != null) {
                d.date = build(day, month, year, published, d);
                d.endDate = d.date == null ? null : build(day2, month, year != null ? year : d.date.getYear(), published, d);
            } else {
                d.date = build(day, month, year, published, d);
            }
            checkWeekday(m.group(1), d);
            out.add(d);
        }
        m = NUMERIC.matcher(text);
        while (m.find()) {
            if (overlaps(out, m.start(), m.end())) continue;
            int day = Integer.parseInt(m.group(2)), month = Integer.parseInt(m.group(3));
            if (month < 1 || month > 12 || day < 1 || day > 31) continue;
            // "1. 2. Stunde" style false positives
            if (lower.startsWith("stunde", skipSpaces(lower, m.end()))) continue;
            Integer year = m.group(4) != null ? normYear(m.group(4)) : null;
            DateMention d = new DateMention();
            d.pos = m.start(); d.endPos = m.end(); d.raw = m.group().trim();
            d.date = build(day, month, year, published, d);
            checkWeekday(m.group(1), d);
            if (m.group(6) != null) {
                Integer y2 = m.group(8) != null ? normYear(m.group(8)) : (d.date != null ? d.date.getYear() : null);
                d.endDate = build(Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7)), y2, published, d);
                if (d.endDate != null && d.date != null && d.endDate.isBefore(d.date)) d.endDate = d.endDate.plusYears(1);
            }
            out.add(d);
        }
        m = RELATIVE.matcher(text);
        while (m.find()) {
            if (overlaps(out, m.start(), m.end())) continue;
            String w = m.group(1).toLowerCase(Locale.GERMAN);
            // "Morgen" as a greeting ("Guten Morgen") is not a date
            if (lower.substring(Math.max(0, m.start() - 7), m.start()).contains("guten")) continue;
            DateMention d = new DateMention();
            d.pos = m.start(); d.endPos = m.end(); d.raw = m.group(); d.relative = true;
            int plus = w.startsWith("heute") ? 0 : w.startsWith("morgen") ? 1 : 2;
            d.date = published.plusDays(plus);
            out.add(d);
        }
        m = WEEKDAY_ONLY.matcher(text);
        while (m.find()) {
            if (overlaps(out, m.start(), m.end())) continue;
            String mod = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.GERMAN);
            DayOfWeek dow = DayOfWeek.of(indexOf(WEEKDAYS, m.group(2).toLowerCase(Locale.GERMAN)) + 1);
            DateMention d = new DateMention();
            d.pos = m.start(); d.endPos = m.end(); d.raw = m.group().trim(); d.relative = true;
            LocalDate next = published.with(TemporalAdjusters.next(dow));
            long gap = ChronoUnit.DAYS.between(published, next);
            if (mod.startsWith("nächst") || mod.startsWith("naechst")) {
                // "nächsten Mittwoch" said on Monday: this week or next week? -> ask
                if (gap <= 2) d.problem = "„" + d.raw + "“ kann diese oder nächste Woche bedeuten";
            } else if (mod.isEmpty() || mod.equals("diesen")) {
                // bare weekday without "am/bis" is too weak a signal (e.g. "jeden Montag")
                if (mod.isEmpty() && !lower.substring(Math.max(0, m.start() - 6), m.start()).matches(".*\\b(am|ab|bis)\\s*$")) continue;
            }
            d.date = next;
            out.add(d);
        }
        out.sort((a, b) -> Integer.compare(a.pos, b.pos));
        for (DateMention d : out) {
            assignRole(lower, d);
            assignTime(text, lower, d);
            if (d.date != null && d.problem == null && !d.relative && published != null) {
                long diff = ChronoUnit.DAYS.between(published, d.date);
                if (diff < -1 && d.yearInferred) {
                    d.problem = "Datum liegt vor der Veröffentlichung";
                }
            }
            if (d.date == null && d.problem == null) d.problem = "ungültiges Datum";
        }
        return out;
    }

    // ------------------------------------------------------------------ classification

    public static Category classify(String lower, String eduType) {
        String et = eduType == null ? "" : eduType;
        switch (et) {
            case "bexam": case "sexam": case "oexam": case "rexam": case "pexam": case "testing": return Category.EXAM;
            case "trip": return Category.TRIP;
            case "excursion": return Category.EXCURSION;
            case "event": case "schoolevent": case "culture": case "parentsevening": case "ctevent": return Category.EVENT;
            default: break;
        }
        if (containsAny(lower, "schullandheim", "klassenfahrt", "skilager", "studienfahrt", "abschlussfahrt")) return Category.TRIP;
        if (containsAny(lower, "ausflug", "exkursion", "wandertag", "unterrichtsgang", "lerngang")) return Category.EXCURSION;
        if (containsAny(lower, "leistungsnachweis", "schulaufgabe", "stegreifaufgabe", "kurzarbeit", "klassenarbeit",
                "klausur", "prüfung", "probe", "vokabeltest") || lower.matches("(?s).*\\b(ln|ex|test)\\b.*")) return Category.EXAM;
        if (containsAny(lower, "elternabend", "schulfest", "projekttag", "theater", "konzert", "veranstaltung",
                "sportfest", "bundesjugendspiele", "gottesdienst", "infoabend", "elternsprechtag", "lesung")) return Category.EVENT;
        for (String c : DEADLINE_CUES) if (lower.contains(c)) return Category.DEADLINE;
        return Category.INFO;
    }

    static String kindLabel(String lower, String eduType, Category c) {
        if (containsAny(lower, "schulaufgabe")) return "Schulaufgabe";
        if (containsAny(lower, "stegreifaufgabe") || lower.matches("(?s).*\\bex\\b.*")) return "Stegreifaufgabe";
        if (containsAny(lower, "kurzarbeit")) return "Kurzarbeit";
        if (containsAny(lower, "leistungsnachweis") || lower.matches("(?s).*\\bln\\b.*")) return "Leistungsnachweis";
        if (containsAny(lower, "klassenarbeit")) return "Klassenarbeit";
        if (containsAny(lower, "vokabeltest")) return "Vokabeltest";
        if (containsAny(lower, "schullandheim")) return "Schullandheim";
        if (containsAny(lower, "klassenfahrt", "studienfahrt", "abschlussfahrt")) return "Klassenfahrt";
        if (containsAny(lower, "skilager")) return "Skilager";
        if (containsAny(lower, "wandertag")) return "Wandertag";
        if (containsAny(lower, "exkursion")) return "Exkursion";
        if (containsAny(lower, "ausflug")) return "Ausflug";
        if (containsAny(lower, "elternabend")) return "Elternabend";
        switch (c) {
            case EXAM: return "Test";
            case TRIP: return "Klassenfahrt";
            case EXCURSION: return "Ausflug";
            case EVENT: return "Schulveranstaltung";
            case DEADLINE: return "Frist";
            default: return "Schulinfo";
        }
    }

    public static String subjectOf(String lower) {
        for (Object[] s : SUBJECTS) {
            for (int i = 1; i < s.length; i++) {
                String k = (String) s[i];
                if (k.startsWith("\\b") ? k.length() > 7 && Pattern.compile(k).matcher(lower).find() : lower.contains(k)) {
                    return (String) s[0];
                }
            }
        }
        return null;
    }

    /** Maps an EduPage subject name/short name to a friendly label. */
    public static String friendlySubject(String name) {
        if (name == null || name.isEmpty()) return null;
        String lower = name.toLowerCase(Locale.GERMAN).trim();
        for (Object[] s : SUBJECTS) {
            for (int i = 1; i < s.length; i++) {
                String k = (String) s[i];
                if (k.startsWith("\\b") ? lower.matches(k.replace("\\b", "")) : lower.contains(k)) return (String) s[0];
            }
        }
        return name.trim();
    }

    // ------------------------------------------------------------------ helpers

    private static void assignRole(String lower, DateMention d) {
        int from = Math.max(0, d.pos - 70);
        // stop the look-back window at the previous sentence boundary or line break
        String before = lower.substring(from, d.pos);
        int cut = Math.max(Math.max(before.lastIndexOf(". "), before.lastIndexOf('\n')), Math.max(before.lastIndexOf("! "), before.lastIndexOf("? ")));
        if (cut >= 0) before = before.substring(cut + 1);
        String after = lower.substring(d.endPos, Math.min(lower.length(), d.endPos + 60));
        int stop = sentenceEnd(after);
        if (stop >= 0) after = after.substring(0, stop);
        String ctx = before + " # " + after;

        boolean dl = before.matches("(?s).*\\b(bis|spätestens|bis spätestens|bis zum|bis zur|bis einschließlich|frist)\\s*(?:zum|zur|am|den)?\\s*$")
                || before.matches("(?s).*\\bbis(?:\\s+(zum|spätestens))?\\b[^#]{0,15}$");
        String cue = null;
        for (String c : DEADLINE_CUES) if (ctx.contains(c)) { cue = c; break; }
        boolean ev = false;
        for (String c : EVENT_CUES) if (ctx.contains(c)) { ev = true; break; }

        if (dl || (cue != null && !ev)) {
            d.role = Role.DEADLINE;
            d.roleCue = cue == null ? "Frist" : cueLabel(cue, ctx);
        } else if (ev) {
            d.role = Role.EVENT;
        }
    }

    private static String cueLabel(String cue, String ctx) {
        if (cue.startsWith("anmeld")) return "Anmeldung";
        if (cue.startsWith("rückgabe") || cue.startsWith("zurück")) return "Rückgabe";
        if (cue.contains("bezahl") || cue.contains("überweis")) return "Bezahlung";
        if (cue.contains("unterschrieben") || ctx.contains("zettel") || ctx.contains("einverständnis")) return "Zettelabgabe";
        if (cue.startsWith("abgabe") || cue.startsWith("abgeb") || cue.startsWith("abzugeb") || cue.startsWith("einreich")
                || cue.startsWith("einzureich") || cue.startsWith("einsammeln")) return "Abgabe";
        return "Frist";
    }

    private static void assignTime(String text, String lower, DateMention d) {
        // look for a time in the same sentence, right after (or shortly before) the date
        int sEnd = d.endPos;
        int stop = sentenceEnd(lower.substring(sEnd));
        String after = stop >= 0 ? text.substring(sEnd, sEnd + stop) : text.substring(sEnd, Math.min(text.length(), sEnd + 80));
        if (after.length() > 80) after = after.substring(0, 80);
        Matcher r = TIME_RANGE.matcher(after);
        if (r.find()) {
            d.start = time(r.group(1), r.group(2));
            d.end = time(r.group(3), r.group(4));
            return;
        }
        Matcher s = TIME_SINGLE.matcher(after);
        if (s.find()) {
            d.start = s.group(1) != null ? time(s.group(1), s.group(2)) : time(s.group(3), s.group(4));
            return;
        }
        Matcher p = PERIOD.matcher(after);
        if (p.find()) {
            int a = Integer.parseInt(p.group(1));
            int b = p.group(2) != null ? Integer.parseInt(p.group(2)) : a;
            LocalTime[] pa = BellTimes.period(a), pb = BellTimes.period(b);
            if (pa != null && pb != null) { d.start = pa[0]; d.end = pb[1]; }
        }
    }

    private static LocalTime time(String h, String m) {
        int hh = Integer.parseInt(h), mm = m == null ? 0 : Integer.parseInt(m);
        if (hh > 23 || mm > 59) return null;
        return LocalTime.of(hh, mm);
    }

    private static LocalDate build(int day, int month, Integer year, LocalDate published, DateMention d) {
        try {
            if (year != null) return LocalDate.of(year, month, day);
            d.yearInferred = true;
            LocalDate ref = published != null ? published : LocalDate.now();
            LocalDate c = LocalDate.of(ref.getYear(), month, day);
            // a date shortly before the publication is a past reference, further back means next year
            if (c.isBefore(ref.minusDays(14))) c = c.plusYears(1);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static void checkWeekday(String wd, DateMention d) {
        if (wd == null || d.date == null) return;
        String w = wd.toLowerCase(Locale.GERMAN);
        int idx = -1;
        for (int i = 0; i < 7; i++) if (WEEKDAYS[i].startsWith(w)) { idx = i; break; }
        if (idx >= 0 && d.date.getDayOfWeek().getValue() != idx + 1) {
            d.problem = "Wochentag passt nicht: " + d.date + " ist ein "
                    + capital(WEEKDAYS[d.date.getDayOfWeek().getValue() - 1]) + ", im Text steht " + capital(WEEKDAYS[idx]);
        }
    }

    static String location(String t) {
        Matcher m = Pattern.compile("\\b(?:[Tt]reffpunkt(?: ist)?|[Oo]rt|in der|in den|in die|im|ins|zum|zur|nach)\\s*:?\\s+((?:[A-ZÄÖÜ][\\wäöüß\\-]+\\s?){1,4})").matcher(t);
        if (m.find()) {
            String loc = m.group(1).trim();
            if (loc.length() > 2 && !loc.matches("(?i)(Unterricht|Klasse|Stunde|Schule|Fach|Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember).*")) {
                return loc;
            }
        }
        return null;
    }

    private static boolean overlaps(List<DateMention> l, int s, int e) {
        for (DateMention d : l) if (s < d.endPos && e > d.pos) return true;
        return false;
    }

    private static int monthIndex(String s) {
        String k = s.toLowerCase(Locale.GERMAN).replace(".", "").replace("maerz", "märz");
        for (int i = 0; i < 12; i++) if (MONTHS[i].startsWith(k.length() >= 3 ? k.substring(0, 3) : k)) return i + 1;
        return -1;
    }

    private static Integer normYear(String y) {
        int v = Integer.parseInt(y);
        return y.length() == 2 ? 2000 + v : v;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    private static final Pattern SENTENCE_END = Pattern.compile("(?<![0-9])\\.\\s|\\n|[!?]\\s");

    private static int sentenceEnd(String s) {
        Matcher m = SENTENCE_END.matcher(s);
        return m.find() ? m.start() : -1;
    }

    private static int firstIndex(String s, String... needles) {
        int best = -1;
        for (String n : needles) {
            int i = s.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    private static String joinRaw(List<DateMention> l) {
        StringBuilder b = new StringBuilder();
        for (DateMention d : l) b.append(b.length() > 0 ? ", " : "").append(d.raw);
        return b.toString();
    }

    public static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
