package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.tpverp.backend.installation.*;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.licensing.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class LicenseRecoveryReconcilerTest {
    private static final Instant NOW=Instant.parse("2026-10-26T12:00:00Z");
    private final LicenseRepository licenses=mock(LicenseRepository.class);
    private final InstallationRepository installations=mock(InstallationRepository.class);
    private final StoreRepository stores=mock(StoreRepository.class);
    private final LicenseSaasCredentialStore credentials=mock(LicenseSaasCredentialStore.class);
    private final LicenseSaasCacheAuthenticator authenticator=new LicenseSaasCacheAuthenticator(credentials);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final LicenseRecoveryReconciler service=new LicenseRecoveryReconciler(licenses,installations,stores,authenticator,Clock.fixed(NOW,ZoneOffset.UTC),jdbc);
    private final Installation installation=new Installation("INST-RECOVERED","public-key",NOW.minusSeconds(3600));
    private final Map<String,String> address=Map.of("linea1","Uno","ciudad","Las Palmas","codigoPostal","35001","provincia","Las Palmas","pais","ES");
    private final Store store=new Store(new Company("B12345674","Empresa",address),"Tienda",address,"hash","Atlantic/Canary","EUR","es-ES");
    private final UUID company=UUID.randomUUID(),centralStore=UUID.randomUUID();
    private License license;
    private RecoveredLicenseSnapshot authority;

    @BeforeEach void setup() {
        license=new License(store,installation,"LIC-B12345674-001",NOW.minusSeconds(3600),NOW.plusSeconds(3600),2,1,
                "B12345674",TaxpayerType.SOCIEDAD,TaxRegime.IGIC,CommercialProfile.MAYORISTA,"SAAS_LINK:LIC-B12345674-001",
                "old-hash",4,NOW.minusSeconds(3600),Map.of("source","SAAS_LINK","saasCompanyId",company.toString(),"saasStoreId",centralStore.toString()),ImportResult.ACEPTADA,null,true);
        license.applyVerifactuPolicy(LocalDate.of(2027,1,1),1,NOW.minusSeconds(60));
        license.applySaasLicenseSnapshot(NOW.minusSeconds(60),LicenseSaasStatus.VALIDA,license.getValidaHasta(),2,1,1);
        when(credentials.readToken()).thenReturn(Optional.of("old-installation-token"));authenticator.seal(license);
        when(credentials.readToken()).thenReturn(Optional.of("activated-installation-token"));
        when(installations.findById(installation.getId())).thenReturn(Optional.of(installation));
        when(licenses.findByInstalacion_IdAndActivaTrue(installation.getId())).thenReturn(List.of(license));
        when(licenses.findByIdForSaasValidationForUpdate(license.getId())).thenReturn(Optional.of(license));
        when(stores.findWithCompanyById(store.getId())).thenReturn(Optional.of(store));
        authority=new RecoveredLicenseSnapshot(UUID.randomUUID(),installation.getId(),UUID.randomUUID(),"a".repeat(64),"b".repeat(64),
                installation.getReferencia(),installation.getPublicKey(),company,centralStore,license.getReferencia(),"B12345674",
                TaxpayerType.SOCIEDAD,TaxRegime.IGIC,CommercialProfile.MAYORISTA,NOW.minusSeconds(3600),NOW.plusSeconds(7200),
                LicenseSaasStatus.VALIDA,3,2,2,LocalDate.of(2027,1,1),1,NOW.minusSeconds(60),null);
        when(jdbc.queryForMap(anyString(),eq(authority.recoveryId()))).thenReturn(Map.of("state","ACTIVE","installation_id",installation.getId(),
                "backup_id",authority.backupId(),"backup_sha256",authority.backupSha256()));
    }

    @Test void replacesOldCredentialCacheOnlyWithCurrentAuthorityAndIsIdempotent() {
        assertThat(authenticator.isAuthentic(license)).isFalse();
        assertThatThrownBy(()->authenticator.requireRefreshable(license)).isInstanceOf(LicenseValidationException.class);
        UUID originalId=license.getId();
        service.reconcile(authority);String hash=license.getHash();
        assertThat(authenticator.isAuthentic(license)).isTrue();assertThat(license.isOperationalAt(NOW)).isTrue();
        assertThat(license.getMaxWindows()).isEqualTo(3);assertThat(license.getSaasLicenseVersion()).isEqualTo(2);
        service.reconcile(authority);assertThat(license.getHash()).isEqualTo(hash);assertThat(license.getId()).isEqualTo(originalId);
        when(credentials.readToken()).thenReturn(Optional.of("old-installation-token"));
        assertThat(authenticator.isAuthentic(license)).isFalse();
        verify(credentials,never()).writeToken(anyString());verify(credentials,never()).clearLinkRecoveryToken();
    }

    @Test void rejectsFiscalIdentityTamperingWithoutSaving() {
        String hash=license.getHash();ReflectionTestUtils.setField(license,"taxpayerType",TaxpayerType.AUTONOMO);
        assertThatThrownBy(()->service.reconcile(authority)).hasMessageContaining("identidad fiscal");
        verify(licenses,never()).save(any());assertThat(license.getHash()).isEqualTo(hash);
    }

    @Test void rejectsForeignCentralStoreAndPublicKeyInsteadOfSealingLocalRows() {
        ReflectionTestUtils.setField(installation,"publicKey","substituted-key");
        assertThatThrownBy(()->service.reconcile(authority)).hasMessageContaining("identidad fiscal");
        ReflectionTestUtils.setField(installation,"publicKey",authority.installationPublicKey());
        ReflectionTestUtils.setField(license,"metadataImportacion",Map.of("source","SAAS_LINK","saasCompanyId",company.toString(),"saasStoreId",UUID.randomUUID().toString()));
        assertThatThrownBy(()->service.reconcile(authority)).hasMessageContaining("identidad fiscal");verify(licenses,never()).save(any());
    }

    @Test void neverPromotesTamperedLimitsVersionOrDatesFromLocalCache() {
        ReflectionTestUtils.setField(license,"maxWindows",99999);ReflectionTestUtils.setField(license,"saasLicenseVersion",99999L);
        ReflectionTestUtils.setField(license,"validaHasta",NOW.plusSeconds(99_999_999));
        ReflectionTestUtils.setField(license,"validaDesde",NOW.minusSeconds(99_999_999));
        service.reconcile(authority);
        assertThat(license.getMaxWindows()).isEqualTo(authority.maxWindows());assertThat(license.getValidaHasta()).isEqualTo(authority.validUntil());
        assertThat(license.getValidaDesde()).isEqualTo(authority.linkedAt());assertThat(license.getSaasLicenseVersion()).isEqualTo(authority.licenseVersion());
    }

    @Test void doesNotPostponeFiscalActivationAlreadyReached() {
        ReflectionTestUtils.setField(license,"verifactuActivationDate",LocalDate.of(2026,1,1));
        assertThatThrownBy(()->service.reconcile(authority)).hasMessageContaining("ya alcanzada");verify(licenses,never()).save(any());
    }

    @Test void refusesMissingOrAmbiguousActiveLicense() {
        when(licenses.findByInstalacion_IdAndActivaTrue(installation.getId())).thenReturn(List.of());
        assertThatThrownBy(()->service.reconcile(authority)).hasMessageContaining("unica");
        when(licenses.findByInstalacion_IdAndActivaTrue(installation.getId())).thenReturn(List.of(license,license));
        assertThatThrownBy(()->service.reconcile(authority)).hasMessageContaining("unica");verify(licenses,never()).save(any());
    }

    @Test void rejectsChangedRecoveryBindingInsideTheLicenseTransaction() {
        when(jdbc.queryForMap(anyString(),eq(authority.recoveryId()))).thenReturn(Map.of("state","ACTIVE","installation_id",installation.getId(),
                "backup_id",UUID.randomUUID(),"backup_sha256",authority.backupSha256()));
        assertThatThrownBy(()->service.reconcile(authority)).hasMessageContaining("esta recuperacion");verify(licenses,never()).save(any());
    }

    @Test void blockedExpiredOrIncompleteAuthorityCannotSealTheCache()throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();String oldHash=license.getHash();
        for(String field:List.of("status","validUntil","taxpayerType","commercialProfile","verifactuActivationDate")) {
            var value=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(authority);
            if(field.equals("status"))value.put(field,"BLOQUEADA_MANUAL");
            else if(field.equals("validUntil"))value.put(field,NOW.minusSeconds(1).toString());
            else value.putNull(field);
            var invalid=mapper.treeToValue(value,RecoveredLicenseSnapshot.class);
            assertThatThrownBy(()->service.reconcile(invalid)).isInstanceOf(LicenseValidationException.class);
            assertThat(license.getHash()).isEqualTo(oldHash);
        }
        verify(licenses,never()).save(any());
    }
}
