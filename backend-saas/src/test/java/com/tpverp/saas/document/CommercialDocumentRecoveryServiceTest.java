package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentRecoveryApi.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.saas.license.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class CommercialDocumentRecoveryServiceTest {
    private final SaasInstallationRepository installations = mock(SaasInstallationRepository.class);
    private final InstallationAuthenticator authenticator = mock(InstallationAuthenticator.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final CommercialDocumentRecoveryService service = new CommercialDocumentRecoveryService(installations, authenticator, jdbc);
    private final SaasInstallation installation = mock(SaasInstallation.class);
    private final SaasCompany company = mock(SaasCompany.class);
    private final SaasStore store = mock(SaasStore.class);
    private final UUID companyId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach void authenticate() {
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(installation.getCompany()).thenReturn(company);
        when(installation.getStore()).thenReturn(store);
        when(installation.getId()).thenReturn(installationId);
        when(installations.findByCompany_IdAndStore_Id(companyId, storeId)).thenReturn(List.of(installation));
        when(authenticator.requireLinkedInstallation(companyId, storeId, List.of(installation), "token")).thenReturn(installation);
    }

    @Test void batchesOnceInInputOrderWithAuthenticatedScopeAndParameterizedIds() throws Exception {
        var row = row(true, true, true);
        when(row.getObject("current_revision", Long.class)).thenReturn(9L);
        when(row.getObject("current_event_id", UUID.class)).thenReturn(eventId);
        when(row.getString("requested_event_status")).thenReturn("PROJECTED");
        when(row.getBoolean("revision_recorded")).thenReturn(true);
        when(row.getObject("customer_linked", Boolean.class)).thenReturn(true);
        when(row.getBigDecimal("total")).thenReturn(new BigDecimal("9007199254740993.01"));
        when(row.getString("currency")).thenReturn("EUR");
        UUID second = UUID.randomUUID();
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<DocumentStatus>>any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    MapSqlParameterSource parameters = invocation.getArgument(1);
                    assertThat(sql).contains("order by q.ordinal", "r.source_payload_hash = e.payload_hash", "ledger_event.installation_id = :installationId")
                            .doesNotContain(documentId.toString(), second.toString());
                    assertThat(parameters.getValue("companyId")).isEqualTo(companyId);
                    assertThat(parameters.getValue("storeId")).isEqualTo(storeId);
                    assertThat(parameters.getValue("installationId")).isEqualTo(installationId);
                    assertThat(parameters.getValue("document0")).isEqualTo(documentId);
                    assertThat(parameters.getValue("event0")).isEqualTo(eventId);
                    assertThat(parameters.getValue("revision0")).isEqualTo(1L);
                    assertThat(parameters.getValue("document1")).isEqualTo(second);
                    assertThat(parameters.getValue("event1")).isNull();
                    assertThat(parameters.getValue("revision1")).isNull();
                    return List.of(invocation.<RowMapper<DocumentStatus>>getArgument(2).mapRow(row, 0));
                });
        var response = service.status(new Request(companyId, storeId,
                List.of(new Document(documentId, eventId, 1L), new Document(second, null, null))), "token");
        assertThat(response.companyId()).isEqualTo(companyId);
        assertThat(response.storeId()).isEqualTo(storeId);
        assertThat(response.installationId()).isEqualTo(installationId);
        assertThat(response.schemaVersion()).isEqualTo(2);
        assertThat(response.documents()).containsExactly(new DocumentStatus(documentId, Status.PROJECTED, 9L,
                eventId, EventStatus.PROJECTED, true, true, "9007199254740993.01", "EUR"));
        verify(jdbc, times(1)).query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<DocumentStatus>>any());
    }

    @ParameterizedTest @EnumSource(value = EventStatus.class, names = {"RECEIVED", "PROJECTED", "IGNORED", "ERROR"})
    void preservesEventStatusWithoutEquatingAnAckOrHeaderToRecordedRevision(EventStatus status) throws Exception {
        var row = row(true, true, true);
        when(row.getString("requested_event_status")).thenReturn(status.name());
        use(row);
        var result = service.status(request(true), "token").documents().getFirst();
        assertThat(result.requestedEventStatus()).isEqualTo(status);
        assertThat(result.requestedRevisionRecorded()).isFalse();
    }

    @Test void redactsEveryOtherInstallationHeaderFieldEvenIfTheResultContainsValues() throws Exception {
        var row = row(true, false, true);
        use(row);
        assertThat(service.status(request(true), "token").documents()).containsExactly(new DocumentStatus(
                documentId, Status.OTHER_INSTALLATION, null, null, EventStatus.MISSING, false, null, null, null));
        verify(row, never()).getObject("current_event_id", UUID.class);
        verify(row, never()).getBigDecimal("total");
        verify(row, never()).getString("requested_event_status");
    }

    @Test void absentProjectionAndPreflightStayUnknownNotLinkedOrRecorded() throws Exception {
        use(row(false, false, false));
        assertThat(service.status(request(false), "token").documents()).containsExactly(new DocumentStatus(
                documentId, Status.MISSING, null, null, EventStatus.NOT_REQUESTED, false, null, null, null));
        use(row(false, false, true));
        assertThat(service.status(request(true), "token").documents().getFirst().requestedEventStatus()).isEqualTo(EventStatus.MISSING);
    }

    @Test void authFailureDoesNotReadDocumentOrEventData() {
        when(authenticator.requireLinkedInstallation(companyId, storeId, List.of(installation), "token"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.status(request(false), "token")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void readOnlyRepeatableReadAndHttpNoStoreAreExplicit() throws Exception {
        var annotation = CommercialDocumentRecoveryService.class.getMethod("status", Request.class, String.class).getAnnotation(Transactional.class);
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        var mocked = mock(CommercialDocumentRecoveryService.class);
        var request = request(false);
        var response = new Response(companyId, storeId, installationId, 2, List.of());
        when(mocked.status(request, "token")).thenReturn(response);
        var http = new CommercialDocumentRecoveryController(mocked).status("token", request);
        assertThat(http.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(http.getBody()).isSameAs(response);
    }

    private Request request(boolean event) { return new Request(companyId, storeId, List.of(new Document(documentId, event ? eventId : null, event ? 1L : null))); }
    private ResultSet row(boolean present, boolean owned, boolean requested) throws Exception {
        var row = mock(ResultSet.class);
        when(row.getObject("document_id", UUID.class)).thenReturn(documentId);
        when(row.getBoolean("document_present")).thenReturn(present);
        when(row.getBoolean("owned")).thenReturn(owned);
        when(row.getBoolean("event_requested")).thenReturn(requested);
        return row;
    }
    private void use(ResultSet row) {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<DocumentStatus>>any()))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<DocumentStatus>>getArgument(2).mapRow(row, 0)));
    }
}
