package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.inventory.StockSettingsService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.FiscalAddress;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.promotion.PromotionEngine;
import com.tpverp.backend.promotion.PromotionRepository;
import com.tpverp.backend.promotion.Promotion;
import com.tpverp.backend.promotion.PromotionCustomerSegment;
import com.tpverp.backend.promotion.PromotionScope;
import com.tpverp.backend.promotion.PromotionStatus;
import com.tpverp.backend.promotion.PromotionType;
import com.tpverp.backend.promotion.PromotionalCouponBenefitType;
import com.tpverp.backend.promotion.PromotionTargetRepository;
import com.tpverp.backend.promotion.PromotionalCouponService;
import com.tpverp.backend.promotion.AuthoritativePromotionPricing;
import com.tpverp.backend.promotion.PromotionCatalogGateway;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import com.tpverp.backend.terminal.StorePaymentConfiguration;
import com.tpverp.backend.terminal.StorePaymentConfigurationRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalPaymentConfiguration;
import com.tpverp.backend.terminal.TerminalPaymentConfigurationCommand;
import com.tpverp.backend.terminal.TerminalPaymentConfigurationRepository;
import com.tpverp.backend.terminal.TerminalType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    @Mock
    private CommercialDocumentRepository documentRepository;
    @Mock
    private DocumentCounterRepository counterRepository;
    @Mock
    private PaymentMethodRepository paymentMethodRepository;
    @Mock
    private DocumentRelationRepository relationRepository;
    @Mock
    private StockDocumentGateway stockGateway;
    @Mock
    private CurrentOrganization currentOrganization;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private DocumentFiscalIntegration fiscalIntegration;
    @Mock
    private VoucherService voucherService;
    @Mock
    private CurrentTerminal currentTerminal;
    @Mock
    private StorePaymentConfigurationRepository storePaymentConfigurations;
    @Mock
    private TerminalPaymentConfigurationRepository terminalPaymentConfigurations;
    @Mock
    private CashPaymentRecorder cashPaymentRecorder;
    @Mock
    private MemberLoyaltyService memberLoyaltyService;
    @Mock
    private SyncOutboxService syncOutbox;
