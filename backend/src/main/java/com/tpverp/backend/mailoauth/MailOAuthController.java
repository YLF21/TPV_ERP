package com.tpverp.backend.mailoauth;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mail-oauth")
public class MailOAuthController {
    private final MailOAuthService service;
    public MailOAuthController(MailOAuthService service) { this.service = service; }

    @GetMapping("/status")
    @RequireGestionGroup(GestionGroup.CONFIGURACION)
    @PreAuthorize("hasRole('ADMIN')")
    public MailOAuthService.Status status() { return service.status(); }

    @PostMapping("/connect")
    @RequireGestionGroup(GestionGroup.CONFIGURACION)
    @PreAuthorize("hasRole('ADMIN')")
    public MailOAuthService.Authorization connect(@Valid @RequestBody Connect request) {
        return service.connect(request.provider(), request.email());
    }

    @DeleteMapping("/connection")
    @RequireGestionGroup(GestionGroup.CONFIGURACION)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> disconnect() { service.disconnect(); return ResponseEntity.noContent().build(); }

    @GetMapping(value = "/callback", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
        boolean success = false;
        try { service.callback(state, code, error); success = true; }
        catch (IllegalStateException | IllegalArgumentException failure) { /* No provider/token details in public output. */ }
        String message = success ? "Cuenta conectada. Vuelve a esPOS y actualiza el estado para enviar una prueba."
                : "No se pudo conectar la cuenta. Vuelve a esPOS e inicia la conexión de nuevo.";
        return ResponseEntity.status(success ? 200 : 400)
                .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
                .body("<!doctype html><html lang=\"es\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>esPOS · Correo</title><h1>esPOS</h1><p>" + message + "</p></html>");
    }
    public record Connect(@NotNull MailOAuthProvider provider, @NotBlank @Email @Size(max=320) String email) { }
}
