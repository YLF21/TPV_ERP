package com.tpverp.backend.security.application;

public record LoginResult(String accessToken, String userName, String role) {
}
