package com.tpverp.backend.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.ProductEditAuthorizationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ProductEditAuthorizationFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void elevatesOnlyTheAuthorizedProductUpdateAndRecordsItsMutation() throws Exception {
        var service = mock(ProductEditAuthorizationService.class);
        var filter = new ProductEditAuthorizationFilter(service);
        var operationId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var authentication = new UsernamePasswordAuthenticationToken(
                "seller", "token", List.of(new SimpleGrantedAuthority("VENTA")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var grant = grant(operationId, productId);
        when(service.validGrant(operationId, productId, authentication))
                .thenReturn(Optional.of(grant));
        var request = new MockHttpServletRequest(
                "PUT", "/api/v1/products/management/" + productId);
        request.addHeader(ProductEditAuthorizationService.HEADER, operationId.toString());
        var response = new MockHttpServletResponse();
        var elevated = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                elevated.set(SecurityContextHolder.getContext().getAuthentication()
                        .getAuthorities().stream()
                        .anyMatch(value -> value.getAuthority().equals("GESTION_PRODUCTO"))));

        assertThat(elevated).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        verify(service).recordMutation(eq(grant), eq("PRODUCT_UPDATE"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void neverElevatesAProductDeletion() throws Exception {
        var service = mock(ProductEditAuthorizationService.class);
        var filter = new ProductEditAuthorizationFilter(service);
        var operationId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var authentication = new UsernamePasswordAuthenticationToken(
                "seller", "token", List.of(new SimpleGrantedAuthority("VENTA")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var request = new MockHttpServletRequest("DELETE", "/api/v1/products/" + productId);
        request.addHeader(ProductEditAuthorizationService.HEADER, operationId.toString());
        var elevated = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                elevated.set(SecurityContextHolder.getContext().getAuthentication()
                        .getAuthorities().stream()
                        .anyMatch(value -> value.getAuthority().equals("GESTION_PRODUCTO"))));

        assertThat(elevated).isFalse();
        verify(service, never()).validGrant(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static ProductEditAuthorizationService.Grant grant(UUID operationId, UUID productId) {
        return new ProductEditAuthorizationService.Grant(
                operationId,
                UUID.randomUUID(),
                productId,
                UUID.randomUUID(),
                "CAJERO",
                UUID.randomUUID(),
                "ENCARGADO",
                true,
                Instant.parse("2026-07-24T12:15:00Z"));
    }
}
