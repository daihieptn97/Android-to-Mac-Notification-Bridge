package com.hieptran.android_to_mac_notification_bridge.util;

import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;

public final class NotificationUtils {

    private NotificationUtils() {
    }

    public static boolean shouldIgnore(@NonNull StatusBarNotification sbn,
                                       @NonNull String ownPackage) {
        if (ownPackage.equals(sbn.getPackageName())) {
            return true;
        }
        return sbn.isOngoing();
    }
}
