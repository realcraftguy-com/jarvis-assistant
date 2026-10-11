package de.felix.jarvis;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a school's online platform from a name or an address: tries the obvious EduPage address, searches the web
 * (DuckDuckGo, no key), looks on the school homepage for links to known platforms and checks each candidate.
 */
final class SchoolFinder {
    static final class Candidate {
        String url, platform, note;
        boolean ok;

        /** EduPage subdomain, or null. */
        String eduSub() {
            Matcher m = Pattern.compile("https?://([a-z0-9-]+)\\.edupage\\.org").matcher(url);
            return m.find() ? m.group(1) : null;
        }

        @Override public String toString() { return (ok ? "✓ " : "✗ ") + platform + " – " + url + (note == null ? "" : " (" + note + ")"); }
    }

    /** Platforms Jarvis recognises. Only EduPage is read natively; every other page works in the general web mode. */
    static String platform(String url, String html) {
        String u = url.toLowerCase(Locale.ROOT), h = html == null ? "" : html.toLowerCase(Locale.ROOT);
        if (u.contains(".edupage.org")) return "EduPage";
        if (u.contains("webuntis.com") || h.contains("webuntis")) return "WebUntis";
        if (u.contains("/iserv") || h.contains("iserv-")) return "IServ";
        if (u.contains("schulmanager-online.de")) return "Schulmanager";
        if (u.contains("moodle") || h.contains("moodle")) return "Moodle";
        if (u.contains("itslearning")) return "itslearning";
        if (u.contains("schul.cloud") || u.contains("schulcloud")) return "Schulcloud";
        if (u.contains("sdui")) return "Sdui";
        if (u.contains("mebis")) return "mebis";
        if (u.contains("eltern-portal.org")) return "Eltern-Portal";
        return "Webseite";
    }

    static boolean native_(String platform) { return "EduPage".equals(platform); }

    /** Platform of a link judged by its host only, or null. Vendor homepages (webuntis.com, moodle.com …) are not a school's platform. */
    static String linkPlatform(String url) {
        try {
            URL u = new URL(url);
            String h = u.getHost().toLowerCase(Locale.ROOT), path = u.getPath().toLowerCase(Locale.ROOT);
            if (h.endsWith(".edupage.org") && !h.equals("www.edupage.org")) return "EduPage";
            if (h.endsWith("webuntis.com") && (u.getQuery() != null && u.getQuery().contains("school=") || path.contains("/webuntis/"))) return "WebUntis";
            if (path.startsWith("/iserv") && !h.contains("iserv.de")) return "IServ";
            if (h.endsWith("schulmanager-online.de")) return "Schulmanager";
            if (h.contains("moodle") && !h.equals("moodle.com") && !h.equals("moodle.org") && !h.endsWith(".moodle.com") && !h.endsWith(".moodle.org")) return "Moodle";
            if (h.contains("itslearning")) return "itslearning";
            if (h.contains("schul.cloud")) return "Schulcloud";
            if (h.endsWith("sdui.app") || h.endsWith("sdui.de")) return "Sdui";
            if (h.startsWith("lernplattform.mebis")) return "mebis";
            if (h.endsWith("eltern-portal.org") && !h.equals("www.eltern-portal.org")) return "Eltern-Portal";
        } catch (Exception ignored) {}
        return null;
    }

    /** Sites that list schools but are not one (directories, social media, maps). */
    static boolean directory(String url) {
        return url.matches("(?i)https?://([a-z0-9-]+\\.)*(wikipedia\\.org|schulen-vergleich|schulliste|meinestadt|gelbeseiten|facebook|instagram|youtube|linkedin|"
                + "google\\.|tripadvisor|yelp|kununu|stepstone|indeed|schulministerium|km\\.bayern|schulsuche|wikiwand|dastelefonbuch|11880|cylex|golocal|branchenbuch|"
                + "webuntis\\.com|moodle\\.(com|org)|edupage\\.org/?$|schulmanager-online\\.de/?$).*");
    }

    static final class Page {
        int code;
        String finalUrl, body;
    }

    /** GET with redirects followed across hosts, at most 400 KB. */
    static Page get(String url) { return get(url, "Mozilla/5.0 (Android) Jarvis-SchoolFinder"); }

