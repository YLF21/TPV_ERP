package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCategory;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class TemporaryPriceAuthorizationServiceTest {

    @Test
    void issuingProofAuthorizesImmediatelyAndNeverAuditsThePasswordOrRawToken() {
        var grants = mock(TemporaryPriceAuthorizationGrantRepository.class);
        var products = mock(ProductRepository.class);
        var organization = mock(CurrentOrganization.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var operationSecurity = mock(SaleOperationSecurityService.class);
        var audit = mock(AuditService.class);
        var now = Instant.parse("2026-08-03T10:00:00Z");
        var service = new TemporaryPriceAuthorizationService(
                grants, products, organization, currentTerminal,
                operationSecurity, audit, Clock.fixed(now, ZoneOffset.UTC));
        var productId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var operator = user("cajero");
        var authorizer = user("responsable");
        var company = mock(Company.class);
        var store = mock(Store.class);
        var product = mock(Product.class);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                operator, null, List.of());
        var policy = new SaleOperationSecurityService.ResolvedOperation(
                UUID.randomUUID(), 11L,
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                SaleOperationCategory.PRODUCT,
                List.of("Ctrl+RePag"),
                List.of("CAMBIAR_PRECIO", "GESTION_VENTAS"),
                true, true, false);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        when(product.getId()).thenReturn(productId);
        when(product.getStoreId()).thenReturn(storeId);
        when(product.getSalePrice()).thenReturn(BigDecimal.TEN);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(operationSecurity.resolve(SaleOperationCode.TEMPORARY_PRICE_CHANGE))
                .thenReturn(policy);
        when(operationSecurity.authorize(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                "responsable", "clave-secreta", authentication))
                .thenReturn(new Authorization(operator, authorizer, true));
        when(grants.save(any(TemporaryPriceAuthorizationGrant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.authorize(
                productId, "linea-1", new BigDecimal("8.50"),
                new OperationAuthorizationRequest("responsable", "clave-secreta"),
                authentication);

        assertThat(result.token()).isNotBlank();
        assertThat(result.expiresAt()).isEqualTo(now.plus(
                TemporaryPriceAuthorizationService.VALIDITY));
        assertThat(result.authorizedBy()).isEqualTo("responsable");
        assertThat(result.delegated()).isTrue();
        assertThat(result.policyVersion()).isEqualTo(11L);
        @SuppressWarnings("unchecked")
        var details = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                eq("TEMPORARY_PRICE_CHANGE_PREAUTHORIZED"),
                eq(AuditResult.EXITO),
                details.capture());
        assertThat(details.getValue().toString())
                .contains("linea-1")
                .contains("responsable")
                .doesNotContain("clave-secreta")
                .doesNotContain(result.token());
    }

    private static UserAccount user(String username) {
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getUserName()).thenReturn(username);
        return user;
    }
}
