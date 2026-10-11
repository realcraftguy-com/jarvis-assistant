package de.felix.jarvis.call;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.PowerManager;

import de.felix.jarvis.shared.GeminiLive;
import de.felix.jarvis.shared.Link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phone call with Gemini Live native audio: the voice model hears and speaks directly (natural prosody,
 * barge-in, server-side turn taking). Jarvis' knowledge and tools stay on the Jarvis phone: the setup comes from
 * there, and tool calls are relayed to it. If Gemini is unreachable the classic voice chain takes over.
 */
final class LiveEngine implements GeminiLive.Events {
    private final CallSession s;
    private final Context ctx;
    private GeminiLive live;
    private AudioTrack track;
    private Thread mic;
    private volatile boolean running, muted;
    private PowerManager.WakeLock proximity;
    private final Map<String, String[]> pendingTools = new ConcurrentHashMap<>(); // tid -> {geminiId, name}
    private final StringBuilder userLine = new StringBuilder(), jarvisLine = new StringBuilder(), transcript = new StringBuilder();
    private volatile long lastVoiceAt, turnStartedAt;
    private volatile boolean awaitingFirstAudio;
    private int answers;
    private long latencySum;

    LiveEngine(CallSession s) {
        this.s = s;
        ctx = s.ctx;
    }

