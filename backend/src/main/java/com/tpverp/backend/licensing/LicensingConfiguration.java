package com.tpverp.backend.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.application.LicenseEnvelopeDecoder;
import com.tpverp.backend.licensing.application.LicenseService;
import com.tpverp.backend.licensing.application.TrustedIssuerKeyProvider;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.shared.crypto.WindowsDpapiSecretProtector;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import com.tpverp.backend.audit.AuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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
            InstallationRepository instalacionRepository,
            StoreRepository tiendaRepository,
            LicenseRepository licenciaRepository,
            InstallationIdentityStore identityStore,
            TrustedIssuerKeyProvider issuerKeyProvider,
            LicenseEnvelopeDecoder decoder,
            Clock clock,
            AuditService auditService,
            JdbcTemplate jdbcTemplate) {
        return new LicenseService(
                instalacionRepository,
                tiendaRepository,
                licenciaRepository,
                identityStore,
                issuerKeyProvider,
                decoder,
                clock,
                auditService,
                jdbcTemplate);
    }

    @Bean
    LicenseSaasCredentialStore licenseSaasCredentialStore(
            @Value("${tpv.installation.key-directory}") Path keyDirectory) {
        return new LicenseSaasCredentialStore(keyDirectory, new WindowsDpapiSecretProtector());
    }

    @Bean
    @ConditionalOnProperty("tpv.license.saas-url")
    LicenseSaasValidationClient httpLicenseSaasValidationClient(
            @Value("${tpv.license.saas-url}") URI saasUrl,
            LicenseSaasCredentialStore credentials) {
        return new HttpLicenseSaasValidationClient(saasUrl, credentials, new ObjectMapper());
    }

    @Bean
    @ConditionalOnProperty("tpv.license.saas-url")
    LicenseSaasLinkClient httpLicenseSaasLinkClient(
            @Value("${tpv.license.saas-url}") URI saasUrl) {
        return new HttpLicenseSaasLinkClient(saasUrl, new ObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean(LicenseSaasValidationClient.class)
    LicenseSaasValidationClient disabledLicenseSaasValidationClient() {
        return new DisabledLicenseSaasValidationClient();
    }

    @Bean
    @ConditionalOnMissingBean(LicenseSaasLinkClient.class)
    LicenseSaasLinkClient disabledLicenseSaasLinkClient() {
        return new DisabledLicenseSaasLinkClient();
    }

    @Bean
    LicenseSaasValidationService licenseSaasValidationService(
            InstallationRepository instalacionRepository,
            StoreRepository tiendaRepository,
            LicenseRepository licenciaRepository,
            LicenseSaasValidationClient client,
            Clock clock) {
        return new LicenseSaasValidationService(
                instalacionRepository,
                tiendaRepository,
                licenciaRepository,
                client,
                clock);
    }

    @Bean
    LicenseSaasValidationEndpointService licenseSaasValidationEndpointService(
            LicenseRepository licenciaRepository) {
        return new LicenseSaasValidationEndpointService(licenciaRepository);
    }

    @Bean
    LicenseSaasLinkService licenseSaasLinkService(
            InstallationRepository instalacionRepository,
            CompanyRepository empresaRepository,
            StoreRepository tiendaRepository,
            LicenseRepository licenciaRepository,
            LicenseSaasLinkClient client,
            LicenseSaasCredentialStore credentials,
            Clock clock,
            AuditService auditService,
            JdbcTemplate jdbcTemplate) {
        return new LicenseSaasLinkService(
                instalacionRepository,
                empresaRepository,
                tiendaRepository,
                licenciaRepository,
                client,
                credentials,
                clock,
                auditService,
                jdbcTemplate);
    }

    @Bean
    LicenseSaasAdminService licenseSaasAdminService(
            LicenseRepository licenciaRepository,
            Clock clock) {
        return new LicenseSaasAdminService(licenciaRepository, clock);
    }

    @Bean
    @ConditionalOnProperty("tpv.license.saas-url")
    LicenseSaasValidationScheduler licenseSaasValidationScheduler(
            LicenseSaasValidationService service) {
        return new LicenseSaasValidationScheduler(service);
    }

    @Bean
    @ConditionalOnProperty("tpv.license.saas-url")
    LicenseSaasValidationStartup licenseSaasValidationStartup(
            LicenseSaasValidationService service) {
        return new LicenseSaasValidationStartup(service);
    }

    @Bean
    LicenseOfflineWarningScheduler licenseOfflineWarningScheduler(
            LicenseRepository licenciaRepository,
            AuditService auditService,
            Clock clock) {
        return new LicenseOfflineWarningScheduler(licenciaRepository, auditService, clock);
    }
}
