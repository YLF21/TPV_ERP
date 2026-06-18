package com.tpverp.backend.shared.i18n;

import java.util.Locale;

public enum SupportedLanguage {
    ES,
    EN,
    ZH;

    public static SupportedLanguage fromHeader(String header) {
        if (header == null || header.isBlank()) {
            return ES;
        }
        String tag;
        try {
            tag = Locale.LanguageRange.parse(header).getFirst().getRange();
        } catch (IllegalArgumentException exception) {
            return ES;
        }
        if (tag.startsWith("en")) {
            return EN;
        }
        if (tag.startsWith("zh")) {
            return ZH;
        }
        return ES;
    }

    public String localeCode() {
        return name().toLowerCase(Locale.ROOT);
    }
}
