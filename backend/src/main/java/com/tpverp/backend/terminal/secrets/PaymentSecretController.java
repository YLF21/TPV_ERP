package com.tpverp.backend.terminal.secrets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment-terminal/secrets")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('PAYMENT_TERMINAL_SECRETS')")
public class PaymentSecretController {
    private final PaymentSecretAdministrationService service;
    public PaymentSecretController(PaymentSecretAdministrationService service){this.service=service;}
    @PostMapping public PaymentSecretStore.SecretReferenceView create(@Valid @RequestBody SecretWriteRequest request){return service.create(request.provider(),request.material());}
    @PostMapping("/{reference}/rotation") public PaymentSecretStore.SecretReferenceView rotate(@PathVariable String reference,@Valid @RequestBody SecretWriteRequest request){return service.rotate(reference,request.material());}
    @DeleteMapping("/{reference}") public void delete(@PathVariable String reference){service.delete(reference);}
    public record SecretWriteRequest(@NotBlank String provider,@NotEmpty char[] material){@Override public String toString(){return "SecretWriteRequest[provider="+provider+", material=[REDACTED]]";}}
}
