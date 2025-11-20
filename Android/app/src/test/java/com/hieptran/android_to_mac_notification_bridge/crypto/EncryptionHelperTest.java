package com.hieptran.android_to_mac_notification_bridge.crypto;

import static org.junit.Assert.assertEquals;

import com.hieptran.android_to_mac_notification_bridge.model.EncryptedPayload;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptionHelperTest {

    @Test
    public void encrypt_roundTrip_success() throws Exception {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
    String base64Key = Base64.getEncoder().encodeToString(keyBytes);
        EncryptionHelper helper = new EncryptionHelper(base64Key);
        JSONObject payload = new JSONObject();
        payload.put("title", "Hello");
        payload.put("text", "World");

        EncryptedPayload encryptedPayload = helper.encrypt(payload);

    byte[] nonce = Base64.getDecoder().decode(encryptedPayload.getNonce());
    byte[] ciphertext = Base64.getDecoder().decode(encryptedPayload.getCiphertext());
    byte[] tag = Base64.getDecoder().decode(encryptedPayload.getTag());

        byte[] combined = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(tag.length * 8, nonce));
        byte[] decrypted = cipher.doFinal(combined);
        JSONObject result = new JSONObject(new String(decrypted, StandardCharsets.UTF_8));

        assertEquals("Hello", result.getString("title"));
        assertEquals("World", result.getString("text"));
    }
}
