package com.tpverp.backend.party;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequireGestionGroup(GestionGroup.CONFIGURACION)
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
            @NotBlank @Size(max = 253) String host,
            @Min(1) @Max(65535) int port,
            @Size(max = 255) String username,
            @Size(max = 512) String password,
            @Email @NotBlank @Size(max = 320) String fromEmail,
            @Size(max = 255) String fromName,
            boolean startTls,
            boolean sslEnabled) {

        MemberSmtpSettingsCommand command() {
            return new MemberSmtpSettingsCommand(
                    enabled, host, port, username, password, fromEmail, fromName,
                    startTls, sslEnabled);
        }
    }

    public record TestRequest(
            @Email @NotBlank @Size(max = 320) String toEmail,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 20000) String body) {
    }
}
