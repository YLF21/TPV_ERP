package com.tpverp.backend.supervision;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PrintFailureReporterTest {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }

    @Test void passesOnlyCurrentTrustedContextAndOriginalFailureAndDoesNotBreakFallbackIfReporterFails() {
        var recorder = mock(ApplicationFailureRecorder.class);
        var reporter = new PrintFailureReporter(recorder);
        var authentication = new UsernamePasswordAuthenticationToken("synthetic", null, List.of());
        var request = new MockHttpServletRequest();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var failure = new IllegalStateException("render failed");
        reporter.record(failure);
        verify(recorder).record(authentication, ApplicationFailureRecorder.Module.PRINTING, failure, request);
        doThrow(new IllegalStateException("database unavailable")).when(recorder)
                .record(authentication, ApplicationFailureRecorder.Module.PRINTING, failure, request);
        assertThatCode(() -> reporter.record(failure)).doesNotThrowAnyException();
    }
}
