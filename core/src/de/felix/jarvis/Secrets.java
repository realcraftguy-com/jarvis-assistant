package de.felix.jarvis;

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

/**
 * Tokens and keys, encrypted with a non-exportable Android Keystore key.
 * Values are never logged or shown; the UI only displays whether a value is set.
 */
public final class Secrets {
    public static final String GROQ = "groq_key";
    public static final String GEMINI = "gemini_key";
    public static final String MCP = "mcp_secret";
    public static final String BRIDGE = "bridge_secret";
    public static final String TELEGRAM = "telegram_token";
    public static final String EDU_COOKIES = "edu_cookies";
    public static final String LINK_PRIVATE = "link_private";
    public static final String LINK_SESSION = "link_session";

    private static final String ALIAS = "jarvis-secrets";

    private Secrets() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("secrets", Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }

    public static synchronized void put(Context c, String name, String value) {
        try {
            if (value == null || value.isEmpty()) { prefs(c).edit().remove(name).apply(); return; }
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = ci.getIV();
            byte[] ct = ci.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            prefs(c).edit().putString(name, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new RuntimeException("Secret konnte nicht gespeichert werden (" + e.getClass().getSimpleName() + ")");
        }
    }

    public static synchronized String get(Context c, String name) {
        String s = prefs(c).getString(name, null);
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

    public static boolean has(Context c, String name) {
        return prefs(c).contains(name);
    }
}
