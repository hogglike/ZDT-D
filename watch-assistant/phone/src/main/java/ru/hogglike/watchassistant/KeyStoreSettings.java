package ru.hogglike.watchassistant;

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

final class KeyStoreSettings {
    private static final String ALIAS = "hogg-watch-api-key-v1";
    private final SharedPreferences prefs;
    KeyStoreSettings(Context c) { prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    void saveKey(String value) throws Exception {
        if (value.isEmpty()) { prefs.edit().remove("secret").remove("iv").commit(); return; }
        if (!value.matches("[A-Za-z0-9_-]{16,256}")) throw new Exception("Проверь ключ: в нём не должно быть пробелов или переносов строк.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        if (!prefs.edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("secret", Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP)).commit())
            throw new Exception("Не удалось сохранить ключ.");
    }
    String readKey() throws Exception {
        String secret = prefs.getString("secret", ""); if (secret.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(secret, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    boolean hasKey() { return !prefs.getString("secret", "").isEmpty(); }
    String model() { return prefs.getString("model", Protocol.DEFAULT_MODEL); }
    void saveModel(String model) throws Exception {
        if (!model.matches("[A-Za-z0-9_./-]{1,120}")) throw new Exception("Укажи корректное имя модели.");
        if (!prefs.edit().putString("model", model).commit()) throw new Exception("Не удалось сохранить модель.");
    }
}
