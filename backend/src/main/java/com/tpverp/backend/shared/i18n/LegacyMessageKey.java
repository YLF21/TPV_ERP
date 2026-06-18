package com.tpverp.backend.shared.i18n;

import java.text.Normalizer;
import java.util.Locale;

final class LegacyMessageKey {

    private LegacyMessageKey() {
    }

    static String slug(String value) {
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "message" : normalized;
    }
}
