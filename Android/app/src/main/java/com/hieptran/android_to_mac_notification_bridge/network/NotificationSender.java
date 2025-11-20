package com.hieptran.android_to_mac_notification_bridge.network;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hieptran.android_to_mac_notification_bridge.model.EncryptedPayload;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NotificationSender {

    private static final String TAG = "NotificationSender";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface Callback {
        void onSuccess();
        void onError(@NonNull Exception exception);
    }

    private final OkHttpClient client;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public NotificationSender() {
        client = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void send(@Nullable String url,
                     @Nullable String apiKey,
                     @NonNull EncryptedPayload payload,
                     @NonNull Callback callback) {
        if (TextUtils.isEmpty(url)) {
            callback.onError(new IllegalStateException("Server URL not available"));
            return;
        }
        if (TextUtils.isEmpty(apiKey)) {
            callback.onError(new IllegalStateException("API key missing"));
            return;
        }
        executorService.execute(() -> {
            try {
                JSONObject json = payload.toJson();
                RequestBody body = RequestBody.create(json.toString(), JSON);
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        callback.onSuccess();
                    } else {
                        callback.onError(new IOException("HTTP " + response.code()));
                    }
                }
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Failed to send notification", e);
                // Include the underlying exception message so callers can show a more
                // informative error to the user instead of the generic "Failed to send".
                String msg = e.getMessage() == null ? "Failed to send" : "Failed to send: " + e.getMessage();
                callback.onError(new IOException(msg, e));
            }
        });
    }
}
