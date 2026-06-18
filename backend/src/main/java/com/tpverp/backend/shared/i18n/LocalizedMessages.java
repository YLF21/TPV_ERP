package com.tpverp.backend.shared.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
public class LocalizedMessages {

    private final MessageSource source;

    public LocalizedMessages(MessageSource source) {
        this.source = source;
    }

    public String system(SystemErrorCode code, SupportedLanguage language) {
        return message("error." + code.name(), language);
    }
    // Traduce errores de sistema completos desde los ficheros de idioma.

    public String required(FieldKey field, SupportedLanguage language) {
        var selected = fallback(language);
        var separator = selected == SupportedLanguage.ZH ? "" : " ";
        return message("field." + key(field), selected) + separator + message("common.is_required", selected);
    }
    // Compone avisos de campo obligatorio reutilizando nombre de campo y texto comun.

    private String message(String key, SupportedLanguage language) {
        return source.getMessage(key, null, locale(fallback(language)));
    }

    private static String key(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static Locale locale(SupportedLanguage language) {
        return Locale.forLanguageTag(language.localeCode());
    }

    private static SupportedLanguage fallback(SupportedLanguage language) {
        return language == null ? SupportedLanguage.ES : language;
    }
}
