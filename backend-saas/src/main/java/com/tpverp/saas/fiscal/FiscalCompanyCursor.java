package com.tpverp.saas.fiscal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record FiscalCompanyCursor(String companySort, UUID companyId) {
    static FiscalCompanyCursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
                    .split("\\u001f", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new FiscalCompanyCursor(parts[0], UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor fiscal de empresa invalido");
        }
    }

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (companySort + "\u001f" + companyId).getBytes(StandardCharsets.UTF_8));
    }
}
