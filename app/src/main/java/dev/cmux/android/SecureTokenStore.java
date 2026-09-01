package dev.cmux.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureTokenStore {
    private static final String ALIAS = "cmux-remote-session";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";
    private static final String IROH_SECRET = "iroh-secret";
    private static final String IROH_ACCOUNT = "iroh-account";
    private final SharedPreferences preferences;

    SecureTokenStore(Context context) {
        preferences = context.getSharedPreferences("secure-session", Context.MODE_PRIVATE);
    }

    synchronized void save(String accessToken, String refreshToken) throws Exception {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(ACCESS, encrypt(accessToken));
        editor.putString(REFRESH, encrypt(refreshToken));
        if (!editor.commit()) throw new IllegalStateException("Could not save session");
    }

    synchronized String accessToken() {
        return decryptOrNull(preferences.getString(ACCESS, null));
    }

    synchronized String refreshToken() {
        return decryptOrNull(preferences.getString(REFRESH, null));
    }

    synchronized void saveIrohSecret(byte[] secret, String account) throws Exception {
        if (!preferences.edit()
            .putString(IROH_SECRET, encrypt(Base64.getEncoder().encodeToString(secret)))
            .putString(IROH_ACCOUNT, encrypt(account)).commit()) {
            throw new IllegalStateException("Could not save Iroh identity");
        }
    }

    synchronized byte[] irohSecret(String account) {
        String savedAccount = decryptOrNull(preferences.getString(IROH_ACCOUNT, null));
        if (!account.equals(savedAccount)) return null;
        String value = decryptOrNull(preferences.getString(IROH_SECRET, null));
        return value == null ? null : Base64.getDecoder().decode(value);
    }

    synchronized void clear() {
        preferences.edit().remove(ACCESS).remove(REFRESH).apply();
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(cipher.getIV()) + "."
            + Base64.getEncoder().encodeToString(ciphertext);
    }

    private String decryptOrNull(String encoded) {
        if (encoded == null) return null;
        try {
            String[] parts = encoded.split("\\.", 2);
            if (parts.length != 2) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))
            );
            return new String(
                cipher.doFinal(Base64.getDecoder().decode(parts[1])),
                StandardCharsets.UTF_8
            );
        } catch (Exception ignored) {
            clear();
            return null;
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry existing = store.getEntry(ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }
}
