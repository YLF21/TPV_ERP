package com.tpverp.backend.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerModel347Controller.class)
@Import(CustomerModel347ControllerTest.MethodSecurityConfiguration.class)
class CustomerModel347ControllerTest {

    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PATH = "/api/v1/customer-document-reports/" + CUSTOMER + "/model-347.pdf";
    @Autowired private MockMvc mvc;
    @MockitoBean private CustomerModel347Service service;

    @ParameterizedTest
    @ValueSource(strings = {"VENTA", "GESTION_VENTAS", "INVOICES_READ", "ROLE_ADMIN"})
    void returnsBackendPdfForAuthorizedInvoiceReaders(String authority) throws Exception {
        var bytes = "%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        when(service.generate(eq(CUSTOMER), eq(2026), eq("es"), any())).thenReturn(bytes);
        mvc.perform(get(PATH).param("year", "2026").with(user("seller").authorities(() -> authority)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(bytes)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"modelo-347-2026.pdf\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TICKETS_READ", "DELIVERY_NOTES_READ", "CUSTOMERS_READ", "GESTION_ALMACEN"})
    void deniesOtherPermissions(String authority) throws Exception {
        mvc.perform(get(PATH).param("year", "2026").with(user("seller").authorities(() -> authority)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9999", "1.5", "2026e0", "not-a-year"})
    void rejectsInvalidYears(String year) throws Exception {
        mvc.perform(get(PATH).param("year", year).with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en", "zh"})
    void passesSupportedPdfLanguage(String locale) throws Exception {
        when(service.generate(any(), anyInt(), any(), any())).thenReturn(new byte[]{1});
        mvc.perform(get(PATH).param("year", "2026").param("locale", locale)
                        .with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isOk());
        verify(service).generate(eq(CUSTOMER), eq(2026), eq(locale), any());
    }

    @Test
    void rejectsMissingYearInvalidCustomerAndUnsupportedLanguage() throws Exception {
        mvc.perform(get(PATH).with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH.replace(CUSTOMER.toString(), "not-a-uuid")).param("year", "2026")
                        .with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("year", "2026").param("locale", "fr")
                        .with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void doesNotReturnPdfForCustomerOutsideCompany() throws Exception {
        when(service.generate(any(), anyInt(), any(), any())).thenThrow(new NoSuchElementException("Customer not found"));
        mvc.perform(get(PATH).param("year", "2026").with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(PATH).param("year", "2026")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
