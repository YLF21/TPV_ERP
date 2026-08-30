package com.tpverp.saas.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasLicense;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalStatusAdminServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Test
    void incluyeInstalacionesSinReporteYUsaLaFechaReportadaParaCalcularObsolescencia() {
        SaasFiscalStatusRepository statuses = mock(SaasFiscalStatusRepository.class);
        SaasInstallationRepository installations = mock(SaasInstallationRepository.class);
        SaasStoreRepository stores = mock(SaasStoreRepository.class);
        Fixture reported = fixture("Empresa A", "Tienda 1", "INST-1");
        Fixture unknown = fixture("Empresa A", "Tienda 2", "INST-2");
        SaasFiscalStatus current = new SaasFiscalStatus(
                UUID.randomUUID(), reported.installation(), reported.company(), reported.store(),
                reported.installation().getInstallationId(), UUID.randomUUID(), "VERIFACTU", "ACTIVE", 4,
                NOW.minusSeconds(3600), LocalDate.of(2026, 8, 1), 3L, "REAL", "TEST", "AEAT",
                NOW.minusSeconds(72 * 3600), NOW.minusSeconds(3600), "a".repeat(64));
        var firstRows = List.of(statusRow(reported, current, true, true), statusRow(unknown, null, true, true));
        var firstCompanyRows = List.of(companyRow(reported.company(), 2, 2, 0, 2, 2, "MIXED", 0, null));
        when(statuses.findAdminPage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(firstRows);
        when(statuses.findAdminCompanyPage(any(), any(), any(), any(), any(), any()))
                .thenReturn(firstCompanyRows);

        FiscalStatusAdminService service = new FiscalStatusAdminService(
                statuses, installations, stores, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.all())
                .hasSize(2)
                .anySatisfy(row -> {
                    assertThat(row.installationReference()).isEqualTo("INST-1");
                    assertThat(row.effectiveMode()).isEqualTo("VERIFACTU");
                    assertThat(row.stale()).isTrue();
                })
                .anySatisfy(row -> {
                    assertThat(row.installationReference()).isEqualTo("INST-2");
                    assertThat(row.effectiveMode()).isEqualTo("UNKNOWN");
                    assertThat(row.activationState()).isEqualTo("UNKNOWN");
                    assertThat(row.stale()).isTrue();
                });

        assertThat(service.companies()).singleElement().satisfies(company -> {
            assertThat(company.installations()).isEqualTo(2);
            assertThat(company.stores()).isEqualTo(2);
            assertThat(company.unlinkedStores()).isZero();
            assertThat(company.staleInstallations()).isEqualTo(2);
            assertThat(company.effectiveMode()).isEqualTo("MIXED");
            assertThat(company.activationState()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    void incluyeUnaTiendaTodaviaNoVinculadaComoUnknown() {
        SaasFiscalStatusRepository statuses = mock(SaasFiscalStatusRepository.class);
        SaasInstallationRepository installations = mock(SaasInstallationRepository.class);
        SaasStoreRepository stores = mock(SaasStoreRepository.class);
        Fixture unlinked = fixture("Empresa A", "Tienda 1", "INST-NO-USADA");
        var secondRows = List.of(statusRow(unlinked, null, true, false));
        var secondCompanyRows = List.of(companyRow(unlinked.company(), 1, 0, 1, 0, 1, "UNKNOWN", 0, null));
        when(statuses.findAdminPage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(secondRows);
        when(statuses.findAdminCompanyPage(any(), any(), any(), any(), any(), any()))
                .thenReturn(secondCompanyRows);
        FiscalStatusAdminService service = new FiscalStatusAdminService(
                statuses, installations, stores, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.all()).singleElement().satisfies(row -> {
            assertThat(row.storeId()).isEqualTo(unlinked.store().getId());
            assertThat(row.installationId()).isNull();
            assertThat(row.installationReference()).isNull();
            assertThat(row.effectiveMode()).isEqualTo("UNKNOWN");
            assertThat(row.stale()).isTrue();
        });
        assertThat(service.companies()).singleElement().satisfies(company -> {
            assertThat(company.stores()).isEqualTo(1);
            assertThat(company.installations()).isZero();
            assertThat(company.unlinkedStores()).isEqualTo(1);
            assertThat(company.staleInstallations()).isZero();
        });
    }

    private static Fixture fixture(String companyName, String storeName, String installationReference) {
        Instant createdAt = NOW.minusSeconds(86400);
        SaasCompany company = new SaasCompany(
                UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"), companyName, "B12345674",
                TaxpayerType.SOCIEDAD, TaxRegime.IVA, CommercialProfile.MINORISTA, createdAt);
        String storeCode = "Tienda 1".equals(storeName) ? "001" : "002";
        SaasStore store = new SaasStore(
                UUID.randomUUID(), company, storeCode, storeName,
                "Atlantic/Canary", createdAt);
        SaasLicense license = new SaasLicense(
                UUID.randomUUID(), company, "LIC-" + installationReference,
                NOW.plusSeconds(86400), 1, 0, createdAt);
        SaasInstallation installation = new SaasInstallation(
                UUID.randomUUID(), company, store, license, UUID.randomUUID(), installationReference,
                "public-key", "token-hash", createdAt);
        return new Fixture(company, store, installation);
    }

    private static SaasFiscalStatusRepository.AdminStatusRow statusRow(
            Fixture fixture, SaasFiscalStatus status, boolean stale, boolean linked) {
        var row = mock(SaasFiscalStatusRepository.AdminStatusRow.class);
        when(row.getCompanyId()).thenReturn(fixture.company().getId());
        when(row.getCompanyName()).thenReturn(fixture.company().getName());
        when(row.getTaxId()).thenReturn(fixture.company().getTaxId());
        when(row.getStoreId()).thenReturn(fixture.store().getId());
        when(row.getStoreName()).thenReturn(fixture.store().getName());
        when(row.getInstallationId()).thenReturn(linked ? fixture.installation().getInstallationId() : null);
        when(row.getInstallationReference()).thenReturn(linked ? fixture.installation().getInstallationReference() : null);
        when(row.getEffectiveMode()).thenReturn(status == null ? "UNKNOWN" : status.getEffectiveMode());
        when(row.getActivationState()).thenReturn(status == null ? "UNKNOWN" : status.getActivationState());
        when(row.getModeVersion()).thenReturn(status == null ? 0 : status.getModeVersion());
        when(row.getModeSince()).thenReturn(status == null ? null : status.getModeSince());
        when(row.getActivationDate()).thenReturn(status == null ? null : status.getActivationDate());
        when(row.getPolicyVersion()).thenReturn(status == null ? null : status.getPolicyVersion());
        when(row.getRuntimeClass()).thenReturn(status == null ? null : status.getRuntimeClass());
        when(row.getEndpointEnvironment()).thenReturn(status == null ? null : status.getEndpointEnvironment());
        when(row.getTransportMode()).thenReturn(status == null ? null : status.getTransportMode());
        when(row.getReportedAt()).thenReturn(status == null ? null : status.getReportedAt());
        when(row.getReceivedAt()).thenReturn(status == null ? null : status.getReceivedAt());
        when(row.isStale()).thenReturn(stale);
        when(row.getCompanySort()).thenReturn(fixture.company().getName().toLowerCase());
        when(row.getStoreSort()).thenReturn(fixture.store().getName().toLowerCase());
        when(row.getCodeSort()).thenReturn(fixture.store().getCode().toLowerCase());
        return row;
    }

    private static SaasFiscalStatusRepository.AdminCompanyRow companyRow(
            SaasCompany company, long stores, long installations, long unlinked, long stale,
            long modeCount, String mode, long stateCount, String state) {
        var row = mock(SaasFiscalStatusRepository.AdminCompanyRow.class);
        when(row.getCompanyId()).thenReturn(company.getId());
        when(row.getCompanyName()).thenReturn(company.getName());
        when(row.getTaxId()).thenReturn(company.getTaxId());
        when(row.getStores()).thenReturn(stores);
        when(row.getInstallations()).thenReturn(installations);
        when(row.getUnlinkedStores()).thenReturn(unlinked);
        when(row.getStaleInstallations()).thenReturn(stale);
        when(row.getModeCount()).thenReturn(modeCount);
        when(row.getSingleMode()).thenReturn(mode);
        when(row.getStateCount()).thenReturn(stateCount);
        when(row.getSingleState()).thenReturn(state);
        when(row.getCompanySort()).thenReturn(company.getName().toLowerCase());
        return row;
    }

    private record Fixture(SaasCompany company, SaasStore store, SaasInstallation installation) {
    }
}
