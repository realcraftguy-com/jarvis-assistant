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
