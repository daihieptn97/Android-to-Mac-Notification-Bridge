package com.hieptran.android_to_mac_notification_bridge.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ServiceDiscoveryManager {

    public interface Listener {
        void onDiscoveryStarted();
        void onServiceResolved(@NonNull String url);
        void onDiscoveryError(@NonNull String message);
    }

    private static final String TAG = "ServiceDiscovery";
    private static final String SERVICE_TYPE = "_securenotif._tcp";

    private final NsdManager nsdManager;
    private final Listener listener;
    private NsdManager.DiscoveryListener discoveryListener;

    public ServiceDiscoveryManager(@NonNull Context context,
                                   @NonNull Listener listener) {
        this.nsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void startDiscovery() {
        stopDiscovery();
        if (nsdManager == null) {
            listener.onDiscoveryError("NSD not available");
            return;
        }
        listener.onDiscoveryStarted();
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Start discovery failed: " + errorCode);
                listener.onDiscoveryError("Start failed: " + errorCode);
                stopDiscovery();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Stop discovery failed: " + errorCode);
                listener.onDiscoveryError("Stop failed: " + errorCode);
                stopDiscovery();
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Discovery started for " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service found: " + serviceInfo.getServiceName());
                if (!TextUtils.isEmpty(serviceInfo.getServiceType()) &&
                        serviceInfo.getServiceType().contains(SERVICE_TYPE)) {
                    nsdManager.resolveService(serviceInfo, new ResolveListener());
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.w(TAG, "Service lost: " + serviceInfo.getServiceName());
            }
        };
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public void stopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop discovery", e);
            } finally {
                discoveryListener = null;
            }
        }
    }

    private class ResolveListener implements NsdManager.ResolveListener {

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(TAG, "Resolve failed: " + errorCode);
            listener.onDiscoveryError("Resolve failed: " + errorCode);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            String host = serviceInfo.getHost() != null ? serviceInfo.getHost().getHostAddress() : null;
            int port = serviceInfo.getPort();
            if (host == null) {
                listener.onDiscoveryError("Host null");
                return;
            }
            String url = "http://" + host + ":" + port + "/notify";
            listener.onServiceResolved(url);
            stopDiscovery();
        }
    }
}
