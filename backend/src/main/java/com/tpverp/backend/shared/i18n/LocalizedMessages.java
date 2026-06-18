package com.tpverp.backend.shared.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

@Component
public class LocalizedMessages {

    private final MessageSource source;
    private final MessageSource fallbackSource;

    public LocalizedMessages(MessageSource source) {
        this.source = source;
        this.fallbackSource = fallbackSource();
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
        var locale = locale(fallback(language));
        try {
            return source.getMessage(key, null, locale);
        } catch (NoSuchMessageException exception) {
            return fallbackSource.getMessage(key, null, locale);
        }
    }

    private static MessageSource fallbackSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
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
