package com.tpverp.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BusinessObservabilityInterceptorTest {

    @Test
    void recordsSuccessfulCriticalOperationWithBoundedTagsAndSafeAuditDetails() throws Exception {
        var registry = new SimpleMeterRegistry();
        var audit = mock(AuditService.class);
        var interceptor = new BusinessObservabilityInterceptor(registry, audit);
        var request = new MockHttpServletRequest(
                "POST", "/api/v1/tickets/11111111-1111-1111-1111-111111111111/returns");
        request.addHeader("X-Request-ID", "checkout-2026.07.21:01");
        var response = new MockHttpServletResponse();
        response.setStatus(201);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(registry.get("tpv.business.operations")
                .tags("domain", "return", "operation", "ticket.return",
                        "method", "POST", "outcome", "success")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("tpv.business.operation.duration")
                .tags("domain", "return", "operation", "ticket.return",
                        "method", "POST", "outcome", "success")
                .timer().count()).isEqualTo(1L);

        var details = detailsCaptor();
        verify(audit).record(eq("TICKET_RETURN_CREATED"), eq(AuditResult.EXITO), details.capture());
        assertThat(details.getValue())
                .containsEntry("operation", "ticket.return")
                .containsEntry("method", "POST")
                .containsEntry("status", 201)
                .containsEntry("requestId", "checkout-2026.07.21:01")
                .doesNotContainKey("path");
    }

    @Test
    void recordsFailedMutationAndIncrementsFailureCounter() throws Exception {
        var registry = new SimpleMeterRegistry();
        var audit = mock(AuditService.class);
        var interceptor = new BusinessObservabilityInterceptor(registry, audit);
        var request = new MockHttpServletRequest(
                "POST", "/api/v1/customer-receivables/id/payments");
        request.addHeader("X-Request-ID", "invalid request id with spaces");
        var response = new MockHttpServletResponse();
        response.setStatus(409);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(registry.get("tpv.business.operation.failures")
                .tags("domain", "credit", "operation", "receivable.payment", "outcome", "client_error")
                .counter().count()).isEqualTo(1.0d);
        var details = detailsCaptor();
        verify(audit).record(
                eq("CUSTOMER_RECEIVABLE_PAYMENT_RECORDED"), eq(AuditResult.FALLO), details.capture());
        assertThat(details.getValue()).containsEntry("status", 409).doesNotContainKey("requestId");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<Map<String, Object>> detailsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
