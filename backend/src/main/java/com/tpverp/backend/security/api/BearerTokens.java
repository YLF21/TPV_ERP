package com.tpverp.backend.security.api;

final class BearerTokens {

	private BearerTokens() {
	}

	static String extract(String header) {
		if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
			throw new IllegalArgumentException("Token Bearer requerido");
		}
		return header.substring(7);
	}
}
