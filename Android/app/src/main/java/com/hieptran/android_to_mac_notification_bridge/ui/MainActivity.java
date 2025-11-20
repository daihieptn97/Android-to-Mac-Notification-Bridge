package com.hieptran.android_to_mac_notification_bridge.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import android.service.notification.NotificationListenerService;

import com.google.android.material.button.MaterialButton;
import com.hieptran.android_to_mac_notification_bridge.R;
import com.hieptran.android_to_mac_notification_bridge.crypto.EncryptionHelper;
import com.hieptran.android_to_mac_notification_bridge.data.ConfigRepository;
import com.hieptran.android_to_mac_notification_bridge.discovery.ServiceDiscoveryManager;
import com.hieptran.android_to_mac_notification_bridge.model.BridgeState;
import com.hieptran.android_to_mac_notification_bridge.model.EncryptedPayload;
import com.hieptran.android_to_mac_notification_bridge.network.NotificationSender;
import com.hieptran.android_to_mac_notification_bridge.service.NotificationBridgeService;

import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private ConfigRepository configRepository;
    private ServiceDiscoveryManager discoveryManager;
    private NotificationSender notificationSender;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private boolean discoveryRunning = false;

    private TextView statusText;
    private TextView messageText;
    private TextView serverText;
    private TextView sentCountText;
    private TextView configStateText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Log.d("hehe123", "onCreate: Starting MainActivity initialization");
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        configRepository = new ConfigRepository(this);
        discoveryManager = new ServiceDiscoveryManager(this, new UiDiscoveryListener());
        notificationSender = new NotificationSender();

        statusText = findViewById(R.id.textStatusValue);
        messageText = findViewById(R.id.textMessageValue);
        serverText = findViewById(R.id.textServerValue);
        sentCountText = findViewById(R.id.textSentCountValue);
        configStateText = findViewById(R.id.textConfigStateValue);
        MaterialButton setupButton = findViewById(R.id.buttonOpenSetup);
        MaterialButton discoveryButton = findViewById(R.id.buttonRetryDiscovery);
        MaterialButton notificationButton = findViewById(R.id.buttonNotificationPermission);
        MaterialButton testButton = findViewById(R.id.buttonSendTestNotification);

        setupButton.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        discoveryButton.setOnClickListener(v -> discoveryManager.startDiscovery());
        notificationButton.setOnClickListener(v -> openNotificationListenerSettings());
        testButton.setOnClickListener(v -> sendTestNotification());

        prefListener = (sharedPreferences, key) -> runOnUiThread(this::renderState);

        renderState();
        Log.d("hehe123", "onCreate: MainActivity initialization complete");
    }

    @Override
    protected void onStart() {
        Log.d("hehe123", "onStart: Registering preference listener and checking notification access");
        super.onStart();
        configRepository.registerListener(prefListener);
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName())) {
            configRepository.updateStatus(BridgeState.Status.ERROR, "Grant notification access");
        }
        Log.d("hehe123", "onStart: Complete");
    }

    @Override
    protected void onStop() {
        Log.d("hehe123", "onStop: Unregistering preference listener");
        super.onStop();
        configRepository.unregisterListener(prefListener);
        Log.d("hehe123", "onStop: Complete");
    }

    @Override
    protected void onDestroy() {
        Log.d("hehe123", "onDestroy: Stopping discovery and cleaning up");
        super.onDestroy();
        discoveryRunning = false;
        discoveryManager.stopDiscovery();
        Log.d("hehe123", "onDestroy: Complete");
    }

    private void renderState() {
        Log.d("hehe123", "renderState: Updating UI with current bridge state");
        BridgeState state = configRepository.getBridgeState();
        statusText.setText(state.getStatus().name());
        messageText.setText(TextUtils.isEmpty(state.getMessage()) ? getString(R.string.status_no_message) : state.getMessage());
        String server = configRepository.getServerUrl();
        serverText.setText(server == null ? getString(R.string.server_unknown) : server);
        sentCountText.setText(String.valueOf(configRepository.getSentCount()));
        configStateText.setText(configRepository.hasValidConfig() ? getString(R.string.config_ready) : getString(R.string.config_missing));
        maybeStartDiscovery(server);
        Log.d("hehe123", "renderState: UI updated, status=" + state.getStatus() + ", server=" + server);
    }

    private void openNotificationListenerSettings() {
        Log.d("hehe123", "openNotificationListenerSettings: Opening notification listener settings");
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        ComponentName componentName = new ComponentName(this, NotificationBridgeService.class);
        NotificationListenerService.requestRebind(componentName);
        Log.d("hehe123", "openNotificationListenerSettings: Settings opened and rebind requested");
    }

    private void maybeStartDiscovery(@Nullable String server) {
        Log.d("hehe123", "maybeStartDiscovery: Checking if discovery should start, server=" + server + ", hasValidConfig=" + configRepository.hasValidConfig() + ", discoveryRunning=" + discoveryRunning);
        if (!configRepository.hasValidConfig()) {
            Log.d("hehe123", "maybeStartDiscovery: No valid config, skipping discovery");
            return;
        }
        if (server == null && !discoveryRunning) {
            Log.d("hehe123", "maybeStartDiscovery: Starting discovery");
            discoveryManager.startDiscovery();
        } else {
            Log.d("hehe123", "maybeStartDiscovery: Discovery not needed");
        }
    }

    private class UiDiscoveryListener implements ServiceDiscoveryManager.Listener {
        @Override
        public void onDiscoveryStarted() {
            Log.d("hehe123", "UiDiscoveryListener.onDiscoveryStarted: Discovery started");
            configRepository.updateStatus(BridgeState.Status.DISCOVERING, getString(R.string.status_discovering));
            discoveryRunning = true;
        }

        @Override
        public void onServiceResolved(@NonNull String url) {
            Log.d("hehe123", "UiDiscoveryListener.onServiceResolved: Service resolved at " + url);
            configRepository.saveServerUrl(url);
            configRepository.updateStatus(BridgeState.Status.CONNECTED, getString(R.string.status_connected_with, url));
            discoveryRunning = false;
        }

        @Override
        public void onDiscoveryError(@NonNull String message) {
            Log.d("hehe123", "UiDiscoveryListener.onDiscoveryError: " + message);
            configRepository.updateStatus(BridgeState.Status.ERROR, message);
            discoveryRunning = false;
        }
    }

    private void sendTestNotification() {
        Log.d("hehe123", "sendTestNotification: Starting test notification send");
        if (!configRepository.hasValidConfig()) {
            Log.d("hehe123", "sendTestNotification: No valid config");
            configRepository.updateStatus(BridgeState.Status.ERROR, getString(R.string.status_test_missing_config));
            showToast(getString(R.string.status_test_missing_config));
            return;
        }
        String serverUrl = configRepository.getServerUrl();
        if (serverUrl == null) {
            Log.d("hehe123", "sendTestNotification: No server URL, starting discovery");
            configRepository.updateStatus(BridgeState.Status.ERROR, getString(R.string.status_test_missing_server));
            discoveryManager.startDiscovery();
            showToast(getString(R.string.status_test_missing_server));
            return;
        }
        String apiKey = configRepository.getApiKey();
        String encryptionKey = configRepository.getEncryptionKey();
        if (TextUtils.isEmpty(apiKey) || TextUtils.isEmpty(encryptionKey)) {
            Log.d("hehe123", "sendTestNotification: Missing API key or encryption key");
            configRepository.updateStatus(BridgeState.Status.ERROR, getString(R.string.status_test_missing_config));
            showToast(getString(R.string.status_test_missing_config));
            return;
        }
        Log.d("hehe123", "sendTestNotification: Preparing to encrypt and send, serverUrl=" + serverUrl);
        configRepository.updateStatus(BridgeState.Status.IDLE, getString(R.string.status_sending_test));
        EncryptedPayload encryptedPayload;
        try {
            Log.d("hehe123", "sendTestNotification: Building test payload");
            JSONObject payload = buildTestPayload();
            Log.d("hehe123", "sendTestNotification: Encrypting payload");
            encryptedPayload = new EncryptionHelper(encryptionKey).encrypt(payload);
            Log.d("hehe123", "sendTestNotification: Payload encrypted successfully");
        } catch (IllegalArgumentException e) {
            Log.d("hehe123", "sendTestNotification: Encryption key error: " + e.getMessage());
            configRepository.updateStatus(BridgeState.Status.ERROR, e.getMessage());
            showToast(e.getMessage());
            return;
        } catch (Exception e) {
            Log.d("hehe123", "sendTestNotification: Encryption error: " + e.getMessage());
            configRepository.updateStatus(BridgeState.Status.ERROR, e.getMessage());
            showToast(getString(R.string.toast_test_notification_failed, e.getMessage()));
            return;
        }
        Log.d("hehe123", "sendTestNotification: Sending notification to server");
        notificationSender.send(serverUrl, apiKey, encryptedPayload, new NotificationSender.Callback() {
            @Override
            public void onSuccess() {
                Log.d("hehe123", "sendTestNotification: Notification sent successfully");
                configRepository.incrementSentCount();
                configRepository.updateStatus(BridgeState.Status.CONNECTED, getString(R.string.status_test_notification_success));
                showToast(getString(R.string.toast_test_notification_sent));
            }

            @Override
            public void onError(@NonNull Exception exception) {
                String message = TextUtils.isEmpty(exception.getMessage())
                        ? getString(R.string.server_unknown)
                        : exception.getMessage();
                Log.d("hehe123", "sendTestNotification: Send failed: " + message);
                configRepository.updateStatus(BridgeState.Status.ERROR,
                        getString(R.string.status_test_notification_error, message));
                showToast(getString(R.string.toast_test_notification_failed, message));
            }
        });
        Log.d("hehe123", "sendTestNotification: Send request initiated");
    }

    @NonNull
    private JSONObject buildTestPayload() throws JSONException {
        Log.d("hehe123", "buildTestPayload: Creating test notification payload");
        JSONObject object = new JSONObject();
        object.put("packageName", getPackageName());
        object.put("title", getString(R.string.test_notification_title));
        object.put("text", getString(R.string.test_notification_text));
        object.put("category", "test");
        object.put("channelId", "bridge_test_channel");
        object.put("ongoing", false);
        object.put("timestamp", System.currentTimeMillis());
        Log.d("hehe123", "buildTestPayload: Payload created");
        return object;
    }

    private void showToast(@NonNull String message) {
        Log.d("hehe123", "showToast: Showing toast: " + message);
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }
}
