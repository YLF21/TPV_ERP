package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerifactuCertificateDeletionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");

    @Mock CurrentOrganization organization;
    @Mock InstallationRepository installations;
    @Mock LicenseRepository licenses;
    @Mock VerifactuConfigurationRepository configurations;
    @Mock VerifactuActivationService activation;
    @Mock FiscalSubmissionStateRepository submissionStates;

    private UUID companyId;
    private UUID storeId;
    private VerifactuConfiguration configuration;
    private License license;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        var company = mock(Company.class);
        var store = mock(Store.class);
        var installation = mock(Installation.class);
        license = mock(License.class);
        configuration = new VerifactuConfiguration(companyId);

        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(store.getEmpresa()).thenReturn(company);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
        when(installation.getId()).thenReturn(UUID.randomUUID());
        when(installations.findAll()).thenReturn(List.of(installation));
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                storeId, installation.getId())).thenReturn(Optional.of(license));
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getVerifactuActivationDate()).thenReturn(LocalDate.of(2027, 1, 1));
        when(configurations.findByCompanyId(companyId)).thenReturn(Optional.of(configuration));
        when(configurations.findForUpdateByCompanyId(companyId))
                .thenReturn(Optional.of(configuration));
    }

    @Test
    void blocksAfterFirstSubmissionEvenIfThereAreNoPendingStates() {
        configuration.activateVoluntarily(NOW.minusSeconds(120));
        configuration.markFirstSubmission(NOW.minusSeconds(60), null);

        assertThat(policy().evaluate()).isEqualTo(
                VerifactuCertificateDeletionDecision.blocked(
                        VerifactuCertificateDeletionPolicy.FIRST_SUBMISSION_RECORDED));
    }

    @Test
    void blocksWheneverActivationServiceReportsCurrentVerifactuActive() {
        when(activation.isActive(
                configuration,
                TaxpayerType.SOCIEDAD,
                LocalDate.of(2027, 1, 1),
                NOW,
                java.time.ZoneId.of("Atlantic/Canary"))).thenReturn(true);

        assertThat(policy().evaluate()).isEqualTo(
                VerifactuCertificateDeletionDecision.blocked(
                        VerifactuCertificateDeletionPolicy.VERIFACTU_ACTIVE));
    }

    @Test
    void blocksOnlyTheThreeApprovedNonFinalSubmissionStatuses() {
        when(submissionStates.countByCompanyIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq(companyId), anyCollection())).thenReturn(1L);

        assertThat(policy().evaluate()).isEqualTo(
                VerifactuCertificateDeletionDecision.blocked(
                        VerifactuCertificateDeletionPolicy.NON_FINAL_SUBMISSIONS_EXIST));

        @SuppressWarnings("unchecked")
        var statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(submissionStates).countByCompanyIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq(companyId), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                FiscalSubmissionStatus.PENDIENTE,
                FiscalSubmissionStatus.ENVIANDO,
                FiscalSubmissionStatus.ENVIADO);
    }

    @Test
    void allowsDeletionBeforeActivationWithoutFirstSubmissionOrNonFinalStates() {
        assertThat(policy().evaluate()).isEqualTo(
                VerifactuCertificateDeletionDecision.allowed());
    }

    @Test
    void updateDecisionLocksTheSameConfigurationUsedByActivationAndFirstSubmission() {
        policy().evaluateForUpdate();

        verify(configurations).insertIfMissing(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(companyId));
        verify(configurations).findForUpdateByCompanyId(companyId);
    }

    private VerifactuCertificateDeletionPolicy policy() {
        return new VerifactuCertificateDeletionPolicy(
                organization, installations, licenses, configurations, activation,
                submissionStates, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
