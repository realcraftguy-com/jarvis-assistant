package de.felix.jarvis;

import android.content.Context;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** User-adjustable settings (stored in the kv table, editable in the app). */
public final class Cfg {
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    // key, default, label shown in the settings screen
    public static final String[][] FIELDS = {
            {"brain", "beides", "Gehirn: beides (Claude, Ersatz API) · nur-claude · nur-api"},
            {"school_enabled", "0", "Schulfunktionen (1 = an, 0 = aus)"},
            {"user_name", "Max", "Dein Vorname (so spricht Jarvis dich an)"},
            {"user_about", "", "Über dich, kurz (z. B. „8. Klasse, Musterschule (8a), wohnt in Musterstadt“)"},
            {"assistant_name", "Jarvis", "Name des Assistenten"},
            {"weather_lat", "", "Wohnort für das Wetter: Breitengrad (z. B. 48.137)"},
            {"weather_lon", "", "Wohnort für das Wetter: Längengrad (z. B. 11.575)"},
            {"edu_subdomain", "", "EduPage-Schule (Subdomain, z. B. „meineschule“ aus meineschule.edupage.org)"},
            {"edu_poll_min", "30", "EduPage prüfen alle … Minuten"},
            {"rem_timed_min", "1440", "Erinnerung bei Terminen mit Uhrzeit (Minuten vorher)"},
            {"rem_allday_min", "420", "Erinnerung bei ganztägigen Terminen (420 = Vortag 17:00)"},
            {"rem_deadline_min", "1020", "Erinnerung bei Fristen (1020 = Vortag 07:00)"},
            {"briefing_enabled", "1", "Briefings von selbst schicken (1/0)"},
            {"briefing_morning", "06:45", "Morgen-Briefing an Schultagen um"},
            {"briefing_week", "18:30", "Wochenvorschau sonntags um"},
            {"mail_address", "", "E-Mail-Adresse (Jarvis liest nur, verschickt nie etwas)"},
            {"mail_imap", "", "IMAP-Server (leer = automatisch, z. B. imap.gmail.com)"},
            {"voice_replies", "spiegeln", "Sprachantworten in Telegram: spiegeln (auf Sprachnachricht), immer, aus"},
            {"wake_call", "", "Weckanruf an Schultagen um (z. B. 06:40, leer = aus)"},
            {"hw_check", "18:30", "Hausaufgaben-Abfrage am Vorabend um"},
            {"study_plan", "1", "Lernplan vor Prüfungen (1/0)"},
            {"quiz_call", "18:00", "Abfrage-Anruf am Abend vor Prüfungen um (leer = aus)"},
            {"vocab_time", "17:00", "Vokabel-Erinnerung täglich um (leer = aus)"},
            {"vision_model", "gemini-flash-latest", "Modell zum Lesen von Fotos (Gemini)"},
            {"search_model", "gemini-2.5-flash", "Modell für Websuche und YouTube (Gemini, mit Google-Suche)"},
            {"quiet_start", "21:30", "Ruhezeit ab"},
            {"quiet_end", "07:00", "Ruhezeit bis"},
            {"call_enabled", "1", "Jarvis darf mich anrufen (1/0)"},
            {"call_max_day", "2", "Max. Anrufe von Jarvis pro Tag"},
            {"call_min_urgency", "3", "Anruf erst ab Dringlichkeit (1–3)"},
            {"claude_model", "opus", "Claude-Modell (opus = Claude Opus 5.5)"},
            {"llm_model", "qwen/qwen3.8-27b", "KI-Modell (Groq)"},
            {"llm_fallback_model", "openai/gpt-oss-120b", "KI-Ersatzmodell (Groq)"},
            {"llm_daily_budget", "600", "Max. KI-Anfragen pro Tag (Groq-Limit 1000)"},
            {"live_model", "gemini-3.8-live", "Telefon-Stimme: Gemini-Live-Modell"},
            {"live_voice", "Charon", "Telefon-Stimme (z. B. Charon, Iapetus, Algieba, Orus, Sadaltager)"},
    };

    private Cfg() {}

    public static String get(Context c, String key) {
        loadDefaults(c);
        for (String[] f : FIELDS) if (f[0].equals(key)) return Db.get(c).kv("cfg_" + key, def(f));
        return Db.get(c).kv("cfg_" + key, null);
    }

    /** jarvis.properties (repo root, packed into the APK): the starting values; the app settings override them. */
    private static volatile java.util.Properties defaults;

    static void loadDefaults(Context c) {
        if (defaults != null) return;
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = c.getAssets().open("jarvis.properties")) {
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        defaults = p;
    }

    private static String def(String[] f) {
        java.util.Properties p = defaults;
        String v = p == null ? null : p.getProperty(f[0]);
        return v == null ? f[1] : v.trim();
    }

    /** "beides" (Claude first, the API key as fallback), "nur-claude" or "nur-api". Old values are understood too. */
    public static String brainMode(Context c) {
        String b = get(c, "brain");
        b = b == null ? "" : b.trim().toLowerCase(java.util.Locale.ROOT);
        if (b.equals("groq") || b.equals("api") || b.equals("nur-api") || b.equals("nur api")) return "nur-api";
        if (b.equals("nur-claude") || b.equals("nur claude")) return "nur-claude";
        return "beides";
    }

    public static boolean school(Context c) { return "1".equals(get(c, "school_enabled")); }

    public static int getInt(Context c, String key) {
        try { return Integer.parseInt(get(c, key).trim()); } catch (Exception e) {
            for (String[] f : FIELDS) if (f[0].equals(key)) return Integer.parseInt(f[1]);
            return 0;
        }
    }

    public static void set(Context c, String key, String v) { Db.get(c).kvSet("cfg_" + key, v); }

    /** Setting without a Context (prompt texts); falls back to the default before the database is open. */
    static String peek(String key) {
        Db d = Db.peek();
        for (String[] f : FIELDS) if (f[0].equals(key)) return d == null ? def(f) : d.kv("cfg_" + key, def(f));
        return d == null ? null : d.kv("cfg_" + key, null);
    }

    /** The user's first name, e.g. "the user". */
    static String user() {
        String n = peek("user_name");
        return n == null || n.trim().isEmpty() ? "Max" : n.trim();
    }

    /** Genitive for prompt texts: "the user's", "Annas". */
    static String userGen() {
        String n = user();
        return n.matches("(?i).*[sßxz]") ? n + "'" : n + "s";
    }

    static String assistant() {
        String n = peek("assistant_name");
        return n == null || n.trim().isEmpty() ? "Jarvis" : n.trim();
    }

    /** Pause: no automatic actions (polling, sending, calls). Stop additionally ends the service. */
    public static boolean paused(Context c) { return "1".equals(Db.get(c).kv("paused", "0")); }

    public static void setPaused(Context c, boolean p) {
        Db.get(c).kvSet("paused", p ? "1" : "0");
        Db.get(c).log("I", "system", p ? "Pausiert – keine automatischen Aktionen" : "Fortgesetzt");
    }

    public static boolean inQuietHours(Context c, ZonedDateTime now) {
        try {
            LocalTime s = LocalTime.parse(get(c, "quiet_start")), e = LocalTime.parse(get(c, "quiet_end")), t = now.toLocalTime();
            return s.isAfter(e) ? (t.isAfter(s) || t.isBefore(e)) : (t.isAfter(s) && t.isBefore(e));
        } catch (Exception ex) {
            return false;
        }
    }

    public static ZonedDateTime now() { return ZonedDateTime.now(ZONE); }
}
