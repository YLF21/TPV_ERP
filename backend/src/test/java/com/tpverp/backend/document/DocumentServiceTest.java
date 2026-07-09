package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.tpverp.backend.excel.ProductImportLineMetadata;
import com.tpverp.backend.excel.ProductImportLineMetadataRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.FiscalAddress;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.promotion.PromotionEngine;
import com.tpverp.backend.promotion.PromotionRepository;
import com.tpverp.backend.promotion.PromotionStatus;
import com.tpverp.backend.promotion.PromotionTargetRepository;
import com.tpverp.backend.promotion.PromotionalCouponService;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
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
    private SupplierRepository supplierRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ConfirmedPurchaseRecorder purchaseRecorder;
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
    private ProductImportLineMetadataRepository importMetadata;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private PromotionTargetRepository promotionTargetRepository;
    @Mock
    private PromotionalCouponService promotionalCoupons;

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
        lenient().when(importMetadata.findByDocumentId(any())).thenReturn(List.of());
        lenient().when(promotionRepository.findByEmpresaIdAndEstado(any(), any(PromotionStatus.class)))
                .thenReturn(List.of());
        lenient().when(promotionTargetRepository.findByPromocionIdIn(any())).thenReturn(List.of());
        lenient().when(memberLoyaltyService.applyLineBenefit(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(productRepository.findById(any())).thenAnswer(invocation -> {
            Product product = org.mockito.Mockito.mock(Product.class);
            lenient().when(product.getId()).thenReturn(invocation.getArgument(0));
            lenient().when(product.getStoreId()).thenReturn(store.getId());
            lenient().when(product.getProductType()).thenReturn(ProductType.UNIT);
            lenient().when(product.getDiscountType()).thenReturn(DiscountType.NORMAL);
            return Optional.of(product);
        });
        lenient().when(productRepository.findAllByStoreIdAndIdIn(any(), any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(1);
            return ids.stream().map(id -> {
                Product product = org.mockito.Mockito.mock(Product.class);
                lenient().when(product.getId()).thenReturn(id);
                lenient().when(product.getDiscountType()).thenReturn(DiscountType.NORMAL);
                return product;
            }).toList();
        });
        service = new DocumentService(
                documentRepository,
                counterRepository,
                paymentMethodRepository,
                relationRepository,
                stockGateway,
                currentOrganization,
                customerRepository,
                supplierRepository,
                productRepository,
                purchaseRecorder,
                fiscalIntegration,
                voucherService,
                currentTerminal,
                storePaymentConfigurations,
                terminalPaymentConfigurations,
                cashPaymentRecorder,
                memberLoyaltyService,
                syncOutbox,
                importMetadata,
                promotionRepository,
                promotionTargetRepository,
                new PromotionEngine(),
                promotionalCoupons,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void authenticatedUserOverridesClientSuppliedStoreAndUser() {
        when(currentOrganization.currentStore()).thenReturn(store);
        when(currentOrganization.currentUser(any())).thenReturn(user);
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        var created = service.createDeliveryNote(
                command(CommercialDocumentType.ALBARAN_VENTA),
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "ADMIN", "n/a"));

        assertThat(created.getTiendaId()).isEqualTo(store.getId());
        assertThat(created.getStockUserId()).isEqualTo(user.getId());
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
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(currentOrganization.currentUser(any())).thenReturn(user);

        assertThatThrownBy(() -> service.confirm(invoice.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fiscales");
    }

    @Test
    void confirmsDeliveryNoteWithAnnualNumber() {
        var document = draft(CommercialDocumentType.ALBARAN_VENTA);
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
        verify(memberLoyaltyService).recordPaidSale(ticket, new BigDecimal("10.00"));
    }

    @Test
    void ticketCreationAddsPromotionLineWithoutProductLookupOrMemberBenefit() {
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        var productId = UUID.randomUUID();
        var promotionId = UUID.randomUUID();
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var ticket = service.createTicket(
                command(CommercialDocumentType.TICKET, List.of(
                        line(productId, "AGUA", "Agua", new BigDecimal("3.00")),
                        promotionCommand(promotionId, null, new BigDecimal("-1.00")))),
                List.of(new PaymentCommand(cash.getId(), new BigDecimal("2.00"), true, null, null)),
                authentication());

        assertThat(ticket.getTotal()).isEqualByComparingTo("2.00");
        assertThat(ticket.getLineas()).extracting(DocumentLine::getLineType)
                .containsExactly(DocumentLineType.PRODUCT, DocumentLineType.PROMOTION);
        verify(productRepository, times(1)).findById(productId);
        verify(memberLoyaltyService, times(1)).applyLineBenefit(any(), any(), any());
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
        verify(memberLoyaltyService).recordPaidSale(ticket, new BigDecimal("6.00"));
    }

    @Test
    void loyaltyAccruesOnlyEligibleProductLines() {
        var paidMethod = new PaymentMethod(store.getEmpresa().getId(), "TARJETA", true);
        var eligibleId = UUID.randomUUID();
        var excludedId = UUID.randomUUID();
        var eligible = org.mockito.Mockito.mock(Product.class);
        var excluded = org.mockito.Mockito.mock(Product.class);
        when(eligible.getId()).thenReturn(eligibleId);
        when(eligible.getStoreId()).thenReturn(store.getId());
        when(eligible.getProductType()).thenReturn(ProductType.UNIT);
        when(eligible.getDiscountType()).thenReturn(DiscountType.NORMAL);
        when(excluded.getStoreId()).thenReturn(store.getId());
        when(excluded.getProductType()).thenReturn(ProductType.UNIT);
        when(excluded.getDiscountType()).thenReturn(DiscountType.NONE);
        when(productRepository.findById(eligibleId)).thenReturn(Optional.of(eligible));
        when(productRepository.findById(excludedId)).thenReturn(Optional.of(excluded));
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

        verify(memberLoyaltyService).recordPaidSale(ticket, new BigDecimal("10.00"));
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
    void convertsConfirmedTicketToInvoiceOnceWithoutStockOrPayments() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, true);
        var customer = completeCustomer();
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(false);
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                store.getId(), "FV", "2026")).thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentOrganization.currentUser(any())).thenReturn(user);

        var invoice = service.convertTicketToInvoice(
                ticket.getId(), customer.getId(), authentication());

        assertThat(invoice.getTipo()).isEqualTo(CommercialDocumentType.FACTURA_VENTA);
        assertThat(invoice.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
        assertThat(invoice.getNumero()).isEqualTo("FV-001-26-000001");
        assertThat(invoice.getNumTicket()).isEqualTo("001-260608-00001");
        assertThat(invoice.getLineas()).hasSize(ticket.getLineas().size());
        verify(stockGateway, never()).confirm(invoice);
        verify(relationRepository).save(any(DocumentRelation.class));
        verify(fiscalIntegration).registerInvoiceFromTicket(invoice, ticket);
    }

    @Test
    void invoiceFromTicketEnqueuesSyncEvent() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, true);
        var customer = completeCustomer();
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(false);
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                store.getId(), "FV", "2026")).thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var invoice = service.convertTicketToInvoice(
                ticket.getId(), customer.getId(), authentication());

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
    void ticketCannotBeConvertedTwice() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, false);
        var customer = completeCustomer();
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(relationRepository.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)).thenReturn(true);

        assertThatThrownBy(() -> service.convertTicketToInvoice(
                ticket.getId(), customer.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("facturado");
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
        verify(memberLoyaltyService).recordPaidSale(partial, new BigDecimal("4.00"));

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("6.00"), false, null, null)),
                authentication());

        assertThat(paid.getEstado()).isEqualTo(DocumentStatus.PAGADO);
        assertThat(paid.getPagos()).hasSize(2);
        verify(memberLoyaltyService).recordPaidSale(paid, new BigDecimal("6.00"));
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
    void nonDrawerPaymentRejectsDeliveredAmountAndChange() {
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
                .hasMessageContaining("message.payment.cash_amounts_only_for_cash_drawer");
    }

    @Test
    void adminEditOfConfirmedDeliveryNoteDoesNotTouchStockOrIdentity() {
        var note = draft(CommercialDocumentType.ALBARAN_COMPRA);
        note.confirm("AC-001-26-000001", UUID.randomUUID(), NOW, true);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));
        when(documentRepository.save(note)).thenReturn(note);

        var edited = service.adminEditConfirmed(
                note.getId(), BigDecimal.TEN, null, UUID.randomUUID(), lines());

        assertThat(edited.getNumero()).isEqualTo("AC-001-26-000001");
        assertThat(edited.getFecha()).isEqualTo(LocalDate.of(2026, 6, 8));
        assertThat(edited.isOrigenStock()).isTrue();
        verify(stockGateway, never()).confirm(any());
        verify(stockGateway, never()).cancel(any());
    }

    @Test
    void adminCannotEditConfirmedDocumentWithFiscalRecord() {
        var ticket = draft(CommercialDocumentType.TICKET);
        ticket.confirm("001-260608-00001", UUID.randomUUID(), NOW, true);
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(fiscalIntegration.hasFiscalRecord(ticket.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.adminEditConfirmed(
                ticket.getId(), BigDecimal.ZERO, null, null, lines()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fiscal");

        verify(documentRepository, never()).save(any());
    }

    @Test
    void onlyInvoicesCanBeRelatedToOriginDocuments() {
        var note = draft(CommercialDocumentType.ALBARAN_VENTA);
        var origin = draft(CommercialDocumentType.TICKET);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> service.relate(
                note.getId(), origin.getId(), DocumentRelationType.FACTURA_DE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("factura");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void invoiceRelationRequiresCompatibleOriginType() {
        var invoice = draft(CommercialDocumentType.FACTURA_VENTA);
        var originInvoice = draft(CommercialDocumentType.FACTURA_VENTA);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(documentRepository.findById(originInvoice.getId())).thenReturn(Optional.of(originInvoice));

        assertThatThrownBy(() -> service.relate(
                invoice.getId(), originInvoice.getId(), DocumentRelationType.FACTURA_DE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("origen");

        verify(relationRepository, never()).save(any());
    }

    @Test
    void confirmedPurchaseDeliveryNoteRecordsSupplierProducts() {
        var supplier = supplier(true);
        var note = purchaseDraft(CommercialDocumentType.ALBARAN_COMPRA, supplier, true);
        preparePurchaseConfirmation(note, supplier, true);

        service.confirm(note.getId(), authentication());

        verify(purchaseRecorder).record(
                supplier.getId(), note.getFecha(), productIds(note));
    }

    @Test
    void confirmedPurchaseDeliveryNoteIgnoresPromotionLinesWhenRecordingSupplierProducts() {
        var supplier = supplier(true);
        var productId = UUID.randomUUID();
        var note = purchaseDraft(
                CommercialDocumentType.ALBARAN_COMPRA,
                supplier,
                true,
                List.of(
                        line(productId, "P-1", "Producto", new BigDecimal("10.00")),
                        promotionCommand(UUID.randomUUID(), null, new BigDecimal("-1.00"))));
        preparePurchaseConfirmation(note, supplier, true);

        service.confirm(note.getId(), authentication());

        @SuppressWarnings("unchecked")
        var products = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(purchaseRecorder).record(
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.eq(note.getFecha()),
                products.capture());
        assertThat(products.getValue()).containsExactly(productId);
    }

    @Test
    void confirmedImportedPurchaseRecordsSupplierReferencesAndClearsMetadata() {
        var supplier = supplier(true);
        var note = purchaseDraft(CommercialDocumentType.ALBARAN_COMPRA, supplier, true);
        preparePurchaseConfirmation(note, supplier, true);
        var productId = note.getLineas().getFirst().getProductoId();
        when(importMetadata.findByDocumentId(note.getId())).thenReturn(List.of(
                new ProductImportLineMetadata(note.getId(), productId, "REF-1")));

        service.confirm(note.getId(), authentication());

        verify(purchaseRecorder).recordWithReferences(
                supplier.getId(), note.getFecha(), Map.of(productId, "REF-1"));
        verify(importMetadata).deleteByDocumentId(note.getId());
    }

    @Test
    void confirmedDirectPurchaseInvoiceRecordsSupplierProducts() {
        var supplier = supplier(true);
        var invoice = purchaseDraft(CommercialDocumentType.FACTURA_COMPRA, supplier, true);
        preparePurchaseConfirmation(invoice, supplier, true);

        service.confirm(invoice.getId(), authentication());

        verify(purchaseRecorder).record(
                supplier.getId(), invoice.getFecha(), productIds(invoice));
    }

    @Test
    void confirmedNonDirectPurchaseInvoiceDoesNotRecordSupplierProductsAgain() {
        var supplier = supplier(true);
        var invoice = purchaseDraft(CommercialDocumentType.FACTURA_COMPRA, supplier, false);
        preparePurchaseConfirmation(invoice, supplier, false);

        service.confirm(invoice.getId(), authentication());

        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    @Test
    void confirmedPurchaseCreditNoteDoesNotRecordSupplierProducts() {
        var supplier = supplier(true);
        var invoice = purchaseDraft(CommercialDocumentType.RECTIFICATIVA_COMPRA, supplier, true);
        preparePurchaseConfirmation(invoice, supplier, true);

        service.confirm(invoice.getId(), authentication());

        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    @Test
    void confirmedSalesDocumentDoesNotRecordSupplierProducts() {
        var note = draft(CommercialDocumentType.ALBARAN_VENTA);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));
        when(documentRepository.save(note)).thenReturn(note);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                note.getTiendaId(), "AV", "2026")).thenReturn(Optional.empty());
        when(stockGateway.confirm(note)).thenReturn(true);

        service.confirm(note.getId(), authentication());

        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    @Test
    void purchaseDeliveryNoteRequiresSupplierBeforeNumberingOrStock() {
        var note = draft(CommercialDocumentType.ALBARAN_COMPRA);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> service.confirm(note.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proveedor");

        verify(counterRepository, never())
                .findByTiendaIdAndTipoAndPeriodo(any(), any(), any());
        verify(stockGateway, never()).confirm(any());
        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    @Test
    void purchaseDeliveryNoteRequiresActiveSupplierBeforeNumberingOrStock() {
        var supplier = supplier(false);
        var note = purchaseDraft(CommercialDocumentType.ALBARAN_COMPRA, supplier, true);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));
        when(supplierRepository.findByIdAndCompanyId(
                supplier.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> service.confirm(note.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivo");

        verify(counterRepository, never())
                .findByTiendaIdAndTipoAndPeriodo(any(), any(), any());
        verify(stockGateway, never()).confirm(any());
        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    private CommercialDocument draft(CommercialDocumentType type) {
        var command = command(type);
        var document = new CommercialDocument(
                store.getId(), command.almacenId(), type, command.fecha(),
                user.getId(), command.descuentoGlobal());
        command.lineas().forEach(line -> document.addLine(line.toEntity(document)));
        return document;
    }

    private CommercialDocument purchaseDraft(
            CommercialDocumentType type, Supplier supplier, boolean stockOrigin) {
        return purchaseDraft(type, supplier, stockOrigin, lines());
    }

    private CommercialDocument purchaseDraft(
            CommercialDocumentType type,
            Supplier supplier,
            boolean stockOrigin,
            List<DocumentLineCommand> lines) {
        var command = command(type, lines);
        var document = new CommercialDocument(
                store.getId(), command.almacenId(), type, command.fecha(),
                user.getId(), command.descuentoGlobal());
        command.lineas().forEach(line -> document.addLine(line.toEntity(document)));
        document.setParties(null, supplier.getId(), null);
        document.setStockOrigin(stockOrigin);
        return document;
    }

    private void preparePurchaseConfirmation(
            CommercialDocument document, Supplier supplier, boolean appliesStock) {
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(supplierRepository.findByIdAndCompanyId(
                supplier.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(supplier));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                document.getTiendaId(),
                document.getTipo().prefix(),
                "2026")).thenReturn(Optional.empty());
        if (appliesStock) {
            when(stockGateway.confirm(document)).thenReturn(true);
        }
    }

    private Supplier supplier(boolean active) {
        var supplier = new Supplier(
                store.getEmpresa(), "Proveedor", null, DocumentType.CIF, "B00000001",
                null, null, null, null);
        if (!active) {
            supplier.deactivate();
        }
        return supplier;
    }

    private Customer completeCustomer() {
        return new Customer(
                store.getEmpresa(), "Cliente", DocumentType.NIF, "12345678Z",
                new FiscalAddress("Calle 1", "35001", "Las Palmas",
                        "Las Palmas", "ES"),
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
    }

    private List<UUID> productIds(CommercialDocument document) {
        return document.getLineas().stream()
                .map(DocumentLine::getProductoId)
                .distinct()
                .toList();
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

    private List<DocumentLineCommand> lines() {
        return List.of(line(UUID.randomUUID(), "P-1", "Producto", new BigDecimal("10.00")));
    }

    private DocumentLineCommand line(UUID productId, String code, String name, BigDecimal price) {
        return new DocumentLineCommand(
                productId, 1, code, name, "VENTA", price,
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
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

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "n/a");
    }
}
