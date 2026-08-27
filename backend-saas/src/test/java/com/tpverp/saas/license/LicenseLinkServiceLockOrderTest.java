package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.web.server.ResponseStatusException;

class LicenseLinkServiceLockOrderTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

    @Test
    void enlaceDescubreLaLicenciaSinLockYBloqueaLicenciaAntesQuePairing() {
        var pairingCodes = mock(SaasPairingCodeRepository.class);
        var licenses = mock(SaasLicenseRepository.class);
        var installations = mock(SaasInstallationRepository.class);
        var tokens = mock(TokenHasher.class);
        var authenticator = mock(InstallationAuthenticator.class);
        var policies = mock(VerifactuActivationPolicyResolver.class);
        var service = new LicenseLinkService(
                pairingCodes,
                licenses,
                installations,
                tokens,
                authenticator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                policies);
        Map<String, String> address = Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        var company = new SaasCompany(
                UUID.randomUUID(), "Empresa", "B12345674",
                TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                CommercialProfile.MAYORISTA, address, NOW);
        var store = new SaasStore(
                UUID.randomUUID(), company, "001", "Tienda",
                address, "Atlantic/Canary", NOW);
        var license = new SaasLicense(
                UUID.randomUUID(), company, "LIC-LOCK-LINK-1",
                NOW.plusSeconds(86_400), 1, 0, NOW);
        var pairing = new SaasPairingCode(
                UUID.randomUUID(), company, store, license, "TPV-LOCK01",
                NOW.minusSeconds(1), NOW.minusSeconds(3600));
        when(pairingCodes.findFirstByCode("TPV-LOCK01")).thenReturn(Optional.of(pairing));
        when(licenses.findByReferenceForUpdate("LIC-LOCK-LINK-1"))
                .thenReturn(Optional.of(license));
        when(pairingCodes.findByCodeForUpdate("TPV-LOCK01")).thenReturn(Optional.of(pairing));
        when(installations.findByInstallationIdForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        when(policies.required(TaxpayerType.SOCIEDAD)).thenReturn(new VerifactuPolicySnapshot(
                LocalDate.of(2027, 1, 1), 1, NOW));
        var request = new LicenseSaasLinkRequest(
                "TPV-LOCK01", UUID.randomUUID(), "INST-LOCK", "public-key",
                store.getId(), "001", "B12345674", "Empresa",
                null, null, "Atlantic/Canary");

        assertThatThrownBy(() -> service.link(request, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("caducado o usado");

        var order = inOrder(pairingCodes, licenses);
        order.verify(pairingCodes).findFirstByCode("TPV-LOCK01");
        order.verify(licenses).findByReferenceForUpdate("LIC-LOCK-LINK-1");
        order.verify(pairingCodes).findByCodeForUpdate("TPV-LOCK01");
    }

    @Test
    void repositorioSoloBloqueaLaRelecturaPosteriorALockDeLicencia() throws Exception {
        Lock preReadLock = SaasPairingCodeRepository.class
                .getMethod("findFirstByCode", String.class)
                .getAnnotation(Lock.class);
        Lock lockedRead = SaasPairingCodeRepository.class
                .getMethod("findByCodeForUpdate", String.class)
                .getAnnotation(Lock.class);

        assertThat(preReadLock).isNull();
        assertThat(lockedRead).isNotNull();
        assertThat(lockedRead.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
