package com.hieptran.android_to_mac_notification_bridge.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hieptran.android_to_mac_notification_bridge.crypto.EncryptionHelper;
import com.hieptran.android_to_mac_notification_bridge.data.ConfigRepository;
import com.hieptran.android_to_mac_notification_bridge.discovery.ServiceDiscoveryManager;
import com.hieptran.android_to_mac_notification_bridge.model.BridgeState;
import com.hieptran.android_to_mac_notification_bridge.model.EncryptedPayload;
import com.hieptran.android_to_mac_notification_bridge.model.NotificationPayload;
import com.hieptran.android_to_mac_notification_bridge.network.NotificationSender;
import com.hieptran.android_to_mac_notification_bridge.util.NotificationUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationBridgeService extends NotificationListenerService {

    private static final String TAG = "BridgeService";

    private ConfigRepository configRepository;
    private NotificationSender notificationSender;
    private EncryptionHelper encryptionHelper;
    private ServiceDiscoveryManager discoveryManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        configRepository = new ConfigRepository(this);
        notificationSender = new NotificationSender();
        discoveryManager = new ServiceDiscoveryManager(this, new DiscoveryListener());
        ensureEncryptionHelper();
        startDiscoveryIfNeeded();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        discoveryManager.stopDiscovery();
        executor.shutdownNow();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        configRepository.updateStatus(BridgeState.Status.IDLE, "Notification access granted");
        startDiscoveryIfNeeded();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        configRepository.updateStatus(BridgeState.Status.ERROR, "Notification listener disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!configRepository.hasValidConfig()) {
            configRepository.updateStatus(BridgeState.Status.ERROR, "Configuration missing");
            return;
        }
        if (NotificationUtils.shouldIgnore(sbn, getPackageName())) {
            return;
        }
        executor.execute(() -> handleNotification(sbn));
    }

    private void handleNotification(StatusBarNotification sbn) {
        try {
            JSONObject payload = NotificationPayload.from(sbn).toJson();
            EncryptedPayload encryptedPayload = getEncryptionHelper().encrypt(payload);
            notificationSender.send(
                    configRepository.getServerUrl(),
                    configRepository.getApiKey(),
                    encryptedPayload,
                    new NotificationSender.Callback() {
                        @Override
                        public void onSuccess() {
                            configRepository.incrementSentCount();
                            configRepository.updateStatus(BridgeState.Status.CONNECTED,
                                    "Sent " + sbn.getPackageName());
                        }

                        @Override
                        public void onError(@NonNull Exception exception) {
                            Log.e(TAG, "Send error", exception);
                            configRepository.updateStatus(BridgeState.Status.ERROR,
                                    exception.getMessage());
                            configRepository.clearServerUrl();
                            startDiscoveryIfNeeded();
                        }
                    }
            );
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
            configRepository.updateStatus(BridgeState.Status.ERROR, "JSON error");
        } catch (Exception e) {
            Log.e(TAG, "Encryption error", e);
            configRepository.updateStatus(BridgeState.Status.ERROR, e.getMessage());
        }
    }

    private void startDiscoveryIfNeeded() {
        if (!configRepository.hasValidConfig()) {
            return;
        }
        if (configRepository.getServerUrl() == null) {
            discoveryManager.startDiscovery();
        }
    }

    private void ensureEncryptionHelper() {
        String key = configRepository.getEncryptionKey();
        if (key != null) {
            try {
                encryptionHelper = new EncryptionHelper(key);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid encryption key", e);
                configRepository.updateStatus(BridgeState.Status.ERROR, "Invalid encryption key");
            }
        }
    }

    @NonNull
    private EncryptionHelper getEncryptionHelper() {
        if (encryptionHelper == null) {
            ensureEncryptionHelper();
        }
        if (encryptionHelper == null) {
            throw new IllegalStateException("Encryption helper not initialized");
        }
        return encryptionHelper;
    }

    private class DiscoveryListener implements ServiceDiscoveryManager.Listener {

        @Override
        public void onDiscoveryStarted() {
            configRepository.updateStatus(BridgeState.Status.DISCOVERING, "Searching for Mac server");
        }

        @Override
        public void onServiceResolved(@NonNull String url) {
            configRepository.saveServerUrl(url);
            configRepository.updateStatus(BridgeState.Status.CONNECTED, "Resolved " + url);
        }

        @Override
        public void onDiscoveryError(@NonNull String message) {
            configRepository.updateStatus(BridgeState.Status.ERROR, message);
        }
    }
}
