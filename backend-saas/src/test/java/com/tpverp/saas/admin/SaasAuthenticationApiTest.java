package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaasAuthenticationApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

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
}
