package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
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

@WebMvcTest(FiscalRecordReadController.class)
@Import(FiscalRecordReadControllerAuthorizationWebMvcTest.MethodSecurityConfiguration.class)
class FiscalRecordReadControllerAuthorizationWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private FiscalRecordReadService service;

    @Test
    void gestionFiscalReaderCanListAndReadFiscalRecords() throws Exception {
        var id = UUID.randomUUID();
        when(service.records(null, null, null, null, null, FiscalRecordNumberMatch.PREFIX,
                null, 0, 25))
                .thenReturn(new FiscalRecordReadPage(List.of(), 0, 25, 0, 0));
        when(service.record(id)).thenReturn(new FiscalRecordDetailView(
                id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F1, "A-1",
                java.time.LocalDate.of(2026, 8, 26), java.time.Instant.now(), "UTC", "B12345678",
                null, null, null, "HASH", null, "1", "1", "1", FiscalMode.NO_VERIFACTU,
                null, null, null, null, List.of(), null));

        mvc.perform(get("/api/v1/verifactu/admin/records").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/verifactu/admin/records/{id}", id).with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordId").value(id.toString()))
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }

    @Test
    void incompleteFiscalPermissionIsRejected() throws Exception {
        mvc.perform(get("/api/v1/verifactu/admin/records")
                        .with(permissions("reader", "VERIFACTU_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/verifactu/admin/records")
                        .with(permissions("user", "APP_GESTION_ACCESS")))
                .andExpect(status().isForbidden());
    }

    @Test
    void conservaElAliasNumberAlDelegarLaBusqueda() {
        var expected = new FiscalRecordReadPage(List.of(), 0, 25, 0, 0);
        when(service.records(null, null, null, null, "T-ALIAS", null, 0, 25))
                .thenReturn(expected);

        var actual = new FiscalRecordReadController(service).records(
                null, null, null, null, null, "T-ALIAS", null, null, 0, 25);

        verify(service).records(null, null, null, null, "T-ALIAS", null, 0, 25);
        org.assertj.core.api.Assertions.assertThat(actual).isSameAs(expected);
    }

    @Test
    void rechazaAliasNumberEnConflictoConDocumentNumber() {
        assertThatThrownBy(() -> new FiscalRecordReadController(service).records(
                null, null, null, null, "T-1", "T-2", null, null, 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pueden diferir");
    }

    @Test
    void cursorEndpointDelegatesSizeAndCursorWithoutCountContract() throws Exception {
        when(service.recordsCursor(null, null, null, null, null, FiscalRecordNumberMatch.PREFIX,
                null, 10, "cursor"))
                .thenReturn(new FiscalRecordReadCursorPage(
                        List.of(), 10, null, null, false, false, 7));

        mvc.perform(get("/api/v1/verifactu/admin/records/cursor")
                        .queryParam("size", "10")
                        .queryParam("cursor", "cursor")
                        .with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.snapshotSequence").value(7))
                .andExpect(jsonPath("$.totalElements").doesNotExist());
        verify(service).recordsCursor(null, null, null, null, null, FiscalRecordNumberMatch.PREFIX,
                null, 10, "cursor");
    }

    @Test
    void cursorEndpointValidatesAndForwardsExactNumberMatch() throws Exception {
        when(service.recordsCursor(null, null, null, null, "A-1", FiscalRecordNumberMatch.EXACT,
                null, 10, null)).thenReturn(new FiscalRecordReadCursorPage(
                        List.of(), 10, null, null, false, false, 0));

        mvc.perform(get("/api/v1/verifactu/admin/records/cursor")
                        .queryParam("number", "A-1")
                        .queryParam("numberMatch", "EXACT")
                        .queryParam("size", "10")
                        .with(reader()))
                .andExpect(status().isOk());

        verify(service).recordsCursor(null, null, null, null, "A-1", FiscalRecordNumberMatch.EXACT,
                null, 10, null);
    }

    @Test
    void legacyEndpointForwardsExactNumberMatch() throws Exception {
        when(service.records(null, null, null, null, "A-1", FiscalRecordNumberMatch.EXACT,
                null, 0, 10)).thenReturn(new FiscalRecordReadPage(List.of(), 0, 10, 0, 0));

        mvc.perform(get("/api/v1/verifactu/admin/records")
                        .queryParam("documentNumber", "A-1")
                        .queryParam("numberMatch", "EXACT")
                        .queryParam("size", "10")
                        .with(reader()))
                .andExpect(status().isOk());

        verify(service).records(null, null, null, null, "A-1", FiscalRecordNumberMatch.EXACT,
                null, 0, 10);
    }

    private static RequestPostProcessor reader() {
        return permissions("reader", "APP_GESTION_ACCESS", "VERIFACTU_READ");
    }

    private static RequestPostProcessor permissions(String username, String... values) {
        return user(username).authorities(Arrays.stream(values)
                .map(SimpleGrantedAuthority::new).toList());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
