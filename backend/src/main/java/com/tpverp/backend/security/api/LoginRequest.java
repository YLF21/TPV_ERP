package com.tpverp.backend.security.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LoginRequest(
		@NotNull UUID terminalId,
		String terminalCredential,
		@NotBlank String userName,
		@NotBlank String password) {
}
