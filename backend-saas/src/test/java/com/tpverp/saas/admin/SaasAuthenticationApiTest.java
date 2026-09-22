package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.tenant.SaasTenantUser;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import java.time.Instant;
import java.util.UUID;
import static com.tpverp.saas.SaasTestData.validCif;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaasAuthenticationApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired LoginAttemptLimiter attempts;
    @Autowired SaasAdminUserRepository admins;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasTenantUserRepository tenants;
    @Autowired AdminPasswordHasher passwords;

    @BeforeEach
    void clearAdminLoginAttempts() {
        attempts.success("login-account", "admin", "");
    }

    @Test
    void internalLoginProducesAnAdminSessionAndKeepsRefreshAndLogout() throws Exception {
        var response = successfulLogin("/api/v1/auth/admin/login", "admin", "admin");
        assertThat(response.mode()).isEqualTo("admin");
        assertThat(response.accessToken()).hasSizeGreaterThanOrEqualTo(40);
        String bearer = "Bearer " + response.accessToken();
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", bearer)).andExpect(status().isOk());
        var refreshed = mvc.perform(post("/api/v1/auth/refresh").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn();
        var renewed = mapper.readValue(refreshed.getResponse().getContentAsString(), SaasLoginResponse.class);
        assertThat(renewed.mode()).isEqualTo("admin");
        assertThat(renewed.accessToken()).isNotEqualTo(response.accessToken());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        String current = "Bearer " + renewed.accessToken();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", current)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", current)).andExpect(status().isUnauthorized());
    }

    @Test
    void validCustomerCredentialsCannotEnterInternalPortalAndRemainUsableThroughCompatibilityApi() throws Exception {
        var company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Mobile customer",
                validCif("B84736210"), TaxpayerType.SOCIEDAD, null, Instant.now()));
        String username = "mobile-customer-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "valid-customer-password";
        tenants.saveAndFlush(new SaasTenantUser(UUID.randomUUID(), company, username, passwords.hash(password), "MANAGER", true, Instant.now()));
        var rejected = mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new SaasLoginRequest(username, password))))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(mapper.readTree(rejected.getResponse().getContentAsString()).has("accessToken")).isFalse();
        var compatible = successfulLogin("/api/v1/auth/login", username, password);
        assertThat(compatible.mode()).isEqualTo("tenant");
        mvc.perform(get("/api/v1/tenant/access").header("Authorization", "Bearer " + compatible.accessToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", "Bearer " + compatible.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalLoginPreservesMandatoryPasswordChangeAndSessionRevocation() throws Exception {
        String username = "internal-change-" + UUID.randomUUID().toString().substring(0, 8);
        String initial = "initial-admin-password", replacement = "replacement-admin-password";
        var account = new SaasAdminUser(UUID.randomUUID(), username, passwords.hash(initial), true, Instant.now());
        account.requirePasswordChange();
        admins.saveAndFlush(account);
        var response = successfulLogin("/api/v1/auth/admin/login", username, initial);
        assertThat(response.mode()).isEqualTo("admin");
        assertThat(response.passwordChangeRequired()).isTrue();
        String bearer = "Bearer " + response.accessToken();
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/password/change").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new PasswordLifecycleController.ChangePasswordRequest(initial, replacement))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        var changed = successfulLogin("/api/v1/auth/admin/login", username, replacement);
        assertThat(changed.mode()).isEqualTo("admin");
        assertThat(changed.passwordChangeRequired()).isFalse();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void switchingLoginRoutesDoesNotBypassTheAccountAttemptLimit() throws Exception {
        for (int attempt = 0; attempt < LoginAttemptLimiter.MAX_FAILURES; attempt++) {
            String path = attempt % 2 == 0 ? "/api/v1/auth/admin/login" : "/api/v1/auth/login";
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(new SaasLoginRequest("admin", "incorrecta"))))
                    .andExpect(status().isUnauthorized());
        }
        for (String path : new String[] {"/api/v1/auth/admin/login", "/api/v1/auth/login"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(new SaasLoginRequest("admin", "admin"))))
                    .andExpect(status().isTooManyRequests());
        }
    }

    private SaasLoginResponse successfulLogin(String path, String username, String password) throws Exception {
        var result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new SaasLoginRequest(username, password))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), SaasLoginResponse.class);
    }

    @Test
    void exchangesPasswordForOpaqueRevocableBearerToken() throws Exception {
        var login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        var response = mapper.readValue(
                login.getResponse().getContentAsString(), SaasLoginResponse.class);
        assertThat(response.username()).isEqualTo("admin");
        assertThat(response.mode()).isEqualTo("admin");
        assertThat(response.accessToken()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(response.expiresAt()).isNotNull();

        String bearer = "Bearer " + response.accessToken();
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", bearer))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void blocksAccountEvenWhenEachFailureUsesADifferentForwardedAddress() throws Exception {
        for (int attempt = 1; attempt <= LoginAttemptLimiter.MAX_FAILURES; attempt++) {
            String address = "198.51.100." + attempt;
            mvc.perform(post("/api/v1/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(address);
                                return request;
                            })
                            .header("X-Forwarded-For", address)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"admin","password":"incorrecta"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.250");
                            return request;
                        })
                        .header("X-Forwarded-For", "198.51.100.250")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin"}
                                """))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void successfulLoginClearsAccountFailuresRegardlessOfTheClientAddress() throws Exception {
        for (int attempt = 1; attempt < LoginAttemptLimiter.MAX_FAILURES; attempt++) {
            String address = "203.0.113." + attempt;
            mvc.perform(post("/api/v1/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(address);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"admin","password":"incorrecta"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.200");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.201");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"incorrecta"}
                                """))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.202");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin"}
                                """))
                .andExpect(status().isOk());
    }
}
