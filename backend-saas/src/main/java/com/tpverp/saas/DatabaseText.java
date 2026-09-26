package com.tpverp.saas;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL UTF-8 text cannot contain NUL or an unpaired UTF-16 surrogate. */
public final class DatabaseText {
    private DatabaseText() { }
    public static void requireValid(String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == 0 || Character.isLowSurrogate(value)) throw invalid();
            if (Character.isHighSurrogate(value)
                    && (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++i)))) throw invalid();
        }
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "El texto contiene caracteres no validos");
    }
}