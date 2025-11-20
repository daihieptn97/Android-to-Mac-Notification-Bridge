package com.hieptran.android_to_mac_notification_bridge.model;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

public class EncryptedPayload {

    private final String nonce;
    private final String ciphertext;
    private final String tag;

    public EncryptedPayload(@NonNull String nonce,
                            @NonNull String ciphertext,
                            @NonNull String tag) {
        this.nonce = nonce;
        this.ciphertext = ciphertext;
        this.tag = tag;
    }

    @NonNull
    public String getNonce() {
        return nonce;
    }

    @NonNull
    public String getCiphertext() {
        return ciphertext;
    }

    @NonNull
    public String getTag() {
        return tag;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("nonce", nonce);
        object.put("ciphertext", ciphertext);
        object.put("tag", tag);
        return object;
    }
}
