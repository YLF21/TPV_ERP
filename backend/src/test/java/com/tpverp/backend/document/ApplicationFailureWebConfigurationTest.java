package com.tpverp.backend.document;
import com.tpverp.backend.supervision.ApplicationFailureRecorder;
import com.tpverp.backend.supervision.ApplicationFailureWebConfiguration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class ApplicationFailureWebConfigurationTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ApplicationFailureRecorder recorder;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        recorder = context.getBean(ApplicationFailureRecorder.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new com.tpverp.backend.shared.api.CorrelationIdFilter()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", null, List.of()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void observesUnhandledFailuresWithoutSwallowingOriginalException() {
        assertThatThrownBy(() -> mvc.perform(get("/api/test/unexpected")))
                .hasRootCauseInstanceOf(NullPointerException.class);
        verify(recorder).record(any(), eq(ApplicationFailureRecorder.Module.SALES), isA(NullPointerException.class), any());
    }

    @Test void observesHandledServerErrorButLeavesClientValidationAndSuccessfulRequestsAlone() throws Exception {
        mvc.perform(get("/api/test/conflict")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/test/expected-state")).andExpect(status().isConflict());
        mvc.perform(get("/api/test/ok")).andExpect(status().isOk());
        verifyNoInteractions(recorder);
        mvc.perform(get("/api/test/server-error")).andExpect(status().isServiceUnavailable());
        verify(recorder).record(any(), eq(ApplicationFailureRecorder.Module.SALES), isA(UnsupportedOperationException.class), any());
    }

    @Test void realConflictHandlerStillReportsOperationalPrintFailureOnceAndPreservesResponseTrace() throws Exception {
        var result = mvc.perform(get("/api/test/print-failure").header("X-Request-ID", "web-client-trace-123"))
                .andExpect(status().isConflict()).andReturn();
        assertThat(result.getResponse().getHeader("X-Request-ID")).isEqualTo("web-client-trace-123");
        assertThat(result.getResponse().getContentAsString()).contains("web-client-trace-123").doesNotContain("private-database-password");
        verify(recorder, times(1)).record(any(), eq(ApplicationFailureRecorder.Module.PRINTING),
                isA(com.tpverp.backend.document.template.PrintRenderingException.class), any());
        verifyNoMoreInteractions(recorder);
    }

    @Test void recognizesPrintFailureThroughLegacyWrapperWithoutReportingOrdinaryConflict() throws Exception {
        mvc.perform(get("/api/test/expected-state")).andExpect(status().isConflict());
        verifyNoInteractions(recorder);
        mvc.perform(get("/api/test/wrapped-print-failure")).andExpect(status().isConflict());
        verify(recorder, times(1)).record(any(), eq(ApplicationFailureRecorder.Module.PRINTING), isA(IllegalStateException.class), any());
        verifyNoMoreInteractions(recorder);
    }

    @Configuration @EnableWebMvc @Import(ApplicationFailureWebConfiguration.class)
    static class Config {
        @Bean ApplicationFailureRecorder recorder() { return mock(ApplicationFailureRecorder.class); }
        @Bean ProbeController probe() { return new ProbeController(); }
        @Bean com.tpverp.backend.shared.api.ApiExceptionHandler realExceptionHandler() {
            var messages = new org.springframework.context.support.ResourceBundleMessageSource();
            messages.setBasename("i18n/messages");
            messages.setDefaultEncoding("UTF-8");
            return new com.tpverp.backend.shared.api.ApiExceptionHandler(messages);
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/test/unexpected") String unexpected() { throw new NullPointerException("private"); }
        @GetMapping("/api/test/conflict") String conflict() { throw new IllegalArgumentException("expected"); }
        @GetMapping("/api/test/server-error") String serverError() { throw new UnsupportedOperationException("unavailable"); }
        @GetMapping("/api/test/ok") String ok() { return "ok"; }
        @GetMapping("/api/test/expected-state") String expectedState() { throw new IllegalStateException("expected_business_conflict"); }
        @GetMapping("/api/test/print-failure") String printFailure() {
            throw new com.tpverp.backend.document.template.PrintRenderingException("invoice_jasper_render_failed",
                    new java.sql.SQLException("private-database-password"));
        }
        @GetMapping("/api/test/wrapped-print-failure") String wrappedPrintFailure() {
            throw new IllegalStateException("legacy_wrapper", new com.tpverp.backend.document.template.PrintRenderingException("invoice_jasper_render_failed"));
        }
        @ExceptionHandler(UnsupportedOperationException.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void serverHandler() { }
    }
}
