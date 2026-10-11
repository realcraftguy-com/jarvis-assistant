package de.felix.jarvis.call;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Natural offline voice: Supertonic 3 (Supertone, OpenRAIL-M model licence, MIT code), run with ONNX Runtime.
 * The 398 MB model is downloaded once from the official Hugging Face repository and then works without internet.
 */
final class NeuralVoice {
    static final String BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/";
    /** path, exact size in bytes (from the repository listing) – used to verify complete downloads */
    static final Object[][] FILES = {
            {"onnx/tts.json", 8253L}, {"onnx/unicode_indexer.json", 277676L}, {"onnx/duration_predictor.onnx", 3700147L},
            {"onnx/text_encoder.onnx", 36416150L}, {"onnx/vector_estimator.onnx", 256534781L}, {"onnx/vocoder.onnx", 101424195L}};
    static final String[] VOICES = {"M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5"};

    private static NeuralVoice instance;
    private static volatile String loadedVoice;

    static File dir(Context c) { return new File(c.getNoBackupFilesDir(), "supertonic3"); }

    static boolean downloaded(Context c) {
        for (Object[] f : FILES) {
            File x = new File(dir(c), (String) f[0]);
            if (!x.isFile() || x.length() != (Long) f[1]) return false;
        }
        for (String v : VOICES) if (!new File(dir(c), "voice_styles/" + v + ".json").isFile()) return false;
        return true;
    }

    interface Progress { void on(long done, long total, String file); }

