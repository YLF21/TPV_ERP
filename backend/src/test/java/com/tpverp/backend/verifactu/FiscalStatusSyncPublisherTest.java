package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxEvent;
import com.tpverp.backend.sync.SyncOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class FiscalStatusSyncPublisherTest {

    @Test
    void publicaLaModalidadDeTodasLasTiendasConLicenciaSaas() {
        Instant now = Instant.parse("2027-01-02T10:00:00Z");
        var first = licensedStore(FiscalMode.VERIFACTU, now);
        var second = licensedStore(FiscalMode.NO_VERIFACTU, now);
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByActivaTrueOrderByValidaDesdeDesc())
                .thenReturn(List.of(first.license, second.license));
        var stores = mock(StoreRepository.class);
        when(stores.findWithCompanyById(first.storeId)).thenReturn(Optional.of(first.store));
        when(stores.findWithCompanyById(second.storeId)).thenReturn(Optional.of(second.store));
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findByCompanyId(first.companyId))
                .thenReturn(Optional.of(first.configuration));
        when(configurations.findByCompanyId(second.companyId))
                .thenReturn(Optional.of(second.configuration));
        var outbox = mock(SyncOutboxService.class);
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"));
        var publisher = new FiscalStatusSyncPublisher(
                mock(CurrentOrganization.class), stores, mock(InstallationRepository.class),
                licenses, configurations, runtime,
                outbox, Clock.fixed(now, ZoneOffset.UTC));

        publisher.publishScheduled();

        var commands = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox, org.mockito.Mockito.times(2)).enqueue(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(SyncOutboundEventCommand::companyId)
                .containsExactly(first.companyId, second.companyId);
        assertThat(commands.getAllValues())
                .extracting(command -> command.payload().get("effectiveMode"))
                .containsExactly("VERIFACTU", "NO_VERIFACTU");
        assertThat(commands.getAllValues())
                .extracting(command -> command.payload().get("activationState"))
                .containsExactly("ACTIVE", "DUE_REVIEW");
        assertThat(commands.getAllValues())
                .allSatisfy(command -> assertThat(command.payload().get("reportedAt"))
                        .isEqualTo(now.toString()));
    }

    @Test
    void noDuplicaElEstadoSinCambiosAntesDelHeartbeat() {
        Instant now = Instant.parse("2027-01-02T10:00:00Z");
        var licensed = licensedStore(FiscalMode.VERIFACTU, now);
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByActivaTrueOrderByValidaDesdeDesc()).thenReturn(List.of(licensed.license));
        var stores = mock(StoreRepository.class);
        when(stores.findWithCompanyById(licensed.storeId)).thenReturn(Optional.of(licensed.store));
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findByCompanyId(licensed.companyId))
                .thenReturn(Optional.of(licensed.configuration));
        var outbox = mock(SyncOutboxService.class);
        when(outbox.latest(licensed.companyId, licensed.storeId,
                "FISCAL_STATUS", licensed.companyId)).thenReturn(Optional.empty());
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"));
        var publisher = new FiscalStatusSyncPublisher(
                mock(CurrentOrganization.class), stores, mock(InstallationRepository.class),
                licenses, configurations, runtime, outbox, Clock.fixed(now, ZoneOffset.UTC));

        publisher.publishScheduled();
        var commands = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(commands.capture());
        var command = commands.getValue();
        var latest = new SyncOutboxEvent(command.companyId(), command.storeId(), command.terminalId(),
                command.entityType(), command.entityId(), command.operation(), command.payload(), now);
        when(outbox.latest(licensed.companyId, licensed.storeId,
                "FISCAL_STATUS", licensed.companyId)).thenReturn(Optional.of(latest));

        publisher.publishScheduled();

        verify(outbox, org.mockito.Mockito.times(1)).enqueue(any());
    }

    private static LicensedStore licensedStore(FiscalMode mode, Instant now) {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        var license = mock(License.class);
        when(license.getSaasCompanyId()).thenReturn(UUID.randomUUID());
        when(license.getSaasStoreId()).thenReturn(UUID.randomUUID());
        when(license.getLocalCompanyId()).thenReturn(companyId);
        when(license.getTiendaId()).thenReturn(storeId);
        when(license.getInstalacionId()).thenReturn(installationId);
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getVerifactuActivationDate()).thenReturn(LocalDate.of(2027, 1, 1));
        when(license.getVerifactuPolicyVersion()).thenReturn(2L);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        var configuration = new VerifactuConfiguration(companyId);
        if (mode != FiscalMode.PRE_SIF) {
            configuration.changeMode(mode, now.minusSeconds(60), null);
        }
        return new LicensedStore(companyId, storeId, license, store, configuration);
    }

    private record LicensedStore(
            UUID companyId,
            UUID storeId,
            License license,
            Store store,
            VerifactuConfiguration configuration) {
    }
}
