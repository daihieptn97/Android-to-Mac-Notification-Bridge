package com.hieptran.android_to_mac_notification_bridge.model;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

public class NotificationPayload {

    private final String packageName;
    private final String title;
    private final String text;
    private final String category;
    private final long postedAt;
    private final String channelId;
    private final boolean ongoing;

    private NotificationPayload(@NonNull String packageName,
                                @Nullable String title,
                                @Nullable String text,
                                @Nullable String category,
                                long postedAt,
                                @Nullable String channelId,
                                boolean ongoing) {
        this.packageName = packageName;
        this.title = title;
        this.text = text;
        this.category = category;
        this.postedAt = postedAt;
        this.channelId = channelId;
        this.ongoing = ongoing;
    }

    @NonNull
    public static NotificationPayload from(@NonNull StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        CharSequence titleCs = extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence textCs = extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null;
        String category = notification.category;
        return new NotificationPayload(
                sbn.getPackageName(),
                charSequenceToString(titleCs),
                charSequenceToString(textCs),
                category,
                sbn.getPostTime(),
                notification.getChannelId(),
                sbn.isOngoing()
        );
    }

    @NonNull
    private static String charSequenceToString(@Nullable CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("packageName", packageName);
        object.put("title", safeString(title));
        object.put("text", safeString(text));
        object.put("category", safeString(category));
        object.put("channelId", safeString(channelId));
        object.put("ongoing", ongoing);
        object.put("timestamp", postedAt);
        return object;
    }

    @NonNull
    private static String safeString(@Nullable String input) {
        return TextUtils.isEmpty(input) ? "" : Objects.requireNonNull(input);
    }
}
