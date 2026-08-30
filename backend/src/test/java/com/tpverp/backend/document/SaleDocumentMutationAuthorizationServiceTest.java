package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCategory;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class SaleDocumentMutationAuthorizationServiceTest {

    @Mock ProductRepository products;
    @Mock PaymentMethodRepository paymentMethods;
    @Mock CurrentOrganization organization;
    @Mock SaleOperationSecurityService operationSecurity;
    @Mock DiscountAuthorizationService discountAuthorizations;
    @Mock AuditService audit;
    @Mock Store store;
    @Mock Company company;
    @Mock Product product;
    @Mock UserAccount operator;

    private final Authentication authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                    "operator", "unused", List.of());
    private SaleDocumentMutationAuthorizationService service;
    private UUID storeId;
    private UUID companyId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        productId = UUID.randomUUID();
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(company.getId()).thenReturn(companyId);
        lenient().when(products.findById(productId)).thenReturn(Optional.of(product));
        lenient().when(product.getStoreId()).thenReturn(storeId);
        lenient().when(product.getName()).thenReturn("Catalog name");
        lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("10.00"));
        lenient().when(organization.currentUser(authentication)).thenReturn(operator);
        lenient().when(operator.getId()).thenReturn(UUID.randomUUID());
        lenient().when(operator.getUserName()).thenReturn("operator");
        lenient().when(operationSecurity.resolve(any(SaleOperationCode.class)))
                .thenAnswer(invocation -> direct(invocation.getArgument(0)));
        lenient().when(operationSecurity.authorize(
                any(SaleOperationCode.class), any(), any(), eq(authentication)))
                .thenReturn(new Authorization(operator, operator, false));
        service = new SaleDocumentMutationAuthorizationService(
                products,
                paymentMethods,
                organization,
                operationSecurity,
                discountAuthorizations,
                audit);
    }

    @Test
    void authorizesManualCardThroughTheConfigurablePaymentPolicy() {
        var method = new PaymentMethod(companyId, "TARJETA", true);
        when(paymentMethods.findByIdAndEmpresaId(method.getId(), companyId))
                .thenReturn(Optional.of(method));
        var credentials = new OperationAuthorizationRequest("manager", "secret");
        var request = new PaymentRequest(
                List.of(new PaymentRequest.Item(
                        method.getId(), new BigDecimal("10.00"), true,
                        null, null, null, "DOC-1",
                        PaymentCardMode.MANUAL, null, null, null, null,
                        UUID.randomUUID(), null)),
                Map.of(SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT, credentials));

        service.authorizePayments(
                request,
                request.operationAuthorizations(),
                SaleDocumentMutationAuthorizationService.IntegratedPaymentPolicy
                        .REJECT_UNPROVEN,
                authentication,
                "GENERIC_INVOICE_PAYMENT",
                UUID.randomUUID());

        verify(operationSecurity).authorize(
                SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                "manager",
                "secret",
                authentication);
    }

    @Test
    void rejectsClientAssertedIntegratedCardInLegacyApi() {
        var request = integratedPaymentRequest(UUID.randomUUID());

        assertThatThrownBy(() -> service.authorizePayments(
                request,
                Map.of(),
                SaleDocumentMutationAuthorizationService.IntegratedPaymentPolicy
                        .REJECT_UNPROVEN,
                authentication,
                "LEGACY_TICKET",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("legacy_integrated_card_payment_not_supported");

        verify(paymentMethods).findByIdAndEmpresaId(any(), eq(companyId));
    }

    @Test
    void allowsIntegratedCardOnlyForFlowThatWillValidatePersistedOperation() {
        var operationId = UUID.randomUUID();
        var request = new PaymentRequest(List.of(new PaymentRequest.Item(
                UUID.randomUUID(),
                new BigDecimal("10.00"),
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                operationId,
                operationId)));

        service.authorizePayments(
                request,
                Map.of(),
                SaleDocumentMutationAuthorizationService.IntegratedPaymentPolicy
                        .REQUIRE_PERSISTED_OPERATION,
                authentication,
                "CUSTOMER_RECEIVABLE_PAYMENT",
                UUID.randomUUID());

        verify(paymentMethods).findByIdAndEmpresaId(any(), eq(companyId));
        verify(operationSecurity, never()).authorize(
                eq(SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT),
                any(), any(), eq(authentication));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SALDO_MIEMBRO", "CREDITO_DEVOLUCION"})
    void rejectsWalletPaymentEvenWhenClientSuppliesTerminalOperationId(String methodName) {
        var method = new PaymentMethod(companyId, methodName, true);
        var operationId = UUID.randomUUID();
        when(paymentMethods.findByIdAndEmpresaId(method.getId(), companyId))
                .thenReturn(Optional.of(method));
        var request = new PaymentRequest(List.of(new PaymentRequest.Item(
                method.getId(), new BigDecimal("10.00"), true,
                null, null, null, null, PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC,
                PaymentTerminalOperationStatus.APPROVED, "AUTH-1", UUID.randomUUID(),
                operationId, operationId)));

        assertThatThrownBy(() -> service.authorizePayments(
                request, Map.of(),
                SaleDocumentMutationAuthorizationService.IntegratedPaymentPolicy
                        .REQUIRE_PERSISTED_OPERATION,
                authentication, "DIRECT_DOCUMENT_PAYMENT", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("direct_document_payment_method_not_allowed");

        verifyNoInteractions(operationSecurity, audit);
    }

    @Test
    void authorizesEveryExplicitSensitiveMutationAfterCatalogueValidation() {
        var credentials = new OperationAuthorizationRequest("manager", "secret");
        var command = command(new DocumentLineCommand(
                productId,
                BigDecimal.ONE.negate(),
                "P1",
                "Temporary name",
                null,
                new BigDecimal("7.50"),
                new BigDecimal("20.00"),
                true,
                "IVA",
                new BigDecimal("21"),
                DocumentLineType.PRODUCT,
                null,
                null,
                null,
                List.of(),
                true,
                true));
        var requested = Map.of(
                SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET, credentials,
                SaleOperationCode.TEMPORARY_NAME, credentials,
                SaleOperationCode.TEMPORARY_PRICE_CHANGE, credentials,
                SaleOperationCode.APPLY_SALE_DISCOUNT, credentials);

        var proof = service.authorize(
                command, requested, authentication, "CUSTOMER_PENDING_DOCUMENT",
                UUID.randomUUID());

        assertThat(proof.policyVersions()).containsOnly(
                Map.entry(SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET, 1L),
                Map.entry(SaleOperationCode.TEMPORARY_NAME, 1L),
                Map.entry(SaleOperationCode.TEMPORARY_PRICE_CHANGE, 1L),
                Map.entry(SaleOperationCode.APPLY_SALE_DISCOUNT, 1L));

        verify(operationSecurity).authorize(
                SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET,
                "manager", "secret", authentication);
        verify(operationSecurity).authorize(
                SaleOperationCode.TEMPORARY_NAME,
                "manager", "secret", authentication);
        verify(operationSecurity).authorize(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                "manager", "secret", authentication);
        verify(operationSecurity).authorize(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                "manager", "secret", authentication);
    }

    @Test
    void automaticMemberOrOfferPriceDoesNotRequireTemporaryPriceAuthorization() {
        var command = command(line(
                BigDecimal.ONE,
                "Catalog name",
                new BigDecimal("7.50"),
                BigDecimal.ZERO));

        service.authorize(
                command, Map.of(), authentication, "CUSTOMER_PENDING_DOCUMENT",
                UUID.randomUUID());

        verify(operationSecurity, never()).authorize(
                eq(SaleOperationCode.TEMPORARY_PRICE_CHANGE),
                any(), any(), eq(authentication));
    }

    @Test
    void rejectsIncoherentTemporaryNameSignal() {
        var command = command(new DocumentLineCommand(
                productId,
                BigDecimal.ONE,
                "P1",
                "Catalog name",
                null,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                true,
                "IVA",
                new BigDecimal("21"),
                DocumentLineType.PRODUCT,
                null,
                null,
                null,
                List.of(),
                true,
                false));

        assertThatThrownBy(() -> service.authorize(
                command, Map.of(), authentication, "TEST", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("temporary_name_override_is_inconsistent");
    }

    @Test
    void rejectsUnsupportedNegativeQuantityBeforeAuthorization() {
        var command = command(line(new BigDecimal("-2"), "Catalog name",
                new BigDecimal("10.00"), BigDecimal.ZERO));

        assertThatThrownBy(() -> service.authorize(
                command, Map.of(), authentication, "TEST", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("manual_return_quantity_must_be_minus_one");

        verify(operationSecurity, never()).authorize(
                any(SaleOperationCode.class), any(), any(), any());
    }

    private SaleOperationSecurityService.ResolvedOperation direct(
            SaleOperationCode code) {
        return new SaleOperationSecurityService.ResolvedOperation(
                storeId,
                1L,
                code,
                SaleOperationCategory.TICKET,
                List.of(),
                List.of(),
                false,
                false,
                false);
    }

    private DocumentCommand command(DocumentLineCommand line) {
        return new DocumentCommand(
                UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 7, 30),
                UUID.randomUUID(),
                null,
                null,
                BigDecimal.ZERO,
                true,
                List.of(line));
    }

    private DocumentLineCommand line(
            BigDecimal quantity,
            String name,
            BigDecimal price,
            BigDecimal discount) {
        return new DocumentLineCommand(
                productId,
                quantity,
                "P1",
                name,
                null,
                price,
                discount,
                true,
                "IVA",
                new BigDecimal("21"));
    }

    private PaymentRequest integratedPaymentRequest(UUID operationId) {
        return new PaymentRequest(List.of(new PaymentRequest.Item(
                UUID.randomUUID(),
                new BigDecimal("10.00"),
                true,
                null,
                null,
                null,
                "AUTH",
                PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC,
                PaymentTerminalOperationStatus.APPROVED,
                "AUTH-1",
                UUID.randomUUID(),
                operationId,
                operationId)));
    }
}
