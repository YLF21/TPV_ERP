package com.tpverp.backend.terminal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerDisplayIdentityController.class)
@Import(CustomerDisplayIdentityControllerTest.MethodSecurityConfiguration.class)
class CustomerDisplayIdentityControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private StoreDocumentPrintConfigurationService printConfiguration;

    @Test
    void readsBrandingAndTimeZoneOfTheCurrentStoreForSales() throws Exception {
        var store = givenStore("Tienda Peña");
        var logo = new StoreDocumentPrintConfigurationService.LogoReference(
                UUID.randomUUID(), "image/png", "a".repeat(64));
        when(printConfiguration.presentation(DocumentTemplateType.TICKET))
                .thenReturn(new StoreDocumentPrintConfigurationService.Presentation(null, logo, false));
        when(printConfiguration.logoDataUri(store.getId(), logo)).thenReturn("data:image/png;base64,logo");
        mvc.perform(get("/api/v1/hardware/customer-display-identity")
                        .param("storeId", UUID.randomUUID().toString())
                        .with(user("sales").authorities(() -> "VENTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Tienda Peña"))
                .andExpect(jsonPath("$.companyName").value("Empresa Álvarez"))
                .andExpect(jsonPath("$.logoDataUri").value("data:image/png;base64,logo"))
                .andExpect(jsonPath("$.timeZone").value("Atlantic/Canary"))
                .andExpect(jsonPath("$.currency").value("EUR"));
        verify(printConfiguration).logoDataUri(store.getId(), logo);
    }

    @Test
    void usesCompanyNameWhenStoreNameIsMissingAndDoesNotInventALogo() throws Exception {
        givenStore(null);
        mvc.perform(get("/api/v1/hardware/customer-display-identity")
                        .with(user("settings").authorities(() -> "CONFIGURACION_TERMINAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Empresa Álvarez"))
                .andExpect(jsonPath("$.logoDataUri").isEmpty());
    }

    @Test
    void deniesUnrelatedPermissionsBeforeReadingStoreIdentity() throws Exception {
        mvc.perform(get("/api/v1/hardware/customer-display-identity")
                        .with(user("reader").authorities(() -> "AUDIT_READ")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(organization, printConfiguration);
    }

    private Store givenStore(String name) {
        var address = Map.of("linea1", "Calle Mayor 1", "codigoPostal", "35001",
                "ciudad", "Las Palmas", "provincia", "Las Palmas", "pais", "ES");
        var store = new Store(new Company("B12345678", "Empresa Álvarez", address),
                "001", name, address, "display-test", "Atlantic/Canary", "EUR", "es-ES");
        when(organization.currentStore()).thenReturn(store);
        when(printConfiguration.presentation(any())).thenReturn(
                new StoreDocumentPrintConfigurationService.Presentation(null, null, true));
        return store;
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration { }
}
