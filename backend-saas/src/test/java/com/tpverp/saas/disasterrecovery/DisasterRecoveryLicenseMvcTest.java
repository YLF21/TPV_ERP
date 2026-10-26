package com.tpverp.saas.disasterrecovery;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.tpverp.saas.JacksonConfiguration;
import com.tpverp.saas.admin.AdminAuthInterceptor;
import com.tpverp.saas.tenant.TenantAuthInterceptor;
import com.tpverp.saas.license.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DisasterRecoveryLicenseController.class)
@Import(JacksonConfiguration.class)
class DisasterRecoveryLicenseMvcTest {
    @Autowired MockMvc mvc;
    @MockitoBean DisasterRecoveryService recovery;
    @MockitoBean VerifactuActivationPolicyResolver policies;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoBean AdminAuthInterceptor adminAuthInterceptor;
    @MockitoBean TenantAuthInterceptor tenantAuthInterceptor;

    @Test void returnsFullAuthoritativeSnapshotThroughActualMvcWithoutReturningAnyCredential()throws Exception {
        UUID id=UUID.randomUUID(),installationId=UUID.randomUUID(),centralId=UUID.randomUUID(),backup=UUID.randomUUID(),companyId=UUID.randomUUID(),storeId=UUID.randomUUID();
        Instant now=Instant.parse("2026-10-26T12:00:00Z");
        var installation=mock(SaasInstallation.class);var license=mock(SaasLicense.class);var company=mock(SaasCompany.class);var store=mock(SaasStore.class);
        when(recovery.requireActivatedLicenseCredential(eq(id),any(),eq("activated-secret"))).thenReturn(installation);
        when(installation.getId()).thenReturn(centralId);when(installation.getInstallationId()).thenReturn(installationId);
        when(installation.getInstallationReference()).thenReturn("INST-RECOVERY");when(installation.getLinkedAt()).thenReturn(now.minusSeconds(3600));
        when(installation.getLicense()).thenReturn(license);when(installation.getStore()).thenReturn(store);
        when(license.getCompany()).thenReturn(company);when(company.getId()).thenReturn(companyId);when(store.getId()).thenReturn(storeId);
        when(company.getTaxId()).thenReturn("B12345674");when(company.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(store.getTaxRegime()).thenReturn(TaxRegime.IGIC);when(store.getCommercialProfile()).thenReturn(CommercialProfile.MAYORISTA);
        when(license.getReference()).thenReturn("LIC-B12345674-001");when(license.getStatus()).thenReturn(LicenseSaasStatus.VALIDA);
        when(license.getValidUntil()).thenReturn(now.plusSeconds(3600));when(license.getMaxWindows()).thenReturn(3);when(license.getMaxPda()).thenReturn(1);when(license.getLicenseVersion()).thenReturn(2L);
        when(clock.instant()).thenReturn(now);
        when(policies.required(TaxpayerType.SOCIEDAD)).thenReturn(new VerifactuPolicySnapshot(LocalDate.of(2027,1,1),4,now));
        when(jdbc.queryForMap(anyString(),eq(id))).thenReturn(Map.of("backup_id",backup,"backup_sha256","a".repeat(64),"destination_key_fingerprint","b".repeat(64)));
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(centralId))).thenReturn("installation-public-key");
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/license").contentType("application/json")
                .header("X-TPV-Installation-Token","activated-secret").content("{\"destinationProof\":\"bound-signature\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recoveryId").value(id.toString()))
                .andExpect(jsonPath("$.installationPublicKey").value("installation-public-key"))
                .andExpect(jsonPath("$.taxpayerType").value("SOCIEDAD")).andExpect(jsonPath("$.taxRegime").value("IGIC"))
                .andExpect(jsonPath("$.commercialProfile").value("MAYORISTA")).andExpect(jsonPath("$.licenseVersion").value(2))
                .andExpect(jsonPath("$.linkedAt").isString()).andExpect(jsonPath("$.validUntil").isString())
                .andExpect(jsonPath("$.verifactuActivationDate").value("2027-01-01"))
                .andExpect(jsonPath("$.installationToken").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
    }
}
