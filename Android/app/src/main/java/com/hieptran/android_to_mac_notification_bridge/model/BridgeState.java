package com.hieptran.android_to_mac_notification_bridge.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class BridgeState {

    public enum Status {
        IDLE,
        DISCOVERING,
        CONNECTED,
        ERROR
    }

    private final Status status;
    @Nullable
    private final String message;
    private final long updatedAt;

    public BridgeState(@NonNull Status status, @Nullable String message, long updatedAt) {
        this.status = status;
        this.message = message;
        this.updatedAt = updatedAt;
    }

    @NonNull
    public Status getStatus() {
        return status;
    }

    @Nullable
    public String getMessage() {
        return message;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    @NonNull
    public static BridgeState idle() {
        return new BridgeState(Status.IDLE, null, System.currentTimeMillis());
    }

    @NonNull
    public static BridgeState fromValues(@Nullable String rawStatus,
                                         @Nullable String rawMessage,
                                         long timestamp) {
        Status status = Status.IDLE;
        if (rawStatus != null) {
            try {
                status = Status.valueOf(rawStatus);
            } catch (IllegalArgumentException ignored) {
                status = Status.ERROR;
            }
        }
        return new BridgeState(status, rawMessage, timestamp);
    }
}
