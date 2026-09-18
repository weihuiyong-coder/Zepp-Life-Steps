package com.weihuiyong.zeppsteps;

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
import org.json.JSONObject;

/** Optional, device-local encrypted storage. No plaintext fallback and no cloud backup. */
final class SecureVault {
    private static final String ALIAS = "zepp_steps_credentials_v1";
    private static final byte[] AAD = "com.weihuiyong.zeppsteps:v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences prefs;

    SecureVault(Context context) { prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE); }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("Missing device key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    void save(String account, String password) throws Exception {
        JSONObject value = new JSONObject().put("account", account).put("password", password);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            throw new IllegalStateException("Device storage write failed");
        }
    }

    String[] load() throws Exception {
        String data = prefs.getString("data", null);
        if (data == null) return null;
        byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        byte[] clear = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP));
        try {
            JSONObject value = new JSONObject(new String(clear, StandardCharsets.UTF_8));
            return new String[]{value.getString("account"), value.getString("password")};
        } finally { java.util.Arrays.fill(clear, (byte) 0); }
    }

    void clear() throws Exception {
        if (!prefs.edit().clear().commit()) throw new IllegalStateException("Device storage clear failed");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }
}
