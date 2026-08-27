package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.licensing.application.CommercialProfile;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LicenseSaasCacheAuthenticatorTest {

    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00.123456Z");

    private final LicenseSaasCredentialStore credentials =
            org.mockito.Mockito.mock(LicenseSaasCredentialStore.class);
    private final LicenseSaasCacheAuthenticator authenticator =
            new LicenseSaasCacheAuthenticator(credentials);

    @Test
    void autenticaElSnapshotCompletoYDetectaCambiosDeEstadoYCupos() {
        when(credentials.readToken()).thenReturn(Optional.of(TOKEN));
        License license = linkedLicense();

        authenticator.seal(license);

        assertThat(license.getFormatVersion()).isEqualTo(6);
        assertThat(license.getHash()).matches("[0-9a-f]{64}");
        assertThat(authenticator.isAuthentic(license)).isTrue();

        license.markSaasBlocked(NOW.plusSeconds(1));
        assertThat(authenticator.isAuthentic(license)).isFalse();

        authenticator.seal(license);
        license.applySaasLicenseSnapshot(
                NOW.plusSeconds(2),
                LicenseSaasStatus.BLOQUEADA_MANUAL,
                license.getValidaHasta().plusSeconds(3600),
                3,
                2,
                2);
        assertThat(authenticator.isAuthentic(license)).isFalse();

        authenticator.seal(license);
        license.deactivate();
        assertThat(authenticator.isAuthentic(license)).isFalse();
    }

    @Test
    void detectaCambioDeIdentidadFiscalYPoliticaVerifactu() {
        when(credentials.readToken()).thenReturn(Optional.of(TOKEN));
        License license = linkedLicense();
        authenticator.seal(license);

        license.updateCommercialProfile(CommercialProfile.MINORISTA);
        assertThat(authenticator.isAuthentic(license)).isFalse();

        authenticator.seal(license);
        license.applyVerifactuPolicy(
                LocalDate.of(2027, 2, 1),
                2,
                NOW.plusSeconds(10));
        assertThat(authenticator.isAuthentic(license)).isFalse();
    }

    @Test
    void faltaOCambioDelTokenProtegidoInvalidaElCache() {
        when(credentials.readToken()).thenReturn(Optional.of(TOKEN));
        License license = linkedLicense();
        authenticator.seal(license);

        when(credentials.readToken()).thenReturn(Optional.empty());
        assertThat(authenticator.isAuthentic(license)).isFalse();

        when(credentials.readToken()).thenReturn(Optional.of("otro-token-protegido"));
        assertThat(authenticator.isAuthentic(license)).isFalse();
    }

    @Test
    void normalizaInstantesSubmicroAntesDeSellarElCache() {
        when(credentials.readToken()).thenReturn(Optional.of(TOKEN));
        Instant submicro = Instant.parse("2026-08-25T12:00:00.123456789Z");
        License license = new License(
                linkedLicenseStore(),
                new Installation("INST-SUBMICRO", "public-key", submicro),
                "LIC-SAAS-SUBMICRO",
                submicro,
                submicro.plusSeconds(3600),
                2,
                1,
                "B12345674",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                CommercialProfile.MAYORISTA,
                "SAAS_LINK:LIC-SAAS-SUBMICRO",
                "legacy-hash",
                4,
                submicro,
                Map.of(
                        "source", "SAAS_LINK",
                        "saasCompanyId", UUID.randomUUID().toString(),
                        "saasStoreId", UUID.randomUUID().toString()),
                ImportResult.ACEPTADA,
                null,
                true);
        license.applyVerifactuPolicy(LocalDate.of(2027, 1, 1), 1, submicro);
        license.applySaasLicenseSnapshot(
                submicro,
                LicenseSaasStatus.VALIDA,
                submicro.plusSeconds(3600),
                2,
                1,
                1);

        Instant expected = submicro.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThat(license.getValidaDesde()).isEqualTo(expected);
        assertThat(license.getValidaHasta()).isEqualTo(expected.plusSeconds(3600));
        assertThat(license.getImportadaEn()).isEqualTo(expected);
        assertThat(license.getUltimaValidacionSaas()).isEqualTo(expected);
        assertThat(license.getVerifactuPolicyUpdatedAt()).isEqualTo(expected);

        authenticator.seal(license);
        assertThat(authenticator.isAuthentic(license)).isTrue();
    }

    @Test
    void admiteFormatoCuatroSoloComoOrigenDeLaPrimeraMigracion() {
        when(credentials.readToken()).thenReturn(Optional.of(TOKEN));
        License license = linkedLicense();

        assertThat(license.getFormatVersion()).isEqualTo(4);
        assertThatCode(() -> authenticator.requireRefreshable(license))
                .doesNotThrowAnyException();

        authenticator.seal(license);

        assertThat(license.getFormatVersion()).isEqualTo(6);
        assertThat(authenticator.isAuthentic(license)).isTrue();
    }

    @Test
    void formatoCincoAutenticoSoloSeAdmiteParaRefrescarYSubeASeis() {
        when(credentials.readToken()).thenReturn(Optional.of(TOKEN));
        License license = linkedLicense();
        String legacyMac = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                authenticator,
                "mac",
                license,
                TOKEN,
                "TPV-ERP-SAAS-LICENSE-CACHE-V5",
                false);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "hash", legacyMac);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "formatVersion", 5);

        assertThat(authenticator.isAuthentic(license)).isFalse();
        assertThatCode(() -> authenticator.requireRefreshable(license))
                .doesNotThrowAnyException();

        authenticator.seal(license);
        assertThat(license.getFormatVersion()).isEqualTo(6);
        assertThat(authenticator.isAuthentic(license)).isTrue();
    }

    @Test
    void formatoCincoReconstruyeMacHistoricoCuandoPostgresqlRedondeaAlMicrosegundoSiguiente() {
        when(credentials.readToken()).thenReturn(Optional.of(TOKEN));
        License license = linkedLicense();
        Instant canonicalInstant = Instant.parse("2026-08-25T12:00:00.123456Z");
        Instant persistedInstant = canonicalInstant.plusNanos(1_000);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "validaDesde", canonicalInstant);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "importadaEn", canonicalInstant);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "ultimaValidacionSaas", canonicalInstant);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "verifactuPolicyUpdatedAt", canonicalInstant);
        String legacyMac = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                authenticator,
                "mac",
                license,
                TOKEN,
                "TPV-ERP-SAAS-LICENSE-CACHE-V5",
                false);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "validaDesde", persistedInstant);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "importadaEn", persistedInstant);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "ultimaValidacionSaas", persistedInstant);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "verifactuPolicyUpdatedAt", persistedInstant);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "hash", legacyMac);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "formatVersion", 5);

        assertThatCode(() -> authenticator.requireRefreshable(license))
                .doesNotThrowAnyException();
    }

    @Test
    void formatoCincoSigueRechazandoLaManipulacionDeUnCampoNoTemporal() {
        when(credentials.readToken()).thenReturn(Optional.of(TOKEN));
        License license = linkedLicense();
        String legacyMac = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                authenticator,
                "mac",
                license,
                TOKEN,
                "TPV-ERP-SAAS-LICENSE-CACHE-V5",
                false);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "hash", legacyMac);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "formatVersion", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "taxId", "B12345675");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authenticator.requireRefreshable(license))
                .isInstanceOf(com.tpverp.backend.licensing.application.LicenseValidationException.class)
                .hasMessageContaining("legacy");
    }

    private static License linkedLicense() {
        var store = linkedLicenseStore();
        var installation = new Installation("INST-1", "public-key", NOW.minusSeconds(3600));
        var license = new License(
                store,
                installation,
                "LIC-SAAS-CACHE-1",
                NOW.minusSeconds(3600),
                Instant.parse("2027-08-25T00:00:00Z"),
                2,
                1,
                "B12345674",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                CommercialProfile.MAYORISTA,
                "SAAS_LINK:LIC-SAAS-CACHE-1",
                "legacy-hash",
                4,
                NOW.minusSeconds(3600),
                Map.of(
                        "source", "SAAS_LINK",
                        "saasCompanyId", UUID.randomUUID().toString(),
                        "saasStoreId", UUID.randomUUID().toString()),
                ImportResult.ACEPTADA,
                null,
                true);
        license.applyVerifactuPolicy(
                LocalDate.of(2027, 1, 1),
                1,
                NOW.minusSeconds(60));
        license.applySaasLicenseSnapshot(
                NOW,
                LicenseSaasStatus.VALIDA,
                license.getValidaHasta(),
                2,
                1,
                1);
        return license;
    }

    private static Store linkedLicenseStore() {
        var company = new Company("B12345674", "Empresa", address());
        return new Store(
                company, "Tienda", address(), "address-hash",
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
