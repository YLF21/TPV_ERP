package com.tpverp.saas.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncEventRequest;
import com.tpverp.saas.sync.SyncOperation;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommercialDocumentSyncProjectorTest {

    private final CommercialDocumentProjectionRepository documents = mock(CommercialDocumentProjectionRepository.class);
    private final CommercialDocumentSyncProjector projector = new CommercialDocumentSyncProjector(documents);

    @Test
    void leavesUnversionedDocumentsAndOtherEntitiesToExistingRouting() {
        assertThat(projector.supports(CommercialDocumentSnapshotTest.request(Map.of("numero", "legacy")))).isFalse();
        SyncEventRequest other = new SyncEventRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "FISCAL_STATUS", UUID.randomUUID(), SyncOperation.ACTUALIZAR, Map.of("schemaVersion", 2));
        assertThat(projector.supports(other)).isFalse();
        assertThat(projector.supports(null)).isFalse();
        verifyNoInteractions(documents);
    }

    @ParameterizedTest
    @MethodSource("schemaRouting")
    void onlyNumericExactVersionOneRemainsLegacy(Object schemaVersion, boolean supported) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        assertThat(projector.supports(CommercialDocumentSnapshotTest.request(payload))).isEqualTo(supported);
    }

    static Stream<Arguments> schemaRouting() {
        return Stream.of(Arguments.of(1, false), Arguments.of(1L, false),
                Arguments.of(new BigDecimal("1.000"), false), Arguments.of(1.0d, true),
                Arguments.of(1.0f, true), Arguments.of(1.0000000000000001d, true),
                Arguments.of(2, true), Arguments.of(3, true), Arguments.of(0, true),
                Arguments.of(-1, true), Arguments.of(null, true), Arguments.of("1", true),
                Arguments.of("2", true), Arguments.of(true, true),
                Arguments.of(new BigDecimal("1.1"), true), Arguments.of(Double.NaN, true));
    }

    @Test
    void routesInvalidOperationsToValidationInsteadOfIgnoringTheirExplicitVersion() {
        SyncEventRequest request = CommercialDocumentSnapshotTest.request(
                CommercialDocumentSnapshotTest.payload(), SyncOperation.BORRAR);
        assertThat(projector.supports(request)).isTrue();
        assertThatThrownBy(() -> projector.project(null, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(documents);
    }

    @Test
    void usesAuthenticatedEventOwnershipAndLeavesPayloadIdentifiersUnresolved() {
        SyncEventRequest request = CommercialDocumentSnapshotTest.request(CommercialDocumentSnapshotTest.payload());
        SaasSyncEvent event = event(request);
        // Request company/store values are not used as projection ownership: the receiver authenticated the event.
        assertThat(event.getCompany().getId()).isNotEqualTo(request.companyId());

        projector.project(event, request);

        verify(documents).project(event, CommercialDocumentSnapshot.parse(request),
                CommercialDocumentQueryMetadata.parse(request));
    }

    @Test
    void refusesInconsistentInstallationOwnershipBeforeWriting() {
        SyncEventRequest request = CommercialDocumentSnapshotTest.request(CommercialDocumentSnapshotTest.payload());
        SaasSyncEvent event = event(request);
        SaasStore wrongStore = mock(SaasStore.class);
        when(wrongStore.getId()).thenReturn(UUID.randomUUID());
        when(event.getInstallation().getStore()).thenReturn(wrongStore);

        assertThatThrownBy(() -> projector.project(event, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo("La procedencia documental no coincide");
                });
        verifyNoInteractions(documents);
    }

    @Test
    void rejectsMalformedMetadataBeforeWritingAnyProjection() {
        Map<String, Object> data = CommercialDocumentSnapshotTest.payload();
        data.put("settledByOrigin", "false");
        SyncEventRequest request = CommercialDocumentSnapshotTest.request(data);

        assertThatThrownBy(() -> projector.project(event(request), request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(documents);
    }

    @Test
    void refusesARequestForADifferentEntityThanThePersistedEvent() {
        SyncEventRequest request = CommercialDocumentSnapshotTest.request(CommercialDocumentSnapshotTest.payload());
        SaasSyncEvent event = event(request);
        when(event.getEntityId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> projector.project(event, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(documents);
    }

    private static SaasSyncEvent event(SyncEventRequest request) {
        SaasSyncEvent event = mock(SaasSyncEvent.class);
        SaasCompany company = mock(SaasCompany.class);
        SaasStore store = mock(SaasStore.class);
        SaasInstallation installation = mock(SaasInstallation.class);
        when(company.getId()).thenReturn(UUID.randomUUID());
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(store.getCompany()).thenReturn(company);
        when(installation.getCompany()).thenReturn(company);
        when(installation.getStore()).thenReturn(store);
        when(event.getCompany()).thenReturn(company);
        when(event.getStore()).thenReturn(store);
        when(event.getInstallation()).thenReturn(installation);
        when(event.getEventId()).thenReturn(request.eventId());
        when(event.getEntityId()).thenReturn(request.entityId());
        when(event.getEntityType()).thenReturn(request.entityType());
        when(event.getOperation()).thenReturn(request.operation());
        return event;
    }
}
