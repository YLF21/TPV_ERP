package com.tpverp.backend.security.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.tpverp.backend.catalog.ProductEditAuthorizationService;
import com.tpverp.backend.communications.CommunicationPublicLinks;
import com.tpverp.backend.communications.CommunicationUnsubscribeController;
import com.tpverp.backend.communications.CommunicationController;
import com.tpverp.backend.communications.CommunicationService;
import com.tpverp.backend.party.MemberSmtpSettingsController;
import com.tpverp.backend.party.MemberSmtpSettingsService;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.mailoauth.MailOAuthController;
import com.tpverp.backend.mailoauth.MailOAuthService;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;

class MailPublicEndpointSecurityTest {
    @Test void onlyCallbackAndUnsubscribeArePublicEvenWhenLicenceExpired() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfiguration.class); context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            var links = context.getBean(CommunicationPublicLinks.class);
            mvc.perform(get("/api/public/communications/unsubscribe").param("token", "test"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            verify(links, never()).unsubscribe(anyString());
            mvc.perform(post("/api/public/communications/unsubscribe").param("token", "test"))
                    .andExpect(status().isOk());
            verify(links).unsubscribe("test");
            mvc.perform(get("/api/v1/mail-oauth/callback").param("state", "test").param("code", "test"))
                    .andExpect(status().isOk()).andExpect(header().string("Referrer-Policy", "no-referrer"));
            mvc.perform(get("/api/v1/mail-oauth/status")).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/communications/templates")).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/mail-oauth/connect").contentType("application/json")
                    .content("{\"provider\":\"GOOGLE\",\"email\":\"test@example.com\"}"))
                    .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isIn(403,423));
            mvc.perform(get("/api/public/communications/unsubscribe/private")).andExpect(status().isForbidden());
        }
    }

    @Test void authenticatedNonAdminCannotUseMailAdministrationButAdminCan() {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfiguration.class); context.refresh();
            var oauth = context.getBean(MailOAuthController.class);
            var communications = context.getBean(CommunicationController.class);
            var smtp = context.getBean(MemberSmtpSettingsController.class);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "cashier", "", java.util.List.of(new SimpleGrantedAuthority("ROLE_CAJERO"))));
            assertThatThrownBy(oauth::status).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(oauth::disconnect).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(communications::templates).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(communications::history).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(smtp::get).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(context.getBean(MailOAuthService.class), context.getBean(CommunicationService.class),
                    context.getBean(MemberSmtpSettingsService.class));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "admin", "", java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            oauth.status(); communications.templates(); smtp.get();
            verify(context.getBean(MailOAuthService.class)).status();
            verify(context.getBean(CommunicationService.class)).templates();
            verify(context.getBean(MemberSmtpSettingsService.class)).get();
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void callbackErrorsNeverEchoProviderSecretsOrInjectedHtml() throws Exception {
        var service = mock(MailOAuthService.class);
        doThrow(new IllegalStateException("secret-token <script>alert(1)</script>"))
                .when(service).callback(anyString(), anyString(), anyString());
        var mvc = MockMvcBuilders.standaloneSetup(new MailOAuthController(service)).build();
        mvc.perform(get("/api/v1/mail-oauth/callback").param("state", "invalid")
                        .param("code", "secret-code").param("error", "<script>bad</script>"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-token", "secret-code", "<script>")
                        .contains("No se pudo conectar"));
    }

    @Test void malformedMailRequestsAreRejectedBeforeCallingApplicationServices() throws Exception {
        var oauth = mock(MailOAuthService.class);
        var communications = mock(CommunicationService.class);
        var smtp = mock(MemberSmtpSettingsService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MailOAuthController(oauth),
                new CommunicationController(communications), new MemberSmtpSettingsController(smtp)).build();
        mvc.perform(post("/api/v1/mail-oauth/connect").contentType("application/json")
                .content("{\"provider\":\"GOOGLE\",\"email\":\"invalid\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/mail-oauth/connect").contentType("application/json")
                .content("{\"provider\":\"ATTACKER\",\"email\":\"a@example.com\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/communications/templates/test").contentType("application/json")
                .content("{\"template\":null,\"toEmail\":\"test@example.com\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/communications/campaigns").contentType("application/json")
                .content("{\"name\":\"Test\",\"templateId\":\"00000000-0000-0000-0000-000000000001\",\"customerIds\":[null]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/member-smtp-settings").contentType("application/json")
                .content("{\"enabled\":true,\"host\":\"smtp.example.com\",\"port\":65536,\"fromEmail\":\"store@example.com\",\"startTls\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(oauth, communications, smtp);
    }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, MailOAuthController.class, CommunicationUnsubscribeController.class,
            CommunicationController.class, MemberSmtpSettingsController.class})
    static class TestConfiguration {
        @Bean UserSessionRepository sessions() { return mock(UserSessionRepository.class); }
        @Bean AuthenticationService authentication() { return mock(AuthenticationService.class); }
        @Bean ProductEditAuthorizationService products() { return mock(ProductEditAuthorizationService.class); }
        @Bean TerminalRepository terminals() { return mock(TerminalRepository.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean MailOAuthService oauth() { return mock(MailOAuthService.class); }
        @Bean CommunicationPublicLinks links() { return mock(CommunicationPublicLinks.class); }
        @Bean CommunicationService communications() { return mock(CommunicationService.class); }
        @Bean MemberSmtpSettingsService smtp() { return mock(MemberSmtpSettingsService.class); }
        @Bean InstallationStatusService installation() {
            var service=mock(InstallationStatusService.class);
            when(service.status()).thenReturn(new InstallationStatusService.InstallationStatus(
                    null,null,null,null,OperationalMode.RESTRICTED,null,false));
            return service;
        }
    }
}
