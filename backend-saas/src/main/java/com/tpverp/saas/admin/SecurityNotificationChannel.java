package com.tpverp.saas.admin;

public interface SecurityNotificationChannel {

    default boolean available() {
        return true;
    }

    boolean deliver(SecurityNotification notification);

    record SecurityNotification(String idempotencyKey, String eventType, String realm, String username, String oneTimeToken) {
    }
}
