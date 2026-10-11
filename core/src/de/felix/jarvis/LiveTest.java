package de.felix.jarvis;

import android.content.Context;

import de.felix.jarvis.shared.GeminiLive;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** Real Gemini Live session from the Jarvis phone with a text question: measures setup, first audio, tool calls. */
final class LiveTest {
    private LiveTest() {}

    static String run(Context c, String question) throws Exception {
        String key = Secrets.get(c, Secrets.GEMINI);
        if (key == null) return "Kein Gemini-Schlüssel eingetragen.";
        Agent agent = Brain.get(c).agent;
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        StringBuilder said = new StringBuilder(), tools = new StringBuilder();
        long[] firstAudio = {0};
        Object done = new Object();
        boolean[] complete = {false};
        GeminiLive[] live = new GeminiLive[1];
        long t0 = System.currentTimeMillis();
        live[0] = new GeminiLive(key, Cfg.get(c, "live_model"), Cfg.get(c, "live_voice"), CallLink.get(c).liveSystem(null),
                GeminiLive.declarations(agent.toolsFor("call")), new GeminiLive.Events() {
            long asked;
            @Override public void onReady() {}
            @Override public void onAudio(byte[] pcm) {
                if (firstAudio[0] == 0) firstAudio[0] = System.currentTimeMillis();
                audio.write(pcm, 0, pcm.length);
            }
            @Override public void onInterrupted() {}
            @Override public void onTurnComplete() {
                // a turn that only carried a tool call is not the answer: wait for spoken audio
                if (audio.size() > 0) synchronized (done) { complete[0] = true; done.notifyAll(); }
            }
            @Override public void onInputText(String t) {}
            @Override public void onOutputText(String t) { said.append(t); }
            @Override public void onToolCall(String id, String name, JSONObject args) {
                String r;
                try { r = agent.run(name, args); } catch (Exception e) { r = "Fehler: " + e.getMessage(); }
                tools.append(name).append(args).append(" ");
                try { live[0].sendToolResponse(id, name, r); } catch (Exception ignored) {}
            }
            @Override public void onClosed(String reason) { synchronized (done) { done.notifyAll(); } }
        });
        live[0].open(10000);
        long ready = System.currentTimeMillis();
        live[0].sendText(question);
        synchronized (done) { if (!complete[0]) done.wait(40000); }
        live[0].close();
        byte[] pcm = audio.toByteArray();
        File wav = new File(c.getFilesDir(), "live-test.wav");
        try (FileOutputStream o = new FileOutputStream(wav)) { o.write(wavHeader(pcm.length, 24000)); o.write(pcm); }
        String r = "Verbindung+Setup " + (ready - t0) + " ms, erste Sprache nach " + (firstAudio[0] == 0 ? "–" : (firstAudio[0] - ready) + " ms")
                + ", Audio " + pcm.length / 48 + " ms, Werkzeuge: " + (tools.length() == 0 ? "keine" : tools)
                + ", Antwort: " + said + (live[0].lastError != null ? " | Fehler: " + live[0].lastError : "");
        Db.get(c).kvSet("live_test", r);
        Db.get(c).kvSet("live_trace", String.join("\n", live[0].trace));
        return r;
    }

    static byte[] wavHeader(int len, int rate) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + len).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(len);
        return b.array();
    }
}
