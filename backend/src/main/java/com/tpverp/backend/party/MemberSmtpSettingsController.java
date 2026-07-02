package com.tpverp.backend.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberSmtpSettingsController {

    private final MemberSmtpSettingsService service;

    public MemberSmtpSettingsController(MemberSmtpSettingsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/member-smtp-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public MemberSmtpSettingsService.MemberSmtpSettingsView get() {
        return service.get();
    }

    @PutMapping("/api/v1/member-smtp-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public MemberSmtpSettingsService.MemberSmtpSettingsView update(
            @Valid @RequestBody SettingsRequest request) {
        return service.update(request.command());
    }

    @PostMapping("/api/v1/member-smtp-settings/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> test(@Valid @RequestBody TestRequest request) {
        service.test(new MemberSmtpSettingsService.MemberSmtpTestCommand(
                request.toEmail(), request.subject(), request.body()));
        return ResponseEntity.noContent().build();
    }

    public record SettingsRequest(
            boolean enabled,
            @NotBlank String host,
            int port,
            String username,
            String password,
            @Email @NotBlank String fromEmail,
            String fromName,
            boolean startTls,
            boolean sslEnabled) {

        MemberSmtpSettingsCommand command() {
            return new MemberSmtpSettingsCommand(
                    enabled, host, port, username, password, fromEmail, fromName,
                    startTls, sslEnabled);
        }
    }

    public record TestRequest(
            @Email @NotBlank String toEmail,
            @NotBlank String subject,
            @NotBlank String body) {
    }
}
