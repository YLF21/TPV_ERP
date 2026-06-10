package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.catalog.ProductSupplierService.ProductSupplierView;
import com.tpverp.backend.party.DocumentType;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@WebMvcTest(ProductSupplierController.class)
@Import(ProductSupplierControllerContractTest.MethodSecurityConfiguration.class)
class ProductSupplierControllerContractTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID SUPPLIER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductSupplierService service;

    @Test
    void exposesProductSupplierEndpointsWithProductPermissions() throws Exception {
        RequestMapping root = ProductSupplierController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/v1/products/{productId}/suppliers");

        assertEndpoint(
                "list",
                PRODUCTS_READ,
                GetMapping.class,
                new String[0],
                UUID.class);
        assertEndpoint(
                "link",
                PRODUCTS_WRITE,
                PostMapping.class,
                new String[0],
                UUID.class,
                ProductSupplierController.LinkRequest.class);
        assertEndpoint(
                "update",
                PRODUCTS_WRITE,
                PutMapping.class,
                new String[] {"/{supplierId}"},
                UUID.class,
                UUID.class,
                ProductSupplierController.ReferenceRequest.class);
        assertEndpoint(
                "unlink",
                PRODUCTS_WRITE,
                DeleteMapping.class,
                new String[] {"/{supplierId}"},
                UUID.class,
                UUID.class);
    }

    @Test
    void requiresSupplierIdOnlyWhenCreatingTheLink() {
        var linkComponents = ProductSupplierController.LinkRequest.class.getRecordComponents();
        var referenceComponents =
                ProductSupplierController.ReferenceRequest.class.getRecordComponents();

        assertThat(linkComponents).extracting(component -> component.getName())
                .containsExactly("supplierId", "supplierReference");
        assertThat(linkComponents[0].getAccessor().isAnnotationPresent(NotNull.class)).isTrue();
        assertThat(referenceComponents).extracting(component -> component.getName())
                .containsExactly("supplierReference");
    }

    @Test
    void bindsAndDelegatesReadAndWriteRequests() throws Exception {
        ProductSupplierView view = view();
        when(service.list(PRODUCT_ID)).thenReturn(List.of(view));
        when(service.link(PRODUCT_ID, SUPPLIER_ID, "REF-POST")).thenReturn(view);
        when(service.updateReference(PRODUCT_ID, SUPPLIER_ID, "REF-PUT")).thenReturn(view);

        mvc.perform(get(path()).with(user("reader").authorities(
                        () -> PRODUCTS_READ, () -> PRODUCTS_WRITE)))
                .andExpect(status().isOk());
        mvc.perform(post(path())
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId":"%s","supplierReference":"REF-POST"}
                                """.formatted(SUPPLIER_ID)))
                .andExpect(status().isOk());
        mvc.perform(put(path() + "/" + SUPPLIER_ID)
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierReference":"REF-PUT"}
                                """))
                .andExpect(status().isOk());

        verify(service).list(PRODUCT_ID);
        verify(service).link(PRODUCT_ID, SUPPLIER_ID, "REF-POST");
        verify(service).updateReference(PRODUCT_ID, SUPPLIER_ID, "REF-PUT");
    }

    @Test
    void deletesAProductSupplierLinkWithNoContent() throws Exception {
        mvc.perform(delete(path() + "/" + SUPPLIER_ID)
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).unlink(PRODUCT_ID, SUPPLIER_ID);
    }

    @Test
    void rejectsSupplierReferencesLongerThan128Characters() throws Exception {
        mvc.perform(post(path())
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId":"%s","supplierReference":"%s"}
                                """.formatted(SUPPLIER_ID, "a".repeat(129))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUpdatedSupplierReferencesLongerThan128Characters() throws Exception {
        mvc.perform(put(path() + "/" + SUPPLIER_ID)
                        .with(user("writer").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierReference":"%s"}
                                """.formatted("a".repeat(129))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forbidsAuthenticatedUsersWithoutProductPermission() throws Exception {
        mvc.perform(get(path()).with(user("unauthorized")))
                .andExpect(status().isForbidden());
    }

    private void assertEndpoint(
            String methodName,
            String permission,
            Class<? extends Annotation> mappingType,
            String[] paths,
            Class<?>... parameterTypes)
            throws Exception {
        Method method = ProductSupplierController.class.getDeclaredMethod(methodName, parameterTypes);
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value())
                .contains("hasRole('ADMIN')")
                .contains("hasAuthority('" + permission + "')");
        assertThat(mappingPaths(method, mappingType)).containsExactly(paths);
    }

    private String[] mappingPaths(
            Method method, Class<? extends Annotation> mappingType) throws Exception {
        Annotation mapping = method.getAnnotation(mappingType);
        assertThat(mapping).isNotNull();
        return (String[]) mappingType.getMethod("value").invoke(mapping);
    }

    private static String path() {
        return "/api/v1/products/" + PRODUCT_ID + "/suppliers";
    }

    private static ProductSupplierView view() {
        return new ProductSupplierView(
                SUPPLIER_ID,
                "Proveedor",
                DocumentType.CIF,
                "B00000001",
                true,
                "REF",
                LocalDate.of(2026, 6, 10));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
