package com.tpverp.backend.document;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.APLICAR_DESCUENTO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.promotion.AuthoritativePromotionPricing;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCategory;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentCardMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class PosCashOperationSecurityTest {

    @Test
    void credentialsAreResolvedPerOperationAndAuditNeverContainsThePassword() {
        var operationSecurity = mock(SaleOperationSecurityService.class);
        var audit = mock(AuditService.class);
        var discountAuthorizations = mock(DiscountAuthorizationService.class);
        var service = service(operationSecurity, audit, discountAuthorizations);
        var operator = user("operador", new BigDecimal("100.00"));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                operator, null, List.of());
        var policy = policy(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                true,
                true,
                List.of("CAMBIAR_PRECIO"));
        var authorization = new Authorization(operator, operator, false);
        when(operationSecurity.resolve(SaleOperationCode.TEMPORARY_PRICE_CHANGE))
                .thenReturn(policy);
        when(operationSecurity.authorize(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                null,
                "secreto-temporal",
                authentication)).thenReturn(authorization);
        var sale = sale(
                BigDecimal.ZERO,
                Map.of(
                        SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                        new OperationAuthorizationRequest(
                                null, "secreto-temporal")));

        service.authorizeSensitiveOperations(
                prepared(SaleOperationCode.TEMPORARY_PRICE_CHANGE),
                sale,
                BigDecimal.TEN,
                authentication,
                "POS_CARD",
                UUID.randomUUID());

        verify(operationSecurity).authorize(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                null,
                "secreto-temporal",
                authentication);
        @SuppressWarnings("unchecked")
        var details = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                org.mockito.ArgumentMatchers.eq(
                        PosCashService.SALE_OPERATION_AUTHORIZED),
                any(),
                details.capture());
        assertThat(details.getValue().toString())
                .contains("TEMPORARY_PRICE_CHANGE")
                .contains("operador")
                .doesNotContain("secreto-temporal");
    }

    @Test
    void openPriceUsesPolicyButDoesNotAuditTheDefaultUnprotectedOperation() {
        var operationSecurity = mock(SaleOperationSecurityService.class);
        var audit = mock(AuditService.class);
        var service = service(
                operationSecurity,
                audit,
                mock(DiscountAuthorizationService.class));
        var operator = user("cajero", BigDecimal.ZERO);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                operator, null, List.of());
        var policy = policy(
                SaleOperationCode.OPEN_PRICE_PRODUCT,
                false,
                false,
                List.of("CAMBIAR_PRECIO", "GESTION_VENTAS"));
        when(operationSecurity.resolve(SaleOperationCode.OPEN_PRICE_PRODUCT))
                .thenReturn(policy);
        when(operationSecurity.authorize(
                SaleOperationCode.OPEN_PRICE_PRODUCT,
                null,
                null,
                authentication)).thenReturn(
                        new Authorization(operator, operator, false));

        service.authorizeSensitiveOperations(
                prepared(SaleOperationCode.OPEN_PRICE_PRODUCT),
                sale(BigDecimal.ZERO, Map.of()),
                BigDecimal.TEN,
                authentication,
                "POS_CASH",
                UUID.randomUUID());

        verify(operationSecurity).authorize(
                SaleOperationCode.OPEN_PRICE_PRODUCT,
                null,
                null,
                authentication);
        verify(audit, never()).record(any(), any(), any());
    }

    @Test
    void discountAboveOperatorLimitDelegatesAndUsesTheAuthorizerLimit() {
        var operationSecurity = mock(SaleOperationSecurityService.class);
        var audit = mock(AuditService.class);
        var discountAuthorizations = mock(DiscountAuthorizationService.class);
        var service = service(operationSecurity, audit, discountAuthorizations);
        var operator = user("cajero", new BigDecimal("5.00"));
        var manager = user("responsable", new BigDecimal("50.00"));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                operator,
                null,
                List.of(new SimpleGrantedAuthority(APLICAR_DESCUENTO)));
        var policy = policy(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                true,
                false,
                List.of(APLICAR_DESCUENTO));
        when(operationSecurity.resolve(SaleOperationCode.APPLY_SALE_DISCOUNT))
                .thenReturn(policy);
        when(operationSecurity.authorizeNamed(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                "responsable",
                "clave",
                authentication)).thenReturn(
                        new Authorization(operator, manager, true));
        var sale = sale(
                new BigDecimal("20.00"),
                Map.of(
                        SaleOperationCode.APPLY_SALE_DISCOUNT,
                        new OperationAuthorizationRequest(
                                "responsable", "clave")));

        service.authorizeSensitiveOperations(
                prepared(SaleOperationCode.APPLY_SALE_DISCOUNT),
                sale,
                new BigDecimal("8.00"),
                authentication,
                "PAYMENT_SESSION",
                UUID.randomUUID());

        verify(operationSecurity).authorizeNamed(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                "responsable",
                "clave",
                authentication);
        verify(operationSecurity, never()).authorize(
                org.mockito.ArgumentMatchers.eq(
                        SaleOperationCode.APPLY_SALE_DISCOUNT),
                any(),
                any(),
                any());
        verify(discountAuthorizations).enforceAuthorizerLimit(
                new BigDecimal("20.00"), manager);
    }

    @Test
    void permissionDisabledMakesThePersonalDiscountLimitIrrelevant() {
        var operationSecurity = mock(SaleOperationSecurityService.class);
        var audit = mock(AuditService.class);
        var discountAuthorizations = mock(DiscountAuthorizationService.class);
        var service = service(operationSecurity, audit, discountAuthorizations);
        var operator = user("cajero", BigDecimal.ZERO);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                operator, null, List.of());
        var policy = policy(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                false,
                false,
                List.of(APLICAR_DESCUENTO));
        when(operationSecurity.resolve(SaleOperationCode.APPLY_SALE_DISCOUNT))
                .thenReturn(policy);
        when(operationSecurity.authorize(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                null,
                null,
                authentication)).thenReturn(
                        new Authorization(operator, operator, false));

        service.authorizeSensitiveOperations(
                prepared(SaleOperationCode.APPLY_SALE_DISCOUNT),
                sale(new BigDecimal("90.00"), Map.of()),
                BigDecimal.ONE,
                authentication,
                "POS_CASH",
                UUID.randomUUID());

        verify(discountAuthorizations, never())
                .enforceAuthorizerLimit(any(), any());
    }

    @Test
    void authorizationCredentialsNeverChangeTheEconomicIdempotencyHash() {
        var line = new PosCashController.LineRequest(
                UUID.randomUUID(),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                new BigDecimal("3.00"));
        var checkoutId = UUID.randomUUID();
        var first = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(
                        null,
                        List.of(line),
                        null,
                        null,
                        null,
                        null,
                        Map.of(
                                SaleOperationCode.OPEN_PRICE_PRODUCT,
                                new OperationAuthorizationRequest(
                                        "primero", "secreto-1"))),
                new BigDecimal("5.00"),
                new BigDecimal("3.00"));
        var second = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(
                        null,
                        List.of(line),
                        null,
                        null,
                        null,
                        null,
                        Map.of(
                                SaleOperationCode.OPEN_PRICE_PRODUCT,
                                new OperationAuthorizationRequest(
                                        "segundo", "secreto-2"))),
                new BigDecimal("5.00"),
                new BigDecimal("3.00"));

        assertThat(PosCashService.requestHash(first))
                .isEqualTo(PosCashService.requestHash(second));
        assertThat(first.sale().toString())
                .doesNotContain("secreto-1");
    }

    @Test
    void nullAuthorizationValueIsRejectedWithAStableValidationError() {
        var authorizations = new HashMap<SaleOperationCode, OperationAuthorizationRequest>();
        authorizations.put(SaleOperationCode.TEMPORARY_NAME, null);

        assertThatThrownBy(() -> new PosCashController.SaleRequest(
                null,
                List.of(new PosCashController.LineRequest(
                        UUID.randomUUID(),
                        BigDecimal.ONE,
                        BigDecimal.ZERO)),
                null,
                null,
                null,
                null,
                authorizations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sale_operation_authorization_value_required");
    }

    @Test
    void legacyTicketEndpointCannotUseAnArbitraryNegativeQuantity() {
        var service = service(
                mock(SaleOperationSecurityService.class),
                mock(AuditService.class),
                mock(DiscountAuthorizationService.class));
        var line = new DocumentLineCommand(
                UUID.randomUUID(),
                new BigDecimal("-2"),
                "SKU",
                "Producto",
                null,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                true,
                "IVA",
                new BigDecimal("21.00"));
        var command = new DocumentCommand(
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 30),
                null,
                null,
                null,
                BigDecimal.ZERO,
                true,
                List.of(line));

        assertThatThrownBy(() -> service.authorizeLegacyTicketMutation(
                command,
                Map.of(),
                UsernamePasswordAuthenticationToken.unauthenticated(
                        user("cajero", BigDecimal.ZERO), null),
                "LEGACY_TICKET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("manual_return_quantity_must_be_minus_one");
    }

    @Test
    void legacyTicketEndpointAuthorizesManualCardAndTransferSeparately() {
        var operationSecurity = mock(SaleOperationSecurityService.class);
        var audit = mock(AuditService.class);
        var paymentMethods = mock(PaymentMethodRepository.class);
        var organization = mock(CurrentOrganization.class);
        var company = mock(Company.class);
        var store = mock(Store.class);
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);

        var card = new PaymentMethod(companyId, "TARJETA", true);
        var transfer = new PaymentMethod(companyId, "TRANSFERENCIA", true);
        when(paymentMethods.findByIdAndEmpresaId(card.getId(), companyId))
                .thenReturn(java.util.Optional.of(card));
        when(paymentMethods.findByIdAndEmpresaId(transfer.getId(), companyId))
                .thenReturn(java.util.Optional.of(transfer));

        var operator = user("cajero", BigDecimal.ZERO);
        var manager = user("responsable", BigDecimal.ZERO);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                operator, null, List.of());
        var authorization = new Authorization(operator, manager, true);
        for (var code : List.of(
                SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                SaleOperationCode.CONFIRM_TRANSFER_PAYMENT)) {
            when(operationSecurity.resolve(code)).thenReturn(policy(
                    code, true, false,
                    List.of("GESTION_VENTAS", "GESTION_CUENTAS")));
        }
        when(operationSecurity.authorize(
                SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                "responsable",
                "clave-tarjeta",
                authentication)).thenReturn(authorization);
        when(operationSecurity.authorize(
                SaleOperationCode.CONFIRM_TRANSFER_PAYMENT,
                "responsable",
                "clave-transferencia",
                authentication)).thenReturn(authorization);

        var service = service(
                operationSecurity,
                audit,
                mock(DiscountAuthorizationService.class),
                paymentMethods,
                organization);
        var command = new DocumentCommand(
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 31),
                null,
                null,
                null,
                BigDecimal.ZERO,
                true,
                List.of());
        var payments = List.of(
                new PaymentCommand(
                        card.getId(), BigDecimal.TEN, true, null, null,
                        null, null, PaymentCardMode.MANUAL,
                        null, null, null, null),
                new PaymentCommand(
                        transfer.getId(), BigDecimal.ONE, false,
                        null, null));

        service.authorizeLegacyTicketMutation(
                command,
                payments,
                Map.of(
                        SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                        new OperationAuthorizationRequest(
                                "responsable", "clave-tarjeta"),
                        SaleOperationCode.CONFIRM_TRANSFER_PAYMENT,
                        new OperationAuthorizationRequest(
                                "responsable", "clave-transferencia")),
                authentication,
                "LEGACY_TICKET");

        verify(operationSecurity).authorize(
                SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                "responsable",
                "clave-tarjeta",
                authentication);
        verify(operationSecurity).authorize(
                SaleOperationCode.CONFIRM_TRANSFER_PAYMENT,
                "responsable",
                "clave-transferencia",
                authentication);
        @SuppressWarnings("unchecked")
        var details = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Map.class);
        verify(audit, times(2)).record(
                org.mockito.ArgumentMatchers.eq(
                        PosCashService.SALE_OPERATION_AUTHORIZED),
                any(),
                details.capture());
        assertThat(details.getAllValues().toString())
                .contains("CONFIRM_MANUAL_CARD_PAYMENT")
                .contains("CONFIRM_TRANSFER_PAYMENT")
                .doesNotContain("clave-tarjeta")
                .doesNotContain("clave-transferencia");
    }

    private static PosCashService service(
            SaleOperationSecurityService operationSecurity,
            AuditService audit,
            DiscountAuthorizationService discountAuthorizations) {
        return service(
                operationSecurity,
                audit,
                discountAuthorizations,
                mock(PaymentMethodRepository.class),
                mock(CurrentOrganization.class));
    }

    private static PosCashService service(
            SaleOperationSecurityService operationSecurity,
            AuditService audit,
            DiscountAuthorizationService discountAuthorizations,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization) {
        return new PosCashService(
                mock(DocumentService.class),
                mock(ProductRepository.class),
                mock(StoreTaxRepository.class),
                mock(WarehouseRepository.class),
                paymentMethods,
                organization,
                mock(PosCashCheckoutRepository.class),
                new PosCashTicketSnapshot(),
                mock(CurrentTerminal.class),
                discountAuthorizations,
                mock(AuthoritativePromotionPricing.class),
                operationSecurity,
                audit);
    }

    private static PosCashService.PreparedSale prepared(
            SaleOperationCode operationCode) {
        return new PosCashService.PreparedSale(
                mock(DocumentCommand.class),
                EnumSet.of(operationCode));
    }

    private static PosCashController.SaleRequest sale(
            BigDecimal lineDiscount,
            Map<SaleOperationCode, OperationAuthorizationRequest> authorizations) {
        return new PosCashController.SaleRequest(
                null,
                List.of(new PosCashController.LineRequest(
                        UUID.randomUUID(),
                        BigDecimal.ONE,
                        lineDiscount)),
                null,
                null,
                null,
                null,
                authorizations);
    }

    private static SaleOperationSecurityService.ResolvedOperation policy(
            SaleOperationCode code,
            boolean requirePermission,
            boolean requirePassword,
            List<String> permissions) {
        return new SaleOperationSecurityService.ResolvedOperation(
                UUID.randomUUID(),
                4L,
                code,
                SaleOperationCategory.PRODUCT,
                List.of(),
                permissions,
                requirePermission,
                requirePassword,
                false);
    }

    private static UserAccount user(String username, BigDecimal maxDiscount) {
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getUserName()).thenReturn(username);
        when(user.getMaxDiscountPercent()).thenReturn(maxDiscount);
        return user;
    }
}
