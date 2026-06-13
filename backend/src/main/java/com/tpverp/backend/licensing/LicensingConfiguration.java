package com.tpverp.backend.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.licensing.application.LicenseEnvelopeDecoder;
import com.tpverp.backend.licensing.application.LicenseService;
import com.tpverp.backend.licensing.application.TrustedIssuerKeyProvider;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import java.nio.file.Path;
import java.time.Clock;
import com.tpverp.backend.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LicensingConfiguration {

    @Bean
    LicenseEnvelopeDecoder licenseEnvelopeDecoder() {
        return new LicenseEnvelopeDecoder(new ObjectMapper());
    }

    @Bean
    TrustedIssuerKeyProvider trustedIssuerKeyProvider(
            @Value("${tpv.license.issuer-public-key-file:}") String publicKeyFile) {
        return new TrustedIssuerKeyProvider(
                publicKeyFile == null || publicKeyFile.isBlank() ? null : Path.of(publicKeyFile));
    }

    @Bean
    LicenseService licenseService(
            InstalacionRepository instalacionRepository,
            TiendaRepository tiendaRepository,
            LicenciaRepository licenciaRepository,
            InstallationIdentityStore identityStore,
            TrustedIssuerKeyProvider issuerKeyProvider,
            LicenseEnvelopeDecoder decoder,
            Clock clock,
            AuditService auditService) {
        return new LicenseService(
                instalacionRepository,
                tiendaRepository,
                licenciaRepository,
                identityStore,
                issuerKeyProvider,
                decoder,
                clock,
                auditService);
    }
}
