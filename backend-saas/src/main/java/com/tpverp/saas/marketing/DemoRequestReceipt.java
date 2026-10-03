package com.tpverp.saas.marketing;

import java.time.Instant;
import java.util.UUID;

public record DemoRequestReceipt(UUID id, Instant receivedAt) {
}
