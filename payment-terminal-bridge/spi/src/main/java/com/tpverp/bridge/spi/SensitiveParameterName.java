package com.tpverp.bridge.spi;

import java.util.Locale;
import java.util.Set;

public final class SensitiveParameterName {
    private static final Set<String> EXACT = Set.of(
            "pan", "pin", "pincode", "cardpin", "customerpin", "cvv", "cvv2", "cvc", "cvc2",
            "cardnumber", "primaryaccountnumber", "track1", "track2", "trackdata", "emvdata");

    private SensitiveParameterName() {
    }

    public static boolean isSensitive(String key) {
        if (key == null) return true;
        var compact = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return EXACT.contains(compact) || compact.contains("secret") || compact.contains("token")
                || compact.contains("password") || compact.contains("credential")
                || compact.contains("apikey") || compact.contains("privatekey");
    }
}
