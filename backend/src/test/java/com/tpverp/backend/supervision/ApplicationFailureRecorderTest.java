package com.tpverp.backend.supervision;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.shared.api.CorrelationIdFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;

class ApplicationFailureRecorderTest {
    @Test
    void capturesOnlyBoundedCodeIdentifiersAndKeepsTraceWithoutMessagesOrFilenames() {
        var recorder = recorder("4.2.0");
        var request = new MockHttpServletRequest();
        UUID trace = UUID.randomUUID();
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE, trace.toString());
        var cause = new NullPointerException("password=secret customer=private SQL SELECT *");
        cause.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.tpverp.backend.document.SaleService", "save", "private-name.java", 42)});
        var evidence = recorder.evidence(ApplicationFailureRecorder.Module.SALES, new RuntimeException("secret wrapper", cause), request);
        assertThat(evidence.traceId()).isEqualTo(trace.toString());
        assertThat(evidence.exceptionType()).isEqualTo("java.lang.NullPointerException");
        assertThat(evidence.errorLocation()).isEqualTo("com.tpverp.backend.document.SaleService.save:42");
        assertThat(evidence.toString()).doesNotContain("secret", "private", "SELECT", ".java");
    }

    @Test
    void rejectsUntrustedVersionAndInvalidLocationsButPreservesLegacyTrace() {
        var request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE, "legacy-trace-without-uuid");
        var failure = new RuntimeException("secret");
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.tpverp.backend.Bad", "<init>", "x", 1)});
        var evidence = recorder("v1 secret").evidence(ApplicationFailureRecorder.Module.APPLICATION, failure, request);
        assertThat(evidence.appVersion()).isNull();
        assertThat(evidence.errorLocation()).isNull();
        assertThat(evidence.traceId()).isEqualTo("legacy-trace-without-uuid");
    }

    @Test
    void skipsUnauthenticatedAndUnscopedRequestsAndDoesNotMaskRecorderFailures() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LicenseRepository licenses = mock(LicenseRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        var recorder = new ApplicationFailureRecorder(jdbc, licenses, transactions, new MockEnvironment(), mock(com.tpverp.backend.sync.SyncOutboxService.class));
        var anonymous = new UsernamePasswordAuthenticationToken("anonymous", "ignored");
        var global = new UsernamePasswordAuthenticationToken("global", "ignored", List.of());
        recorder.record(null, ApplicationFailureRecorder.Module.APPLICATION, new RuntimeException(), null);
        recorder.record(anonymous, ApplicationFailureRecorder.Module.APPLICATION, new RuntimeException(), null);
        recorder.record(global, ApplicationFailureRecorder.Module.APPLICATION, new RuntimeException(), null);
        verifyNoInteractions(jdbc, licenses, transactions);
        global.setDetails(new OperationalSessionContext(UUID.randomUUID(), UUID.randomUUID()));
        when(transactions.getTransaction(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatCode(() -> recorder.record(global, ApplicationFailureRecorder.Module.SALES, new RuntimeException(), null))
                .doesNotThrowAnyException();
        verifyNoInteractions(jdbc, licenses);
    }

    private ApplicationFailureRecorder recorder(String version) {
        return new ApplicationFailureRecorder(mock(JdbcTemplate.class), mock(LicenseRepository.class),
                mock(PlatformTransactionManager.class), new MockEnvironment().withProperty("tpv.verifactu.system-version", version), mock(com.tpverp.backend.sync.SyncOutboxService.class));
    }
}
