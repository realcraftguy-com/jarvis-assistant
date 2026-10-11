package de.felix.jarvis.shared;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted message link between Jarvis (Jarvis phone) and the call app (user's phone) over ntfy.sh.
 * ntfy is a free public relay (no account): limits 250 messages/day, 4 KB per message, 1 request/5 s
 * after a burst of 60. It only ever sees ciphertext and random topic names.
 */
public final class Link {
    public static final String DEFAULT_SERVER = "https://ntfy.sh";
    private static final SecureRandom RNG = new SecureRandom();

    private Link() {}

    // ---------------------------------------------------------------- encoding

    public static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    public static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }

    public static String randomId(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return b64(b);
    }

    // ---------------------------------------------------------------- key agreement

    public static KeyPair newKeyPair() throws Exception {
        // Android's provider (Conscrypt) creates X25519 keys by default and rejects explicit parameter specs
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (Exception e) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("XDH");
            try { g.initialize(NamedParameterSpec.X25519); } catch (Exception ignored) {}
            return g.generateKeyPair();
        }
    }

    public static String pub(KeyPair kp) { return b64(kp.getPublic().getEncoded()); }

    public static String priv(KeyPair kp) { return b64(kp.getPrivate().getEncoded()); }

    public static byte[] sharedKey(String myPrivB64, String theirPubB64, byte[] salt) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("XDH");
        PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(unb64(myPrivB64)));
        PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(unb64(theirPubB64)));
        KeyAgreement ka = KeyAgreement.getInstance("XDH");
        ka.init(pk);
        ka.doPhase(pub, true);
        return hkdf(ka.generateSecret(), salt, "jarvis-link-v1".getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt == null || salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return mac.doFinal(); // 32 bytes
    }

    public static String proof(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return b64(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- sealing

    public static String seal(byte[] key, String topic, JSONObject payload) throws Exception {
        byte[] nonce = new byte[12];
        RNG.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(topic.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("v", 1).put("n", b64(nonce)).put("c", b64(ct)).toString();
    }

    /** null if not decryptable (foreign or tampered message). */
    public static JSONObject open(byte[] key, String topic, String envelope) {
        try {
            JSONObject e = new JSONObject(envelope);
            if (e.optInt("v") != 1 || !e.has("c")) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, unb64(e.getString("n"))));
            c.updateAAD(topic.getBytes(StandardCharsets.UTF_8));
            return new JSONObject(new String(c.doFinal(unb64(e.getString("c"))), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- replay protection

    public static final class Seen {
        private final Map<String, Long> ids = new LinkedHashMap<String, Long>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 500; }
        };

        /** Accepts a message once, and only if it is fresh. */
        public synchronized boolean accept(JSONObject m, long maxAgeMs) {
            String id = m.optString("id", "");
            long ts = m.optLong("ts", 0);
            if (id.isEmpty() || ids.containsKey(id)) return false;
            if (Math.abs(System.currentTimeMillis() - ts) > maxAgeMs) return false;
            ids.put(id, ts);
            return true;
        }
    }

    /** Compresses a long text for a message field (ntfy allows 4 KB per message). */
    public static String pack(String text) throws Exception {
        java.util.zip.Deflater d = new java.util.zip.Deflater(9);
        byte[] in = text.getBytes(StandardCharsets.UTF_8);
        d.setInput(in);
        d.finish();
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        return b64(o.toByteArray());
    }

    public static String unpack(String packed) throws Exception {
        java.util.zip.Inflater f = new java.util.zip.Inflater();
        f.setInput(unb64(packed));
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!f.finished()) { int n = f.inflate(buf); if (n == 0 && (f.needsInput() || f.needsDictionary())) break; o.write(buf, 0, n); }
        return o.toString("UTF-8");
    }

    /** Splits a packed payload into message-sized parts: {group, i, n, chunk}. */
    public static java.util.List<String> parts(String packed, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < packed.length(); i += max) out.add(packed.substring(i, Math.min(packed.length(), i + max)));
        return out;
    }

    /** Reassembles parts; returns the packed payload when complete. */
    public static final class Assembler {
        private final Map<String, String[]> groups = new LinkedHashMap<>();

        public synchronized String add(String group, int i, int n, String chunk) {
            String[] g = groups.computeIfAbsent(group, k -> new String[n]);
            if (i < 0 || i >= g.length) return null;
            g[i] = chunk;
            for (String x : g) if (x == null) return null;
            groups.remove(group);
            return String.join("", g);
        }
    }

    public static JSONObject msg(String type) throws Exception {
        return new JSONObject().put("t", type).put("id", randomId(9)).put("ts", System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- ntfy transport

    public static void publish(String server, String topic, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/" + topic).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Firebase", "no"); // do not forward through ntfy's Firebase
        c.setRequestProperty("Content-Type", "text/plain");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream os = c.getOutputStream()) { os.write(b); }
        int code = c.getResponseCode();
        c.disconnect();
        if (code == 429) throw new Exception("ntfy-Limit erreicht (429)");
        if (code >= 300) throw new Exception("ntfy HTTP " + code);
    }

    public interface Listener {
        void onMessage(String id, String body);
        default void onOpen() {}
    }

    /**
     * Streams messages of a topic until alive() is false or the connection drops.
     * since: last seen ntfy message id (resume without gaps) or e.g. "60s".
     * Returns the last message id seen.
     */
    public static String subscribe(String server, String topic, String since, Listener l, BooleanSupplier alive) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/" + topic + "/json?since=" + since).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(90_000); // ntfy sends keepalives every 45 s
        String last = since;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            l.onOpen();
            String line;
            while (alive.getAsBoolean() && (line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                JSONObject ev = new JSONObject(line);
                if ("message".equals(ev.optString("event"))) {
                    last = ev.optString("id", last);
                    l.onMessage(last, ev.optString("message", ""));
                }
            }
        } finally {
            c.disconnect();
        }
        return last;
    }
}
