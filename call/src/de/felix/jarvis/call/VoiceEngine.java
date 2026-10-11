package de.felix.jarvis.call;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Microphone -> voice activity detection -> speech recognition (Groq Whisper) -> Jarvis,
 * and Jarvis' text -> speech in the call audio path (USAGE_VOICE_COMMUNICATION, so the platform echo
 * canceller knows it). Barge-in stops Jarvis; an echo filter drops recognised text that is just
 * Jarvis' own voice coming back through the speaker.
 */
final class VoiceEngine {
    private static final int RATE = 16000, FRAME = 320; // 20 ms frames

    private final CallSession s;
    private final Context ctx;
    private volatile boolean running, muted;
    private Thread rec;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private final AtomicInteger speaking = new AtomicInteger();
    private volatile boolean discardUntilFinal;
    private final ArrayDeque<String> recentJarvis = new ArrayDeque<>();
    private final ExecutorService stt = Executors.newSingleThreadExecutor();
    private final List<String[]> pending = new ArrayList<>(); // say() before TTS is ready
    private PowerManager.WakeLock proximity;
    private volatile long lastSpeechEnd;
    private volatile boolean awaitingFirstAudio;
    // neural voice pipeline: sentences -> synth thread -> audio thread
    private final java.util.concurrent.LinkedBlockingQueue<String> toSynth = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.LinkedBlockingQueue<float[]> toPlay = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean neural;
    private android.media.AudioTrack track;
    private Thread synthThread, playThread;

    VoiceEngine(CallSession s) {
        this.s = s;
        ctx = s.ctx;
    }

