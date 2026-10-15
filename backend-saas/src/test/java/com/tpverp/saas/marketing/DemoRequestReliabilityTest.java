package com.tpverp.saas.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tpverp.saas.admin.AdminAuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DemoRequestReliabilityTest {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final String PAYLOAD = """
            {"products":["APP_VENTA"],"name":"Test","company":"Test company",
             "email":"test@example.com","locale":"es","privacyAccepted":true}
            """;

    @Test
    void availabilityReturnsOnlyItsBooleanAndUnavailableDatabaseHasNoInternalDetails() throws Exception {
        var service = mock(DemoRequestService.class);
        var mvc = mvc(service);
        when(service.available()).thenReturn(true);
        mvc.perform(get("/api/v1/public/demo-requests/availability"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"available\":true}"));
        when(service.available()).thenThrow(new DataAccessResourceFailureException("private-db-host credentials"));
        mvc.perform(get("/api/v1/public/demo-requests/availability"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-db-host"))));
    }

    @Test
    void passesCanonicalRetryKeyAndActualRemoteAddressButNotSpoofableForwardedHeader() throws Exception {
        var service = mock(DemoRequestService.class);
        UUID key = UUID.randomUUID();
        when(service.submit(any(), eq(key), eq("127.0.0.1")))
                .thenReturn(new DemoRequestReceipt(UUID.randomUUID(), NOW));
        mvc(service).perform(post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key.toString()).header("X-Forwarded-For", "1.2.3.4")
                        .content(PAYLOAD))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNotEmpty());
        verify(service).submit(any(), eq(key), eq("127.0.0.1"));
    }

    @Test
    void rejectsMalformedRetryKeyAndReturnsSafePerFieldValidationCodes() throws Exception {
        var service = mock(DemoRequestService.class);
        var mvc = mvc(service);
        mvc.perform(post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "private-invalid-value").content(PAYLOAD))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.idempotencyKey").value("INVALID"));
        mvc.perform(post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD.replace("test@example.com", "private-bad-email").replace("true", "false")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").value("INVALID"))
                .andExpect(jsonPath("$.fields.privacyAccepted").value("MUST_ACCEPT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-bad-email"))));
        verifyNoInteractions(service);
    }

    @Test
    void retriesReturnOnePersistedReceiptEvenPastTheSubmissionLimitAndChangedPayloadConflicts() {
        var repository = mock(DemoRequestRepository.class);
        var stored = new AtomicReference<DemoRequestRepository.StoredSubmission>();
        UUID key = UUID.randomUUID();
        when(repository.findSubmission(key)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(), eq(key), any())).thenAnswer(invocation -> {
            DemoRequestView value = invocation.getArgument(0);
            stored.set(new DemoRequestRepository.StoredSubmission(invocation.getArgument(2),
                    new DemoRequestReceipt(value.id(), value.createdAt())));
            return true;
        });
        var service = new DemoRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC), mock(AdminAuditService.class), 120);
        DemoRequestReceipt receipt = service.submit(request(null), key, "127.0.0.1");
        for (int retry = 0; retry < 10; retry++) {
            assertThat(service.submit(request(null), key, "127.0.0.1")).isEqualTo(receipt);
        }
        verify(repository, times(1)).save(any(), eq(key), any());
        assertThatThrownBy(() -> service.submit(request("Different message"), key, "127.0.0.1"))
                .isInstanceOfSatisfying(DemoRequestFailure.class,
                        failure -> assertThat(failure.code).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void concurrentInsertConflictReplaysWinnerWithoutReplacingItsLead() {
        var repository = mock(DemoRequestRepository.class);
        UUID key = UUID.randomUUID();
        var winner = new AtomicReference<DemoRequestRepository.StoredSubmission>();
        when(repository.findSubmission(key)).thenAnswer(invocation -> Optional.ofNullable(winner.get()));
        when(repository.save(any(), eq(key), any())).thenAnswer(invocation -> {
            winner.set(new DemoRequestRepository.StoredSubmission(invocation.getArgument(2),
                    new DemoRequestReceipt(UUID.randomUUID(), NOW)));
            return false;
        });
        var service = new DemoRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC), mock(AdminAuditService.class), 120);
        assertThat(service.submit(request(null), key, "127.0.0.1")).isEqualTo(winner.get().receipt());
        verify(repository, never()).save(any(DemoRequestView.class));
    }

    @Test
    void validatesPageBoundsAndAuditsStateWithoutContactContent() {
        var repository = mock(DemoRequestRepository.class);
        var audit = mock(AdminAuditService.class);
        var service = new DemoRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC), audit, 120);
        assertThatThrownBy(() -> service.page(-1, 20, null, null)).isInstanceOf(DemoRequestFailure.class);
        assertThatThrownBy(() -> service.page(0, 101, null, null)).isInstanceOf(DemoRequestFailure.class);
        assertThatThrownBy(() -> service.page(0, 20, null, "x".repeat(161))).isInstanceOf(DemoRequestFailure.class);
        verifyNoInteractions(repository);
        UUID id = UUID.randomUUID();
        var lead = new DemoRequestView(id, DemoProduct.APP_VENTA, List.of(DemoProduct.APP_VENTA),
                "Private name", "Private company", "private@example.com", null, null, "es",
                null, null, null, null, null, "CONTACTED", NOW, NOW);
        when(repository.updateStatus(id, DemoRequestStatus.CONTACTED, DemoRequestStatus.NEW)).thenReturn(Optional.of(lead));
        assertThat(service.updateStatus(id, DemoRequestStatus.CONTACTED, DemoRequestStatus.NEW)).isEqualTo(lead);
        verify(audit).log("UPDATE_DEMO_REQUEST_STATUS", "MARKETING_DEMO_REQUEST", id.toString(), "CONTACTED");
        when(repository.updateStatus(id, DemoRequestStatus.CLOSED, DemoRequestStatus.NEW)).thenReturn(Optional.empty());
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateStatus(id, DemoRequestStatus.CLOSED, DemoRequestStatus.NEW)).isInstanceOfSatisfying(
                DemoRequestFailure.class, failure -> assertThat(failure.code).isEqualTo("NOT_FOUND"));
    }

    @Test
    void adminContractBindsFiltersAndRejectsUnknownStatusesWithoutEchoingThem() throws Exception {
        var service = mock(DemoRequestService.class);
        when(service.page(2, 20, DemoRequestStatus.NEW, "Test"))
                .thenReturn(new DemoRequestPage(List.of(), 41, 2, 20));
        var mvc = mvc(service);
        mvc.perform(get("/api/v1/admin/demo-requests/page").param("page", "2").param("size", "20")
                        .param("status", "NEW").param("query", "Test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(41)).andExpect(jsonPath("$.page").value(2));
        mvc.perform(get("/api/v1/admin/demo-requests/page").param("status", "PRIVATE_UNKNOWN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.status").value("INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_UNKNOWN"))));
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/v1/admin/demo-requests/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PRIVATE_UNKNOWN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put("/api/v1/admin/demo-requests/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.status").value("REQUIRED"));
        verify(service, never()).updateStatus(any(), any(), any());
    }

    private static MockMvc mvc(DemoRequestService service) {
        return MockMvcBuilders.standaloneSetup(new DemoRequestController(service), new DemoRequestAdminController(service))
                .setControllerAdvice(new DemoRequestExceptionHandler()).build();
    }

    private static CreateDemoRequest request(String message) {
        return new CreateDemoRequest(DemoProduct.APP_VENTA, null, "Test", "Test company", "test@example.com",
                null, message, "es", true, "", null, null, null, null, null);
    }
}
