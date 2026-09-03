package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @BeforeEach
    void clearAdminLoginAttempts() {
        attempts.success("admin-account", "admin", "");
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
