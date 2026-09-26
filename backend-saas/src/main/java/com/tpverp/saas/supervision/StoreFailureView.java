package com.tpverp.saas.supervision;

import java.time.Instant;
import java.util.UUID;

public record StoreFailureView(String id, String source, UUID sourceId, UUID companyId, String companyName,
        UUID storeId, String storeName, String internalCode, UUID installationId, String installationReference,
        String status, String severity, String code, String detail, Instant firstSeenAt, Instant lastSeenAt,
        long occurrences, boolean central, Boolean storeActive, String module, String appVersion,
        String traceId, String exceptionType, String errorLocation, Instant receivedAt) { }
