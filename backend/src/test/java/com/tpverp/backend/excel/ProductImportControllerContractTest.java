package com.tpverp.backend.excel;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentStatus;
import java.io.InputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

@WebMvcTest(ProductImportController.class)
@Import(ProductImportControllerContractTest.MethodSecurityConfiguration.class)
class ProductImportControllerContractTest {

    private static final UUID WAREHOUSE_ID = UUID.randomUUID();
    private static final UUID SUPPLIER_ID = UUID.randomUUID();
    private static final String PRODUCT_IMPORT_PERMISSION =
            "hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','PRODUCTS_WRITE')";

    @Autowired
    private MockMvc mvc;

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @MockitoBean
    private ProductImportService service;

    @Test
    void exposesProductImportEndpointsWithProductPermissions() throws Exception {
        RequestMapping root = ProductImportController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/v1/excel/product-import");

        assertEndpoint("preview", new String[] {"/preview"}, MultipartFile.class, ProductImportMapping.class);
        assertEndpoint(
                "confirm",
                new String[] {"/confirm"},
                MultipartFile.class,
                ProductImportConfirmRequest.class,
                Authentication.class);
    }

    @Test
    void previewAcceptsMultipartFileAndMappingAndDelegatesToService() throws Exception {
        ProductImportMapping mapping = mapping();
        when(service.preview(any(InputStream.class), eq(mapping)))
                .thenReturn(new ProductImportPreview(List.of()));

        mvc.perform(multipart("/api/v1/excel/product-import/preview")
                        .file(file())
                        .file(jsonPart("mapping", mapping))
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf()))
                .andExpect(status().isOk());

        ArgumentCaptor<InputStream> input = ArgumentCaptor.forClass(InputStream.class);
        verify(service).preview(input.capture(), eq(mapping));
        assertThat(input.getValue().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void previewAllowsAdminUsers() throws Exception {
        when(service.preview(any(InputStream.class), any(ProductImportMapping.class)))
                .thenReturn(new ProductImportPreview(List.of()));

        mvc.perform(multipart("/api/v1/excel/product-import/preview")
                        .file(file())
                        .file(jsonPart("mapping", mapping()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void previewAllowsGestionProductoUsers() throws Exception {
        when(service.preview(any(InputStream.class), any(ProductImportMapping.class)))
                .thenReturn(new ProductImportPreview(List.of()));

        mvc.perform(multipart("/api/v1/excel/product-import/preview")
                        .file(file())
                        .file(jsonPart("mapping", mapping()))
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void previewForbidsUsersWithUnrelatedAuthority() throws Exception {
        mvc.perform(multipart("/api/v1/excel/product-import/preview")
                        .file(file())
                        .file(jsonPart("mapping", mapping()))
                        .with(user("reader").authorities(() -> "PRODUCTS_READ"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void previewReportsExcelReadFailuresAsBadRequest() throws Exception {
        when(service.preview(any(InputStream.class), any(ProductImportMapping.class)))
                .thenThrow(new IOException("broken file"));

        mvc.perform(multipart("/api/v1/excel/product-import/preview")
                        .file(file())
                        .file(jsonPart("mapping", mapping()))
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmAcceptsMultipartFileAndRequestAndDelegatesToService() throws Exception {
        ProductImportConfirmRequest request = request();
        CommercialDocument document = document();
        when(service.confirm(any(InputStream.class), eq(request), any(Authentication.class)))
                .thenReturn(document);

        mvc.perform(multipart("/api/v1/excel/product-import/confirm")
                        .file(file())
                        .file(jsonPart("request", request))
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf()))
                .andExpect(status().isOk());

        ArgumentCaptor<InputStream> input = ArgumentCaptor.forClass(InputStream.class);
        verify(service).confirm(input.capture(), eq(request), any(Authentication.class));
        assertThat(input.getValue().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void confirmRejectsRequestsWithoutMapping() throws Exception {
        ProductImportConfirmRequest request = new ProductImportConfirmRequest(
                null,
                WAREHOUSE_ID,
                SUPPLIER_ID,
                "EXT-1",
                CommercialDocumentType.ALBARAN_COMPRA,
                LocalDate.of(2026, 7, 1));

        mvc.perform(multipart("/api/v1/excel/product-import/confirm")
                        .file(file())
                        .file(jsonPart("request", request))
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private void assertEndpoint(String methodName, String[] paths, Class<?>... parameterTypes)
            throws Exception {
        Method method = ProductImportController.class.getDeclaredMethod(methodName, parameterTypes);
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo(PRODUCT_IMPORT_PERMISSION);
        assertThat(mappingPaths(method, PostMapping.class)).containsExactly(paths);
    }

    private String[] mappingPaths(Method method, Class<? extends Annotation> mappingType) throws Exception {
        Annotation mapping = method.getAnnotation(mappingType);
        assertThat(mapping).isNotNull();
        return (String[]) mappingType.getMethod("value").invoke(mapping);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile(
                "file",
                "products.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[] {1, 2, 3});
    }

    private MockMultipartFile jsonPart(String name, Object value) throws Exception {
        return new MockMultipartFile(
                name,
                "",
                MediaType.APPLICATION_JSON_VALUE,
                mapper.writeValueAsBytes(value));
    }

    private static ProductImportMapping mapping() {
        return new ProductImportMapping(
                "A",
                "B",
                "C",
                "D",
                "E",
                "F",
                "G",
                "H",
                "I",
                "J",
                "K",
                2,
                true,
                true,
                true,
                true,
                true,
                true);
    }

    private static ProductImportConfirmRequest request() {
        return new ProductImportConfirmRequest(
                mapping(),
                WAREHOUSE_ID,
                SUPPLIER_ID,
                "EXT-1",
                CommercialDocumentType.ALBARAN_COMPRA,
                LocalDate.of(2026, 7, 1));
    }

    private static CommercialDocument document() {
        CommercialDocument document = org.mockito.Mockito.mock(CommercialDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getTipo()).thenReturn(CommercialDocumentType.ALBARAN_COMPRA);
        when(document.getEstado()).thenReturn(DocumentStatus.PENDIENTE);
        when(document.getNumero()).thenReturn("AC-1");
        when(document.getFecha()).thenReturn(LocalDate.of(2026, 7, 1));
        when(document.getBaseTotal()).thenReturn(new BigDecimal("10.00"));
        when(document.getImpuestoTotal()).thenReturn(new BigDecimal("2.10"));
        when(document.getTotal()).thenReturn(new BigDecimal("12.10"));
        when(document.getNumTicket()).thenReturn(null);
        when(document.isOrigenStock()).thenReturn(true);
        when(document.getPagos()).thenReturn(List.of());
        return document;
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
