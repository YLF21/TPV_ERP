package com.tpverp.saas.admin;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ManualPaymentReconciliationAdapter implements PaymentReconciliationAdapter {

    private static final Set<String> PROVIDERS = Set.of("MANUAL_BANK", "MANUAL_GATEWAY");

    @Override
    public boolean supports(String provider) {
        return provider != null && PROVIDERS.contains(provider.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public void validate(CreatePaymentReconciliationRequest request) {
        if (!supports(request.provider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proveedor de conciliacion no permitido");
        }
        try {
            BigDecimal amount = new BigDecimal(request.amount());
            if (amount.signum() <= 0 || amount.scale() > 2) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Importe de conciliacion no valido");
        }
        if (request.currency() == null || !request.currency().trim().toUpperCase(Locale.ROOT).matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Moneda no valida");
        }
    }
}
