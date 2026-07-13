package com.tpverp.backend.terminal.secrets;

import com.tpverp.backend.shared.crypto.WindowsMachineDpapiSecretProtector;
import java.time.Clock;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PaymentSecretConfiguration {
    @Bean PaymentSecretOwnerResolver paymentSecretOwnerResolver(CurrentOrganization organization,CurrentTerminal terminal){return new PaymentSecretOwnerResolver(organization,terminal);}
    @Bean PaymentSecretStore paymentSecretStore(PaymentSecretReferenceRepository repository, Clock clock,PaymentSecretOwnerResolver owners) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return new UnavailablePaymentSecretStore();
        }
        return new ProtectedPaymentSecretStore(repository, new WindowsMachineDpapiSecretProtector(), clock,owners);
    }
}