    /** Resumable download with size verification. */
    static void download(Context c, Progress p) throws Exception {
        long total = 0, done = 0;
        for (Object[] f : FILES) total += (Long) f[1];
        for (Object[] f : FILES) {
            String path = (String) f[0];
            long size = (Long) f[1];
            File target = new File(dir(c), path);
            if (target.isFile() && target.length() == size) { done += size; continue; }
            target.getParentFile().mkdirs();
            File part = new File(target.getPath() + ".part");
            long have = part.isFile() ? part.length() : 0;
            HttpURLConnection h = (HttpURLConnection) new URL(BASE + path).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setInstanceFollowRedirects(true);
            if (have > 0) h.setRequestProperty("Range", "bytes=" + have + "-");
            int code = h.getResponseCode();
            if (code == 200) have = 0; // server ignored the range: start over
            else if (code != 206) throw new IOException("Download " + path + ": HTTP " + code);
            try (InputStream in = h.getInputStream(); RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                out.setLength(have);
                out.seek(have);
                byte[] buf = new byte[1 << 16];
                int n;
                long last = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    have += n;
                    if (System.currentTimeMillis() - last > 500) { p.on(done + have, total, path); last = System.currentTimeMillis(); }
                }
            }
            if (part.length() != size) throw new IOException("Unvollständig: " + path + " (" + part.length() + "/" + size + ")");
            if (!part.renameTo(target)) throw new IOException("Speichern fehlgeschlagen: " + path);
            done += size;
            p.on(done, total, path);
        }
        for (String v : VOICES) {
            File target = new File(dir(c), "voice_styles/" + v + ".json");
            if (target.isFile()) continue;
            target.getParentFile().mkdirs();
            HttpURLConnection h = (HttpURLConnection) new URL(BASE + "voice_styles/" + v + ".json").openConnection();
            try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    /** Loaded engine for the chosen voice (sessions are shared, only the style changes). */
    static synchronized NeuralVoice get(Context c) throws Exception {
        String voice = Vault.get(c, "neural_voice", "M5");
        if (instance == null) instance = new NeuralVoice(c);
        if (!voice.equals(loadedVoice)) { instance.setVoice(voice); loadedVoice = voice; }
        return instance;
    }

    // ---------------------------------------------------------------- inference

    final OrtEnvironment env = OrtEnvironment.getEnvironment();
    final OrtSession dp, encoder, vector, vocoder;
    OnnxTensor styleDp, styleTtl;
    final long[] indexer;
    final int sampleRate, chunkSize, latentDim;
    final File directory;
    final Random random = new Random();

    private NeuralVoice(Context c) throws Exception {
        directory = dir(c);
        JSONObject config = new JSONObject(read(new File(directory, "onnx/tts.json")));
        sampleRate = config.getJSONObject("ae").getInt("sample_rate");
        int compression = config.getJSONObject("ttl").getInt("chunk_compress_factor");
        chunkSize = config.getJSONObject("ae").getInt("base_chunk_size") * compression;
        latentDim = config.getJSONObject("ttl").getInt("latent_dim") * compression;
        JSONArray ids = new JSONArray(read(new File(directory, "onnx/unicode_indexer.json")));
        indexer = new long[ids.length()];
        for (int i = 0; i < ids.length(); i++) indexer[i] = ids.getLong(i);
        List<OrtSession> opened = new ArrayList<>();
        try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
            o.setIntraOpNumThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)));
            o.setInterOpNumThreads(1);
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            dp = env.createSession(new File(directory, "onnx/duration_predictor.onnx").getPath(), o); opened.add(dp);
            encoder = env.createSession(new File(directory, "onnx/text_encoder.onnx").getPath(), o); opened.add(encoder);
            vector = env.createSession(new File(directory, "onnx/vector_estimator.onnx").getPath(), o); opened.add(vector);
            vocoder = env.createSession(new File(directory, "onnx/vocoder.onnx").getPath(), o); opened.add(vocoder);
        } catch (Exception e) {
            for (OrtSession s : opened) s.close();
            throw e;
        }
    }

    private void setVoice(String v) throws Exception {
        JSONObject style = new JSONObject(read(new File(directory, "voice_styles/" + v + ".json")));
        if (styleDp != null) styleDp.close();
        if (styleTtl != null) styleTtl.close();
        styleDp = style(style.getJSONObject("style_dp"));
        styleTtl = style(style.getJSONObject("style_ttl"));
    }

    interface Valid { boolean check(); }

    /** Mono float PCM at sampleRate. steps: quality (5–8), speed: 1.0 normal. */
    synchronized float[] synth(String input, int steps, float speed, Valid valid) throws Exception {
        // NFKD (decomposed umlauts) is what the model was trained on – NFKC turned "geprüft" into "gepufft" (measured)
        String text = Normalizer.normalize(pronounce(input), Normalizer.Form.NFKD).replaceAll("[–—‑]", "-").replaceAll("[_|/\\[\\]#*]", " ")
                .replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return new float[0];
        if (!text.matches("(?s).*[.!?;:…]$")) text += ".";
        text = "<de>" + text + "</de>";
        int[] points = text.codePoints().filter(cp -> cp < indexer.length && indexer[cp] >= 0).toArray();
        long[] tokens = new long[points.length];
        float[] mask = new float[points.length];
        Arrays.fill(mask, 1f);
        for (int i = 0; i < points.length; i++) tokens[i] = indexer[points[i]];
        try (OnnxTensor ids = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), new long[]{1, tokens.length});
             OnnxTensor tm = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), new long[]{1, 1, mask.length});
             OrtSession.Result duration = dp.run(map("text_ids", ids, "style_dp", styleDp, "text_mask", tm));
             OrtSession.Result embedding = encoder.run(map("text_ids", ids, "style_ttl", styleTtl, "text_mask", tm))) {
            if (!valid.check()) return new float[0];
            Object raw = duration.get(0).getValue();
            float seconds = (raw instanceof float[][] ? ((float[][]) raw)[0][0] : ((float[]) raw)[0]) / speed;
            if (!Float.isFinite(seconds) || seconds <= 0 || seconds > 40) throw new IOException("Ungültige Sprachdauer");
            int samples = (int) (seconds * sampleRate), length = (samples + chunkSize - 1) / chunkSize;
            float[][][] latent = new float[1][latentDim][length];
            for (int d = 0; d < latentDim; d++) for (int t = 0; t < length; t++) latent[0][d][t] = (float) random.nextGaussian();
            float[] lm = new float[length];
            Arrays.fill(lm, 1f);
            try (OnnxTensor latentMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(lm), new long[]{1, 1, length});
                 OnnxTensor total = OnnxTensor.createTensor(env, new float[]{steps})) {
                for (int step = 0; step < steps; step++) {
                    if (!valid.check()) return new float[0];
                    try (OnnxTensor noise = tensor(latent); OnnxTensor now = OnnxTensor.createTensor(env, new float[]{step});
                         OrtSession.Result r = vector.run(map("noisy_latent", noise, "text_emb", (OnnxTensor) embedding.get(0), "style_ttl", styleTtl,
                                 "latent_mask", latentMask, "text_mask", tm, "current_step", now, "total_step", total))) {
                        latent = (float[][][]) r.get(0).getValue();
                    }
                }
            }
            try (OnnxTensor fl = tensor(latent); OrtSession.Result r = vocoder.run(map("latent", fl))) {
                return Arrays.copyOf(((float[][]) r.get(0).getValue())[0], samples);
            }
        }
    }

    /**
     * Respellings for words the model mispronounces, each verified with Whisper (tools/tts_check.py):
     * "Mathe" was heard as "Mat", "Matte" sounds like "Mathe"; "EduPage" was read as "Edu Paj".
     */
    static final String[][] LEXICON = {
            {"(?i)\\bmathe(?!matik)", "Matte"},
            {"(?i)\\bedupage\\b", "Edu-Pehdsch"},
    };

    static String pronounce(String t) {
        for (String[] r : LEXICON) t = t.replaceAll(r[0], r[1]);
        return t;
    }

    private OnnxTensor style(JSONObject value) throws Exception {
        JSONArray dims = value.getJSONArray("dims");
        long[] shape = {dims.getLong(0), dims.getLong(1), dims.getLong(2)};
        float[] flat = new float[(int) (shape[0] * shape[1] * shape[2])];
        int k = 0;
        JSONArray batches = value.getJSONArray("data");
        for (int b = 0; b < batches.length(); b++) {
            JSONArray rows = batches.getJSONArray(b);
            for (int r = 0; r < rows.length(); r++) {
                JSONArray cells = rows.getJSONArray(r);
                for (int x = 0; x < cells.length(); x++) flat[k++] = (float) cells.getDouble(x);
            }
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape);
    }

    private OnnxTensor tensor(float[][][] a) throws OrtException {
        int x = a.length, y = a[0].length, z = a[0][0].length;
        float[] flat = new float[x * y * z];
        int k = 0;
        for (float[][] rows : a) for (float[] row : rows) { System.arraycopy(row, 0, flat, k, z); k += z; }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), new long[]{x, y, z});
    }

    private static Map<String, OnnxTensor> map(Object... p) {
        Map<String, OnnxTensor> m = new HashMap<>();
        for (int i = 0; i < p.length; i += 2) m.put((String) p[i], (OnnxTensor) p[i + 1]);
        return m;
    }

    static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }
}
