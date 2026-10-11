package de.felix.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small free, key-less information tools. */
final class Tools {
    private Tools() {}

    /** Open-Meteo (free, no key) for the place in the settings. */
    static String weather(String date) {
        String lat = Cfg.peek("weather_lat"), lon = Cfg.peek("weather_lon");
        if (lat == null || lat.trim().isEmpty() || lon == null || lon.trim().isEmpty()) return "Wohnort fürs Wetter ist nicht eingestellt (Einstellungen: Breiten-/Längengrad).";
        try {
            Http.Resp r = Http.request("GET", "https://api.open-meteo.com/v1/forecast?latitude=" + lat.trim() + "&longitude=" + lon.trim()
                    + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=Europe%2FBerlin"
                    + "&start_date=" + date + "&end_date=" + date, null, null, 15000);
            JSONObject d = new JSONObject(r.body).getJSONObject("daily");
            return date + ": " + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + " bis "
                    + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + " °C, Regenwahrscheinlichkeit "
                    + d.getJSONArray("precipitation_probability_max").optInt(0) + " %, " + code(d.getJSONArray("weather_code").optInt(0));
        } catch (Exception e) {
            return "Wetter nicht abrufbar (" + e.getMessage() + ")";
        }
    }

    /** What matters today hour by hour: rain from when, cold/heat, storms. null if nothing worth a word. */
    static String weatherNotable(String date) {
        String lat = Cfg.peek("weather_lat"), lon = Cfg.peek("weather_lon");
        if (lat == null || lat.trim().isEmpty() || lon == null || lon.trim().isEmpty()) return null;
        try {
            Http.Resp r = Http.request("GET", "https://api.open-meteo.com/v1/forecast?latitude=" + lat.trim() + "&longitude=" + lon.trim()
                    + "&hourly=temperature_2m,precipitation_probability,weather_code&timezone=Europe%2FBerlin&start_date=" + date + "&end_date=" + date, null, null, 15000);
            JSONObject h = new JSONObject(r.body).getJSONObject("hourly");
            JSONArray t = h.getJSONArray("temperature_2m"), p = h.getJSONArray("precipitation_probability"), w = h.getJSONArray("weather_code");
            int rainFrom = -1, rainProb = 0, storm = -1;
            double min = 99, max = -99;
            for (int i = 7; i <= 20 && i < t.length(); i++) {
                min = Math.min(min, t.optDouble(i)); max = Math.max(max, t.optDouble(i));
                if (rainFrom < 0 && p.optInt(i) >= 55) { rainFrom = i; rainProb = p.optInt(i); }
                if (storm < 0 && w.optInt(i) >= 95) storm = i;
            }
            StringBuilder b = new StringBuilder();
            if (storm >= 0) b.append("Gewitter möglich ab ").append(storm).append(" Uhr. ");
            if (rainFrom >= 0) b.append("Regen ab etwa ").append(rainFrom).append(" Uhr (").append(rainProb).append(" %) – Schirm oder Regenjacke. ");
            if (min <= 3) b.append("Kalt: nur ").append(Math.round(min)).append(" °C am Morgen – dicke Jacke. ");
            if (max >= 29) b.append("Heiß: bis ").append(Math.round(max)).append(" °C – genug trinken. ");
            return b.length() == 0 ? null : b.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String code(int c) {
        if (c == 0) return "klar";
        if (c <= 3) return "bewölkt";
        if (c <= 48) return "Nebel";
        if (c <= 67) return "Regen";
        if (c <= 77) return "Schnee";
        if (c <= 82) return "Schauer";
        return "Gewitter";
    }

    static String wikipedia(String q) {
        try {
            Http.Resp r = Http.request("GET", "https://de.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=1&srsearch="
                    + Http.enc(q), null, null, 15000);
            JSONArray hits = new JSONObject(r.body).getJSONObject("query").getJSONArray("search");
            if (hits.length() == 0) return "Nichts gefunden.";
            String title = hits.getJSONObject(0).getString("title");
            Http.Resp s = Http.request("GET", "https://de.wikipedia.org/api/rest_v1/page/summary/" + Http.enc(title.replace(' ', '_')), null, null, 15000);
            return title + ": " + new JSONObject(s.body).optString("extract", "") + " (Quelle: Wikipedia)";
        } catch (Exception e) {
            return "Wikipedia nicht erreichbar (" + e.getMessage() + ")";
        }
    }
}
