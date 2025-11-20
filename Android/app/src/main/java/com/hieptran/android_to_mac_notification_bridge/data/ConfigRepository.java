package com.hieptran.android_to_mac_notification_bridge.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.hieptran.android_to_mac_notification_bridge.model.BridgeState;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class ConfigRepository {

    private static final String TAG = "ConfigRepository";
    private static final String PREFS_NAME = "bridge_secure_config";

    private static final String KEY_API = "api_key";
    private static final String KEY_ENCRYPTION = "encryption_key";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SENT_COUNT = "sent_count";
    private static final String KEY_STATUS = "status";
    private static final String KEY_STATUS_MESSAGE = "status_message";
    private static final String KEY_STATUS_UPDATED_AT = "status_updated_at";

    private final SharedPreferences preferences;

    public ConfigRepository(@NonNull Context context) {
        this.preferences = createPrefs(context.getApplicationContext());
    }

    private SharedPreferences createPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Falling back to normal SharedPreferences", e);
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public void saveConfig(@NonNull String apiKey, @NonNull String encryptionKeyBase64) {
        preferences.edit()
                .putString(KEY_API, apiKey)
                .putString(KEY_ENCRYPTION, encryptionKeyBase64)
                .apply();
    }

    public boolean hasValidConfig() {
        return getApiKey() != null && getEncryptionKey() != null;
    }

    @Nullable
    public String getApiKey() {
        return preferences.getString(KEY_API, null);
    }

    @Nullable
    public String getEncryptionKey() {
        return preferences.getString(KEY_ENCRYPTION, null);
    }

    public void saveServerUrl(@NonNull String url) {
        preferences.edit()
                .putString(KEY_SERVER_URL, url)
                .apply();
    }

    @Nullable
    public String getServerUrl() {
        return preferences.getString(KEY_SERVER_URL, null);
    }

    public void clearServerUrl() {
        preferences.edit()
                .remove(KEY_SERVER_URL)
                .apply();
    }

    public void incrementSentCount() {
        long current = preferences.getLong(KEY_SENT_COUNT, 0L);
        preferences.edit()
                .putLong(KEY_SENT_COUNT, current + 1L)
                .apply();
    }

    public long getSentCount() {
        return preferences.getLong(KEY_SENT_COUNT, 0L);
    }

    public void updateStatus(@NonNull BridgeState.Status status, @Nullable String message) {
        preferences.edit()
                .putString(KEY_STATUS, status.name())
                .putString(KEY_STATUS_MESSAGE, message)
                .putLong(KEY_STATUS_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    @NonNull
    public BridgeState getBridgeState() {
        String rawStatus = preferences.getString(KEY_STATUS, BridgeState.Status.IDLE.name());
        String msg = preferences.getString(KEY_STATUS_MESSAGE, null);
        long updatedAt = preferences.getLong(KEY_STATUS_UPDATED_AT, 0L);
        return BridgeState.fromValues(rawStatus, msg, updatedAt);
    }

    public void registerListener(@NonNull SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterListener(@NonNull SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public static boolean isValidKey(@Nullable String base64Key) {
        if (base64Key == null) {
            return false;
        }
        try {
            byte[] bytes = Base64.decode(base64Key, Base64.DEFAULT);
            return bytes.length == 32;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid base64 key", e);
            return false;
        }
    }
}
