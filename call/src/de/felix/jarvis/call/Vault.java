package de.felix.jarvis.call;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Settings and secrets of the call app. Secrets are encrypted with a non-exportable Keystore key. */
final class Vault {
    static final String LINK_KEY = "link_key", GROQ = "groq", GEMINI = "gemini", PRIVATE = "priv";
    private static final String ALIAS = "jarvis-call";

    private Vault() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("call", Context.MODE_PRIVATE); }

    static String get(Context c, String k, String def) { return prefs(c).getString(k, def); }

    static void set(Context c, String k, String v) { prefs(c).edit().putString(k, v).apply(); }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return g.generateKey();
    }

    static synchronized void secret(Context c, String name, String value) {
        try {
            if (value == null || value.isEmpty()) { prefs(c).edit().remove("s_" + name).apply(); return; }
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = ci.getIV(), ct = ci.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            prefs(c).edit().putString("s_" + name, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new RuntimeException("Speichern fehlgeschlagen");
        }
    }

    static synchronized String secret(Context c, String name) {
        String s = prefs(c).getString("s_" + name, null);
        if (s == null) return null;
        try {
            byte[] all = Base64.decode(s, Base64.NO_WRAP);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(ci.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean paired(Context c) { return secret(c, LINK_KEY) != null && "1".equals(get(c, "welcomed", "0")); }

    /** Call history shown in the app (newest first, max 30). */
    static void addHistory(Context c, String line) {
        String h = get(c, "history", "");
        String[] parts = h.isEmpty() ? new String[0] : h.split("\n");
        StringBuilder b = new StringBuilder(line);
        for (int i = 0; i < Math.min(29, parts.length); i++) b.append("\n").append(parts[i]);
        set(c, "history", b.toString());
    }

    static void log(Context c, String msg) {
        android.util.Log.i("JarvisCall", msg);
        String h = get(c, "log", "");
        String line = new java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.GERMAN).format(new java.util.Date()) + "  " + msg;
        set(c, "log", (line + "\n" + h).substring(0, Math.min(8000, line.length() + 1 + h.length())));
    }
}
