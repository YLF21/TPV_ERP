package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.application.LoginResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthenticationService authenticationService;

	public AuthController(AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
	}

	@PostMapping("/login")
	public LoginResult login(@Valid @RequestBody LoginRequest request) {
		return authenticationService.login(
				request.terminalId(),
				request.terminalCredential(),
				request.userName(),
				request.password());
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
		authenticationService.logout(BearerTokens.extract(authorization));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/renew")
	public LoginResult renew(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
		return authenticationService.renew(BearerTokens.extract(authorization));
	}
}
