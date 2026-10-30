package com.tpverp.backend.security.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.ProductEditAuthorizationService;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.*;
import com.tpverp.backend.licensing.api.LicenseController;
import com.tpverp.backend.licensing.application.*;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.*;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.shared.api.ApiExceptionHandler;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Instant;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class InitialSetupSecurityTest {
    @Test
    void onlyTheExactInitialPostIsPublicAndTemporarySessionsRemainRestricted() throws Exception {
        try (var context = context()) {
            var service = context.getBean(LicenseSaasLinkService.class);
            var license = mock(LicenseSaasLinkResponse.class);
            when(license.status()).thenReturn(LicenseSaasStatus.VALIDA);
            when(service.bootstrapInitialDatabase("PAIRING"))
                    .thenReturn(new LicenseSaasLinkResult(license, null, null, null));
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            mvc.perform(post("/api/v1/licenses/link-saas/bootstrap-initial")
                    .contentType("application/json").content("{\"pairingCode\":\"PAIRING\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("VALIDA"))
                    .andExpect(jsonPath("$.installationToken").doesNotExist());
            for (var path : new String[] {"/api/v1/licenses/link-saas/bootstrap-empty", "/api/v1/licenses/link-saas"}) {
                mvc.perform(post(path).contentType("application/json").content("{\"pairingCode\":\"PAIRING\"}"))
                        .andExpect(status().isForbidden());
            }
            mvc.perform(get("/api/v1/licenses/link-saas/bootstrap-initial")).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/licenses/link-saas/bootstrap-initial")).andExpect(status().isForbidden());

            var user = new UserAccount(null, "ADMIN", "initial-hash", new Role(null, "ADMIN"));
            user.requirePasswordChange();
            var session = new UserSession(user, null, "hash", Instant.now());
            when(context.getBean(AuthenticationService.class).hash("temporary")).thenReturn("hash");
            when(context.getBean(UserSessionRepository.class).findByTokenHashAndRevocadaEnIsNull("hash"))
                    .thenReturn(Optional.of(session));
            mvc.perform(post("/api/v1/licenses/link-saas/bootstrap-initial")
                    .header("Authorization", "Bearer temporary")
                    .contentType("application/json").content("{\"pairingCode\":\"PAIRING\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
            verify(service, times(1)).bootstrapInitialDatabase("PAIRING");
            verify(service, never()).bootstrapEmptyDatabase(any());
        }
    }

    @Test
    void initialGuardFailuresHaveDistinctStableCodes() throws Exception {
        try (var context = context()) {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            for (var reason : InitialSetupException.Reason.values()) {
                doThrow(new InitialSetupException(reason)).when(context.getBean(LicenseSaasLinkService.class))
                        .bootstrapInitialDatabase("PAIRING");
                mvc.perform(post("/api/v1/licenses/link-saas/bootstrap-initial")
                        .contentType("application/json").content("{\"pairingCode\":\"PAIRING\"}"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value(reason.name()));
            }
        }
    }

    private AnnotationConfigWebApplicationContext context() {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, LicenseController.class, ApiExceptionHandler.class})
    static class TestConfiguration {
        @Bean UserSessionRepository sessions() { return mock(UserSessionRepository.class); }
        @Bean AuthenticationService authentication() { return mock(AuthenticationService.class); }
        @Bean ProductEditAuthorizationService products() { return mock(ProductEditAuthorizationService.class); }
        @Bean TerminalRepository terminals() { return mock(TerminalRepository.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean LicenseSaasLinkService linking() { return mock(LicenseSaasLinkService.class); }
        @Bean LicenseService licenses() { return mock(LicenseService.class); }
        @Bean LicenseSaasValidationService validation() { return mock(LicenseSaasValidationService.class); }
        @Bean AuditService audit() { return mock(AuditService.class); }
        @Bean org.springframework.context.MessageSource messageSource() { return new StaticMessageSource(); }
        @Bean InstallationStatusService installation() {
            var service = mock(InstallationStatusService.class);
            when(service.status()).thenReturn(new InstallationStatusService.InstallationStatus(
                    null, null, null, null, OperationalMode.UNLINKED, null, false));
            return service;
        }
    }
}
