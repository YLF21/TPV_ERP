package com.tpverp.backend.licensing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.shared.crypto.InstallationIdentity;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LicenseServiceTest {

    private static final Map<String, String> ADDRESS = Map.of(
            "linea1", "Calle Uno", "ciudad", "Las Palmas", "codigoPostal", "35001",
            "provincia", "Las Palmas", "pais", "ES");

    @Mock private InstallationRepository installations;
    @Mock private StoreRepository stores;
    @Mock private LicenseRepository licenses;
    @Mock private InstallationIdentityStore identityStore;
    @Mock private TrustedIssuerKeyProvider issuerKeys;
    @Mock private LicenseEnvelopeDecoder decoder;
    @Mock private AuditService audit;
    @Mock private JdbcTemplate jdbc;
    @Mock private PrivateKey privateKey;
    @Mock private PublicKey publicKey;

    private LicenseService service;
    private Company company;
    private Store store;

    @BeforeEach
    void setUp() {
        company = new Company("DEMO-00000000", "Company", ADDRESS);
        store = new Store(
                company, "Store", ADDRESS, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var installation = new Installation(
                "INST-1", "public-key", Instant.parse("2026-06-08T00:00:00Z"));
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(identityStore.loadOrCreate())
                .thenReturn(new InstallationIdentity("key", publicKey, privateKey));
        when(issuerKeys.load()).thenReturn(publicKey);
        when(licenses.findByReferencia(any())).thenReturn(Optional.empty());
        service = new LicenseService(
                installations, stores, licenses, identityStore, issuerKeys, decoder,
                Clock.fixed(Instant.parse("2026-06-08T10:00:00Z"), ZoneOffset.UTC),
                audit, jdbc);
    }

    @Test
    void firstRealLicenseAdoptsTheSignedTaxId() {
        when(decoder.decode(any(), any(), any(), any(), any())).thenReturn(preview("B12345678"));

        service.activate("license", "hash");

        assertThat(company.getTaxId()).isEqualTo("B12345678");
        verify(licenses).save(any(License.class));
    }

    @Test
    void renewalRejectsAnotherTaxIdBeforeMutatingLicenses() {
        company.adoptLicensedTaxId("B87654321");
        when(decoder.decode(any(), any(), any(), any(), any())).thenReturn(preview("B12345678"));

        assertThatThrownBy(() -> service.activate("license", "hash"))
                .isInstanceOf(LicenseValidationException.class)
                .hasMessageContaining("NIF");

        verify(licenses, never()).save(any());
    }

    private LicensePreview preview(String taxId) {
        return new LicensePreview(
                "LIC-1", taxId, TaxpayerType.SOCIEDAD, "Company", "Store",
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2027-06-09T00:00:00Z"),
                1, 0, TaxRegime.IVA, "issuer", "hash");
    }
}