    /** cfg from Jarvis: system, tools, model, voice, opening. Throws if Gemini cannot be reached. */
    void start(JSONObject cfg) throws Exception {
        running = true;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (s.connection == null) am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        String key = Vault.secret(ctx, Vault.GEMINI);
        String voice = Vault.get(ctx, "live_voice", cfg.optString("voice", "Charon"));
        live = new GeminiLive(key, cfg.optString("model", "gemini-3.8-live"), voice, cfg.getString("system"),
                cfg.optJSONArray("tools") == null ? new JSONArray() : cfg.getJSONArray("tools"), this);
        long t0 = System.currentTimeMillis();
        live.open(8000);
        Vault.log(ctx, "Gemini Live verbunden (" + (System.currentTimeMillis() - t0) + " ms, Stimme " + voice + ")");
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4, 24000))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
        if (!s.managed) route(false); // in the phone app's UI the user picks the route there
        mic = new Thread(this::micLoop, "jarvis-live-mic");
        mic.start();
        String opening = cfg.optString("opening", "");
        if (!opening.isEmpty()) {
            turnStartedAt = System.currentTimeMillis();
            awaitingFirstAudio = true;
            lastVoiceAt = turnStartedAt;
            live.sendText(opening);
        }
        s.setStatus("Hört zu");
    }

    void stop() {
        running = false;
        if (live != null) live.close();
        if (mic != null) mic.interrupt();
        if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) {} }
        if (proximity != null && proximity.isHeld()) proximity.release();
        AudioManager am = ctx.getSystemService(AudioManager.class);
        am.clearCommunicationDevice();
        if (s.connection == null) am.setMode(AudioManager.MODE_NORMAL);
        flushLines();
        sendTranscript();
        if (answers > 0) LinkService.send(ctx, "stats", CallSession.obj("text", "Gemini Live: Ø " + latencySum / answers + " ms bis Jarvis spricht (" + answers + " Antworten)"));
    }

    void setMuted(boolean m) { muted = m; }

    void route(boolean speaker) {
        if (s.connection != null) s.connection.route(speaker);
        else {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == (speaker ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) { am.setCommunicationDevice(d); break; }
            }
        }
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "jarvis:proximity");
        if (proximity != null) {
            if (!speaker && !proximity.isHeld()) proximity.acquire(60 * 60_000L);
            if (speaker && proximity.isHeld()) proximity.release();
        }
        s.speaker = speaker;
        s.changed();
    }

    /** Result of a tool Jarvis ran on the Jarvis phone. */
    void toolResult(String tid, String result) {
        String[] t = pendingTools.remove(tid);
        if (t == null || live == null) return;
        try { live.sendToolResponse(t[0], t[1], result); } catch (Exception e) { Vault.log(ctx, "Werkzeugantwort: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- microphone -> Gemini

    private void micLoop() {
        int rate = 16000, frame = 640; // 40 ms
        AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 2 * 8));
        } catch (SecurityException e) {
            s.setStatus("Mikrofon nicht erlaubt");
            return;
        }
        if (AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler a = AcousticEchoCanceler.create(ar.getAudioSessionId()); if (a != null) a.setEnabled(true); }
        if (NoiseSuppressor.isAvailable()) { NoiseSuppressor n = NoiseSuppressor.create(ar.getAudioSessionId()); if (n != null) n.setEnabled(true); }
        ar.startRecording();
        byte[] buf = new byte[frame * 2], silence = new byte[frame * 2];
        double noise = 300;
        try {
            while (running) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) continue;
                // local voice estimate only for measuring the answer latency (turn detection is Gemini's)
                double sum = 0;
                for (int i = 0; i + 1 < n; i += 2) { short v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8)); sum += (double) v * v; }
                double rms = Math.sqrt(sum / (n / 2.0));
                if (rms > Math.max(noise * 3, 700)) lastVoiceAt = System.currentTimeMillis(); else noise = noise * 0.97 + rms * 0.03;
                live.sendAudio(muted ? silence : buf, n);
            }
        } finally {
            try { ar.stop(); } catch (Exception ignored) {}
            ar.release();
        }
    }

    // ---------------------------------------------------------------- Gemini -> phone

    @Override public void onReady() {}

    @Override public void onAudio(byte[] pcm) {
        if (awaitingFirstAudio) {
            awaitingFirstAudio = false;
            long ref = Math.max(turnStartedAt, lastVoiceAt);
            long ms = System.currentTimeMillis() - ref;
            if (ms > 0 && ms < 15000) {
                answers++;
                latencySum += ms;
                recordLatency(ms);
            }
        }
        s.setStatus("Jarvis spricht");
        if (track != null && running) { try { track.write(pcm, 0, pcm.length); } catch (Exception ignored) {} } // hang-up may release it meanwhile
    }

    @Override public void onInterrupted() {
        // the user talks: drop what is still buffered, immediately
        if (track != null) { try { track.pause(); track.flush(); track.play(); } catch (Exception ignored) {} }
        flushJarvis(" …");
        s.setStatus("Hört zu");
    }

    @Override public void onTurnComplete() {
        flushJarvis("");
        awaitingFirstAudio = true;
        turnStartedAt = 0;
        s.setStatus("Hört zu");
    }

    @Override public void onInputText(String chunk) {
        if (jarvisLine.length() > 0) flushJarvis("");
        userLine.append(chunk);
        awaitingFirstAudio = true;
    }

    @Override public void onOutputText(String chunk) {
        if (userLine.length() > 0) {
            String u = userLine.toString().trim();
            userLine.setLength(0);
            if (!u.isEmpty()) { s.addLine("Du", u); transcript.append("F: ").append(u).append("\n"); }
        }
        jarvisLine.append(chunk);
    }

    @Override public void onToolCall(String id, String name, JSONObject args) {
        String tid = Link.randomId(6);
        pendingTools.put(tid, new String[]{id, name});
        s.setStatus("Schaut nach …");
        try {
            LinkService.send(ctx, "tool", new JSONObject().put("call", s.callId).put("tid", tid).put("name", name).put("args", args.toString()));
        } catch (Exception ignored) {}
    }

    @Override public void onClosed(String reason) {
        Vault.log(ctx, "Gemini Live beendet: " + reason);
        if (running) s.liveLost(reason);
    }

    // ---------------------------------------------------------------- transcript

    private void flushJarvis(String suffix) {
        String j = jarvisLine.toString().trim();
        jarvisLine.setLength(0);
        if (!j.isEmpty()) { s.addLine("Jarvis", j + suffix); transcript.append("J: ").append(j).append(suffix).append("\n"); }
    }

    private void flushLines() {
        String u = userLine.toString().trim();
        userLine.setLength(0);
        if (!u.isEmpty()) { s.addLine("Du", u); transcript.append("F: ").append(u).append("\n"); }
        flushJarvis("");
    }

    private void sendTranscript() {
        if (transcript.length() == 0) return;
        try {
            java.util.List<String> parts = Link.parts(Link.pack(transcript.toString()), 2600);
            String group = Link.randomId(6);
            for (int i = 0; i < parts.size(); i++) {
                LinkService.send(ctx, "transcript", new JSONObject().put("call", s.callId == null ? "" : s.callId)
                        .put("group", group).put("i", i).put("n", parts.size()).put("chunk", parts.get(i)));
            }
        } catch (Exception ignored) {}
    }

    private void recordLatency(long ms) {
        String prev = Vault.get(ctx, "latencies", "");
        String[] parts = prev.isEmpty() ? new String[0] : prev.split(",");
        StringBuilder b = new StringBuilder(String.valueOf(ms));
        for (int i = 0; i < Math.min(29, parts.length); i++) b.append(",").append(parts[i]);
        Vault.set(ctx, "latencies", b.toString());
        Vault.log(ctx, "Antwortzeit (Gemini Live): " + ms + " ms");
    }
}
