package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class LicenseSaasLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    private final InstallationRepository installations = org.mockito.Mockito.mock(InstallationRepository.class);
    private final CompanyRepository companies = org.mockito.Mockito.mock(CompanyRepository.class);
    private final StoreRepository stores = org.mockito.Mockito.mock(StoreRepository.class);
    private final LicenseRepository licenses = org.mockito.Mockito.mock(LicenseRepository.class);
    private final LicenseSaasLinkClient client = org.mockito.Mockito.mock(LicenseSaasLinkClient.class);
    private final LicenseSaasCredentialStore credentials = org.mockito.Mockito.mock(LicenseSaasCredentialStore.class);
    private final AuditService audit = org.mockito.Mockito.mock(AuditService.class);
    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final LicenseSaasLinkService service = new LicenseSaasLinkService(
            installations,
            companies,
            stores,
            licenses,
            client,
            credentials,
            Clock.fixed(NOW, ZoneOffset.UTC),
            audit,
            jdbc);

    @Test
    void vinculaInstalacionConSaasYActivaLicenciaDevuelta() {
        var installation = installation();
        var store = store();
        var previous = license(store, installation, "LIC-OLD");
        var saasCompanyId = UUID.randomUUID();
        var saasStoreId = UUID.randomUUID();
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(store.getId(), installation.getId()))
                .thenReturn(Optional.of(previous));
        when(client.link(any())).thenReturn(saasResponse(saasCompanyId, saasStoreId));

        LicenseSaasLinkResponse result = service.link("ABC123");

        var request = ArgumentCaptor.forClass(LicenseSaasLinkRequest.class);
        verify(client).link(request.capture());
        assertThat(request.getValue().pairingCode()).isEqualTo("ABC123");
        assertThat(request.getValue().installationId()).isEqualTo(installation.getId());
        assertThat(request.getValue().installationReference()).isEqualTo("INST-1");
        assertThat(request.getValue().installationPublicKey()).isEqualTo("public-key");
        assertThat(request.getValue().storeId()).isEqualTo(store.getId());
        assertThat(request.getValue().storeCode()).isEqualTo("001");
        assertThat(request.getValue().taxId()).isEqualTo("B12345678");

        var saved = ArgumentCaptor.forClass(License.class);
        verify(licenses).save(saved.capture());
        assertThat(previous.isActiva()).isFalse();
        assertThat(saved.getValue().getReferencia()).isEqualTo("LIC-SAAS-1");
        assertThat(saved.getValue().getValidaDesde()).isEqualTo(NOW);
        assertThat(saved.getValue().getValidaHasta()).isEqualTo(Instant.parse("2027-08-10T00:00:00Z"));
        assertThat(saved.getValue().getMaxWindows()).isEqualTo(2);
        assertThat(saved.getValue().getMaxPda()).isEqualTo(1);
        assertThat(saved.getValue().getEstadoSaas()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(saved.getValue().getUltimaValidacionSaas()).isEqualTo(NOW);
        assertThat(result.licenseReference()).isEqualTo("LIC-SAAS-1");
        verify(credentials).writeToken("token-instalacion");
        verify(audit).record(
                org.mockito.Mockito.eq("LICENSE_SAAS_LINKED"),
                org.mockito.Mockito.eq(AuditResult.EXITO),
                org.mockito.Mockito.anyMap());
    }

    @Test
    void vinculaInstalacionNuevaCreandoEmpresaYTiendaDesdeSaas() {
        var installation = installation();
        var saasCompanyId = UUID.randomUUID();
        var saasStoreId = UUID.randomUUID();
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of());
        when(client.link(any())).thenReturn(saasResponse(saasCompanyId, saasStoreId));
        when(companies.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stores.save(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.link("ABC123");

        var request = ArgumentCaptor.forClass(LicenseSaasLinkRequest.class);
        verify(client).link(request.capture());
        assertThat(request.getValue().storeId()).isNull();
        assertThat(request.getValue().storeCode()).isNull();
        assertThat(request.getValue().taxId()).isNull();

        var company = ArgumentCaptor.forClass(Company.class);
        verify(companies).save(company.capture());
        assertThat(company.getValue().getTaxId()).isEqualTo("B12345678");
        assertThat(company.getValue().getRazonSocial()).isEqualTo("EMPRESA REAL");

        var store = ArgumentCaptor.forClass(Store.class);
        verify(stores).save(store.capture());
        assertThat(store.getValue().getCodigoTienda()).isEqualTo("001");
        assertThat(store.getValue().getNombreEfectivo()).isEqualTo("TIENDA 001");

        var saved = ArgumentCaptor.forClass(License.class);
        verify(licenses).save(saved.capture());
        assertThat(saved.getValue().getReferencia()).isEqualTo("LIC-SAAS-1");
    }

    private static Installation installation() {
        return new Installation("INST-1", "public-key", Instant.parse("2026-06-08T00:00:00Z"));
    }

    private static Store store() {
        var company = new Company("B12345678", "Company", address());
        return new Store(company, "Store", address(), "hash", "Atlantic/Canary", "EUR", "es-ES");
    }

    private static License license(Store store, Installation installation, String reference) {
        return new License(
                store,
                installation,
                reference,
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                1,
                0,
                "B12345678",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                "{}",
                "hash-" + reference,
                3,
                Instant.parse("2026-06-08T00:00:00Z"),
                Map.of(),
                ImportResult.ACEPTADA,
                null,
                true);
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }

    private static LicenseSaasLinkResponse saasResponse(UUID companyId, UUID storeId) {
        return new LicenseSaasLinkResponse(
                "LIC-SAAS-1",
                companyId,
                storeId,
                "B12345678",
                "EMPRESA REAL",
                address(),
                "001",
                "TIENDA 001",
                address(),
                Instant.parse("2027-08-10T00:00:00Z"),
                LicenseSaasStatus.VALIDA,
                2,
                1,
                "B12345678",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                "token-instalacion");
    }
}
