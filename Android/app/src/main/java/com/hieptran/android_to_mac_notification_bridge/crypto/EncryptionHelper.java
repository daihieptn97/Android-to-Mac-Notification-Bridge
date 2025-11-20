package com.hieptran.android_to_mac_notification_bridge.crypto;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hieptran.android_to_mac_notification_bridge.model.EncryptedPayload;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptionHelper {

    private static final String TAG = "EncryptionHelper";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey secretKey;

    public EncryptionHelper(@NonNull String base64Key) {
        byte[] keyBytes = Base64.decode(base64Key, Base64.DEFAULT);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @NonNull
    public EncryptedPayload encrypt(@NonNull JSONObject payload) throws Exception {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        byte[] cipherWithTag = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
        if (cipherWithTag.length < TAG_BYTES) {
            throw new IllegalStateException("Ciphertext shorter than tag");
        }
        int cipherLength = cipherWithTag.length - TAG_BYTES;
        byte[] ciphertext = Arrays.copyOfRange(cipherWithTag, 0, cipherLength);
        byte[] tag = Arrays.copyOfRange(cipherWithTag, cipherLength, cipherWithTag.length);
        return new EncryptedPayload(
                Base64.encodeToString(nonce, Base64.NO_WRAP),
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                Base64.encodeToString(tag, Base64.NO_WRAP)
        );
    }
}
