package com.tpverp.backend.security.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tpverp.backend.catalog.ProductEditAuthorizationService;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.terminal.*;
import java.time.Clock;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Exercises the real filter order and public-path rules, not only controller methods. */
class WebTerminalSecurityTest {
    @Test void defaultsOffAndNeverTrustsAVisitorToEnableBrowserRegistration() throws Exception {
        try (var context = context(false)) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
            mvc.perform(post("/api/v1/web-terminal/status").contentType("application/json").content("{}")
                            .header("Origin", "https://venta.example.test").header("X-espos-web-terminal", "1"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WEB_TERMINAL_DISABLED"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            verifyNoInteractions(context.getBean(TerminalLinkingService.class));
        }
    }

    @Test void exactSameOriginRecoveryWorksAfterLicenseExpiryButCrossOriginAndGetNeverReachTheService() throws Exception {
        try (var context = context(true)) {
            var linking = context.getBean(TerminalLinkingService.class);
            when(linking.browserStatus(any(), any(), any())).thenReturn(Optional.empty());
            when(linking.bootstrap(anyString())).thenReturn(new TerminalLinkingService.Bootstrap(1,
                    UUID.randomUUID(), "installation", UUID.randomUUID(), "Store", "Company", "public-key", "nonce", "signature", 2,
                    List.of(), "ABC"));
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
            for (var origin : new String[] { "", "https://evil.example.test", "null" }) {
                mvc.perform(post("/api/v1/web-terminal/status").contentType("application/json").content("{}")
                                .header("Origin", origin).header("X-espos-web-terminal", "1"))
                        .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            }
            mvc.perform(get("/api/v1/web-terminal/status").contentType("application/json")
                            .header("Origin", "https://venta.example.test").header("X-espos-web-terminal", "1"))
                    .andExpect(status().isForbidden());
            mvc.perform(options("/api/v1/web-terminal/status").header("Origin", "https://evil.example.test")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mvc.perform(post(URI.create("/api/v1/web-terminal/%73tatus")).contentType("application/json").content("{}")
                            .header("Origin", "https://venta.example.test").header("X-espos-web-terminal", "1"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(linking);
            mvc.perform(post("/api/v1/web-terminal/status").contentType("application/json").content("{}")
                            .header("Origin", "https://venta.example.test").header("X-espos-web-terminal", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNREGISTERED"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(post("/api/v1/web-terminal/admin").contentType("application/json").content("{}")
                            .header("Origin", "https://venta.example.test").header("X-espos-web-terminal", "1"))
                    .andExpect(status().isForbidden());
            verify(linking, never()).request(any());
        }
    }

    private AnnotationConfigWebApplicationContext context(boolean enabled) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("browser-test", Map.of(
                "tpv.web-terminal.enabled", enabled, "tpv.web-terminal.allowed-origin", "https://venta.example.test")));
        context.register(TestConfiguration.class); context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, WebTerminalController.class, WebTerminalCookie.class})
    static class TestConfiguration {
        @Bean UserSessionRepository sessions() { return mock(UserSessionRepository.class); }
        @Bean AuthenticationService authentication() { return mock(AuthenticationService.class); }
        @Bean ProductEditAuthorizationService products() { return mock(ProductEditAuthorizationService.class); }
        @Bean TerminalRepository terminals() { return mock(TerminalRepository.class); }
        @Bean TerminalLinkingService linking() { return mock(TerminalLinkingService.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean InstallationStatusService installation() {
            var service = mock(InstallationStatusService.class);
            when(service.status()).thenReturn(new InstallationStatusService.InstallationStatus(
                    null, null, null, null, OperationalMode.RESTRICTED, null, false));
            return service;
        }
    }
}
