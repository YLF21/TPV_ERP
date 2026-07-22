package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class VerifactuResolutionPolicyServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    @Mock
    private CurrentOrganization organization;
    @Mock
    private FiscalRecordRepository records;
    @Mock
    private FiscalSubmissionStateRepository states;
    @Mock
    private Store store;
    @Mock
    private Company company;
    @Mock
    private FiscalRecord record;

    private UUID companyId;
    private UUID storeId;
    private UUID recordId;
    private VerifactuResolutionPolicyService service;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        recordId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(store.getEmpresa()).thenReturn(company);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        service = new VerifactuResolutionPolicyService(
                organization, records, states, new VerifactuDefectClassifier());
    }

    @Test
    void rejectedAltaRequiresCorrectionAndOnlyGrantsItToFiscalCorrectors() {
        arrange(FiscalRecordOperation.ALTA, incident(FiscalSubmissionStatus.RECHAZADO));

        var reader = service.resolution(recordId, authentication("VERIFACTU_READ"));
        var corrector = service.resolution(
                recordId, authentication("VERIFACTU_READ", "VERIFACTU_CORRECT"));

        assertThat(reader.category()).isEqualTo(VerifactuResolutionCategory.AEAT_REJECTED);
        assertThat(reader.recommendedAction())
                .isEqualTo(VerifactuResolutionAction.CREATE_CORRECTION);
        assertThat(reader.permittedActions()).isEmpty();
        assertThat(corrector.permittedActions())
                .containsExactly(VerifactuResolutionAction.CREATE_CORRECTION);
        assertThat(corrector.errorCode()).isEqualTo("AEAT-1");
    }

    @Test
    void acceptedAltaRequiresRectifyingInvoiceNeverVerifactuCorrection() {
        arrange(FiscalRecordOperation.ALTA, state(FiscalSubmissionStatus.ACEPTADO));

        var fiscalCorrector = service.resolution(
                recordId, authentication("VERIFACTU_CORRECT"));
        var salesManager = service.resolution(
                recordId, authentication("GESTION_VENTAS"));

        assertThat(fiscalCorrector.category())
                .isEqualTo(VerifactuResolutionCategory.ACCEPTED_FINAL);
        assertThat(fiscalCorrector.recommendedAction())
                .isEqualTo(VerifactuResolutionAction.CREATE_RECTIFYING_INVOICE);
        assertThat(fiscalCorrector.permittedActions()).isEmpty();
        assertThat(salesManager.permittedActions())
                .containsExactly(VerifactuResolutionAction.CREATE_RECTIFYING_INVOICE);
    }

    @Test
    void acceptedWithErrorsAltaAllowsAdministrativeCorrection() {
        arrange(
                FiscalRecordOperation.ALTA,
                incident(FiscalSubmissionStatus.ACEPTADO_CON_ERRORES));

        var result = service.resolution(
                recordId, authentication("VERIFACTU_CORRECT"));

        assertThat(result.category())
                .isEqualTo(VerifactuResolutionCategory.AEAT_ACCEPTED_WITH_ERRORS);
        assertThat(result.recommendedAction())
                .isEqualTo(VerifactuResolutionAction.CREATE_CORRECTION);
    }

    @Test
    void sentAndRetryableTechnicalDefectsOnlyAllowRetryToFiscalManagers() {
        arrange(FiscalRecordOperation.ALTA, state(FiscalSubmissionStatus.ENVIADO));
        var sent = service.resolution(recordId, authentication("VERIFACTU_MANAGE"));
        arrange(FiscalRecordOperation.ALTA, incident(
                FiscalSubmissionStatus.DEFECTUOSO, "INVALID_AEAT_RESPONSE"));
        var defective = service.resolution(recordId, authentication("VERIFACTU_MANAGE"));

        assertThat(sent.category())
                .isEqualTo(VerifactuResolutionCategory.COMMUNICATION_PENDING);
        assertThat(sent.permittedActions()).containsExactly(VerifactuResolutionAction.RETRY);
        assertThat(defective.category())
                .isEqualTo(VerifactuResolutionCategory.LOCAL_TECHNICAL_ERROR);
        assertThat(defective.permittedActions())
                .containsExactly(VerifactuResolutionAction.RETRY);
    }

    @Test
    void invalidXsdAndUnknownDefectsRequireTechnicalReview() {
        arrange(FiscalRecordOperation.ALTA, incident(
                FiscalSubmissionStatus.DEFECTUOSO, "INVALID_XSD"));
        var invalidXsd = service.resolution(
                recordId, authentication("VERIFACTU_MANAGE", "VERIFACTU_CORRECT"));
        arrange(FiscalRecordOperation.ALTA, incident(
                FiscalSubmissionStatus.DEFECTUOSO, "UNCLASSIFIED"));
        var unknown = service.resolution(
                recordId, authentication("VERIFACTU_MANAGE", "VERIFACTU_CORRECT"));

        assertThat(invalidXsd.category())
                .isEqualTo(VerifactuResolutionCategory.TECHNICAL_REVIEW);
        assertThat(invalidXsd.recommendedAction())
                .isEqualTo(VerifactuResolutionAction.TECHNICAL_REVIEW);
        assertThat(invalidXsd.permittedActions()).isEmpty();
        assertThat(unknown.category())
                .isEqualTo(VerifactuResolutionCategory.TECHNICAL_REVIEW);
        assertThat(unknown.permittedActions()).isEmpty();
    }

    @Test
    void rejectedCancellationRequiresTechnicalReviewNotAltaCorrection() {
        arrange(FiscalRecordOperation.ANULACION, incident(FiscalSubmissionStatus.RECHAZADO));

        var result = service.resolution(recordId, authentication("VERIFACTU_CORRECT"));

        assertThat(result.category())
                .isEqualTo(VerifactuResolutionCategory.TECHNICAL_REVIEW);
        assertThat(result.recommendedAction())
                .isEqualTo(VerifactuResolutionAction.TECHNICAL_REVIEW);
        assertThat(result.permittedActions()).isEmpty();
    }

    @Test
    void lookupIsAlwaysScopedToCurrentCompanyAndStore() {
        when(records.findByIdAndCompanyIdAndStoreId(recordId, companyId, storeId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolution(recordId, authentication("ROLE_ADMIN")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Registro fiscal no encontrado");
        verify(records).findByIdAndCompanyIdAndStoreId(recordId, companyId, storeId);
    }

    private void arrange(FiscalRecordOperation operation, FiscalSubmissionState state) {
        when(record.getOperation()).thenReturn(operation);
        when(records.findByIdAndCompanyIdAndStoreId(recordId, companyId, storeId))
                .thenReturn(Optional.of(record));
        when(states.findById(recordId)).thenReturn(Optional.of(state));
    }

    private FiscalSubmissionState state(FiscalSubmissionStatus status) {
        return new FiscalSubmissionState(recordId, status, NOW);
    }

    private FiscalSubmissionState incident(FiscalSubmissionStatus status) {
        return incident(status, "AEAT-1");
    }

    private FiscalSubmissionState incident(
            FiscalSubmissionStatus status, String errorCode) {
        var state = state(status);
        state.markIncident(status, errorCode, "Detalle tecnico protegido", NOW);
        return state;
    }

    private static Authentication authentication(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                "ignored",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }
}
