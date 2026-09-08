package com.tpverp.saas.admin;

import java.util.Locale;
import java.util.Set;
import java.text.Normalizer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class FiscalEvidencePolicy {

    private static final Set<String> PLACEHOLDERS = Set.of(
            "na", "none", "noaplica", "notapplicable", "test", "dummy", "sindatos",
            "pendiente", "desconocido", "unknown", "placeholder");
    private static final Set<String> FILLER_WORDS = Set.of(
            "a", "al", "de", "del", "el", "es", "esto", "la", "las", "lo", "los",
            "no", "aplica", "aplicable", "porque", "por", "sin", "datos", "dato",
            "test", "dummy", "prueba", "una", "un", "pendiente", "desconocido", "unknown");

    private FiscalEvidencePolicy() {
    }

    static String require(String value, String field, int minimumLength) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized == null || normalized.length() < minimumLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " debe contener una justificacion verificable");
        }
        String folded = Normalizer.normalize(normalized.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String key = folded.replaceAll("[^a-z0-9]", "");
        String[] words = folded.split("[^a-z0-9]+", -1);
        long distinctCharacters = key.chars().distinct().count();
        String firstWord = words.length == 0 ? "" : words[0];
        boolean repeatedWord = !firstWord.isBlank()
                && java.util.Arrays.stream(words).filter(word -> !word.isBlank())
                .allMatch(firstWord::equals);
        boolean repeatedPattern = key.matches("^(.{1,4})\\1{2,}$");
        long meaningfulWords = java.util.Arrays.stream(words)
                .filter(word -> !word.isBlank() && !FILLER_WORDS.contains(word))
                .filter(word -> word.chars().anyMatch(Character::isLetter))
                .count();
        if (PLACEHOLDERS.contains(key) || distinctCharacters < 4 || repeatedWord
                || repeatedPattern || meaningfulWords == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " debe aportar informacion concreta y no repetitiva");
        }
        return normalized;
    }
}
