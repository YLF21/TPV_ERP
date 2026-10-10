package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.shared.crypto.InstallationIdentity;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

class TerminalStorePresentationTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0100001"})
    void bootstrapAndCredentialVerifiedStatusExposeTheCurrentStoreCode(String code) {
        var now = Instant.parse("2026-10-08T10:00:00Z");
        var address = Map.of("linea1", "x", "ciudad", "x", "codigoPostal", "35001", "provincia", "x", "pais", "ES");
        var store = new Store(new Company("B12345674", "Empresa", address), "Tienda", address,
                "hash", "Atlantic/Canary", "EUR", "es-ES");
        store.rememberSaasInternalCode(code);
        var terminal = new Terminal(store, "SERVIDOR", TerminalType.SERVIDOR, "hash");
        var binding = new TerminalPhysicalBinding(terminal, UUID.randomUUID(), UUID.randomUUID(), "hash",
                "Principal", "PC", now, now.plusSeconds(60));
        binding.approve(now);
        var installation = new Installation("TEST", "AQ==", now);
        var stores = mock(StoreRepository.class);
        when(stores.findAll()).thenReturn(List.of(store));
        when(stores.findByIdForUpdate(store.getId())).thenReturn(Optional.of(store));
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var bindings = mock(TerminalPhysicalBindingRepository.class);
        when(bindings.findByRequestId(binding.getRequestId())).thenReturn(Optional.of(binding));
        var status = mock(InstallationStatusService.class);
        var state = new InstallationStatusService.InstallationStatus(installation.getId(), "TEST", now,
                now, OperationalMode.DEVELOPMENT, null, true);
        when(status.status()).thenReturn(state);
        when(status.statusForStore(store.getId())).thenReturn(state);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(store.getEmpresa());
        var key = mock(PublicKey.class);
        when(key.getEncoded()).thenReturn(new byte[] {1});
        var identity = mock(InstallationIdentityStore.class);
        when(identity.loadOrCreate()).thenReturn(new InstallationIdentity("test", key, null));
        when(identity.sign(any(byte[].class))).thenReturn(new byte[] {2});
        var encoder = mock(PasswordEncoder.class);
        when(encoder.matches("proof", "hash")).thenReturn(true);
        var terminals = mock(TerminalRepository.class);
        when(terminals.findForCashSessionPreparation(terminal.getId(), store.getId())).thenReturn(Optional.of(terminal));
        var service = new TerminalLinkingService(terminals, bindings, stores, organization,
                mock(LicenseRepository.class), installations, status, identity, encoder,
                mock(UserSessionRepository.class), mock(AuditService.class), Clock.fixed(now, ZoneOffset.UTC), 1, 15);

        assertThat(service.bootstrap("a".repeat(32)).storeInternalCode()).isEqualTo(code);
        var response = service.status(new TerminalLinkingService.Proof(binding.getRequestId(), "proof"));
        assertThat(response.storeInternalCode()).isEqualTo(code);
        assertThat(response.storeId()).isEqualTo(store.getId());
        assertThat(response.status()).isEqualTo("ACTIVE");
    }
}
