package com.tpverp.saas.mail;

import com.tpverp.saas.admin.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MailOAuthController {
    private final MailOAuthService service;
    public MailOAuthController(MailOAuthService service) { this.service = service; }

    @GetMapping("/api/v1/admin/mail-oauth/status")
    public MailOAuthService.Status status(@RequestParam(required=false) UUID accountId) { return service.status(accountId); }

    @PostMapping("/api/v1/admin/mail-oauth/connect")
    public MailOAuthService.Authorization connect(@Valid @RequestBody MailOAuthService.Connect input,
            @RequestAttribute(AdminAuditService.USERNAME_ATTRIBUTE) String username) {
        return service.connect(input, username);
    }

    @DeleteMapping("/api/v1/admin/mail-oauth/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@RequestParam(required=false) UUID accountId, @RequestParam long version) { service.disconnect(accountId,version); }

    @GetMapping(value="/api/v1/mail-oauth/callback", produces="text/html;charset=UTF-8")
    public ResponseEntity<String> callback(@RequestParam(required=false) String state,
            @RequestParam(required=false) String code, @RequestParam(required=false) String error, HttpServletRequest request) {
        boolean success;
        try {
            service.callback(state,code,error);
            success = true;
        } catch (RuntimeException failure) {
            // Browser-visible results must never echo query parameters, provider errors or token material.
            success = false;
        }
        var copy = CallbackCopy.forRequest(request);
        String heading = success ? copy.success : copy.failure;
        String message = success ? copy.refresh : copy.retry;
        return ResponseEntity.status(success ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").header("Content-Language",copy.lang)
                .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
                .body("""
                        <!doctype html><html lang="%s"><head><meta charset="utf-8">
                        <meta name="viewport" content="width=device-width,initial-scale=1"><title>%s · esPOS SaaS</title>
                        <style>
                        *{box-sizing:border-box}body{margin:0;background:#eef2f7;color:#19334d;font:16px/1.55 system-ui,sans-serif}
                        header{background:#0b2c4f;color:white;padding:.8rem 1.4rem;font-size:1.2rem;font-weight:650}
                        main{background:white;border:1px solid #cdd8e3;border-radius:5px;max-width:32rem;
                        margin:clamp(2rem,10vh,6rem) auto;padding:1.5rem 1.75rem}h1{font-size:1.35rem;margin:0}
                        p{margin:.65rem 0 0}@media(max-width:36rem){main{margin:2rem 1rem;padding:1.25rem}}
                        </style></head><body><header>esPOS SaaS</header><main><h1>%s</h1><p>%s</p></main></body></html>
                        """.formatted(copy.lang,heading,heading,message));
    }

    private enum CallbackCopy {
        ES("es", "Cuenta de correo conectada", "No se pudo conectar la cuenta de correo",
                "Vuelve al panel de esPOS SaaS y actualiza el estado de la conexión.",
                "Vuelve al panel de esPOS SaaS e inicia la conexión de nuevo."),
        EN("en", "Mail account connected", "Could not connect the mail account",
                "Return to the esPOS SaaS panel and refresh the connection status.",
                "Return to the esPOS SaaS panel and start the connection again."),
        ZH("zh", "邮箱已连接", "无法连接邮箱", "请返回 esPOS SaaS 面板，刷新连接状态。", "请返回 esPOS SaaS 面板，重新连接邮箱。");

        final String lang, success, failure, refresh, retry;
        CallbackCopy(String lang, String success, String failure, String refresh, String retry) {
            this.lang = lang;
            this.success = success;
            this.failure = failure;
            this.refresh = refresh;
            this.retry = retry;
        }
        static CallbackCopy forRequest(HttpServletRequest request) {
            if (request.getHeader("Accept-Language") != null) {
                for (var locale : Collections.list(request.getLocales())) {
                    for (var copy : values()) if (copy.lang.equals(locale.getLanguage())) return copy;
                }
            }
            return ES;
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalidInput() { return SmtpSettings.invalid().getBody(); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> oauthProblem(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ex.getBody());
    }
}
