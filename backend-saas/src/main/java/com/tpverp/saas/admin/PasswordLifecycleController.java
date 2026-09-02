package com.tpverp.saas.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password")
public class PasswordLifecycleController {

    private final PasswordLifecycleService service;
    private final LoginAttemptLimiter attempts;

    public PasswordLifecycleController(PasswordLifecycleService service, LoginAttemptLimiter attempts) {
        this.service = service;
        this.attempts = attempts;
    }

    @PostMapping("/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody ChangePasswordRequest request) {
        service.changeAuthenticated(SaasAuthenticationController.bearer(authorization),
                request.currentPassword(), request.newPassword());
    }

    @PostMapping("/recovery/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestRecovery(
            @Valid @RequestBody RecoveryRequest request,
            HttpServletRequest httpRequest) {
        String address = SaasAuthenticationController.remoteAddress(httpRequest);
        if (attempts.blocked("password-recovery", request.username(), address)) {
            return;
        }
        service.requestReset(request.username(), address);
        attempts.failure("password-recovery", request.username(), address);
    }

    @PostMapping("/recovery/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmRecovery(@Valid @RequestBody RecoveryConfirmation request) {
        service.confirmReset(request.token(), request.newPassword());
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 12, max = 200) String newPassword) {
    }

    public record RecoveryRequest(@NotBlank @Size(max = 80) String username) {
    }

    public record RecoveryConfirmation(
            @NotBlank @Size(min = 32, max = 200) String token,
            @NotBlank @Size(min = 12, max = 200) String newPassword) {
    }
}
