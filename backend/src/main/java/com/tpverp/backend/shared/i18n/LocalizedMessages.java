package com.tpverp.backend.shared.i18n;

import java.util.Locale;
import java.util.Optional;
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

    public Optional<String> legacy(String detail, SupportedLanguage language) {
        if (detail == null || detail.isBlank()) {
            return Optional.empty();
        }
        return exactLegacy(detail, language)
                .or(() -> requiredLegacy(detail, language))
                .or(() -> negativeLegacy(detail, language))
                .or(() -> notFoundLegacy(detail, language));
    }
    // Traduce mensajes antiguos mientras se migran gradualmente a codigos explicitos.

    private Optional<String> exactLegacy(String detail, SupportedLanguage language) {
        try {
            return Optional.of(message("legacy." + LegacyMessageKey.slug(detail), language));
        } catch (NoSuchMessageException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> requiredLegacy(String detail, SupportedLanguage language) {
        var suffix = requiredSuffix(detail);
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        var field = detail.substring(0, detail.length() - suffix.get().length()).trim();
        var selected = fallback(language);
        var key = "legacy.field." + LegacyMessageKey.slug(field);
        try {
            var separator = selected == SupportedLanguage.ZH ? "" : " ";
            return Optional.of(message(key, selected) + separator + message("common.is_required", selected));
        } catch (NoSuchMessageException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> requiredSuffix(String detail) {
        if (detail.endsWith(" es obligatorio")) {
            return Optional.of(" es obligatorio");
        }
        if (detail.endsWith(" obligatorio")) {
            return Optional.of(" obligatorio");
        }
        if (detail.endsWith(" obligatoria")) {
            return Optional.of(" obligatoria");
        }
        return Optional.empty();
    }

    private Optional<String> negativeLegacy(String detail, SupportedLanguage language) {
        if (!detail.endsWith(" no puede ser negativo")) {
            return Optional.empty();
        }
        var field = detail.substring(0, detail.length() - " no puede ser negativo".length()).trim();
        return legacyField(field, language)
                .map(translated -> translated + separator(language) + message("common.cannot_be_negative", language));
    }

    private Optional<String> notFoundLegacy(String detail, SupportedLanguage language) {
        if (!detail.endsWith(" no encontrado") && !detail.endsWith(" no encontrada")) {
            return Optional.empty();
        }
        var suffix = detail.endsWith(" no encontrado") ? " no encontrado" : " no encontrada";
        var field = detail.substring(0, detail.length() - suffix.length()).trim();
        return legacyField(field, language)
                .map(translated -> translated + separator(language) + message("common.not_found", language));
    }

    private Optional<String> legacyField(String field, SupportedLanguage language) {
        try {
            return Optional.of(message("legacy.field." + LegacyMessageKey.slug(field), language));
        } catch (NoSuchMessageException exception) {
            return Optional.empty();
        }
    }

    private static String separator(SupportedLanguage language) {
        return fallback(language) == SupportedLanguage.ZH ? "" : " ";
    }

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