@Mock
    private PromotionRepository promotionRepository;
    @Mock
    private PromotionTargetRepository promotionTargetRepository;
    @Mock
    private PromotionalCouponService promotionalCoupons;
    @Mock
    private AuthoritativePromotionPricing promotionPricing;
    @Mock
    private PromotionCatalogGateway promotionCatalog;
    @Mock
    private CustomerReceivablePaymentReservationRepository receivablePaymentReservations;
    @Mock
    private StockSettingsService stockSettings;
    @Mock
    private com.tpverp.backend.control.ControlAlertDetectionService controlAlerts;
    @Mock
    private DocumentOperationalEventRecorder operationalEvents;
    @Mock
    private TicketCancellationOperationRepository ticketCancellations;
    @Mock
    private SaleOperationSecurityService saleOperationSecurity;
    @Mock
    private SalesInvoiceRectificationRepository salesInvoiceRectificationRepository;

    private DocumentService service;
    private Store store;
    private UserAccount user;
    private UUID terminalId;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Store(
                new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var role = new Role(store, "ADMIN");
        user = new UserAccount(store, "ADMIN", "hash", role);
        terminalId = UUID.randomUUID();
        lenient().when(currentOrganization.currentStore()).thenReturn(store);
        lenient().when(currentOrganization.currentCompany())
                .thenReturn(store.getEmpresa());
        lenient().when(currentOrganization.currentUser(any())).thenReturn(user);
        lenient().when(currentTerminal.terminalId(any())).thenReturn(terminalId);
        lenient().when(saleOperationSecurity.authorize(
                        org.mockito.ArgumentMatchers.eq(
                                SaleOperationCode.CONVERT_TICKET_TO_INVOICE),
                        any(),
                        any(),
                        any()))
                .thenReturn(new Authorization(user, user, false));
        lenient().when(promotionRepository.findByEmpresaIdAndEstado(any(), any(PromotionStatus.class)))
                .thenReturn(List.of());
        lenient().when(promotionTargetRepository.findByPromocionIdIn(any())).thenReturn(List.of());
        lenient().when(memberLoyaltyService.applyLineBenefit(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(promotionPricing.customerContext(
                        any(), org.mockito.ArgumentMatchers.nullable(UUID.class)))
                .thenReturn(AuthoritativePromotionPricing.CustomerContext.anonymous());
        lenient().when(promotionPricing.matchesSegment(any(), any())).thenReturn(true);
        lenient().when(promotionPricing.priceLine(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        lenient().when(promotionCatalog.products(any(), any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(1);
            return ids.stream().collect(java.util.stream.Collectors.toMap(
                    id -> id,
                    id -> productSnapshot(product(id, DiscountType.NORMAL))));
        });
        lenient().when(productRepository.findAllByStoreIdAndIdIn(any(), any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(1);
            return ids.stream().map(id -> {
                Product product = org.mockito.Mockito.mock(Product.class);
                lenient().when(product.getId()).thenReturn(id);
                lenient().when(product.getStoreId()).thenReturn(store.getId());
                lenient().when(product.getProductType()).thenReturn(ProductType.UNIT);
                lenient().when(product.getDiscountType()).thenReturn(DiscountType.NORMAL);
                return product;
            }).toList();
        });
        service = new DocumentService(
                documentRepository,
                counterRepository,
                paymentMethodRepository,
                relationRepository,
                receivablePaymentReservations,
                stockGateway,
                currentOrganization,
                customerRepository,
                productRepository,
                fiscalIntegration,
                voucherService,
                currentTerminal,
                storePaymentConfigurations,
                terminalPaymentConfigurations,
                cashPaymentRecorder,
                memberLoyaltyService,
                syncOutbox,
                promotionRepository,
                promotionTargetRepository,
                new PromotionEngine(),
                promotionalCoupons,
                promotionPricing,
                promotionCatalog,
                stockSettings,
                controlAlerts,
                operationalEvents,
                ticketCancellations,
                saleOperationSecurity,
                Clock.fixed(NOW, ZoneOffset.UTC));
        service.setSalesInvoiceRectifications(salesInvoiceRectificationRepository);
    }

    @Test
    void fullSalesInvoiceReturnCreatesOperationalCancellationRectification() {
        var original = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.now(), user.getId(), BigDecimal.ZERO);
        original.setParties(UUID.randomUUID(), null, null);
        var sourceLine = new DocumentLine(
                original, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-FV", "Producto facturado", "VENTA",
                new BigDecimal("100.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21.00"));
        original.addLine(sourceLine);
        original.confirm("FV-001-26-000001", user.getId(), NOW, false);
        addFullCashPayment(original);
        original.updatePaymentStatus();
        var valuation = new TicketReturnValuationService.Valuation(
                new BigDecimal("100.00"), BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        when(documentRepository.findLockedRefundSource(original.getId(), store.getId()))
                .thenReturn(Optional.of(original));
        when(documentRepository.confirmedRefundedQuantity(sourceLine.getId()))
                .thenReturn(BigDecimal.ZERO);
        when(documentRepository.confirmedReturnAmount(original.getId()))
                .thenReturn(BigDecimal.ZERO);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(stockGateway.confirm(any())).thenReturn(false);

        var rectification = service.createApprovedReturn(
                UUID.randomUUID(), original.getId(), new BigDecimal("100.00"),
                List.of(new PaymentTerminalRefundLineSelection(
                        sourceLine.getId(), BigDecimal.ONE)),
                null, valuation, authentication());

        assertThat(rectification.getTipo())
                .isEqualTo(CommercialDocumentType.RECTIFICATIVA_VENTA);
        assertThat(rectification.getTotal()).isEqualByComparingTo("-100.00");
        verify(relationRepository).save(any(DocumentRelation.class));
        verify(salesInvoiceRectificationRepository).save(argThat(metadata ->
                metadata.getDocumentId().equals(rectification.getId())
                        && metadata.getOriginalDocumentId().equals(original.getId())
                        && metadata.getReason()
                                == SalesInvoiceRectificationReason.OPERATION_CANCELLATION
                        && metadata.isAffectsStock()));
        verify(fiscalIntegration).registerAlta(rectification, false);
        verify(fiscalIntegration, never()).registerTicketRectification(any(), any());
    }

    @Test
    void authenticatedUserOverridesClientSuppliedStoreAndUser() {
        when(currentOrganization.currentStore()).thenReturn(store);
        when(currentOrganization.currentUser(any())).thenReturn(user);
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        var created = service.createDeliveryNote(
                command(CommercialDocumentType.ALBARAN_VENTA),
                authentication());

        assertThat(created.getTiendaId()).isEqualTo(store.getId());
        assertThat(created.getStockUserId()).isEqualTo(user.getId());
        assertThat(created.getTerminalOrigenId()).isEqualTo(terminalId);
        verify(operationalEvents).record(
                created,
                DocumentOperationalEventType.CREADO,
                user.getId(),
                terminalId,
                created.getCreadoEn());
    }
    @Test
    void salesInvoiceCannotBeConfirmedWithoutCustomer() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(currentOrganization.currentUser(any())).thenReturn(user);

        assertThatThrownBy(() -> service.confirm(invoice.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cliente");
    }

    @Test
    void salesInvoiceRequiresCompleteCustomerFiscalData() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        var customer = new Customer(
                store.getEmpresa(), "Cliente", DocumentType.NIF, "12345678Z",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        invoice.setParties(customer.getId(), null, null);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(customerRepository.findByIdAndCompanyId(
                customer.getId(), store.getEmpresa().getId())).thenReturn(Optional.of(customer));
        when(currentOrganization.currentUser(any())).thenReturn(user);

        assertThatThrownBy(() -> service.confirm(invoice.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fiscales");
    }

    @Test
    void confirmsDeliveryNoteWithAnnualNumber() {
        var document = draft(CommercialDocumentType.ALBARAN_VENTA);
        var printSnapshots = org.mockito.Mockito.mock(
                InvoicePresentationSnapshotFactory.class);
        service.setInvoicePrintSnapshots(printSnapshots);
        when(printSnapshots.create(
                com.tpverp.backend.document.template.DocumentTemplateType.ALBARAN_VENTA))
                .thenReturn("{\"template\":\"ALBARAN_A4\"}");
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                document.getTiendaId(), "AV", "2026")).thenReturn(Optional.empty());
        when(stockGateway.confirm(document)).thenReturn(true);
        when(currentOrganization.currentUser(any())).thenReturn(user);

        var confirmed = service.confirm(document.getId(), authentication());

        assertThat(confirmed.getNumero()).isEqualTo("AV-001-26-000001");
        assertThat(confirmed.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
        assertThat(confirmed.isOrigenStock()).isTrue();
        assertThat(confirmed.getTerminalOrigenId()).isEqualTo(terminalId);
        assertThat(confirmed.getInvoicePrintSnapshot())
                .isEqualTo("{\"template\":\"ALBARAN_A4\"}");
        verify(operationalEvents).record(
                confirmed,
                DocumentOperationalEventType.CONFIRMADO,
                user.getId(),
                terminalId,
                NOW);
    }

    @Test
    void confirmedDeliveryNoteEnqueuesSyncEvent() {
        var document = draft(CommercialDocumentType.ALBARAN_VENTA);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                document.getTiendaId(), "AV", "2026")).thenReturn(Optional.empty());
        when(stockGateway.confirm(document)).thenReturn(true);

        var confirmed = service.confirm(document.getId(), authentication());

        var command = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(syncOutbox).enqueue(command.capture());
        assertThat(command.getValue().entityId()).isEqualTo(confirmed.getId());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CONFIRMAR);
        assertThat(command.getValue().payload())
                .containsEntry("tipo", "ALBARAN_VENTA")
                .containsEntry("numero", confirmed.getNumero());
    }

    @Test
    void confirmationKeepsCreationTerminalAndRecordsTheConfirmingTerminal() {
        var document = draft(CommercialDocumentType.ALBARAN_VENTA);
        var creationTerminal = UUID.randomUUID();
        document.assignOriginTerminal(creationTerminal);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                document.getTiendaId(), "AV", "2026")).thenReturn(Optional.empty());

        var confirmed = service.confirm(document.getId(), authentication());

        assertThat(confirmed.getTerminalOrigenId()).isEqualTo(creationTerminal);
        verify(operationalEvents).record(
                confirmed,
                DocumentOperationalEventType.CONFIRMADO,
                user.getId(),
                terminalId,
                NOW);
    }

    @Test
    void ticketRequiresPaymentsToMatchTotal() {
        when(currentOrganization.currentStore()).thenReturn(store);
        when(currentOrganization.currentUser(any())).thenReturn(user);

        assertThatThrownBy(() -> service.createTicket(
                command(CommercialDocumentType.TICKET),
                List.of(new PaymentCommand(
                        UUID.randomUUID(), new BigDecimal("9.99"), true, null, null)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");

        verify(documentRepository, never()).save(any());
    }

    @Test
    void f11QuotePersistedSalePaymentAndFiscalSnapshotShareTheSameReducedTaxTotals() {
        var productId = UUID.randomUUID();
        var command = command(
                CommercialDocumentType.TICKET,
                List.of(line(productId, "P-F11", "Producto F11", new BigDecimal("40.00"))));
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockGateway.confirm(any())).thenReturn(false);

        var quote = service.quoteTicket(
                command, null, new BigDecimal("5.00"), authentication());
        var sale = service.createTicket(
                command,
                List.of(new PaymentCommand(
                        cash.getId(), new BigDecimal("35.00"), true,
                        new BigDecimal("35.00"), BigDecimal.ZERO)),
                null,
                new BigDecimal("5.00"),
                authentication());
        var fiscalSnapshot = new com.tpverp.backend.verifactu.FiscalSnapshotFactory()
                .create(
                        sale,
                        "B12345674",
                        com.tpverp.backend.verifactu.FiscalRecordOperation.ALTA,
                        com.tpverp.backend.verifactu.FiscalDocumentType.F2,
                        null);

        assertThat(quote.getTotal()).isEqualByComparingTo("35.00");
        assertThat(quote.getBaseTotal()).isEqualByComparingTo("28.93");
        assertThat(quote.getImpuestoTotal()).isEqualByComparingTo("6.07");
        assertThat(sale.getTotal()).isEqualByComparingTo(quote.getTotal());
        assertThat(sale.getBaseTotal()).isEqualByComparingTo(quote.getBaseTotal());
        assertThat(sale.getImpuestoTotal()).isEqualByComparingTo(quote.getImpuestoTotal());
        assertThat(sale.getPaidTotal()).isEqualByComparingTo(sale.getTotal());
        assertThat(sale.getBaseTotal().add(sale.getImpuestoTotal()))
                .isEqualByComparingTo(sale.getTotal());
        assertThat(fiscalSnapshot)
                .containsEntry("baseTotal", sale.getBaseTotal())
                .containsEntry("impuestoTotal", sale.getImpuestoTotal())
                .containsEntry("total", sale.getTotal());
        assertThat(TicketPrintView.from(sale).checkoutDiscountTotal())
                .isEqualByComparingTo("5.00");
    }

    @Test
    void unitProductRejectsDecimalQuantity() {
        var command = new DocumentCommand(
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 8),
                null,
                null,
                null,
                BigDecimal.ZERO,
                false,
                List.of(new DocumentLineCommand(
                        UUID.randomUUID(), new BigDecimal("1.500"), "P-1", "Producto", "VENTA",
                        BigDecimal.TEN, BigDecimal.ZERO, true, "IVA", new BigDecimal("21"))));

        assertThatThrownBy(() -> service.createTicket(command, List.of(), authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.unit_quantity_must_be_integer");

        verify(documentRepository, never()).save(any());
    }

    @Test
    void unitProductReturnAlsoRejectsDecimalQuantity() {
        var productId = UUID.randomUUID();
        var command = new DocumentCommand(
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 8),
                null,
                null,
                null,
                BigDecimal.ZERO,
                false,
                List.of(new DocumentLineCommand(
                        productId, new BigDecimal("-1.500"), "P-1", "Producto", "VENTA",
                        BigDecimal.TEN, BigDecimal.ZERO, true, "IVA", new BigDecimal("21"),
                        DocumentLineType.PRODUCT, null, null, null, List.of(), false, false,
                        TicketReturnService.ReturnSourceType.TICKET, "T-1", UUID.randomUUID(),
                        UUID.randomUUID(), null)));

        assertThatThrownBy(() -> service.createTicket(command, List.of(), authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.unit_quantity_must_be_integer");

        verify(documentRepository, never()).save(any());
    }

    @Test
    void ticketCreationLetsCashRecorderRequireOpenSessionWhenNeeded() {
        when(currentOrganization.currentStore()).thenReturn(store);
        when(currentOrganization.currentUser(any())).thenReturn(user);
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("No hay una sesion de caja abierta"))
                .when(cashPaymentRecorder).recordDocumentPayments(any(), any());

        assertThatThrownBy(() -> service.createTicket(
                command(CommercialDocumentType.TICKET),
                List.of(new PaymentCommand(
                        cash.getId(), new BigDecimal("10.00"), true, null, null)),
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sesion de caja abierta");

        verify(cashPaymentRecorder).recordDocumentPayments(any(), any());
    }

    @Test
    void createsDirectTicketWithDailyNumberAndMixedPayments() {
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        var card = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentOrganization.currentStore()).thenReturn(store);
        when(currentOrganization.currentUser(any())).thenReturn(user);

        var ticket = service.createTicket(
                command(CommercialDocumentType.TICKET),
                List.of(
                        new PaymentCommand(cash.getId(), new BigDecimal("5.00"), true,
                                new BigDecimal("5.00"), BigDecimal.ZERO),
                        new PaymentCommand(card.getId(), new BigDecimal("5.00"), false, null, null)),
                authentication());

        assertThat(ticket.getNumero()).isEqualTo("001-260608-00001");
        assertThat(ticket.getEstado()).isEqualTo(DocumentStatus.CONFIRMADO);
        assertThat(ticket.getPagos()).hasSize(2);
        assertThat(ticket.isOrigenStock()).isFalse();
        verify(cashPaymentRecorder).recordDocumentPayments(terminalId, ticket);
        verify(memberLoyaltyService).recordPaidSale(same(ticket), accrualOf("10.00"));
    }

    @Test
    void approvedCardRefundReplaysDurableDocumentWithoutRepeatingStockOrFiscalEffects() {
        var operationId=UUID.randomUUID();
        var existing=new CommercialDocument(store.getId(),UUID.randomUUID(),CommercialDocumentType.TICKET,
                LocalDate.now(),user.getId(),BigDecimal.ZERO);
        existing.identifyPaymentTerminalRefund(operationId);
        when(documentRepository.findByPaymentTerminalRefundOperationId(operationId)).thenReturn(Optional.of(existing));

        assertThat(service.createApprovedCardRefund(operationId,UUID.randomUUID(),BigDecimal.TEN,
                UsernamePasswordAuthenticationToken.authenticated(user,"x",List.of()))).isSameAs(existing);
        verify(stockGateway,never()).confirm(any());
        verify(fiscalIntegration,never()).registerAlta(any(),any(Boolean.class));
        verify(relationRepository,never()).save(any());
    }

    @Test
    void partialCardRefundRequiresExactAvailableFiscalLineQuantitiesBeforeSendingMoney() {
        var original = new CommercialDocument(store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.now(), user.getId(), BigDecimal.ZERO);
        var line = new DocumentLine(original, UUID.randomUUID(), 1, new BigDecimal("2.000"), "P-1", "Producto",
                "VENTA", new BigDecimal("5.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO);
        original.addLine(line);
        original.confirm("001-260608-00001", user.getId(), NOW, false);
        when(documentRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(documentRepository.confirmedRefundedQuantity(line.getId())).thenReturn(new BigDecimal("0.000"));
        var oneUnit = List.of(new PaymentTerminalRefundLineSelection(line.getId(), BigDecimal.ONE));

        service.validateApprovedCardRefund(original.getId(), new BigDecimal("5.00"), oneUnit);
        assertThatThrownBy(() -> service.validateApprovedCardRefund(
                original.getId(),
                new BigDecimal("2.50"),
                List.of(new PaymentTerminalRefundLineSelection(
                        line.getId(), new BigDecimal("0.500")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.unit_quantity_must_be_integer");
        assertThatThrownBy(() -> service.validateApprovedCardRefund(original.getId(), new BigDecimal("4.99"), oneUnit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("importe");
        assertThatThrownBy(() -> service.validateApprovedCardRefund(original.getId(), new BigDecimal("15.00"),
                List.of(new PaymentTerminalRefundLineSelection(line.getId(), new BigDecimal("3.000")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("saldo reembolsable");
    }

    @Test
    void historicalReturnCreatesBalancedR5AdjustmentAndReversesLoyaltyOnConfirmation() {
        var original = new CommercialDocument(
                store.getId(),
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.now(),
                user.getId(),
                BigDecimal.ZERO);
        var productLine = new DocumentLine(
                original,
                UUID.randomUUID(),
                1,
                new BigDecimal("3.000"),
                "P-3X2",
                "Producto 3x2",
                "VENTA",
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                true,
                "IVA",
                new BigDecimal("21.00"));
        original.addLine(productLine);
        original.addLine(DocumentLine.special(
                original,
                2,
                "PROMOCION 3X2",
                new BigDecimal("-10.00"),
                true,
                "IVA",
                new BigDecimal("21.00"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null));
        original.confirm("001-260608-00001", user.getId(), NOW, false);
        var requestId = UUID.randomUUID();
        var selected = List.of(new PaymentTerminalRefundLineSelection(
                productLine.getId(), BigDecimal.ONE));
        var valuation = new TicketReturnValuationService.Valuation(
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("20.00"),
                List.of(new TicketReturnValuationService.TaxAdjustment(
                        true,
                        "IVA",
                        new BigDecimal("21.00"),
                        new BigDecimal("10.00"))));
        when(documentRepository.findLockedRefundSource(original.getId(), store.getId()))
                .thenReturn(Optional.of(original));
        when(documentRepository.confirmedRefundedQuantity(productLine.getId()))
                .thenReturn(new BigDecimal("0.000"));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(stockGateway.confirm(any())).thenReturn(false);

        var refund = service.createApprovedReturn(
                requestId,
                original.getId(),
                BigDecimal.ZERO,
                selected,
                null,
                valuation,
                authentication());

        assertThat(refund.getTotal()).isEqualByComparingTo("0.00");
        assertThat(refund.getLineas()).extracting(DocumentLine::getLineType)
                .containsExactly(DocumentLineType.PRODUCT, DocumentLineType.RETURN_ADJUSTMENT);
        assertThat(refund.getLineas().get(0).getTotal()).isEqualByComparingTo("-10.00");
        assertThat(refund.getLineas().get(1).getTotal()).isEqualByComparingTo("10.00");
        verify(fiscalIntegration).registerTicketRectification(refund, original);
        verify(memberLoyaltyService).reverseConfirmedReturn(
                original, refund, new BigDecimal("0.00"), new BigDecimal("0.00"));
    }

    @Test
    void serializedReturnOnlyOffersSerialNumbersNotPreviouslyReturned() {
        var original = new CommercialDocument(store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.now(), user.getId(), BigDecimal.ZERO);
        var productId = UUID.randomUUID();
        var line = new DocumentLine(original, productId, 1, new BigDecimal("2.000"),
                "P-SN", "Producto con serie", "VENTA", new BigDecimal("5.00"),
                BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO);
        line.assignSerialNumbers(List.of("SN-001", "SN-002"));
        original.addLine(line);
        original.confirm("001-260608-00002", user.getId(), NOW, false);
        when(documentRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(documentRepository.confirmedRefundedQuantity(line.getId()))
                .thenReturn(new BigDecimal("1.000"));
        when(documentRepository.confirmedRefundedSerialNumbers(line.getId()))
                .thenReturn(List.of("sn-001"));
        var product = org.mockito.Mockito.mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getProductType()).thenReturn(ProductType.UNIT);
        when(product.getBarcode()).thenReturn("8430000000010");
        when(product.getBarcode2()).thenReturn("20000001");
        doReturn(List.of(product)).when(productRepository)
                .findAllByStoreIdAndIdIn(any(), any());

        assertThat(service.cardRefundLineOptions(original.getId())).singleElement().satisfies(option -> {
            assertThat(option.refundableQuantity()).isEqualByComparingTo("1.000");
            assertThat(option.refundableSerialNumbers()).containsExactly("SN-002");
            assertThat(option.barcode()).isEqualTo("8430000000010");
            assertThat(option.barcode2()).isEqualTo("20000001");
        });

        service.validateApprovedCardRefund(
                original.getId(),
                new BigDecimal("5.00"),
                List.of(new PaymentTerminalRefundLineSelection(
                        line.getId(), BigDecimal.ONE, List.of("SN-002"))));
        assertThatThrownBy(() -> service.validateApprovedCardRefund(
                original.getId(),
                new BigDecimal("5.00"),
                List.of(new PaymentTerminalRefundLineSelection(
                        line.getId(), BigDecimal.ONE, List.of("SN-001")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no esta disponible");
    }

    @Test
    void documentLineRequiresOneUniqueSerialNumberPerWholeUnit() {
        var document = new CommercialDocument(store.getId(), UUID.randomUUID(),
                CommercialDocumentType.TICKET, LocalDate.now(), user.getId(), BigDecimal.ZERO);
        var line = new DocumentLine(document, UUID.randomUUID(), 1, new BigDecimal("2.000"),
                "P-SN", "Producto con serie", "VENTA", BigDecimal.ONE,
                BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO);

        line.assignSerialNumbers(List.of("SN-A", "SN-B"));

        assertThat(line.getSerialNumbers()).containsExactly("SN-A", "SN-B");
        assertThatThrownBy(() -> line.assignSerialNumbers(List.of("SN-A")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cada unidad");
        assertThatThrownBy(() -> line.assignSerialNumbers(List.of("SN-A", "sn-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unicos");
    }

    @Test
    void approvedCardSnapshotKeepsAuthorizedFiscalLinesWithoutRepricingLoyaltyOrPromotions() {
        var card = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));
        var terminalConfiguration=org.mockito.Mockito.mock(TerminalPaymentConfiguration.class);
        when(terminalConfiguration.getCardMode()).thenReturn(PaymentCardMode.INTEGRATED);
        when(terminalConfiguration.isEnabled()).thenReturn(true);
        when(terminalConfiguration.getProvider()).thenReturn(PaymentTerminalProvider.GLOBAL_PAYMENTS);
        when(terminalPaymentConfigurations.findByTerminalId(terminalId)).thenReturn(Optional.of(terminalConfiguration));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any())).thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var frozen = new ApprovedCardTicketSnapshot(
                store.getId(), UUID.randomUUID(), LocalDate.of(2026, 6, 8), null, UUID.randomUUID(),
                BigDecimal.ZERO, new BigDecimal("8.26"), new BigDecimal("1.74"),
                new BigDecimal("10.00"), List.of(new DocumentLineCommand(
                        UUID.randomUUID(), BigDecimal.ONE, "P-OLD", "Precio autorizado",
                        "SOCIO", new BigDecimal("10.00"), BigDecimal.ZERO,
                        true, "IVA", new BigDecimal("21"))));

        var ticket = service.createApprovedCardTicketFromSnapshot(
                frozen, List.of(new PaymentCommand(
                        card.getId(), new BigDecimal("10.00"), true, null, null,
                        null, "REF", PaymentCardMode.INTEGRATED,
                        PaymentTerminalProvider.GLOBAL_PAYMENTS,
                        PaymentTerminalOperationStatus.APPROVED, "AUTH", terminalId)),
                authentication());

        assertThat(ticket.getTotal()).isEqualByComparingTo("10.00");
        assertThat(ticket.getBaseTotal()).isEqualByComparingTo("8.26");
        assertThat(ticket.getImpuestoTotal()).isEqualByComparingTo("1.74");
        assertThat(ticket.getLineas().getFirst().getPrecioUnitario()).isEqualByComparingTo("10.00");
        assertThat(ticket.getLineas().getFirst().getTarifa()).isEqualTo("SOCIO");
        assertThat(ticket.getPagos().getFirst().getPaymentTerminalProvider())
                .isEqualTo(PaymentTerminalProvider.GLOBAL_PAYMENTS);
        verify(productRepository, never()).findById(any());
        verify(memberLoyaltyService, never()).applyLineBenefit(any(), any(), any());
        verify(memberLoyaltyService).recordPaidSale(same(ticket), accrualOf("10.00"));
        verifyNoInteractions(promotionRepository);
        verify(promotionalCoupons, never()).generateAfterTicketConfirmation(any());
    }

    @Test
    void positiveExchangePersistsOnlySaleLinesAndLinksItsInternalRectification() {
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        var compensation = new PaymentMethod(
                store.getEmpresa().getId(),
                PaymentMethodService.EXCHANGE_COMPENSATION_METHOD,
                true);
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(paymentMethodRepository.findById(compensation.getId()))
                .thenReturn(Optional.of(compensation));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var sourceLineId = UUID.randomUUID();
        var sourceTicketId = UUID.randomUUID();
        var returnedProductId = UUID.randomUUID();
        var soldProductId = UUID.randomUUID();
        var refund = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 5), user.getId(), BigDecimal.ZERO);
        refund.addLine(new DocumentLineCommand(
                returnedProductId, new BigDecimal("-1"), "RETURN", "Devuelto", "VENTA",
                new BigDecimal("100.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21")).toEntity(refund));
        refund.confirm("001-260805-00001", user.getId(), NOW, false);

        var returnLine = new DocumentLineCommand(
                returnedProductId, new BigDecimal("-1"), "RETURN", "Devuelto", "VENTA",
                new BigDecimal("100.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21"), DocumentLineType.PRODUCT, null, null, null,
                List.of(), false, false, TicketReturnService.ReturnSourceType.TICKET,
                "001-260804-00001", sourceTicketId, sourceLineId, null);
        var saleLine = new DocumentLineCommand(
                soldProductId, BigDecimal.ONE, "SALE", "Comprado", "VENTA",
                new BigDecimal("101.10"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21"));
        var mixed = new ApprovedCardTicketSnapshot(
                store.getId(), UUID.randomUUID(), LocalDate.of(2026, 8, 5), null,
                cash.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1.10"), List.of(returnLine, saleLine));

        var sale = service.createApprovedExchangeSaleFromSnapshot(
                mixed,
                List.of(
                        new PaymentCommand(cash.getId(), new BigDecimal("1.10"), true,
                                new BigDecimal("1.10"), BigDecimal.ZERO),
                        new PaymentCommand(compensation.getId(), new BigDecimal("100.00"),
                                false, null, null, null, refund.getNumero())),
                refund,
                authentication());

        assertThat(sale.getTotal()).isEqualByComparingTo("101.10");
        assertThat(sale.getLineas()).singleElement()
                .satisfies(line -> assertThat(line.getProductoId()).isEqualTo(soldProductId));
        assertThat(sale.getPagos()).hasSize(2);
        verify(relationRepository).save(any(DocumentRelation.class));
    }

    @Test
    void approvedSnapshotPersistsCashOnlyPayment() {
        var cash=new PaymentMethod(store.getEmpresa().getId(),"EFECTIVO",true);
        var ticket=createFrozenTicket(List.of(new PaymentCommand(cash.getId(),new BigDecimal("10.00"),true,
                new BigDecimal("20.00"),new BigDecimal("10.00"))),cash);
        assertThat(ticket.getPagos()).singleElement().satisfies(payment->{
            assertThat(payment.getMetodoPago().getNombre()).isEqualTo("EFECTIVO");
            assertThat(payment.getImporte()).isEqualByComparingTo("10.00");
            assertThat(payment.getEntregado()).isEqualByComparingTo("20.00");
            assertThat(payment.getCambio()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void approvedSnapshotPersistsManualCardPayment() {
        var card=new PaymentMethod(store.getEmpresa().getId(),"TARJETA",true);
        var ticket=createFrozenTicket(List.of(new PaymentCommand(card.getId(),new BigDecimal("10.00"),true,
                null,null,null,"MANUAL-REF",PaymentCardMode.MANUAL,null,null,null,null)),card);
        assertThat(ticket.getPagos()).singleElement().satisfies(payment->{
            assertThat(payment.getCardMode()).isEqualTo(PaymentCardMode.MANUAL);
            assertThat(payment.getReferencia()).isEqualTo("MANUAL-REF");
            assertThat(payment.getImporte()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void approvedSnapshotPersistsExactMixedSplitPayments() {
        var cash=new PaymentMethod(store.getEmpresa().getId(),"EFECTIVO",true);
        var card=new PaymentMethod(store.getEmpresa().getId(),"TARJETA",true);
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));
        var ticket=createFrozenTicket(List.of(
                new PaymentCommand(cash.getId(),new BigDecimal("3.25"),true,new BigDecimal("5.00"),new BigDecimal("1.75")),
                new PaymentCommand(card.getId(),new BigDecimal("6.75"),false,null,null,null,"MANUAL-2",PaymentCardMode.MANUAL,null,null,null,null)));
        assertThat(ticket.getPagos()).extracting(DocumentPayment::getImporte)
                .containsExactly(new BigDecimal("3.25"),new BigDecimal("6.75"));
        assertThat(ticket.getPagos().stream().map(DocumentPayment::getImporte).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo(ticket.getTotal());
    }

    @Test
    void ticketCreationRejectsClientSuppliedPromotionLine() {
        var productId = UUID.randomUUID();
        var promotionId = UUID.randomUUID();

        assertThatThrownBy(() -> service.createTicket(
                command(CommercialDocumentType.TICKET, List.of(
                        line(productId, "AGUA", "Agua", new BigDecimal("3.00")),
                        promotionCommand(promotionId, null, new BigDecimal("-1.00")))),
                List.of(), authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backend");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void memberBalancePaymentDoesNotAccrueNewLoyalty() {
        var balance = new PaymentMethod(store.getEmpresa().getId(), "SALDO_MIEMBRO", true);
        var card = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        when(paymentMethodRepository.findById(balance.getId())).thenReturn(Optional.of(balance));
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(memberLoyaltyService.consumeBalanceForPayment(
                any(), org.mockito.ArgumentMatchers.eq(new BigDecimal("4.00"))))
                .thenReturn(new BigDecimal("4.00"));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var ticket = service.createTicket(
                command(CommercialDocumentType.TICKET),
                List.of(
                        new PaymentCommand(balance.getId(), new BigDecimal("4.00"), true, null, null),
                        new PaymentCommand(card.getId(), new BigDecimal("6.00"), false, null, null)),
                authentication());

        assertThat(ticket.getPagos()).hasSize(2);
        verify(memberLoyaltyService).consumeBalanceForPayment(ticket, new BigDecimal("4.00"));
        verify(memberLoyaltyService).recordPaidSale(same(ticket), accrualOf("6.00"));
    }

    @Test
    void loyaltyAccruesOnlyEligibleProductLines() {
        var paidMethod = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        var eligibleId = UUID.randomUUID();
        var excludedId = UUID.randomUUID();
        var eligible = org.mockito.Mockito.mock(Product.class);
        var excluded = org.mockito.Mockito.mock(Product.class);
        when(eligible.getId()).thenReturn(eligibleId);
        when(excluded.getId()).thenReturn(excludedId);
        when(eligible.getProductType()).thenReturn(ProductType.UNIT);
        when(eligible.getDiscountType()).thenReturn(DiscountType.NORMAL);
        when(eligible.isActive()).thenReturn(true);
        when(excluded.getProductType()).thenReturn(ProductType.UNIT);
        when(excluded.getDiscountType()).thenReturn(DiscountType.NONE);
        when(excluded.isActive()).thenReturn(true);
        doReturn(Map.of(
                eligibleId, productSnapshot(eligible),
                excludedId, productSnapshot(excluded)))
                .when(promotionCatalog).products(any(), any());
        doReturn(List.of(eligible, excluded))
                .when(productRepository).findAllByStoreIdAndIdIn(any(), any());
        when(paymentMethodRepository.findById(paidMethod.getId())).thenReturn(Optional.of(paidMethod));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var ticket = service.createTicket(
                command(CommercialDocumentType.TICKET, List.of(
                        line(eligibleId, "P-1", "Producto", new BigDecimal("10.00")),
                        line(excludedId, "P-2", "Excluido", new BigDecimal("30.00")))),
                List.of(new PaymentCommand(paidMethod.getId(), new BigDecimal("40.00"), true, null, null)),
                authentication());

        verify(memberLoyaltyService).recordPaidSale(same(ticket), accrualOf("10.00"));
    }

    @Test
    void confirmedTicketEnqueuesSyncEvent() {
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var ticket = service.createTicket(
                command(CommercialDocumentType.TICKET),
                List.of(new PaymentCommand(
                        cash.getId(), new BigDecimal("10.00"), true, null, null)),
                authentication());

        var command = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(syncOutbox).enqueue(command.capture());
        assertThat(command.getValue().companyId()).isEqualTo(store.getEmpresa().getId());
        assertThat(command.getValue().storeId()).isEqualTo(store.getId());
        assertThat(command.getValue().terminalId()).isEqualTo(terminalId);
        assertThat(command.getValue().entityType()).isEqualTo("DOCUMENTO");
        assertThat(command.getValue().entityId()).isEqualTo(ticket.getId());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CONFIRMAR);
        assertThat(command.getValue().payload())
                .containsEntry("tipo", "TICKET")
                .containsEntry("numero", ticket.getNumero())
                .containsEntry("fecha", "2026-06-08")
                .containsEntry("clienteId", null)
                .containsEntry("proveedorId", null)
                .containsEntry("almacenId", ticket.getAlmacenId().toString())
                .containsEntry("descuentoGlobal", "0.00")
                .containsEntry("subtotal", "8.26")
                .containsEntry("impuestos", "1.74");
        assertThat(command.getValue().payload().get("lineas"))
                .asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("productoId", ticket.getLineas().getFirst().getProductoId().toString())
                .containsEntry("codigo", "P-1")
                .containsEntry("nombre", "Producto")
                .containsEntry("cantidad", "1.000")
                .containsEntry("precioUnitario", "10.00")
                .containsEntry("descuento", "0.00")
                .containsEntry("impuestosIncluidos", true)
                .containsEntry("regimenImpuesto", "IVA")
                .containsEntry("porcentajeImpuesto", "21.00")
                .containsEntry("base", "8.26")
                .containsEntry("impuesto", "1.74")
                .containsEntry("total", "10.00");
        assertThat(command.getValue().payload().get("pagos"))
                .asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("metodoPagoId", cash.getId().toString())
                .containsEntry("metodoPago", "EFECTIVO")
                .containsEntry("importe", "10.00")
                .containsEntry("principal", true);
    }

    @Test
    void ticketPaidWithVoucherConsumesAndStoresVoucherCode() {
        var voucherMethod = new PaymentMethod(store.getEmpresa().getId(), "VALE", true);
        when(paymentMethodRepository.findById(voucherMethod.getId()))
                .thenReturn(Optional.of(voucherMethod));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(voucherService.consume(any(), any(), any())).thenAnswer(invocation -> {
            CommercialDocument purchaseTicket = invocation.getArgument(2);
            assertThat(purchaseTicket.getNumero()).isEqualTo("001-260608-00001");
            return new VoucherConsumptionResult(null, invocation.getArgument(1), Optional.empty());
        });

        var ticket = service.createTicket(
                command(CommercialDocumentType.TICKET),
                List.of(new PaymentCommand(
                        voucherMethod.getId(), new BigDecimal("10.00"), true,
                        null, null, "VABC123")),
                authentication());

        assertThat(ticket.getPagos().getFirst().getVoucherCode()).isEqualTo("VABC123");
        verify(voucherService).consume("VABC123", new BigDecimal("10.00"), ticket);
    }

    @Test
    void negativeTicketIssuesVoucherAutomatically() {
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var ticket = service.createTicket(
                negativeTicketCommand(),
                List.of(),
                authentication());

        assertThat(ticket.getTotal()).isEqualByComparingTo("-10.00");
        assertThat(ticket.getPagos())
                .as("DocumentPayment rejects negative amounts, so negative tickets issue a voucher instead of recording a DEVOLUCION_EFECTIVO payment row.")
                .isEmpty();
        var order = inOrder(documentRepository, voucherService, fiscalIntegration);
        order.verify(documentRepository).save(ticket);
        order.verify(voucherService).issueFromNegativeTicket(ticket);
        order.verify(fiscalIntegration).registerAlta(ticket, false);
    }

    @Test
    void cancelsTicketAndReversesAppliedStock() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, true);
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(false);
        when(documentRepository.save(ticket)).thenReturn(ticket);
        when(stockGateway.cancel(ticket)).thenReturn(true);
        when(currentOrganization.currentUser(any())).thenReturn(user);

        var cancelled = service.cancelTicket(ticket.getId(), authentication(), "ERROR");

        assertThat(cancelled.getEstado()).isEqualTo(DocumentStatus.ANULADO);
        assertThat(cancelled.getMotivoAnulacion()).isEqualTo("ERROR");
        verify(stockGateway).cancel(ticket);
    }

    @Test
    void cancelledTicketEnqueuesSyncEvent() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(false);
        when(documentRepository.save(ticket)).thenReturn(ticket);

        var cancelled = service.cancelTicket(ticket.getId(), authentication(), "ERROR");

        var command = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(syncOutbox).enqueue(command.capture());
        assertThat(command.getValue().entityId()).isEqualTo(cancelled.getId());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.ANULAR);
        assertThat(command.getValue().payload())
                .containsEntry("tipo", "TICKET")
                .containsEntry("estado", "ANULADO");
    }

    @Test
    void ticketWithInvoiceCannotBeCancelled() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(true);

        assertThatThrownBy(() -> service.cancelTicket(
                ticket.getId(), authentication(), "ERROR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("facturado");

        verify(documentRepository, never()).save(any());
        verify(stockGateway, never()).cancel(any());
    }

    @Test
    void ticketWithPreviousReturnsUsesSpecificCancellationBlock() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findByIdAndTiendaId(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(documentRepository.findByIdAndTiendaIdWithPayments(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(false);
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.RECTIFICA)).thenReturn(true);

        assertThatThrownBy(() -> service.validateTicketCancellation(ticket.getId()))
                .isInstanceOf(TicketHasPreviousReturnsException.class)
                .hasMessage(TicketHasPreviousReturnsException.MESSAGE_KEY);

        verify(documentRepository, never()).save(any());
        verify(stockGateway, never()).cancel(any());
    }

    @Test
    void manualCardCancellationDoesNotRequestReferenceWhenMethodDoesNotRequireIt() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        var card = new PaymentMethod(
                store.getEmpresa().getId(), "TARJETA", true, false, false);
        ticket.addPayment(new DocumentPayment(
                ticket, card, 1, ticket.getTotal(), true,
                null, null, null, null, NOW,
                PaymentCardMode.MANUAL, null, null, null, null));
        when(documentRepository.findByIdAndTiendaId(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(documentRepository.findByIdAndTiendaIdWithPayments(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));

        var validation = service.validateTicketCancellation(ticket.getId());

        assertThat(validation.manualReferences()).isEmpty();
    }

    @Test
    void manualCardCancellationRequestsReferenceWhenMethodRequiresIt() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        var card = new PaymentMethod(
                store.getEmpresa().getId(), "TARJETA", true, true, false);
        ticket.addPayment(new DocumentPayment(
                ticket, card, 1, ticket.getTotal(), true,
                null, null, null, null, NOW,
                PaymentCardMode.MANUAL, null, null, null, null));
        when(documentRepository.findByIdAndTiendaId(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(documentRepository.findByIdAndTiendaIdWithPayments(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));

        var validation = service.validateTicketCancellation(ticket.getId());

        assertThat(validation.manualReferences())
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.paymentId()).isEqualTo(
                            ticket.getPagos().getFirst().getId());
                    assertThat(reference.paymentMethod()).isEqualTo("TARJETA");
                });
    }

    @Test
    void ticketWithVoucherImpactCannotBeCancelled() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(false);
        when(voucherService.hasVoucherImpact(ticket)).thenReturn(true);

        assertThatThrownBy(() -> service.cancelTicket(
                ticket.getId(), authentication(), "ERROR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vale");

        verify(documentRepository, never()).save(any());
        verify(stockGateway, never()).cancel(any());
    }

    @Test
    void convertsConfirmedPaidTicketToSettledInvoiceWithoutDuplicatingPayments() {
        var ticket = draft(CommercialDocumentType.TICKET);
        addFullCashPayment(ticket);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, true);
        var customer = completeCustomer();
        when(documentRepository.findLockedDocument(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(relationRepository.findDocumentIdByOriginIdAndType(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(Optional.empty());
        when(customerRepository.findByIdAndCompanyId(
                customer.getId(), store.getEmpresa().getId())).thenReturn(Optional.of(customer));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                store.getId(), "FV", "2026")).thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentOrganization.currentUser(any())).thenReturn(user);

        var invoice = service.convertTicketToInvoice(
                ticket.getId(),
                customer.getId(),
                "SUPERVISOR",
                "secret",
                authentication());

        assertThat(invoice.getTipo()).isEqualTo(CommercialDocumentType.FACTURA_VENTA);
        assertThat(invoice.getEstado()).isEqualTo(DocumentStatus.PAGADO);
        assertThat(invoice.getPaidTotal()).isEqualByComparingTo(invoice.getTotal());
        assertThat(invoice.getPendingTotal()).isZero();
        assertThat(invoice.getPagos()).isEmpty();
        assertThat(invoice.isSettledByOrigin()).isTrue();
        assertThat(invoice.getNumero()).isEqualTo("FV-001-26-000001");
        assertThat(invoice.getNumTicket()).isEqualTo("001-260608-00001");
        assertThat(invoice.getLineas()).hasSize(ticket.getLineas().size());
        verify(stockGateway, never()).confirm(invoice);
        verify(relationRepository).save(any(DocumentRelation.class));
        verify(fiscalIntegration).registerInvoiceFromTicket(invoice, ticket);
        verify(saleOperationSecurity).authorize(
                org.mockito.ArgumentMatchers.eq(
                        SaleOperationCode.CONVERT_TICKET_TO_INVOICE),
                org.mockito.ArgumentMatchers.eq("SUPERVISOR"),
                org.mockito.ArgumentMatchers.eq("secret"),
                org.mockito.ArgumentMatchers.any(
                        org.springframework.security.core.Authentication.class));
        verify(operationalEvents).record(
                org.mockito.ArgumentMatchers.eq(ticket),
                org.mockito.ArgumentMatchers.eq(
                        DocumentOperationalEventType.CONVERTIDO),
                org.mockito.ArgumentMatchers.eq(user.getId()),
                org.mockito.ArgumentMatchers.eq(terminalId),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.argThat(data ->
                        user.getId().toString().equals(data.get("operatorUserId"))
                                && user.getId().toString().equals(
                                        data.get("authorizerUserId"))
                                && !data.containsValue("secret")));
    }

    @Test
    void invoiceFromTicketEnqueuesSyncEvent() {
        var ticket = draft(CommercialDocumentType.TICKET);
        addFullCashPayment(ticket);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, true);
        var customer = completeCustomer();
        when(documentRepository.findLockedDocument(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(relationRepository.findDocumentIdByOriginIdAndType(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(Optional.empty());
        when(customerRepository.findByIdAndCompanyId(
                customer.getId(), store.getEmpresa().getId())).thenReturn(Optional.of(customer));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                store.getId(), "FV", "2026")).thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var invoice = service.convertTicketToInvoice(
                ticket.getId(), customer.getId(), null, null, authentication());

        var command = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(syncOutbox).enqueue(command.capture());
        assertThat(command.getValue().entityId()).isEqualTo(invoice.getId());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CONFIRMAR);
        assertThat(command.getValue().payload())
                .containsEntry("tipo", "FACTURA_VENTA")
                .containsEntry("numero", invoice.getNumero())
                .containsEntry("clienteId", customer.getId().toString());
    }

    @Test
    void historicalReplayGeneratesCouponsOnlyForTheCurrentSegment() {
        var promotion = org.mockito.Mockito.mock(Promotion.class);
        var promotionId = UUID.randomUUID();
        when(promotion.id()).thenReturn(promotionId);
        when(promotion.type()).thenReturn(PromotionType.PURCHASE_THRESHOLD_COUPON);
        when(promotion.scope()).thenReturn(PromotionScope.SALE);
        when(promotion.customerSegment()).thenReturn(PromotionCustomerSegment.ALL);
        when(promotion.startDate()).thenReturn(LocalDate.of(2026, 1, 1));
        when(promotion.minimumAmount()).thenReturn(new BigDecimal("5.00"));
        when(promotion.couponAmount()).thenReturn(new BigDecimal("2.00"));
        when(promotion.couponValidDays()).thenReturn(30);
        when(promotionRepository.findByEmpresaIdAndEstado(
                store.getEmpresa().getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(promotion));
        var combined = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), user.getId(), BigDecimal.ZERO);
        combined.addLine(new DocumentLine(
                combined, UUID.randomUUID(), 1, BigDecimal.ONE, "H", "Historico",
                null, new BigDecimal("100.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21")));
        combined.addLine(new DocumentLine(
                combined, UUID.randomUUID(), 2, BigDecimal.ONE, "N", "Actual",
                null, new BigDecimal("10.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21")));
        var requestedCurrentLines = List.of(
                DocumentLineCommand.from(combined.getLineas().get(1)));

        var generated = service.historicalReplayGeneratedCoupons(
                combined, 1, requestedCurrentLines);

        assertThat(generated).singleElement().satisfies(coupon -> {
            assertThat(coupon.promotionId()).isEqualTo(promotionId);
            assertThat(coupon.benefitType())
                    .isEqualTo(PromotionalCouponBenefitType.AMOUNT);
            assertThat(coupon.amount()).isEqualByComparingTo("2.00");
        });
        assertThat(service.historicalReplayGeneratedCoupons(
                combined, 2, List.of())).isEmpty();
        verify(promotionalCoupons, never()).generateAfterTicketConfirmation(any());

        combined.addLine(DocumentLine.frozenSpecial(
                combined, 3, DocumentLineType.MANUAL_DISCOUNT,
                "Descuento global de esta venta", new BigDecimal("-1.00"),
                true, "IVA", new BigDecimal("21"), null, null, null,
                new BigDecimal("-0.83"), new BigDecimal("-0.17"),
                new BigDecimal("-1.00")));
        assertThat(service.historicalReplayGeneratedCoupons(
                combined, 1, requestedCurrentLines)).singleElement();
    }

    @Test
    void historicalReplayCouponUsesTheCurrentAmountBeforeCheckoutDiscount() {
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        var historicalCouponId = UUID.randomUUID();
        var historicalPromotionId = UUID.randomUUID();
        var currentCouponId = UUID.randomUUID();
        var currentPromotionId = UUID.randomUUID();
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(promotionalCoupons.redeemAuthorized(any())).thenAnswer(invocation -> {
            PromotionalCouponService.AuthorizedRedemptionCommand command =
                    invocation.getArgument(0);
            return new PromotionalCouponService.RedemptionResult(
                    command.documentId(), currentCouponId, currentPromotionId, "1234",
                    new BigDecimal("10.00"), null, Optional.empty());
        });
        var historicalProductId = UUID.randomUUID();
        var currentProductId = UUID.randomUUID();
        var historicalProduct = new DocumentLineCommand(
                historicalProductId, BigDecimal.ONE, "H", "Historico", "VENTA",
                new BigDecimal("50.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO,
                DocumentLineType.PRODUCT, null, null, null, List.of(), false, false,
                null, null, null, null, null,
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"));
        var historicalCoupon = new DocumentLineCommand(
                null, BigDecimal.ONE, "CUPON", "CUPON HISTORICO", null,
                new BigDecimal("-5.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO,
                DocumentLineType.PROMOTIONAL_COUPON, historicalPromotionId, null,
                historicalCouponId, List.of(), false, false, null, null, null,
                null, null, new BigDecimal("-5.00"), BigDecimal.ZERO,
                new BigDecimal("-5.00"));
        var currentProduct = new DocumentLineCommand(
                currentProductId, BigDecimal.ONE, "N", "Actual", "VENTA",
                new BigDecimal("100.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO,
                DocumentLineType.PRODUCT, null, null, null, List.of(), false, false,
                null, null, null, null, null,
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"));
        var currentCoupon = new DocumentLineCommand(
                null, BigDecimal.ONE, "CUPON", "CUPON ****1234", null,
                new BigDecimal("-10.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO,
                DocumentLineType.PROMOTIONAL_COUPON, currentPromotionId, null,
                currentCouponId,
                List.of(), false, false, null, null, null, null, null,
                new BigDecimal("-10.00"), BigDecimal.ZERO, new BigDecimal("-10.00"));
        var checkoutDiscount = new DocumentLineCommand(
                null, BigDecimal.ONE, "DESCUENTO", "Descuento directo", null,
                new BigDecimal("-5.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO,
                DocumentLineType.MANUAL_DISCOUNT, null, null, null,
                List.of(), false, false, null, null, null, null, null,
                new BigDecimal("-5.00"), BigDecimal.ZERO, new BigDecimal("-5.00"));
        var replay = new HistoricalTicketReplayMetadata(
                UUID.randomUUID(), "T-ORIGEN", "fingerprint", 2,
                new BigDecimal("45.00"), new BigDecimal("100.00"),
                List.of(), List.of(), List.of());
        var snapshot = new ApprovedCardTicketSnapshot(
                store.getId(), UUID.randomUUID(), LocalDate.of(2026, 8, 7), null,
                cash.getId(), BigDecimal.ZERO, new BigDecimal("130.00"),
                BigDecimal.ZERO, new BigDecimal("130.00"),
                List.of(historicalProduct, historicalCoupon, currentProduct,
                        currentCoupon, checkoutDiscount),
                null, replay);

        var ticket = service.createApprovedCardTicketFromSnapshot(
                snapshot,
                List.of(new PaymentCommand(
                        cash.getId(), new BigDecimal("130.00"), true,
                        new BigDecimal("130.00"), BigDecimal.ZERO)),
                authentication());

        var redemption = org.mockito.ArgumentCaptor.forClass(
                PromotionalCouponService.AuthorizedRedemptionCommand.class);
        verify(promotionalCoupons).redeemAuthorized(redemption.capture());
        assertThat(redemption.getValue().pendingDocumentAmount())
                .isEqualByComparingTo("100.00");
        assertThat(ticket.getTotal()).isEqualByComparingTo("130.00");
        assertThat(ticket.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON)
                .map(DocumentLine::getPromotionalCouponId))
                .containsExactly(historicalCouponId, currentCouponId);
    }

    @Test
    void historicalReplayMarksOnlyDirectPromotionsFromTheCurrentSegment() {
        var historicalVersionId = UUID.randomUUID();
        var currentVersionId = UUID.randomUUID();
        var currentPromotion = org.mockito.Mockito.mock(Promotion.class);
        when(promotionRepository.findById(currentVersionId))
                .thenReturn(Optional.of(currentPromotion));
        var combined = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), user.getId(), BigDecimal.ZERO);
        combined.addLine(DocumentLine.special(
                combined, 1, "PROMOCION HISTORICA", new BigDecimal("-1.00"),
                true, "IVA", new BigDecimal("21"), UUID.randomUUID(),
                historicalVersionId, null));
        combined.addLine(DocumentLine.special(
                combined, 2, "PROMOCION ACTUAL", new BigDecimal("-2.00"),
                true, "IVA", new BigDecimal("21"), UUID.randomUUID(),
                currentVersionId, null));
        var replay = new HistoricalTicketReplayMetadata(
                UUID.randomUUID(), "T-1", "fingerprint", 1,
                new BigDecimal("1.00"), BigDecimal.ZERO, List.of(), List.of());

        service.markHistoricalReplayCurrentPromotions(combined, replay);

        verify(promotionRepository, never()).findById(historicalVersionId);
        verify(promotionRepository).findById(currentVersionId);
        verify(currentPromotion).markUsed();
    }

    @Test
    void historicalReplayUsesTheOriginalLoyaltyEligibilitySnapshot() {
        var historicalProductId = UUID.randomUUID();
        var currentProductId = UUID.randomUUID();
        var currentProduct = org.mockito.Mockito.mock(Product.class);
        when(currentProduct.getId()).thenReturn(currentProductId);
        when(currentProduct.getDiscountType()).thenReturn(DiscountType.NONE);
        doReturn(List.of(currentProduct))
                .when(productRepository).findAllByStoreIdAndIdIn(
                        org.mockito.ArgumentMatchers.eq(store.getId()),
                        org.mockito.ArgumentMatchers.any());
        var combined = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), user.getId(), BigDecimal.ZERO);
        combined.addLine(new DocumentLine(
                combined, historicalProductId, 1, BigDecimal.ONE, "H", "Historico",
                null, new BigDecimal("10.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21")));
        combined.addLine(new DocumentLine(
                combined, currentProductId, 2, BigDecimal.ONE, "N", "Actual",
                null, new BigDecimal("10.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21")));
        var replay = new HistoricalTicketReplayMetadata(
                UUID.randomUUID(), "T-1", "fingerprint", 1,
                new BigDecimal("10.00"), BigDecimal.ZERO, List.of(), List.of(),
                List.of(new HistoricalTicketReplayMetadata.HistoricalLoyaltyLine(
                        1, true, new BigDecimal("5.00"))));

        var accrual = service.loyaltyAccrual(
                combined, new BigDecimal("20.00"), replay);

        assertThat(accrual.documentAmount()).isEqualByComparingTo("20.00");
        assertThat(accrual.eligibleDocumentAmount()).isEqualByComparingTo("5.00");
        assertThat(accrual.eligiblePaidAmount()).isEqualByComparingTo("5.00");
        assertThat(accrual.lines().get(combined.getLineas().getFirst().getId()))
                .satisfies(line -> {
                    assertThat(line.eligible()).isTrue();
                    assertThat(line.amount()).isEqualByComparingTo("5.00");
                });
        assertThat(accrual.lines().get(combined.getLineas().get(1).getId()))
                .satisfies(line -> {
                    assertThat(line.eligible()).isFalse();
                    assertThat(line.amount()).isZero();
                });
    }

    @Test
    void historicalReplayClampsCurrentLoyaltyAgainstTheCurrentSegmentOnly() {
        var historicalProductId = UUID.randomUUID();
        var currentProductId = UUID.randomUUID();
        var currentProduct = org.mockito.Mockito.mock(Product.class);
        when(currentProduct.getId()).thenReturn(currentProductId);
        when(currentProduct.getDiscountType()).thenReturn(DiscountType.NORMAL);
        doReturn(List.of(currentProduct))
                .when(productRepository).findAllByStoreIdAndIdIn(
                        org.mockito.ArgumentMatchers.eq(store.getId()),
                        org.mockito.ArgumentMatchers.any());
        var combined = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), user.getId(), BigDecimal.ZERO);
        combined.addLine(new DocumentLine(
                combined, historicalProductId, 1, BigDecimal.ONE, "H", "Historico",
                null, new BigDecimal("10.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21")));
        combined.addLine(new DocumentLine(
                combined, currentProductId, 2, BigDecimal.ONE, "N", "Actual",
                null, new BigDecimal("10.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21")));
        combined.addLine(DocumentLine.manualDiscount(
                combined, 3, new BigDecimal("-9.00"),
                true, "IVA", new BigDecimal("21")));
        var replay = new HistoricalTicketReplayMetadata(
                UUID.randomUUID(), "T-1", "fingerprint", 1,
                new BigDecimal("10.00"), BigDecimal.ZERO, List.of(), List.of(),
                List.of(new HistoricalTicketReplayMetadata.HistoricalLoyaltyLine(
                        1, false, BigDecimal.ZERO)));

        var accrual = service.loyaltyAccrual(
                combined, new BigDecimal("11.00"), replay);

        assertThat(accrual.documentAmount()).isEqualByComparingTo("11.00");
        assertThat(accrual.eligibleDocumentAmount()).isEqualByComparingTo("1.00");
        assertThat(accrual.eligiblePaidAmount()).isEqualByComparingTo("1.00");
        assertThat(accrual.lines().get(combined.getLineas().getFirst().getId()).amount())
                .isZero();
        assertThat(accrual.lines().get(combined.getLineas().get(1).getId()).amount())
                .isEqualByComparingTo("1.00");
    }

    @Test
    void normalSaleClampsEligibleLineSnapshotsToTheInvoicedTotal() {
        var productId = UUID.randomUUID();
        var eligible = product(productId, DiscountType.NORMAL);
        doReturn(List.of(eligible)).when(productRepository)
                .findAllByStoreIdAndIdIn(
                        org.mockito.ArgumentMatchers.eq(store.getId()), any());
        var ticket = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), user.getId(), BigDecimal.ZERO);
        ticket.addLine(new DocumentLine(
                ticket, productId, 1, BigDecimal.ONE, "P", "Producto socio",
                "VENTA", new BigDecimal("100.00"), BigDecimal.ZERO,
                true, "IVA", BigDecimal.ZERO));
        ticket.addLine(DocumentLine.special(
                ticket, 2, "CUPON", new BigDecimal("-10.00"),
                true, "IVA", BigDecimal.ZERO, UUID.randomUUID(), null,
                UUID.randomUUID(), DocumentLineType.PROMOTIONAL_COUPON));

        var accrual = service.loyaltyAccrual(
                ticket, new BigDecimal("90.00"), null);

        assertThat(accrual.documentAmount()).isEqualByComparingTo("90.00");
        assertThat(accrual.eligibleDocumentAmount()).isEqualByComparingTo("90.00");
        assertThat(accrual.lines().get(ticket.getLineas().getFirst().getId()).amount())
                .isEqualByComparingTo("90.00");
    }

    @Test
    void finalSerialGuardRejectsSerialAlreadyUsedByAnotherStockOutput() {
        var combined = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), user.getId(), BigDecimal.ZERO);
        var line = new DocumentLine(
                combined, UUID.randomUUID(), 1, BigDecimal.ONE, "S", "Serializado",
                null, new BigDecimal("10.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21"));
        line.assignSerialNumbers(List.of("NEW-SERIAL"));
        combined.addLine(line);
        when(documentRepository.usedSerialNumbers(
                store.getId(), List.of("NEW-SERIAL")))
                .thenReturn(List.of("NEW-SERIAL"));

        assertThatThrownBy(() -> new DocumentSerialNumberGuard(documentRepository)
                .lockAndValidate(combined, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.serial_number_already_used");
    }

    @Test
    void inactiveCustomerCannotBeUsedWhenConvertingTicketToInvoice() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, true);
        var customer = completeCustomer();
        customer.deactivate();
        when(documentRepository.findLockedDocument(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(relationRepository.findDocumentIdByOriginIdAndType(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(Optional.empty());
        when(customerRepository.findByIdAndCompanyId(
                customer.getId(), store.getEmpresa().getId())).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.convertTicketToInvoice(
                ticket.getId(), customer.getId(), null, null, authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.customer_inactivo");

        verify(documentRepository, never()).save(any());
        verify(relationRepository, never()).save(any());
    }

    @Test
    void repeatedTicketConversionReturnsTheExistingInvoiceForTheSameCustomer() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        var customer = completeCustomer();
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.setParties(customer.getId(), null, null);
        when(documentRepository.findLockedDocument(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(relationRepository.findDocumentIdByOriginIdAndType(
                ticket.getId(), DocumentRelationType.FACTURA_DE))
                .thenReturn(Optional.of(invoice.getId()));
        when(documentRepository.findLockedDocument(invoice.getId(), store.getId()))
                .thenReturn(Optional.of(invoice));

        assertThat(service.convertTicketToInvoice(
                ticket.getId(), customer.getId(), null, null, authentication()))
                .isSameAs(invoice);
        verify(fiscalIntegration, never()).registerInvoiceFromTicket(any(), any());
        verify(relationRepository, never()).save(any());
    }

    @Test
    void repeatedTicketConversionRejectsAnotherCustomer() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.setParties(UUID.randomUUID(), null, null);
        when(documentRepository.findLockedDocument(ticket.getId(), store.getId()))
                .thenReturn(Optional.of(ticket));
        when(relationRepository.findDocumentIdByOriginIdAndType(
                ticket.getId(), DocumentRelationType.FACTURA_DE))
                .thenReturn(Optional.of(invoice.getId()));
        when(documentRepository.findLockedDocument(invoice.getId(), store.getId()))
                .thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.convertTicketToInvoice(
                ticket.getId(), UUID.randomUUID(), null, null, authentication()))
                .isInstanceOf(TicketAlreadyInvoicedException.class)
                .hasMessage(TicketAlreadyInvoicedException.MESSAGE_KEY);
    }

    @Test
    void invoiceCanBePaidPartiallyAndThenFully() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var method = new PaymentMethod(
                store.getEmpresa().getId(), "TRANSFERENCIA", false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(method.getId())).thenReturn(Optional.of(method));
        when(documentRepository.save(invoice)).thenReturn(invoice);

        var partial = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("4.00"), true, null, null)),
                authentication());

        assertThat(partial.getEstado()).isEqualTo(DocumentStatus.PARCIAL);
        assertThat(partial.getPendingTotal()).isEqualByComparingTo("6.00");
        verify(memberLoyaltyService).recordPaidSale(same(partial), accrualOf("4.00"));

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("6.00"), false, null, null)),
                authentication());

        assertThat(paid.getEstado()).isEqualTo(DocumentStatus.PAGADO);
        assertThat(paid.getPagos()).hasSize(2);
        verify(memberLoyaltyService).recordPaidSale(same(paid), accrualOf("10.00"));
    }

    @Test
    void invoicePaymentEnqueuesSyncUpdateWithPayments() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var method = new PaymentMethod(
                store.getEmpresa().getId(), "TRANSFERENCIA", false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(method.getId())).thenReturn(Optional.of(method));
        when(documentRepository.save(invoice)).thenReturn(invoice);

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("4.00"), true, null, null)),
                authentication());

        var command = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(syncOutbox).enqueue(command.capture());
        assertThat(command.getValue().entityId()).isEqualTo(paid.getId());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.ACTUALIZAR);
        assertThat(command.getValue().payload())
                .containsEntry("estado", "PARCIAL");
        assertThat(command.getValue().payload().get("pagos"))
                .asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("metodoPagoId", method.getId().toString())
                .containsEntry("importe", "4.00")
                .containsEntry("principal", true);
    }

    @Test
    void deliveryNoteCanBePaidPartially() {
        var note = draft(CommercialDocumentType.ALBARAN_VENTA);
        note.confirm("AV-001-26-000001", UUID.randomUUID(), NOW, true);
        var method = new PaymentMethod(
                store.getEmpresa().getId(), "TARJETA", false);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));
        when(paymentMethodRepository.findById(method.getId())).thenReturn(Optional.of(method));
        when(documentRepository.save(note)).thenReturn(note);

        var partial = service.payDeliveryNote(
                note.getId(),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("3.00"), true, null, null)),
                authentication());

        assertThat(partial.getEstado()).isEqualTo(DocumentStatus.PARCIAL);
        assertThat(partial.getPendingTotal()).isEqualByComparingTo("7.00");
        verify(cashPaymentRecorder).recordDocumentPayments(terminalId, note);
    }

    @Test
    void createsPendingSaleWithZeroPartialOrFullActualPayments() {
        var customer = completeCustomer();
        var method = new PaymentMethod(
                store.getEmpresa().getId(), "TRANSFERENCIA", false, true, false);
        var requestId = UUID.randomUUID();
        when(customerRepository.findByIdAndCompanyId(customer.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(customer));
        when(paymentMethodRepository.findById(method.getId())).thenReturn(Optional.of(method));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        var pending = service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.of(2026, 7, 8), List.of(), authentication());
        assertThat(pending.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
        assertThat(pending.getPagos()).isEmpty();

        var partial = service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.of(2026, 7, 8),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("3.00"), true, null, null,
                        null, "TR-1", null, null, null, null, null, requestId)),
                authentication());
        assertThat(partial.getEstado()).isEqualTo(DocumentStatus.PARCIAL);
        assertThat(partial.getPendingTotal()).isEqualByComparingTo("7.00");
        assertThat(partial.getPagos()).singleElement()
                .extracting(DocumentPayment::getRequestId).isEqualTo(requestId);

        var paid = service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.of(2026, 7, 8),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("10.00"), true, null, null,
                        null, "TR-2", null, null, null, null, null, UUID.randomUUID())),
                authentication());
        assertThat(paid.getEstado()).isEqualTo(DocumentStatus.PAGADO);
    }

    @Test
    void pendingInvoiceQuoteRejectsCustomerWithoutCompleteFiscalData() {
        var customer = new Customer(
                store.getEmpresa(), "Cliente incompleto", DocumentType.NIF,
                "12345678Z", null, null, null, null,
                CustomerRate.VENTA, BigDecimal.ZERO);
        when(customerRepository.findByIdAndCompanyId(
                customer.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.quotePendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.of(2026, 7, 8), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El cliente no tiene datos fiscales completos");
    }

    @Test
    void pendingSaleRejectsInvalidTypeMissingOrInactiveCustomerDueDateZeroDuplicateAndOverpayment() {
        var customer = completeCustomer();
        customer.deactivate();
        when(customerRepository.findByIdAndCompanyId(customer.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.createPendingSale(
                command(CommercialDocumentType.TICKET, lines(), customer.getId()),
                LocalDate.now(), List.of(), authentication()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.now(), List.of(), authentication()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inactivo");

        customer.activate();
        var method = new PaymentMethod(store.getEmpresa().getId(), "TRANSFERENCIA", false);
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                null, List.of(), authentication()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.now(), List.of(new PaymentCommand(
                        method.getId(), BigDecimal.ZERO, true, null, null,
                        null, null, null, null, null, null, null, id)), authentication()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positivo");
        assertThatThrownBy(() -> service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.now(), List.of(
                        new PaymentCommand(method.getId(), BigDecimal.ONE, true, null, null,
                                null, null, null, null, null, null, null, id),
                        new PaymentCommand(method.getId(), BigDecimal.ONE, false, null, null,
                                null, null, null, null, null, null, null, id)), authentication()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
        assertThatThrownBy(() -> service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.now(), List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("10.01"), true, null, null)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment_exceeds_pending_total");
    }

    @Test
    void receivablePaymentCannotExceedPendingTotal() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var method = new PaymentMethod(
                store.getEmpresa().getId(), "TRANSFERENCIA", false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("10.01"), true, null, null)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.document.payment_exceeds_pending_total");

        verify(documentRepository, never()).save(invoice);
    }

    @Test
    void transferPaymentStoresItsOptionalDateAndRejectsFutureOrNonTransferDates() {
        var transferDate = LocalDate.of(2026, 6, 8);
        var transfer = new PaymentMethod(
                store.getEmpresa().getId(), "TRANSFERENCIA", false);
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));
        when(documentRepository.save(invoice)).thenReturn(invoice);

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        transfer.getId(), new BigDecimal("10.00"), true,
                        null, null, null, "TR-1", null, null, null,
                        null, null, UUID.randomUUID(), null, transferDate)),
                authentication());

        assertThat(paid.getPagos().getFirst().getTransferDate()).isEqualTo(transferDate);

        var futureInvoice = draft(CommercialDocumentType.FACTURA_VENTA);
        futureInvoice.confirm("FV-001-26-000002", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(futureInvoice.getId()))
                .thenReturn(Optional.of(futureInvoice));
        assertThatThrownBy(() -> service.payInvoice(
                futureInvoice.getId(),
                List.of(new PaymentCommand(
                        transfer.getId(), new BigDecimal("10.00"), true,
                        null, null, null, "TR-2", null, null, null,
                        null, null, UUID.randomUUID(), null,
                        transferDate.plusDays(1))),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.payment.transfer_date_cannot_be_future");

        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", false);
        var cashInvoice = draft(CommercialDocumentType.FACTURA_VENTA);
        cashInvoice.confirm("FV-001-26-000003", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(cashInvoice.getId()))
                .thenReturn(Optional.of(cashInvoice));
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        assertThatThrownBy(() -> service.payInvoice(
                cashInvoice.getId(),
                List.of(new PaymentCommand(
                        cash.getId(), new BigDecimal("10.00"), true,
                        null, null, null, null, null, null, null,
                        null, null, UUID.randomUUID(), null, transferDate)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.payment.transfer_date_only_for_transfer");
    }

    @Test
    void receivablePaymentRejectsZeroAndDuplicateRequestIds() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var method = new PaymentMethod(
                store.getEmpresa().getId(), "TRANSFERENCIA", false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var requestId = UUID.randomUUID();

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        method.getId(), BigDecimal.ZERO, true, null, null)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positivo");

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(
                        new PaymentCommand(
                                method.getId(), BigDecimal.ONE, true, null, null,
                                null, null, null, null, null, null, null, requestId),
                        new PaymentCommand(
                                method.getId(), BigDecimal.ONE, false, null, null,
                                null, null, null, null, null, null, null, requestId)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void pendingSaleCashPaymentRequiresOpenCashSession() {
        var customer = completeCustomer();
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        when(customerRepository.findByIdAndCompanyId(customer.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(customer));
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("No hay una sesion de caja abierta"))
                .when(cashPaymentRecorder).recordDocumentPayments(any(), any());

        assertThatThrownBy(() -> service.createPendingSale(
                command(CommercialDocumentType.FACTURA_VENTA, lines(), customer.getId()),
                LocalDate.of(2026, 7, 8),
                List.of(new PaymentCommand(
                        cash.getId(), new BigDecimal("10.00"), true,
                        new BigDecimal("10.00"), BigDecimal.ZERO)), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sesion de caja abierta");
    }

    @Test
    void directPosInvoiceAndDeliveryNoteApplyStockExactlyOnce() {
        var customer = completeCustomer();
        when(customerRepository.findByIdAndCompanyId(customer.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(customer));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(stockGateway.confirm(any())).thenReturn(true);

        var invoice = service.createPendingSale(
                directCommand(CommercialDocumentType.FACTURA_VENTA, customer.getId()),
                LocalDate.of(2026, 7, 8), List.of(), authentication());
        var note = service.createPendingSale(
                directCommand(CommercialDocumentType.ALBARAN_VENTA, customer.getId()),
                LocalDate.of(2026, 7, 8), List.of(), authentication());

        assertThat(invoice.isOrigenStock()).isTrue();
        assertThat(note.isOrigenStock()).isTrue();
        verify(stockGateway, times(1)).confirm(invoice);
        verify(stockGateway, times(1)).confirm(note);
    }

    @Test
    void invoicePaymentRequiresOpenCashSessionAndRecordsCashOnly() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        var card = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(documentRepository.save(invoice)).thenReturn(invoice);

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(
                        new PaymentCommand(cash.getId(), new BigDecimal("4.00"), true, null, null),
                        new PaymentCommand(card.getId(), new BigDecimal("6.00"), false, null, null)),
                authentication());

        assertThat(paid.getEstado()).isEqualTo(DocumentStatus.PAGADO);
        verify(cashPaymentRecorder).recordDocumentPayments(terminalId, invoice);
    }

    @Test
    void configuredReferenceIsRequiredForPaymentMethod() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var card = new PaymentMethod(
                store.getEmpresa().getId(), "TARJETA", true, true, false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(card.getId(), new BigDecimal("10.00"), true, null, null)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.payment.reference_required");
    }

    @Test
    void manualCardReferenceUsesThePaymentMethodInsteadOfTheLegacyStoreFlag() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var card = new PaymentMethod(
                store.getEmpresa().getId(), "TARJETA", true, false, false);
        var legacyRules = org.mockito.Mockito.mock(StorePaymentConfiguration.class);
        when(legacyRules.isCardManualEnabled()).thenReturn(true);
        when(storePaymentConfigurations.findByStoreId(store.getId()))
                .thenReturn(Optional.of(legacyRules));
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(documentRepository.save(invoice)).thenReturn(invoice);

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        card.getId(), new BigDecimal("10.00"), true,
                        null, null, null, null, PaymentCardMode.MANUAL,
                        null, null, null, null)),
                authentication());

        assertThat(paid.getEstado()).isEqualTo(DocumentStatus.PAGADO);
        verify(legacyRules, never()).isCardManualReferenceRequired();
    }

    @Test
    void integratedCardPaymentMustMatchCurrentEnabledTerminalConfiguration() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        var card = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));
        var terminal = new Terminal(store, "CAJA 1", TerminalType.TERMINAL_VENTA, "credential");
        var configuration = TerminalPaymentConfiguration.manual(terminal);
        configuration.configure(new TerminalPaymentConfigurationCommand(
                PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC,
                "PinPad",
                true,
                false,
                Map.of("ip", "192.168.1.50"),
                null));
        when(terminalPaymentConfigurations.findByTerminalId(terminalId))
                .thenReturn(Optional.of(configuration));

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        card.getId(), new BigDecimal("10.00"), true, null, null,
                        null, "AUTH-1", PaymentCardMode.INTEGRATED,
                        PaymentTerminalProvider.REDSYS_TPV_PC,
                        PaymentTerminalOperationStatus.APPROVED,
                        "A1B2C3", terminalId)),
                authentication());

        assertThat(paid.getPagos().getFirst().getPaymentTerminalId()).isEqualTo(terminalId);
    }

    @Test
    void integratedCardPaymentCannotClaimAnotherTerminal() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var card = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        card.getId(), new BigDecimal("10.00"), true, null, null,
                        null, "AUTH-1", PaymentCardMode.INTEGRATED,
                        PaymentTerminalProvider.REDSYS_TPV_PC,
                        PaymentTerminalOperationStatus.APPROVED,
                        "A1B2C3", UUID.randomUUID())),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.payment_terminal.current_terminal_required");
    }

    @Test
    void paymentTerminalMetadataRequiresExplicitCardMode() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var card = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        card.getId(), new BigDecimal("10.00"), true, null, null,
                        null, "AUTH-1", null,
                        PaymentTerminalProvider.REDSYS_TPV_PC,
                        PaymentTerminalOperationStatus.APPROVED,
                        "A1B2C3", terminalId)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.payment_terminal.card_mode_required");
    }

    @Test
    void nonCardPaymentCannotIncludePaymentTerminalMetadata() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        cash.getId(), new BigDecimal("10.00"), true, null, null,
                        null, null, PaymentCardMode.INTEGRATED,
                        PaymentTerminalProvider.REDSYS_TPV_PC,
                        PaymentTerminalOperationStatus.APPROVED,
                        "A1B2C3", terminalId)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.payment_terminal.only_card_payment_allows_terminal_metadata");
    }

    @Test
    void nonCashPaymentRejectsDeliveredAmountAndChange() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var card = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        card.getId(), new BigDecimal("10.00"), true,
                        new BigDecimal("10.00"), BigDecimal.ZERO)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.payment.cash_amounts_only_for_cash_method");
    }

    @Test
    void cashPaymentAllowsDeliveredAmountAndChangeWhenDrawerOpeningIsDisabled() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        invoice.confirm("FV-001-26-000001", UUID.randomUUID(), NOW, false);
        var cash = new PaymentMethod(
                store.getEmpresa().getId(), "EFECTIVO", true, false, false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(documentRepository.save(invoice)).thenReturn(invoice);

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        cash.getId(), new BigDecimal("10.00"), true,
                        new BigDecimal("20.00"), new BigDecimal("10.00"))),
                authentication());

        assertThat(paid.getPagos()).singleElement().satisfies(payment -> {
            assertThat(payment.getMetodoPago().isCash()).isTrue();
            assertThat(payment.getMetodoPago().isAbreCajaRegistradora()).isFalse();
            assertThat(payment.getEntregado()).isEqualByComparingTo("20.00");
            assertThat(payment.getCambio()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void adminCannotEditConfirmedDocumentWithFiscalRecord() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, true);
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(fiscalIntegration.hasFiscalRecord(ticket.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.adminEditConfirmed(
                ticket.getId(), BigDecimal.ZERO, null, null, lines(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fiscal");

        verify(documentRepository, never()).save(any());
    }

    @Test
    void onlyInvoicesCanBeRelatedToOriginDocuments() {
        var note = draft(CommercialDocumentType.ALBARAN_VENTA);
        var origin = draft(CommercialDocumentType.TICKET);
        stubLocked(note, origin);

        assertThatThrownBy(() -> service.relate(
                note.getId(), origin.getId(), DocumentRelationType.FACTURA_DE, authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("factura");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void invoiceRelationRequiresCompatibleOriginType() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        var originInvoice = draft(CommercialDocumentType.FACTURA_VENTA);
        stubLocked(invoice, originInvoice);

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), originInvoice.getId(), DocumentRelationType.FACTURA_DE,
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("origen");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void facturaDeRejectsNonDeliveryNoteSalesOrigin() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        var ticket = draft(CommercialDocumentType.TICKET);
        stubLocked(invoice, ticket);

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), ticket.getId(), DocumentRelationType.FACTURA_DE,
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALBARAN_VENTA");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void facturaDeRejectsSecondInvoiceForSameDeliveryNoteAfterDocumentLocks() {
        var customerId = UUID.randomUUID();
        var invoice = confirmedSales(
                CommercialDocumentType.FACTURA_VENTA, customerId, "100.00");
        var origin = confirmedSales(
                CommercialDocumentType.ALBARAN_VENTA, customerId, "100.00");
        stubLocked(invoice, origin);
        when(relationRepository.existsByOrigen_IdAndTipo(
                origin.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(true);

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), origin.getId(), DocumentRelationType.FACTURA_DE,
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("albaran");

        var order = inOrder(documentRepository, relationRepository);
        var firstId = invoice.getId().compareTo(origin.getId()) < 0
                ? invoice.getId() : origin.getId();
        var secondId = firstId.equals(invoice.getId()) ? origin.getId() : invoice.getId();
        order.verify(documentRepository).findLockedDocument(firstId, store.getId());
        order.verify(documentRepository).findLockedDocument(secondId, store.getId());
        order.verify(relationRepository).existsByOrigen_IdAndTipo(
                origin.getId(), DocumentRelationType.FACTURA_DE);
        verify(relationRepository, never()).save(any());
    }

    @Test
    void facturaDeRejectsSecondDeliveryNoteForSameInvoice() {
        var customerId = UUID.randomUUID();
        var invoice = confirmedSales(
                CommercialDocumentType.FACTURA_VENTA, customerId, "100.00");
        var origin = confirmedSales(
                CommercialDocumentType.ALBARAN_VENTA, customerId, "100.00");
        stubLocked(invoice, origin);
        when(relationRepository.existsByDocumento_IdAndTipo(
                invoice.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(true);

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), origin.getId(), DocumentRelationType.FACTURA_DE,
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("factura");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void salesDeliveryNoteWithPartialPaymentCannotBecomeInvoiceOrigin() {
        var customerId = UUID.randomUUID();
        var invoice = confirmedSales(
                CommercialDocumentType.FACTURA_VENTA, customerId, "100.00");
        var origin = confirmedSales(
                CommercialDocumentType.ALBARAN_VENTA, customerId, "100.00");
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        origin.addPayment(new DocumentPayment(
                origin, cash, 1, new BigDecimal("30.00"), true,
                null, null, null, null, NOW));
        origin.updatePaymentStatus();
        stubLocked(invoice, origin);

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), origin.getId(), DocumentRelationType.FACTURA_DE,
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pagos");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void salesInvoiceAndDeliveryNoteRelationRequiresSameCustomer() {
        var invoice = confirmedSales(
                CommercialDocumentType.FACTURA_VENTA, UUID.randomUUID(), "100.00");
        var origin = confirmedSales(
                CommercialDocumentType.ALBARAN_VENTA, UUID.randomUUID(), "100.00");
        stubLocked(invoice, origin);

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), origin.getId(), DocumentRelationType.FACTURA_DE,
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cliente");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void salesInvoiceAndDeliveryNoteRelationRequiresSameTotal() {
        var customerId = UUID.randomUUID();
        var invoice = confirmedSales(
                CommercialDocumentType.FACTURA_VENTA, customerId, "100.00");
        var origin = confirmedSales(
                CommercialDocumentType.ALBARAN_VENTA, customerId, "90.00");
        stubLocked(invoice, origin);

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), origin.getId(), DocumentRelationType.FACTURA_DE,
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void salesDeliveryNoteCannotBeInvoicedWhilePhysicalCollectionIsReserved() {
        var customerId = UUID.randomUUID();
        var invoice = confirmedSales(
                CommercialDocumentType.FACTURA_VENTA, customerId, "100.00");
        var origin = confirmedSales(
                CommercialDocumentType.ALBARAN_VENTA, customerId, "100.00");
        stubLocked(invoice, origin);
        var now = NOW.minusSeconds(1);
        var active = CustomerReceivablePaymentReservation.reserve(
                UUID.randomUUID(), origin.getId(), store.getId(), terminalId, user.getId(),
                "a".repeat(64), new BigDecimal("30.00"),
                CustomerReceivablePaymentReservation.Kind.INTEGRATED_CARD,
                UUID.randomUUID(), NOW.plusSeconds(30), now);
        when(receivablePaymentReservations.findAllLockedByDocumentId(origin.getId()))
                .thenReturn(List.of(active));
        when(receivablePaymentReservations.findAllLockedByDocumentId(invoice.getId()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), origin.getId(), DocumentRelationType.FACTURA_DE,
                authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cobro");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void validUnpaidSalesRelationLocksDocumentsInStableIdentifierOrder() {
        var customerId = UUID.randomUUID();
        var invoice = confirmedSales(
                CommercialDocumentType.FACTURA_VENTA, customerId, "100.00");
        var origin = confirmedSales(
                CommercialDocumentType.ALBARAN_VENTA, customerId, "100.00");
        stubLocked(invoice, origin);

        service.relate(
                invoice.getId(), origin.getId(), DocumentRelationType.FACTURA_DE,
                authentication());

        var firstId = invoice.getId().compareTo(origin.getId()) < 0
                ? invoice.getId() : origin.getId();
        var secondId = firstId.equals(invoice.getId()) ? origin.getId() : invoice.getId();
        var order = inOrder(documentRepository);
        order.verify(documentRepository).findLockedDocument(firstId, store.getId());
        order.verify(documentRepository).findLockedDocument(secondId, store.getId());
        verify(relationRepository).save(any(DocumentRelation.class));
    }
    @Test
    void rejectsInactiveProductWhenConfirmingASaleAndPolicyIsDisabled() {
        var note = draft(CommercialDocumentType.ALBARAN_VENTA);
        var productId = note.getLineas().getFirst().getProductoId();
        var inactive = product(productId, DiscountType.NORMAL);
        when(inactive.isActive()).thenReturn(false);
        doReturn(Map.of(productId, productSnapshot(inactive)))
                .when(promotionCatalog).products(store.getId(), List.of(productId));
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> service.confirm(note.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.product.inactive_sale_not_allowed");

        verify(counterRepository, never())
                .findByTiendaIdAndTipoAndPeriodo(any(), any(), any());
        verify(stockGateway, never()).confirm(any());
    }

    @Test
    void allowsInactiveProductWhenStorePolicyIsEnabled() {
        var productId = UUID.randomUUID();
        var inactive = product(productId, DiscountType.NORMAL);
        doReturn(Map.of(productId, productSnapshot(inactive)))
                .when(promotionCatalog).products(store.getId(), List.of(productId));
        when(stockSettings.allowsInactiveProductSales(store.getId())).thenReturn(true);

        var quoted = service.quoteTicket(
                command(CommercialDocumentType.TICKET, List.of(
                        line(productId, "P-1", "Producto", new BigDecimal("10.00")))),
                authentication());

        assertThat(quoted.getLineas()).hasSize(1);
    }

    @Test
    void fiscalQrIsResolvedOnlyByThePostCommitPrintJob() {
        var fiscalQr = org.mockito.Mockito.mock(DocumentFiscalQrService.class);
        var fiscalQrImages = org.mockito.Mockito.mock(
                com.tpverp.backend.verifactu.FiscalQrImageService.class);
        service.setFiscalQrServices(fiscalQr, fiscalQrImages);
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", user.getId(), NOW, false);

        var transactionalView = service.ticketPrintView(ticket);

        assertThat(transactionalView.qrUrl()).isNull();
        assertThat(transactionalView.qrImage()).isNull();
        verifyNoInteractions(fiscalQr, fiscalQrImages);
    }

    @Test
    void fiscalPrintUsesTheFrozenQrPayloadAndGeneratedImage() {
        var fiscalQr = org.mockito.Mockito.mock(DocumentFiscalQrService.class);
        var fiscalQrImages = org.mockito.Mockito.mock(
                com.tpverp.backend.verifactu.FiscalQrImageService.class);
        service.setFiscalQrServices(fiscalQr, fiscalQrImages);
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", user.getId(), NOW, false);
        var url = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?nif=B12345674";
        var fiscalData = new DocumentFiscalQrService.FiscalQrPrintData(
                url, "a".repeat(64), "AEAT_QR_0.5.0", "TPV-ERP-2026.08.25",
                com.tpverp.backend.verifactu.FiscalMode.NO_VERIFACTU,
                com.tpverp.backend.verifactu.FiscalEndpointEnvironment.TEST,
                "Prefijo congelado:", null, "Aviso congelado");
        when(fiscalQr.resolveForPrint(ticket.getId())).thenReturn(Optional.of(fiscalData));
        when(fiscalQrImages.png(url, 240)).thenReturn(
                new com.tpverp.backend.verifactu.FiscalQrImage(
                        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47,
                                0x0D, 0x0A, 0x1A, 0x0A},
                        "image/png"));

        var printed = service.renderTicketPrintView(ticket, service.ticketPrintView(ticket));

        assertThat(printed.qrUrl()).isEqualTo(url);
        assertThat(printed.qrImage()).startsWith("data:image/png;base64,");
        assertThat(printed.fiscal()).isEqualTo(fiscalData.toView());
    }

    @Test
    void nonFiscalPrintRemainsQrFree() {
        var fiscalQr = org.mockito.Mockito.mock(DocumentFiscalQrService.class);
        var fiscalQrImages = org.mockito.Mockito.mock(
                com.tpverp.backend.verifactu.FiscalQrImageService.class);
        service.setFiscalQrServices(fiscalQr, fiscalQrImages);
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", user.getId(), NOW, false);
        when(fiscalQr.resolveForPrint(ticket.getId())).thenReturn(Optional.empty());

        var printed = service.renderTicketPrintView(ticket, service.ticketPrintView(ticket));

        assertThat(printed.qrUrl()).isNull();
        assertThat(printed.qrImage()).isNull();
        verifyNoInteractions(fiscalQrImages);
    }

    @Test
    void compensatingExchangeSummaryNeverInheritsSaleQrOrJasperRaster() {
        var fiscalQr = org.mockito.Mockito.mock(DocumentFiscalQrService.class);
        var fiscalQrImages = org.mockito.Mockito.mock(
                com.tpverp.backend.verifactu.FiscalQrImageService.class);
        var renderer = org.mockito.Mockito.mock(
                com.tpverp.backend.document.template.TicketJasperRenderer.class);
        service.setFiscalQrServices(fiscalQr, fiscalQrImages);
        service.setTicketJasperRenderer(renderer);
        var refund = draft(CommercialDocumentType.TICKET);
        refund.confirm("001-260608-00001", user.getId(), NOW, false);
        var sale = draft(CommercialDocumentType.TICKET);
        sale.confirm("001-260608-00002", user.getId(), NOW, false);

        var printed = service.renderTicketPrintView(
                sale, service.ticketPrintViewFromExchange(sale, refund));

        assertThat(printed.nonFiscalSummary()).isTrue();
        assertThat(printed.qrUrl()).isNull();
        assertThat(printed.qrImage()).isNull();
        assertThat(printed.fiscal()).isNull();
        assertThat(printed.ticketRenderedPdf()).isNull();
        assertThat(printed.ticketRenderedImage()).isNull();
        verifyNoInteractions(fiscalQr, fiscalQrImages, renderer);
    }

    @Test
    void compensatingExchangeReprintReconstructsRectificationBeforeReplacementSale() {
        var fiscalQr = org.mockito.Mockito.mock(DocumentFiscalQrService.class);
        var fiscalQrImages = org.mockito.Mockito.mock(
                com.tpverp.backend.verifactu.FiscalQrImageService.class);
        service.setFiscalQrServices(fiscalQr, fiscalQrImages);
        var refund = draft(CommercialDocumentType.TICKET);
        refund.confirm("R-001", user.getId(), NOW.minusSeconds(1), false);
        var sale = draft(CommercialDocumentType.TICKET);
        sale.confirm("T-002", user.getId(), NOW, false);
        when(documentRepository.findById(sale.getId())).thenReturn(Optional.of(sale));
        when(documentRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(relationRepository.findOriginId(sale.getId(), DocumentRelationType.COMPENSA))
                .thenReturn(Optional.of(refund.getId()));
        when(fiscalQr.resolveForPrint(any())).thenReturn(Optional.empty());

        var printSet = service.loadRenderedTicketPrintSet(sale.getId());

        assertThat(printSet.printTicket().documentId()).isEqualTo(sale.getId());
        assertThat(printSet.additionalPrintTickets()).singleElement()
                .extracting(TicketPrintView::documentId)
                .isEqualTo(refund.getId());
        assertThat(printSet.nonFiscalSummary()).isNotNull();
        assertThat(printSet.nonFiscalSummary().nonFiscalSummary()).isTrue();
        assertThat(printSet.nonFiscalSummary().qrUrl()).isNull();
        assertThat(printSet.nonFiscalSummary().ticketRenderedImage()).isNull();
        var compatiblePrint = service.loadRenderedTicketPrintView(sale.getId());
        assertThat(compatiblePrint.documentId()).isEqualTo(sale.getId());
        assertThat(compatiblePrint.nonFiscalSummary()).isFalse();
        verify(fiscalQr, times(3)).resolveForPrint(any());
        verifyNoInteractions(fiscalQrImages);
    }

    @Test
    void fiscalPrintFailsExplicitlyWhenQrImageCannotBeGenerated() {
        var fiscalQr = org.mockito.Mockito.mock(DocumentFiscalQrService.class);
        var fiscalQrImages = org.mockito.Mockito.mock(
                com.tpverp.backend.verifactu.FiscalQrImageService.class);
        service.setFiscalQrServices(fiscalQr, fiscalQrImages);
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", user.getId(), NOW, false);
        var url = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?nif=B12345674";
        when(fiscalQr.resolveForPrint(ticket.getId())).thenReturn(Optional.of(
                new DocumentFiscalQrService.FiscalQrPrintData(url, "a".repeat(64))));
        when(fiscalQrImages.png(url, 240))
                .thenThrow(new IllegalStateException("qr_encoder_failed"));

        assertThatThrownBy(() -> service.renderTicketPrintView(
                ticket, service.ticketPrintView(ticket)))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason.IMAGE_GENERATION_FAILED);
    }

    @Test
    void fiscalPrintRejectsAResponseThatIsNotActuallyPng() {
        var fiscalQr = org.mockito.Mockito.mock(DocumentFiscalQrService.class);
        var fiscalQrImages = org.mockito.Mockito.mock(
                com.tpverp.backend.verifactu.FiscalQrImageService.class);
        service.setFiscalQrServices(fiscalQr, fiscalQrImages);
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", user.getId(), NOW, false);
        var url = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?nif=B12345674";
        when(fiscalQr.resolveForPrint(ticket.getId())).thenReturn(Optional.of(
                new DocumentFiscalQrService.FiscalQrPrintData(url, "a".repeat(64))));
        when(fiscalQrImages.png(url, 240)).thenReturn(
                new com.tpverp.backend.verifactu.FiscalQrImage(
                        "not-png".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "image/png"));

        assertThatThrownBy(() -> service.renderTicketPrintView(
                ticket, service.ticketPrintView(ticket)))
                .isInstanceOf(FiscalQrUnavailableException.class)
                .extracting(error -> ((FiscalQrUnavailableException) error).reason())
                .isEqualTo(FiscalQrUnavailableException.Reason.IMAGE_GENERATION_FAILED);
    }

    private CommercialDocument draft(CommercialDocumentType type) {
        var command = command(type);
        var document = new CommercialDocument(
                store.getId(), command.almacenId(), type, command.fecha(),
                user.getId(), command.descuentoGlobal());
        command.lineas().forEach(line -> document.addLine(line.toEntity(document)));
        return document;
    }

    private void addFullCashPayment(CommercialDocument document) {
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        document.addPayment(new DocumentPayment(
                document, cash, 1, document.getTotal(), true,
                document.getTotal(), BigDecimal.ZERO, null, null, NOW));
    }

    private CommercialDocument confirmedSales(
            CommercialDocumentType type, UUID customerId, String total) {
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), type, LocalDate.of(2026, 6, 8),
                user.getId(), BigDecimal.ZERO);
        document.setParties(customerId, null, null);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P-REL", "Producto", "VENTA",
                new BigDecimal(total), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.confirm(type.prefix() + "-001-26-000001", user.getId(), NOW, false);
        return document;
    }

    private void stubLocked(CommercialDocument first, CommercialDocument second) {
        when(documentRepository.findLockedDocument(first.getId(), store.getId()))
                .thenReturn(Optional.of(first));
        when(documentRepository.findLockedDocument(second.getId(), store.getId()))
                .thenReturn(Optional.of(second));
    }

    private Customer completeCustomer() {
        return new Customer(
                store.getEmpresa(), "Cliente", DocumentType.NIF, "12345678Z",
                new FiscalAddress("Calle 1", "35001", "Las Palmas",
                        "Las Palmas", "ES"),
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
    }

    private DocumentCommand command(CommercialDocumentType type) {
        return command(type, lines());
    }

    private DocumentCommand command(CommercialDocumentType type, List<DocumentLineCommand> lines) {
        return new DocumentCommand(
                UUID.randomUUID(),
                type,
                LocalDate.of(2026, 6, 8),
                null,
                null,
                null,
                BigDecimal.ZERO,
                false,
                lines);
    }

    private DocumentCommand command(
            CommercialDocumentType type, List<DocumentLineCommand> lines, UUID customerId) {
        var base = command(type, lines);
        return new DocumentCommand(
                base.almacenId(), base.tipo(), base.fecha(), customerId, null, null,
                base.descuentoGlobal(), base.directo(), base.lineas());
    }

    private DocumentCommand directCommand(CommercialDocumentType type, UUID customerId) {
        var base = command(type, lines(), customerId);
        return new DocumentCommand(
                base.almacenId(), base.tipo(), base.fecha(), base.clienteId(), null, null,
                base.descuentoGlobal(), true, base.lineas());
    }

    private List<DocumentLineCommand> lines() {
        return List.of(line(UUID.randomUUID(), "P-1", "Producto", new BigDecimal("10.00")));
    }

    private DocumentLineCommand line(UUID productId, String code, String name, BigDecimal price) {
        return new DocumentLineCommand(
                productId, 1, code, name, "VENTA", price,
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
    }

    private Product product(UUID productId, DiscountType discountType) {
        var product = org.mockito.Mockito.mock(Product.class);
        lenient().when(product.getId()).thenReturn(productId);
        lenient().when(product.getStoreId()).thenReturn(store.getId());
        lenient().when(product.getProductType()).thenReturn(ProductType.UNIT);
        lenient().when(product.getDiscountType()).thenReturn(discountType);
        lenient().when(product.isActive()).thenReturn(true);
        return product;
    }

    private PromotionCatalogGateway.ProductSnapshot productSnapshot(Product product) {
        var snapshot = org.mockito.Mockito.mock(PromotionCatalogGateway.ProductSnapshot.class);
        lenient().when(snapshot.product()).thenReturn(product);
        lenient().when(snapshot.authoritativeSnapshot(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return snapshot;
    }

    private DocumentLineCommand promotionCommand(
            UUID promotionId,
            UUID couponId,
            BigDecimal amount) {
        return new DocumentLineCommand(
                null, BigDecimal.ONE, "PROMO", "PROMOCION 3x2 Agua", null,
                amount, BigDecimal.ZERO, true, "IVA", new BigDecimal("21"),
                couponId == null ? DocumentLineType.PROMOTION : DocumentLineType.PROMOTIONAL_COUPON,
                promotionId, null, couponId);
    }

    private DocumentCommand negativeTicketCommand() {
        return new DocumentCommand(
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 8),
                null,
                null,
                null,
                BigDecimal.ZERO,
                false,
                List.of(new DocumentLineCommand(
                        UUID.randomUUID(), -1, "P-1", "Producto", "VENTA",
                        new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                        new BigDecimal("21"))));
    }

    private CommercialDocument createFrozenTicket(List<PaymentCommand> payments,PaymentMethod... methods) {
        for(var method:methods) when(paymentMethodRepository.findById(method.getId())).thenReturn(Optional.of(method));
        return createFrozenTicket(payments);
    }

    private CommercialDocument createFrozenTicket(List<PaymentCommand> payments) {
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(),any(),any())).thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        var frozen=new ApprovedCardTicketSnapshot(store.getId(),UUID.randomUUID(),LocalDate.of(2026,6,8),null,
                payments.getFirst().metodoPagoId(),BigDecimal.ZERO,new BigDecimal("8.26"),new BigDecimal("1.74"),
                new BigDecimal("10.00"),List.of(new DocumentLineCommand(UUID.randomUUID(),BigDecimal.ONE,"P","Producto",
                "VENTA",new BigDecimal("10.00"),BigDecimal.ZERO,true,"IVA",new BigDecimal("21"))));
        return service.createApprovedCardTicketFromSnapshot(frozen,payments,authentication());
    }

    private static MemberLoyaltyService.LoyaltyAccrual accrualOf(String amount) {
        return argThat(accrual -> accrual != null
                && accrual.eligiblePaidAmount().compareTo(new BigDecimal(amount)) == 0);
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "ADMIN", "n/a", List.of(() -> "ROLE_ADMIN"));
    }

    private UsernamePasswordAuthenticationToken authentication(String authority) {
        return UsernamePasswordAuthenticationToken.authenticated(
                "USER", "n/a", List.of(() -> authority));
    }
}
