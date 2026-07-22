package com.tpverp.backend.observability;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

final class BusinessObservabilityInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessObservabilityInterceptor.class);
    private static final String START_ATTRIBUTE = BusinessObservabilityInterceptor.class.getName() + ".start";
    private static final String OPERATION_ATTRIBUTE = BusinessObservabilityInterceptor.class.getName() + ".operation";

    private final MeterRegistry meters;
    private final AuditService audit;

    BusinessObservabilityInterceptor(MeterRegistry meters, AuditService audit) {
        this.meters = meters;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        BusinessOperationClassifier.classify(request.getMethod(), request.getRequestURI())
                .ifPresent(operation -> {
                    request.setAttribute(START_ATTRIBUTE, System.nanoTime());
                    request.setAttribute(OPERATION_ATTRIBUTE, operation);
                });
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        if (!(request.getAttribute(OPERATION_ATTRIBUTE) instanceof BusinessOperation operation)) {
            return;
        }
        var start = request.getAttribute(START_ATTRIBUTE) instanceof Long value
                ? value
                : System.nanoTime();
        var duration = Math.max(0L, System.nanoTime() - start);
        var outcome = outcome(response.getStatus(), exception);
        var method = request.getMethod().toUpperCase(Locale.ROOT);
        var tags = new String[] {
                "domain", operation.domain(),
                "operation", operation.name(),
                "method", method,
                "outcome", outcome
        };

        Counter.builder("tpv.business.operations")
                .description("Business API operations handled by the TPV backend")
                .tags(tags)
                .register(meters)
                .increment();
        Timer.builder("tpv.business.operation.duration")
                .description("Business API operation duration")
                .tags(tags)
                .register(meters)
                .record(duration, TimeUnit.NANOSECONDS);
        if (exception != null || response.getStatus() >= 400) {
            Counter.builder("tpv.business.operation.failures")
                    .description("Failed business API operations")
                    .tags("domain", operation.domain(), "operation", operation.name(), "outcome", outcome)
                    .register(meters)
                    .increment();
        }
        if (operation.isAudited()) {
            recordAudit(request, response, exception, operation, duration);
        }
    }

    private void recordAudit(
            HttpServletRequest request,
            HttpServletResponse response,
            Exception exception,
            BusinessOperation operation,
            long durationNanos) {
        var details = new LinkedHashMap<String, Object>();
        details.put("operation", operation.name());
        details.put("method", request.getMethod().toUpperCase(Locale.ROOT));
        details.put("status", response.getStatus());
        details.put("durationMs", TimeUnit.NANOSECONDS.toMillis(durationNanos));
        var requestId = safeRequestId(request.getHeader("X-Request-ID"));
        if (requestId != null) {
            details.put("requestId", requestId);
        }
        try {
            audit.record(
                    operation.auditEvent(),
                    exception == null && response.getStatus() < 400 ? AuditResult.EXITO : AuditResult.FALLO,
                    details);
        } catch (RuntimeException auditFailure) {
            // Observability must never alter the already completed business response.
            LOGGER.warn("Could not persist business audit event {}", operation.auditEvent(), auditFailure);
        }
    }

    private static String safeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        var normalized = requestId.trim();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._:-]+")) {
            return null;
        }
        return normalized;
    }

    private static String outcome(int status, Exception exception) {
        if (exception != null || status >= 500) {
            return "server_error";
        }
        if (status >= 400) {
            return "client_error";
        }
        return "success";
    }
}
