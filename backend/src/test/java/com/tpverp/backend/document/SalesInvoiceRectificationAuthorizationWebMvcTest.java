package com.tpverp.backend.document;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(SalesInvoiceRectificationController.class)
@Import(SalesInvoiceRectificationAuthorizationWebMvcTest.MethodSecurityConfiguration.class)
class SalesInvoiceRectificationAuthorizationWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private SalesInvoiceRectificationService rectifications;
    @MockitoBean private DocumentService documents;
    @MockitoBean private DocumentViewAssembler views;
    @MockitoBean private DocumentFiscalQrService fiscalQr;

    @Test
    void managementAccessAndInvoicePermissionAreBothRequired() throws Exception {
        var path = "/api/v1/invoices/" + UUID.randomUUID() + "/rectification-source";

        mvc.perform(get(path).with(permissions("sales", "GESTION_VENTAS")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(permissions("app", "APP_GESTION_ACCESS")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(permissions(
                        "manager", "APP_GESTION_ACCESS", "INVOICES_WRITE")))
                .andExpect(status().isOk());
        mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor permissions(String username, String... permissionCodes) {
        var authorities = Arrays.stream(permissionCodes)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return user(username).authorities(authorities);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