    void start() {
        running = true;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (s.connection == null) am.setMode(AudioManager.MODE_IN_COMMUNICATION); // without Telecom
        if (!s.managed) route(false); // in the phone app's UI the user picks the route there
        tts = new TextToSpeech(ctx, st -> {
            if (st != TextToSpeech.SUCCESS) { Vault.log(ctx, "Sprachausgabe nicht verfügbar"); return; }
            tts.setLanguage(Locale.GERMANY);
            Voice v = pickVoice(tts, Vault.get(ctx, "voice", ""));
            if (v != null) tts.setVoice(v);
            tts.setSpeechRate(Float.parseFloat(Vault.get(ctx, "rate", "1.05")));
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    if (awaitingFirstAudio && lastSpeechEnd > 0) {
                        awaitingFirstAudio = false;
                        recordLatency(System.currentTimeMillis() - lastSpeechEnd);
                    }
                    s.setStatus("Jarvis spricht");
                }
                @Override public void onDone(String id) { if (speaking.decrementAndGet() <= 0) { speaking.set(0); s.setStatus("Hört zu"); } }
                @Override public void onError(String id) { onDone(id); }
                @Override public void onStop(String id, boolean interrupted) { speaking.set(0); }
            });
            ttsReady = true;
            synchronized (pending) {
                for (String[] p : pending) speak(p[0], "1".equals(p[1]));
                pending.clear();
            }
        });
        neural = "neural".equals(Vault.get(ctx, "voice_engine", "neural")) && NeuralVoice.downloaded(ctx);
        if (neural) startNeural();
        rec = new Thread(this::recordLoop, "jarvis-mic");
        rec.start();
        s.setStatus("Hört zu");
    }

    private void startNeural() {
        synthThread = new Thread(() -> {
            try {
                NeuralVoice v = NeuralVoice.get(ctx);
                int steps = Integer.parseInt(Vault.get(ctx, "neural_steps", "6"));
                float speed = Float.parseFloat(Vault.get(ctx, "neural_speed", "1.05"));
                while (running) {
                    String sentence = toSynth.take();
                    int gen = generation.get();
                    long t0 = System.currentTimeMillis();
                    float[] pcm = v.synth(sentence, steps, speed, () -> running && gen == generation.get());
                    Vault.set(ctx, "synth_ms_last", (System.currentTimeMillis() - t0) + "/" + (pcm.length * 1000L / v.sampleRate));
                    if (gen == generation.get() && pcm.length > 0) toPlay.put(pcm); else speaking.decrementAndGet();
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable e) {
                Vault.log(ctx, "Neuronale Stimme ausgefallen, nutze Google-Stimme: " + e.getMessage());
                neural = false;
                speaking.set(0);
                List<String> rest = new ArrayList<>();
                toSynth.drainTo(rest);
                for (String r : rest) speak(r, true);
            }
        }, "jarvis-synth");
        synthThread.start();
        playThread = new Thread(() -> {
            int rate = 44100;
            try { rate = NeuralVoice.get(ctx).sampleRate; } catch (Exception ignored) {}
            int min = android.media.AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
            track = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, rate * 4 / 5)).setTransferMode(android.media.AudioTrack.MODE_STREAM).build();
            track.play();
            try {
                while (running) {
                    float[] pcm = toPlay.take();
                    int gen = generation.get();
                    if (awaitingFirstAudio && lastSpeechEnd > 0) { awaitingFirstAudio = false; recordLatency(System.currentTimeMillis() - lastSpeechEnd); }
                    s.setStatus("Jarvis spricht");
                    float gain = Float.parseFloat(Vault.get(ctx, "neural_gain", "1.6"));
                    for (int i = 0; i < pcm.length; i++) pcm[i] = Math.max(-1f, Math.min(1f, pcm[i] * gain));
                    for (int off = 0; off < pcm.length && running && gen == generation.get(); ) {
                        int n = track.write(pcm, off, Math.min(4096, pcm.length - off), android.media.AudioTrack.WRITE_BLOCKING);
                        if (n <= 0) break;
                        off += n;
                    }
                    if (speaking.decrementAndGet() <= 0) {
                        speaking.set(0);
                        // let the buffered tail play out before listening normally again
                        android.os.SystemClock.sleep(250);
                        if (speaking.get() == 0) s.setStatus("Hört zu");
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                try { track.stop(); track.release(); } catch (Exception ignored) {}
            }
        }, "jarvis-play");
        playThread.start();
    }

    /** Sentences, so the first one is heard while the rest is still being synthesised. */
    static List<String> sentences(String t) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String part : t.split("(?<=[.!?…])\\s+")) {
            if (cur.length() > 0 && cur.length() + part.length() > 160) { out.add(cur.toString()); cur.setLength(0); }
            cur.append(cur.length() > 0 ? " " : "").append(part);
            if (cur.length() > 40) { out.add(cur.toString()); cur.setLength(0); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    void stop() {
        running = false;
        generation.incrementAndGet();
        if (rec != null) rec.interrupt();
        if (synthThread != null) synthThread.interrupt();
        if (playThread != null) playThread.interrupt();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (proximity != null && proximity.isHeld()) proximity.release();
        AudioManager am = ctx.getSystemService(AudioManager.class);
        am.clearCommunicationDevice();
        if (s.connection == null) am.setMode(AudioManager.MODE_NORMAL);
        stt.shutdownNow();
    }

    void setMuted(boolean m) { muted = m; }

    /** Earpiece (default, like a phone call) or loudspeaker. */
    void route(boolean speaker) {
        if (s.connection != null) s.connection.route(speaker);
        else {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == (speaker ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) {
                    am.setCommunicationDevice(d);
                    break;
                }
            }
        }
        // screen off at the ear, like a phone
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "jarvis:proximity");
        }
        if (proximity != null) {
            if (!speaker && !proximity.isHeld()) proximity.acquire(60 * 60_000L);
            if (speaker && proximity.isHeld()) proximity.release();
        }
        s.speaker = speaker;
        s.changed();
    }

    // ---------------------------------------------------------------- speaking

    void speak(String text, boolean last) {
        if (!ttsReady && !neural) { synchronized (pending) { pending.add(new String[]{text, last ? "1" : "0"}); } return; }
        if (discardUntilFinal) {
            if (last) discardUntilFinal = false;
            return;
        }
        String t = text.trim();
        if (t.isEmpty()) return;
        synchronized (recentJarvis) {
            recentJarvis.addFirst(t);
            while (recentJarvis.size() > 3) recentJarvis.removeLast();
        }
        if (neural) {
            for (String sentence : sentences(t)) { speaking.incrementAndGet(); toSynth.add(sentence); }
            return;
        }
        speaking.incrementAndGet();
        Bundle p = new Bundle();
        p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        tts.speak(t, TextToSpeech.QUEUE_ADD, p, "u" + System.nanoTime());
    }

    private void bargeIn() {
        if (speaking.get() > 0) {
            generation.incrementAndGet();
            toSynth.clear();
            toPlay.clear();
            if (track != null) { try { track.pause(); track.flush(); track.play(); } catch (Exception ignored) {} }
            if (tts != null) tts.stop();
            speaking.set(0);
            discardUntilFinal = true; // drop the rest of the interrupted answer
            Vault.log(ctx, "Unterbrochen");
        }
    }

    // ---------------------------------------------------------------- listening

    private void recordLoop() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 10));
        } catch (SecurityException e) {
            s.setStatus("Mikrofon nicht erlaubt");
            return;
        }
        if (AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler a = AcousticEchoCanceler.create(ar.getAudioSessionId()); if (a != null) a.setEnabled(true); }
        if (NoiseSuppressor.isAvailable()) { NoiseSuppressor n = NoiseSuppressor.create(ar.getAudioSessionId()); if (n != null) n.setEnabled(true); }
        ar.startRecording();
        short[] f = new short[FRAME];
        double noise = 300;
        ArrayDeque<short[]> preroll = new ArrayDeque<>();
        ByteArrayOutputStream utt = null;
        int loud = 0, quiet = 0, frames = 0;
        try {
            while (running) {
                int n = ar.read(f, 0, FRAME);
                if (n <= 0) continue;
                double rms = rms(f, n);
                boolean jarvisTalking = speaking.get() > 0;
                double on = jarvisTalking ? Math.max(noise * 5, 2200) : Math.max(noise * 3, 650);
                if (muted) { loud = 0; continue; }
                if (utt == null) {
                    preroll.addLast(f.clone());
                    if (preroll.size() > 15) preroll.removeFirst();
                    if (rms > on) loud++; else { loud = 0; noise = noise * 0.95 + rms * 0.05; }
                    int needed = jarvisTalking ? 15 : 4; // barge-in needs 300 ms of clear speech
                    if (loud >= needed) {
                        if (jarvisTalking) bargeIn();
                        utt = new ByteArrayOutputStream();
                        for (short[] p : preroll) write(utt, p, p.length);
                        preroll.clear();
                        quiet = 0;
                        frames = loud;
                        s.setStatus("Hört zu …");
                    }
                } else {
                    write(utt, f, n);
                    frames++;
                    if (rms < on * 0.6) quiet++; else quiet = 0;
                    if (quiet >= 35 || frames > 1000) { // 700 ms pause or 20 s
                        byte[] pcm = utt.toByteArray();
                        utt = null;
                        loud = 0;
                        long end = System.currentTimeMillis();
                        if (frames - quiet >= 12) recognise(pcm, end); // >= 240 ms of speech
                    }
                }
            }
        } finally {
            ar.stop();
            ar.release();
        }
    }

    private void recognise(byte[] pcm, long speechEnd) {
        s.setStatus("Versteht …");
        if (!running || stt.isShutdown()) return;
        try { stt.submit(() -> {
            long t0 = System.currentTimeMillis();
            String text;
            try {
                text = Stt.transcribe(ctx, pcm, RATE);
            } catch (Exception e) {
                Vault.log(ctx, "Spracherkennung: " + e.getMessage());
                s.setStatus("Spracherkennung gestört");
                return;
            }
            long sttMs = System.currentTimeMillis() - t0;
            if (text == null || text.isEmpty() || isHallucination(text) || isEcho(text)) {
                if (speaking.get() == 0) s.setStatus("Hört zu");
                return;
            }
            lastSpeechEnd = speechEnd;
            awaitingFirstAudio = true;
            discardUntilFinal = false;
            s.setStatus("Denkt nach …");
            s.utterance(text, sttMs, speechEnd);
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    // ---------------------------------------------------------------- filters

    private static final String[] HALLUCINATIONS = {"untertitel", "vielen dank fürs zuschauen", "copyright", "swr", "zdf",
            "amara.org", "bis zum nächsten mal", "danke fürs zuhören"};

    static boolean isHallucination(String t) {
        String l = t.toLowerCase(Locale.GERMAN).trim();
        for (String h : HALLUCINATIONS) if (l.contains(h)) return true;
        return l.replaceAll("[^a-zäöüß]", "").length() < 2;
    }

    /** True if the text is mostly Jarvis' own recent words (heard back through the speaker). */
    boolean isEcho(String t) {
        Set<String> heard = words(t);
        if (heard.size() < 2) return false;
        Set<String> jarvis = new HashSet<>();
        synchronized (recentJarvis) { for (String r : recentJarvis) jarvis.addAll(words(r)); }
        int common = 0;
        for (String w : heard) if (jarvis.contains(w)) common++;
        boolean echo = common >= Math.max(2, Math.ceil(heard.size() * 0.7));
        if (echo) Vault.log(ctx, "Eigenes Echo verworfen: " + t);
        return echo;
    }

    static Set<String> words(String t) {
        Set<String> out = new HashSet<>();
        for (String w : t.toLowerCase(Locale.GERMAN).replaceAll("[^a-zäöüß0-9 ]", " ").split("\\s+")) if (w.length() > 2) out.add(w);
        return out;
    }

    private static double rms(short[] f, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) f[i] * f[i];
        return Math.sqrt(sum / n);
    }

    private static void write(ByteArrayOutputStream o, short[] f, int n) {
        for (int i = 0; i < n; i++) { o.write(f[i] & 0xff); o.write((f[i] >> 8) & 0xff); }
    }

    private void recordLatency(long ms) {
        String prev = Vault.get(ctx, "latencies", "");
        String[] parts = prev.isEmpty() ? new String[0] : prev.split(",");
        StringBuilder b = new StringBuilder(String.valueOf(ms));
        for (int i = 0; i < Math.min(29, parts.length); i++) b.append(",").append(parts[i]);
        Vault.set(ctx, "latencies", b.toString());
        Vault.log(ctx, "Antwortzeit (Sprechende → Jarvis' Stimme): " + ms + " ms");
    }

    // ---------------------------------------------------------------- voice choice

    static Voice pickVoice(TextToSpeech t, String wanted) {
        Voice best = null;
        Set<Voice> vs;
        try { vs = t.getVoices(); } catch (Exception e) { return null; }
        if (vs == null) return null;
        String[] prefer = {"deg", "deb", "dea", "nfh"};
        for (Voice v : vs) {
            if (!v.getLocale().getLanguage().equals("de") || v.isNetworkConnectionRequired()) continue;
            if (v.getName().equals(wanted)) return v;
            if (best == null) best = v;
            for (int i = 0; i < prefer.length; i++) {
                if (v.getName().contains(prefer[i]) && (rank(best.getName(), prefer) > i || v.getQuality() > best.getQuality())) best = v;
            }
        }
        return best;
    }

    private static int rank(String name, String[] prefer) {
        for (int i = 0; i < prefer.length; i++) if (name.contains(prefer[i])) return i;
        return prefer.length;
    }
}
