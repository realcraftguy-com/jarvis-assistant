package de.felix.jarvis;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import de.felix.jarvis.shared.GeminiLive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * Jarvis' telephone voice for Telegram: the answer text is spoken by Gemini Live (same voice as on the phone)
 * and packed as an OGG/Opus voice message.
 */
final class Voice {
    private Voice() {}

    /** 24 kHz mono PCM of the text, read out word for word. */
    static byte[] speak(Context c, String text) throws Exception {
        String key = Secrets.get(c, Secrets.GEMINI);
        if (key == null) throw new Exception("kein Gemini-Schlüssel");
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        Object done = new Object();
        boolean[] complete = {false};
        GeminiLive live = new GeminiLive(key, Cfg.get(c, "live_model"), Cfg.get(c, "live_voice"),
                "Du bist die Stimme von " + Cfg.assistant() + " (ruhig, souverän, trocken-humorvoll, natürlich, auf Deutsch). "
                        + "Du bekommst einen fertigen Text. Sprich ihn wörtlich, in ruhigem, eher langsamem Tempo mit kleinen Pausen zwischen den Sätzen, "
                        + "wie eine entspannte Sprachnachricht an einen Freund – "
                        + "nichts hinzufügen, nichts weglassen, nichts kommentieren.",
                new JSONArray(), new GeminiLive.Events() {
            @Override public void onReady() {}
            @Override public void onAudio(byte[] pcm) { synchronized (audio) { audio.write(pcm, 0, pcm.length); } }
            @Override public void onInterrupted() {}
            @Override public void onTurnComplete() { if (audio.size() > 0) synchronized (done) { complete[0] = true; done.notifyAll(); } }
            @Override public void onInputText(String t) {}
            @Override public void onOutputText(String t) {}
            @Override public void onToolCall(String id, String name, JSONObject args) {}
            @Override public void onClosed(String reason) { synchronized (done) { done.notifyAll(); } }
        });
        live.open(10000);
        live.sendText("Sprich diesen Text:\n" + text);
        synchronized (done) { if (!complete[0]) done.wait(60000); }
        live.close();
        if (audio.size() == 0) throw new Exception(live.lastError != null ? live.lastError : "keine Sprache erzeugt");
        return audio.toByteArray();
    }

    /** Text as it should be spoken: no list bullets, no markdown, no links. */
    static String spoken(String t) {
        return t.replaceAll("(?m)^\\s*[-•*]\\s+", "").replaceAll("https?://\\S+", "").replace("**", "").replace("#", "").trim();
    }

    /** PCM 16 bit mono -> OGG/Opus file (Telegram voice message). */
    static File ogg(Context c, byte[] pcm, int rate) throws Exception {
        // Telegram's player expects 48 kHz voice messages (a 24 kHz header plays far too fast there)
        if (rate != 48000) { pcm = resample(pcm, rate, 48000); rate = 48000; }
        File out = new File(c.getCacheDir(), "jarvis-voice.ogg");
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
        int track = -1, pos = 0;
        boolean inputDone = false;
        long frames = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                if (!inputDone) {
                    int in = enc.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = enc.getInputBuffer(in);
                        int n = Math.min(b.remaining(), Math.min(pcm.length - pos, 1920 * 2)); // 20 ms at 48 kHz
                        long pts = frames * 1_000_000L / rate;
                        if (n <= 0) {
                            enc.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            b.put(pcm, pos, n);
                            enc.queueInputBuffer(in, 0, n, pts, 0);
                            pos += n;
                            frames += n / 2;
                        }
                    }
                }
                int o = enc.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                } else if (o >= 0) {
                    ByteBuffer b = enc.getOutputBuffer(o);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && track >= 0) mux.writeSampleData(track, b, info);
                    enc.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            enc.stop();
            enc.release();
            try { mux.stop(); } catch (Exception ignored) {}
            mux.release();
        }
        return out;
    }

    /** Linear resampling of 16 bit mono PCM (good enough for speech). */
    static byte[] resample(byte[] pcm, int from, int to) {
        int n = pcm.length / 2, m = (int) ((long) n * to / from);
        byte[] out = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            double pos = (double) i * from / to;
            int a = (int) pos;
            double f = pos - a;
            int s0 = (short) ((pcm[2 * a] & 0xff) | (pcm[2 * a + 1] << 8));
            int s1 = a + 1 < n ? (short) ((pcm[2 * a + 2] & 0xff) | (pcm[2 * a + 3] << 8)) : s0;
            int v = (int) Math.round(s0 + (s1 - s0) * f);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    /** Spoken answer as a Telegram voice message (text as caption if it fits). Returns false if that did not work. */
    static boolean reply(Context c, String text) {
        try {
            long t0 = System.currentTimeMillis();
            byte[] pcm = speak(c, spoken(text));
            File f = ogg(c, pcm, 24000);
            new Telegram(c).sendVoice(f, text.length() <= 1000 ? text : null, pcm.length / 48);
            if (text.length() > 1000) Outbox.enqueue(c, "voicetext:" + System.currentTimeMillis(), text, null, null);
            Db.get(c).log("I", "telegram", "Sprachantwort gesendet (" + pcm.length / 48 / 1000 + " s Audio, " + (System.currentTimeMillis() - t0) + " ms)");
            return true;
        } catch (Exception e) {
            Db.get(c).log("W", "telegram", "Sprachantwort nicht möglich: " + e.getMessage());
            return false;
        }
    }
}
