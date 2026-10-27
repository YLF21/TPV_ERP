package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SaasInstallationRecoveryInvalidationTest {
    private static final Instant NOW = Instant.parse("2026-10-27T01:00:00Z");
    private final TokenHasher tokens = new TokenHasher();

    @Test
    void invalidationPreservesFrozenHashAndBlocksPairingAcrossRepeatedCalls() {
        String recoveryHash = tokens.hash("original-link-recovery-secret");
        var installation = installation(null, null, null, recoveryHash);
        UUID pairingId = UUID.randomUUID();
        installation.usePairing(pairingId);
        installation.rotateTokenHash(tokens.hash("activated-credential"));

        installation.invalidateLinkRecovery();
        installation.invalidateLinkRecovery();

        assertThat(installation.hasLinkRecoveryTokenHash(recoveryHash)).isTrue();
        assertThat(installation.isCurrentPairing(pairingId)).isFalse();
        assertThat(installation.isCurrentPairing(UUID.randomUUID())).isFalse();
        assertThat(installation.hasTokenHash(tokens.hash("activated-credential"))).isTrue();
        assertThat(installation.hasTokenHash(tokens.hash("retired-credential"))).isFalse();
    }

    @Test
    void legacyNullHashRemainsAbsentAndItsPairingIsInvalidated() {
        var installation = installation(null, null, null, null);

        installation.invalidateLinkRecovery();

        assertThat(installation.hasLinkRecoveryTokenHash(tokens.hash("unrelated"))).isFalse();
        assertThat(installation.isCurrentPairing(UUID.randomUUID())).isFalse();
    }

    @Test
    void consumedPairingCannotRecoverCredentialUsingPreservedHashAfterActivation() {
        var company = new SaasCompany(UUID.randomUUID(), "Recovery test", "B12345674",
                TaxpayerType.SOCIEDAD, TaxRegime.IGIC, NOW);
        var store = new SaasStore(UUID.randomUUID(), company, "001", "Store", "Atlantic/Canary", NOW);
        var license = new SaasLicense(UUID.randomUUID(), company, "LIC-RECOVERY-TEST",
                NOW.plusSeconds(86400), 1, 1, NOW);
        String recoveryToken = "original-recovery-secret-with-at-least-43-characters";
        String recoveryHash = tokens.hash(recoveryToken);
        var installation = installation(company, store, license, recoveryHash);
        var pairing = new SaasPairingCode(UUID.randomUUID(), company, store, license,
                "TPV-RECOVERY", NOW.plusSeconds(3600), NOW);
        installation.usePairing(pairing.getId());
        pairing.consume(NOW, installation, recoveryHash, tokens.hash("retired-credential"));
        installation.rotateTokenHash(tokens.hash("activated-credential"));
        installation.invalidateLinkRecovery();

        var pairingCodes = mock(SaasPairingCodeRepository.class);
        var licenses = mock(SaasLicenseRepository.class);
        var installations = mock(SaasInstallationRepository.class);
        var authenticator = mock(InstallationAuthenticator.class);
        var policies = mock(VerifactuActivationPolicyResolver.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(pairing.getCode()))).thenReturn(List.of(Map.of(
                "id", pairing.getId(), "company_id", company.getId(), "reference", license.getReference())));
        when(licenses.findByReferenceForUpdate(license.getReference())).thenReturn(Optional.of(license));
        when(pairingCodes.findByCodeForUpdate(pairing.getCode())).thenReturn(Optional.of(pairing));
        when(installations.findByInstallationIdForUpdate(installation.getInstallationId()))
                .thenReturn(Optional.of(installation));
        var service = new LicenseLinkService(pairingCodes, licenses, installations, tokens,
                authenticator, Clock.fixed(NOW, ZoneOffset.UTC), policies, jdbc);
        var request = new LicenseSaasLinkRequest(pairing.getCode(), installation.getInstallationId(),
                installation.getInstallationReference(), null, store.getId(), "001", "B12345674", "Recovery test");

        assertThatThrownBy(() -> service.link(request, "retired-credential", recoveryToken))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ya no es el vigente");

        assertThat(installation.hasLinkRecoveryTokenHash(recoveryHash)).isTrue();
        assertThat(installation.hasTokenHash(tokens.hash("activated-credential"))).isTrue();
        verify(installations, never()).save(any());
        verify(pairingCodes, never()).save(any());
        verifyNoInteractions(authenticator, policies);
    }

    private SaasInstallation installation(SaasCompany company, SaasStore store, SaasLicense license,
            String recoveryHash) {
        return new SaasInstallation(UUID.randomUUID(), company, store, license, UUID.randomUUID(),
                "INST-RECOVERY-TEST", null, tokens.hash("retired-credential"), recoveryHash, NOW);
    }
}
