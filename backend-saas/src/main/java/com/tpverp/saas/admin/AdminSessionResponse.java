package com.tpverp.saas.admin;

import java.util.Set;

public record AdminSessionResponse(
        String username,
        Set<String> permissions) {
}