    static Page get(String url, String agent) {
        Page p = new Page();
        try {
            String cur = url;
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", agent);
                c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9");
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) { cur = new URL(new URL(cur), loc).toString(); c.disconnect(); continue; }
                p.code = code;
                p.finalUrl = cur;
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0 && bo.size() < 400_000) bo.write(b, 0, n);
                    p.body = bo.toString("UTF-8");
                }
                c.disconnect();
                return p;
            }
        } catch (Exception e) {
            p.code = -1;
            p.body = e.getMessage();
        }
        return p;
    }

    /** Is there a working page of this platform at the url? */
    static Candidate check(String url) {
        Candidate c = new Candidate();
        c.url = url;
        Page p = get(url);
        String finalUrl = p.finalUrl == null ? url : p.finalUrl;
        c.platform = platform(finalUrl, p.body);
        if (p.code <= 0) { c.note = "nicht erreichbar"; return c; }
        if (c.platform.equals("EduPage")) {
            // unknown EduPage schools are redirected to the general edupage.org site
            String sub = c.eduSub();
            c.ok = p.code < 400 && sub != null && finalUrl.contains(sub + ".edupage.org");
            c.note = c.ok ? "EduPage-Schule gefunden" : "keine EduPage-Schule unter dieser Adresse";
            return c;
        }
        c.ok = p.code < 400;
        c.url = finalUrl;
        c.note = c.ok ? (native_(c.platform) ? null : "allgemeiner Web-Modus") : "HTTP " + p.code;
        return c;
    }

    static String slug(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"), Normalizer.Form.NFKD);
        return n.replaceAll("[^a-z0-9]", "");
    }

    /** Result links of a web search (DuckDuckGo HTML, the "lite" version as fallback; no key needed). */
    static List<String> search(String query) {
        List<String> out = new ArrayList<>();
        for (String engine : new String[]{"https://html.duckduckgo.com/html/?q=", "https://lite.duckduckgo.com/lite/?q="}) {
            Page p = get(engine + enc(query));
            if (p.body == null) continue;
            Matcher m = Pattern.compile("uddg=([^&\"']+)|href=\"(https?://[^\"]+)\"").matcher(p.body);
            while (m.find() && out.size() < 10) {
                try {
                    String u = m.group(1) != null ? URLDecoder.decode(m.group(1), "UTF-8") : m.group(2);
                    if (u.startsWith("http") && !u.contains("duckduckgo.com") && !out.contains(u)) out.add(u);
                } catch (Exception ignored) {}
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    private static final String UA = "Jarvis-SchoolFinder/1.0 (https://github.com/realcraftguy-com)";

    /**
     * The school's own website from open data: OpenStreetMap (schools with a "website" tag) and Wikidata
     * ("official website"). Both are meant for this kind of lookup; web search engines block automated queries.
     */
    private static long lastOsm;

    static List<String> homepages(String name) {
        List<String> out = new ArrayList<>();
        synchronized (SchoolFinder.class) { // OpenStreetMap's rule: at most one request per second
            long wait = lastOsm + 1100 - System.currentTimeMillis();
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
            lastOsm = System.currentTimeMillis();
        }
        Page osm = get("https://nominatim.openstreetmap.org/search?format=jsonv2&extratags=1&limit=5&q=" + enc(name), UA);
        if (osm.body != null) {
            Matcher m = Pattern.compile("\"(?:contact:)?website\":\"([^\"]+)\"").matcher(osm.body);
            while (m.find()) { String u = m.group(1).replace("\\/", "/"); if (!out.contains(u)) out.add(u.startsWith("http") ? u : "https://" + u); }
        }
        if (out.isEmpty()) {
            Page wd = get("https://www.wikidata.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=" + enc(name), UA);
            Matcher id = wd.body == null ? null : Pattern.compile("\"title\":\"(Q\\d+)\"").matcher(wd.body);
            for (int i = 0; id != null && id.find() && i < 3; i++) {
                Page c = get("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&property=P856&entity=" + id.group(1), UA);
                Matcher w = c.body == null ? null : Pattern.compile("\"value\":\"(https?:[^\"]+)\"").matcher(c.body);
                if (w != null && w.find()) { String u = w.group(1).replace("\\/", "/"); if (!out.contains(u)) out.add(u); }
            }
        }
        for (String u : search(name)) if (!directory(u) && !out.contains(u)) out.add(u); // if a search engine lets us
        return out;
    }

    /** "Max-Planck-Gymnasium Musterstadt" -> maxplanckgymnasiummusterstadt, maxplanckgymnasium, … (EduPage addresses). */
    static List<String> slugs(String name) {
        List<String> out = new ArrayList<>();
        String[] w = name.trim().replaceAll("(?i)\\b(in|bei|an der|am|im)\\b", " ").split("[\\s,]+");
        for (int n = w.length; n >= Math.max(1, w.length - 2); n--) {
            String sl = slug(String.join(" ", java.util.Arrays.copyOfRange(w, 0, n)));
            if (sl.length() >= 5 && !out.contains(sl)) out.add(sl);
        }
        return out;
    }

    /** Links from a page to known school platforms (school homepages usually link "EduPage", "WebUntis", "IServ" …). */
    static List<String> platformLinks(String base, String html) {
        List<String> out = new ArrayList<>();
        if (html == null) return out;
        Matcher m = Pattern.compile("href=[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find() && out.size() < 6) {
            try {
                String u = new URL(new URL(base), m.group(1)).toString();
                if (linkPlatform(u) != null && !out.contains(u)) out.add(u);
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Pages on the school's own site that talk about its apps (at most 3). */
    static List<String> infoPages(String base, String html) {
        List<String> out = new ArrayList<>();
        if (html == null) return out;
        Matcher m = Pattern.compile("href=[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find() && out.size() < 3) {
            try {
                URL u = new URL(new URL(base), m.group(1));
                if (!u.getHost().equals(new URL(base).getHost())) continue;
                if (u.toString().toLowerCase(Locale.ROOT).matches(".*(elternportal|eltern-portal|webuntis|untis|schulmanager|iserv|moodle|edupage|vertretungsplan|stundenplan|lernplattform).*")
                        && !out.contains(u.toString())) out.add(u.toString());
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Name ("Max-Planck-Gymnasium Musterstadt") or address ("meineschule.de"): checked candidates, best first. */
    static List<Candidate> find(String query) {
        String q = query.trim();
        Map<String, Candidate> found = new LinkedHashMap<>();
        if (q.matches("(?i)(https?://)?[a-z0-9.-]+\\.[a-z]{2,}(/.*)?")) {
            String url = q.startsWith("http") ? q : "https://" + q;
            Candidate c = check(url);
            found.put(c.url, c);
            Page p = get(c.url);
            for (String l : platformLinks(c.url, p.body)) found.putIfAbsent(l, check(l));
            for (String info : infoPages(c.url, p.body)) {
                Page ip = get(info);
                for (String l : platformLinks(ip.finalUrl == null ? info : ip.finalUrl, ip.body)) found.putIfAbsent(l, check(l));
            }
            if (!q.contains("/iserv")) {
                // IServ lives under /iserv on the school's own domain
                try {
                    Candidate is = check(new URL(new URL(url), "/iserv/").toString());
                    if (is.ok && "IServ".equals(is.platform)) found.putIfAbsent(is.url, is);
                } catch (Exception ignored) {}
            }
        } else {
            // 1. the obvious EduPage addresses (with and without the town)
            for (String sl : slugs(q)) {
                Candidate e = check("https://" + sl + ".edupage.org/login/");
                if (e.ok) { found.put(e.url, e); break; }
            }
            // 2. the school's own homepage, and the school apps it links to (plus IServ on its domain)
            for (String u : homepages(q)) {
                if (directory(u)) continue;
                if (linkPlatform(u) != null) { found.putIfAbsent(u, check(u)); continue; }
                Page p = get(u);
                if (p.code <= 0 || p.code >= 400) continue;
                for (String l : platformLinks(p.finalUrl, p.body)) found.putIfAbsent(l, check(l));
                // school apps are often linked from an info page ("Elternportal", "WebUntis", "Schulmanager" …)
                for (String info : infoPages(p.finalUrl, p.body)) {
                    Page ip = get(info);
                    for (String l : platformLinks(ip.finalUrl == null ? info : ip.finalUrl, ip.body)) found.putIfAbsent(l, check(l));
                }
                try {
                    Candidate is = check(new URL(new URL(p.finalUrl), "/iserv/").toString());
                    if (is.ok && "IServ".equals(is.platform)) found.putIfAbsent(is.url, is);
                } catch (Exception ignored) {}
                Candidate home = new Candidate();
                home.url = p.finalUrl; home.platform = "Webseite"; home.ok = true; home.note = "Schul-Homepage, allgemeiner Web-Modus";
                found.putIfAbsent(home.url, home);
                break; // the first real homepage is the school's
            }
        }
        // one entry per site (the shortest address: usually the login start page)
        Map<String, Candidate> perHost = new LinkedHashMap<>();
        for (Candidate c : found.values()) {
            if (directory(c.url) && !"EduPage".equals(c.platform)) continue;
            String host;
            try { host = new URL(c.url).getHost(); } catch (Exception e) { host = c.url; }
            Candidate have = perHost.get(host);
            if (have == null || (c.ok && !have.ok) || (c.ok == have.ok && c.url.replaceAll("\\?.*", "").length() < have.url.length())) {
                if (c.url.contains("?")) c.url = c.url.replaceAll("\\?.*", "");
                perHost.put(host, c);
            }
        }
        List<Candidate> l = new ArrayList<>(perHost.values());
        l.sort((a, b) -> Integer.compare(score(b), score(a)));
        return l;
    }

    private static int score(Candidate c) {
        return (c.ok ? 100 : 0) + ("EduPage".equals(c.platform) ? 20 : "Webseite".equals(c.platform) ? 0 : 10);
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }

    /** Text for chat/tool use. */
    static String describe(List<Candidate> l) {
        if (l.isEmpty()) return "Nichts gefunden. Gib die Adresse der Schulseite oder Schul-App direkt ein (z. B. meineschule.de oder meineschule.webuntis.com).";
        StringBuilder b = new StringBuilder("Gefunden:\n");
        for (Candidate c : l) b.append("• ").append(c).append("\n");
        b.append("Einrichten in der Jarvis-App unter Einstellungen → Schule: EduPage wird direkt gelesen, alles andere über die Anmeldung im eingebauten Browser und „Diese Seite beobachten“.");
        return b.toString();
    }
}
