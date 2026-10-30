package com.tpverp.backend.security.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record LoginRequest(
		@NotNull UUID terminalId,
		@Size(max = 512) String terminalCredential,
		@NotBlank @Size(max = 120) String userName,
		@NotBlank @Size(max = 512) String password) {
}
