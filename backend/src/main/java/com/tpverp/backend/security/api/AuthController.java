package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.application.LoginResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

	@PostMapping("/installation-login")
	public LoginResult installationLogin(@Valid @RequestBody InstallationLoginRequest request) {
		return authenticationService.installationLogin(request.userName(), request.password());
	}

	@PutMapping("/installation-password")
	public LoginResult changeInstallationPassword(
			@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
			@Valid @RequestBody InstallationPasswordRequest request) {
		return authenticationService.changeInstallationPassword(
				BearerTokens.extract(authorization),
				request.currentPassword(),
				request.newPassword());
	}

	@PutMapping("/password")
	public ResponseEntity<Void> changePassword(
			@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
			@Valid @RequestBody ChangePasswordRequest request) {
		authenticationService.changePassword(
				BearerTokens.extract(authorization),
				request.currentPassword(),
				request.newPassword());
		return ResponseEntity.noContent().build();
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

	public record InstallationLoginRequest(
			@jakarta.validation.constraints.NotBlank String userName,
			@jakarta.validation.constraints.NotBlank String password) {
	}

	public record InstallationPasswordRequest(
			@jakarta.validation.constraints.NotBlank String currentPassword,
			@jakarta.validation.constraints.NotBlank
			@jakarta.validation.constraints.Pattern(regexp = "\\d{4,12}") String newPassword) {
	}

	public record ChangePasswordRequest(
			@jakarta.validation.constraints.NotBlank String currentPassword,
			@jakarta.validation.constraints.NotBlank
			@jakarta.validation.constraints.Pattern(regexp = "\\d{4,12}") String newPassword) {
	}
}
