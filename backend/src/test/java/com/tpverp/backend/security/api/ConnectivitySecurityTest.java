package com.tpverp.backend.security.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.catalog.ProductEditAuthorizationService;
import com.tpverp.backend.connectivity.ConnectivityController;
import com.tpverp.backend.connectivity.SaasConnectivityService;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.shared.access.OperationalMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class ConnectivitySecurityTest {
    @Test
    void loginCanReadOnlyPublicConnectivityEvenWhenInstallationIsRestricted() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            mvc.perform(get("/api/v1/connectivity"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.saasConnected").value(true))
                    .andExpect(jsonPath("$.checkedAt").value("2026-09-19T12:00:00Z"));
            mvc.perform(get("/api/v1/connectivity/private"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/users"))
                    .andExpect(status().isForbidden());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, ConnectivityController.class})
    static class TestConfiguration {
        @Bean UserSessionRepository sessions() { return mock(UserSessionRepository.class); }
        @Bean AuthenticationService authentication() { return mock(AuthenticationService.class); }
        @Bean ProductEditAuthorizationService products() { return mock(ProductEditAuthorizationService.class); }
        @Bean InstallationStatusService installation() {
            var service = mock(InstallationStatusService.class);
            when(service.status()).thenReturn(new InstallationStatusService.InstallationStatus(
                    null, null, null, null, OperationalMode.RESTRICTED, null, false));
            return service;
        }
        @Bean SaasConnectivityService connectivity() {
            var service = mock(SaasConnectivityService.class);
            when(service.status()).thenReturn(new SaasConnectivityService.ConnectivityStatus(
                    true, Instant.parse("2026-09-19T12:00:00Z")));
            return service;
        }
    }
}
