package com.tpverp.saas.admin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record BasicCredentials(String username, String password) {

    public static BasicCredentials parse(String header) {
        if (header == null || !header.startsWith("Basic ")) {
            return null;
        }
        String value;
        try {
            value = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator < 1) {
            return null;
        }
        return new BasicCredentials(value.substring(0, separator), value.substring(separator + 1));
    }
}
