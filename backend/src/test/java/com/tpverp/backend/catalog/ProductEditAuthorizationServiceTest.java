package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ProductEditAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

    private CatalogService catalog;
    private CurrentOrganization organization;
    private OperationalPermissionAuthorizationService authorizations;
    private ControlAlertDetectionService controlAlerts;
    private AuditService audit;
    private ProductEditAuthorizationService service;
    private Product product;
    private Store store;
    private UserAccount operator;
    private UserAccount authorizer;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogService.class);
        organization = mock(CurrentOrganization.class);
        authorizations = mock(OperationalPermissionAuthorizationService.class);
        controlAlerts = mock(ControlAlertDetectionService.class);
        audit = mock(AuditService.class);
        service = new ProductEditAuthorizationService(
                catalog, organization, authorizations, controlAlerts, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        product = mock(Product.class);
        store = mock(Store.class);
        operator = mock(UserAccount.class);
        authorizer = mock(UserAccount.class);
        authentication = new UsernamePasswordAuthenticationToken(operator, "token");
        when(product.getId()).thenReturn(UUID.randomUUID());
        when(product.getName()).thenReturn("Cafe");
        when(product.getCode()).thenReturn("A001");
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(operator.getId()).thenReturn(UUID.randomUUID());
        when(operator.getUserName()).thenReturn("CAJERO");
        when(authorizer.getId()).thenReturn(UUID.randomUUID());
        when(authorizer.getUserName()).thenReturn("ENCARGADO");
        when(catalog.product(product.getId())).thenReturn(product);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(operator);
        when(authorizations.authorize(eq("GESTION_PRODUCTO"), any(), any(), eq(authentication)))
                .thenReturn(new OperationalPermissionAuthorizationService.Authorization(
                        operator, authorizer, true));
    }

    @Test
    void grantsOnlyTheSelectedProductAndRecordsTheAuthorizer() {
        var authorization = service.authorize(
                product.getId(), "encargado", "1234", authentication);

        assertThat(authorization.product().id()).isEqualTo(product.getId());
        assertThat(authorization.delegated()).isTrue();
        assertThat(service.validGrant(
                authorization.operationId(), product.getId(), authentication)).isPresent();
        assertThat(service.validGrant(
                authorization.operationId(), UUID.randomUUID(), authentication)).isEmpty();
        verify(audit).record(eq("PRODUCT_EDIT_AUTHORIZED"), eq(AuditResult.EXITO), any());
    }

    @Test
    void recordsTheCatalogMutationAndEmitsAControlEvent() {
        var authorization = service.authorize(
                product.getId(), "encargado", "1234", authentication);
        var grant = service.validGrant(
                authorization.operationId(), product.getId(), authentication).orElseThrow();

        service.recordMutation(grant, "PRODUCT_UPDATE", authentication);

        var productId = product.getId();
        var authorizerId = authorizer.getId();
        verify(audit).record(eq("PRODUCT_CATALOG_MODIFIED"), eq(AuditResult.EXITO), any());
        verify(controlAlerts).detectProductCatalogModified(
                eq(authorization.operationId()),
                eq(productId),
                eq("A001"),
                eq("Cafe"),
                eq("PRODUCT_UPDATE"),
                eq(null),
                eq(authorizerId),
                eq("ENCARGADO"),
                eq(true),
                eq(authentication));
    }
}
