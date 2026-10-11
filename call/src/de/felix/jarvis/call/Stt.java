package de.felix.jarvis.call;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** German speech recognition with Groq Whisper (free tier: 2000 requests / 28 800 audio seconds per day). */
final class Stt {
    private Stt() {}

    static String transcribe(Context c, byte[] pcm, int rate) throws Exception {
        String key = Vault.secret(c, Vault.GROQ);
        if (key == null) throw new IllegalStateException("kein Sprachschlüssel – Jarvis überträgt ihn nach der Kopplung");
        byte[] wav = wav(pcm, rate);
        String boundary = "----jv" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", "whisper-large-v3-turbo");
        field(body, boundary, "language", "de");
        field(body, boundary, "temperature", "0");
        field(body, boundary, "response_format", "json");
        field(body, boundary, "prompt", "Gespräch mit Jarvis, dem persönlichen Assistenten.");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection h = (HttpURLConnection) new URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection();
        h.setRequestMethod("POST");
        h.setConnectTimeout(8000);
        h.setReadTimeout(20000);
        h.setDoOutput(true);
        h.setRequestProperty("Authorization", "Bearer " + key);
        h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        byte[] b = body.toByteArray();
        h.setFixedLengthStreamingMode(b.length);
        try (OutputStream os = h.getOutputStream()) { os.write(b); }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        String resp = read(in);
        if (code == 429) throw new Exception("Groq-Limit für Spracherkennung erreicht");
        if (code >= 400) throw new Exception("HTTP " + code);
        return new JSONObject(resp).optString("text", "").trim();
    }

    private static void field(ByteArrayOutputStream o, String b, String name, String value) throws Exception {
        o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    static byte[] wav(byte[] pcm, int rate) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(pcm.length + 44);
        int byteRate = rate * 2;
        o.write('R'); o.write('I'); o.write('F'); o.write('F');
        le32(o, 36 + pcm.length);
        o.write('W'); o.write('A'); o.write('V'); o.write('E'); o.write('f'); o.write('m'); o.write('t'); o.write(' ');
        le32(o, 16); le16(o, 1); le16(o, 1); le32(o, rate); le32(o, byteRate); le16(o, 2); le16(o, 16);
        o.write('d'); o.write('a'); o.write('t'); o.write('a');
        le32(o, pcm.length);
        o.write(pcm, 0, pcm.length);
        return o.toByteArray();
    }

    private static void le32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }

    private static void le16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }
}
