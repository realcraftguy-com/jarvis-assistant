package de.felix.jarvis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal blocking HTTP helper (no third-party libraries). */
public final class Http {
    public static final class Resp {
        public int code;
        public String body;
        public Map<String, java.util.List<String>> headers;
        public String header(String k) {
            if (headers == null) return null;
            for (Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k) && !e.getValue().isEmpty()) return e.getValue().get(0);
            }
            return null;
        }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    private Http() {}

    public static Resp request(String method, String url, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(Math.min(timeoutMs, 15000));
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Jarvis/1.0 (Android)");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.headers = c.getHeaderFields();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? "" : readAll(in);
        c.disconnect();
        return r;
    }

    /** Raw download (e.g. a Telegram voice message). */
    public static byte[] bytes(String url, int timeoutMs, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(Math.min(timeoutMs, 15000));
        c.setReadTimeout(timeoutMs);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > maxBytes) throw new IOException("Datei zu groß");
            }
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    public static Resp postJson(String url, Map<String, String> headers, String json, int timeoutMs) throws IOException {
        java.util.HashMap<String, String> h = new java.util.HashMap<>();
        if (headers != null) h.putAll(headers);
        h.put("Content-Type", "application/json; charset=utf-8");
        return request("POST", url, h, json.getBytes(StandardCharsets.UTF_8), timeoutMs);
    }

    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    public static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
