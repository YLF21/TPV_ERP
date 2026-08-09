package com.tpverp.backend.organization;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CompanyPrintIdentityController.class)
@Import(CompanyPrintIdentityControllerTest.MethodSecurityConfiguration.class)
class CompanyPrintIdentityControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private CurrentOrganization organization;

    @Test
    void returnsCurrentTenantCompanyIdentityToSalesUsers() throws Exception {
        when(organization.currentCompany()).thenReturn(new Company(
                "B12345678",
                "TPV ERP SL",
                Map.of(
                        "linea1", "Calle Mayor 1",
                        "codigoPostal", "35001",
                        "ciudad", "Las Palmas",
                        "provincia", "Las Palmas",
                        "pais", "ES")));

        mvc.perform(get("/api/v1/organization/current/print-identity")
                        .with(user("sales").authorities(() -> "VENTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TPV ERP SL"))
                .andExpect(jsonPath("$.taxId").value("B12345678"))
                .andExpect(jsonPath("$.address.line1").value("Calle Mayor 1"))
                .andExpect(jsonPath("$.address.postalCode").value("35001"))
                .andExpect(jsonPath("$.address.city").value("Las Palmas"));
    }

    @Test
    void rejectsUsersWithoutLabelCatalogPermissions() throws Exception {
        mvc.perform(get("/api/v1/organization/current/print-identity")
                        .with(user("cashier").authorities(() -> "TICKETS_CREATE")))
                .andExpect(status().isForbidden());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
