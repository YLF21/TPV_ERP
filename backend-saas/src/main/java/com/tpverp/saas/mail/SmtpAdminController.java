package com.tpverp.saas.mail;

import com.tpverp.saas.admin.AdminAuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/smtp-settings")
public class SmtpAdminController {
    private final SmtpSettings settings;
    private final SmtpTransport smtp;
    private final AdminAuditService audit;
    private final TransactionTemplate transactions;
    public SmtpAdminController(SmtpSettings settings, SmtpTransport smtp, AdminAuditService audit, TransactionTemplate transactions) {
        this.settings = settings;
        this.smtp = smtp;
        this.audit = audit;
        this.transactions = transactions;
    }

    @GetMapping
    public SmtpSettings.View get() { return settings.get(); }

    @PutMapping
    public SmtpSettings.View save(@Valid @RequestBody SmtpSettings.Update input) { return settings.save(input); }

    @PutMapping("/notices")
    public SmtpSettings.View notices(@Valid @RequestBody SmtpSettings.Notices input) { return settings.saveNotices(input); }

    @GetMapping("/accounts")
    public List<SmtpSettings.View> list() { return settings.accounts(); }

    @PostMapping("/accounts")
    public SmtpSettings.View create(@Valid @RequestBody SmtpSettings.Update input) { return settings.create(input); }

    @GetMapping("/accounts/{id}")
    public SmtpSettings.View get(@PathVariable UUID id) { return settings.get(id); }

    @PutMapping("/accounts/{id}")
    public SmtpSettings.View save(@PathVariable UUID id, @Valid @RequestBody SmtpSettings.Update input) {
        return settings.save(id,input);
    }

    @DeleteMapping("/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id, @RequestParam long version) { settings.remove(id,version); }

    public record TestRequest(@NotBlank @Size(max=254) String recipient) {}

    @PostMapping({"/test", "/accounts/{id}/test"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void test(@PathVariable(required=false) UUID id, @Valid @RequestBody TestRequest input) {
        SmtpSettings.validateAddress(input.recipient());
        // Reserve before network I/O so failed attempts also consume the global cooldown.
        UUID accountId = id == null ? settings.get().id() : id;
        long version = settings.reserveTest(accountId);
        transactions.executeWithoutResult(tx -> sendTest(accountId, version, input.recipient()));
    }

    private void sendTest(UUID id, long version, String recipient) {
        var config = settings.lockForSending(id);
        if (!config.view().testReady() || config.view().version() != version) {
            throw SmtpSettings.conflict("SMTP_VERSION_CONFLICT", "La configuración SMTP ha cambiado");
        }
        var content = new ClientDecisionEmail.Content("esPOS · Prueba de correo SMTP",
                "Este correo confirma el envío de prueba desde esPOS SaaS.",
                "<div style='font-family:Arial,sans-serif;color:#15334d'><b>esPOS</b><p>Este correo confirma el envío de prueba desde esPOS SaaS.</p></div>");
        try {
            smtp.sendTest(config, UUID.randomUUID(), recipient, content);
        } catch (SmtpTransport.Failure ex) {
            String reason = "SMTP_DELIVERY_UNCERTAIN".equals(ex.getMessage())
                    ? "No se pudo confirmar la entrega de la prueba"
                    : "El proveedor de correo no ha aceptado la prueba";
            throw SmtpSettings.problem(HttpStatus.BAD_GATEWAY, ex.getMessage(), reason);
        }
        audit.log("TEST_SMTP_SETTINGS", "SMTP_SETTINGS", id.toString());
    }

    // Spring validation diagnostics may include the rejected raw password or SMTP response.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalidInput() { return SmtpSettings.invalid().getBody(); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> smtpProblem(ResponseStatusException ex) {
        var response = ResponseEntity.status(ex.getStatusCode());
        if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After", "30");
        return response.body(ex.getBody());
    }
}
