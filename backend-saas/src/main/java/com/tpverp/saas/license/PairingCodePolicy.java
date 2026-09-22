package com.tpverp.saas.license;

import java.time.Duration;

public final class PairingCodePolicy {
    public static final Duration VALIDITY = Duration.ofMinutes(30);

    private PairingCodePolicy() { }
}
