package com.tpverp.saas.fiscal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record FiscalStatusAdminCursor(String companySort, String storeSort, String codeSort, UUID storeId) {
    static FiscalStatusAdminCursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
                    .split("\\u001f", -1);
            if (parts.length != 4) throw new IllegalArgumentException();
            return new FiscalStatusAdminCursor(parts[0], parts[1], parts[2], UUID.fromString(parts[3]));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor fiscal invalido");
        }
    }

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (companySort + "\u001f" + storeSort + "\u001f" + codeSort + "\u001f" + storeId)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
