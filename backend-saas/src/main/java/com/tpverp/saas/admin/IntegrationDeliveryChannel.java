package com.tpverp.saas.admin;

public interface IntegrationDeliveryChannel {

    default boolean available() {
        return true;
    }

    boolean deliver(IntegrationDelivery delivery);

    record IntegrationDelivery(
            java.util.UUID integrationId,
            String integrationType,
            String targetUrl,
            String apiKey,
            String payload,
            String idempotencyKey) {
    }
}
