package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.excel.ProductImportLineMetadataRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.promotion.Promotion;
import com.tpverp.backend.promotion.PromotionEngine;
import com.tpverp.backend.promotion.PromotionRepository;
import com.tpverp.backend.promotion.PromotionScope;
import com.tpverp.backend.promotion.PromotionStatus;
import com.tpverp.backend.promotion.PromotionTarget;
import com.tpverp.backend.promotion.PromotionTargetRepository;
import com.tpverp.backend.promotion.PromotionTargetType;
import com.tpverp.backend.promotion.PromotionType;
import com.tpverp.backend.promotion.PromotionalCouponBenefitType;
import com.tpverp.backend.promotion.PromotionalCouponService;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.StorePaymentConfigurationRepository;
import com.tpverp.backend.terminal.TerminalPaymentConfigurationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class DocumentPromotionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-09T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 9);

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
        lenient().when(currentOrganization.currentStore()).thenReturn(store);
        lenient().when(currentOrganization.currentCompany()).thenReturn(store.getEmpresa());
        lenient().when(currentOrganization.currentUser(any())).thenReturn(user);
        lenient().when(currentTerminal.terminalId(any())).thenReturn(UUID.randomUUID());
        lenient().when(importMetadata.findByDocumentId(any())).thenReturn(List.of());
        lenient().when(memberLoyaltyService.applyLineBenefit(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
    void ticketAppliesBuyXPayYPromotionBeforePaymentValidation() {
        var productId = UUID.randomUUID();
        var product = product(productId, UUID.randomUUID(), null);
        var promotion = buyXPayY("3x2 Agua", 3, 2);
        var cash = new PaymentMethod(store.getEmpresa().getId(), "EFECTIVO", true);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.findAllByStoreIdAndIdIn(store.getId(), List.of(productId)))
                .thenReturn(List.of(product));
        when(promotionRepository.findByEmpresaIdAndEstado(store.getEmpresa().getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(promotion));
        when(promotionTargetRepository.findByPromocionIdIn(List.of(promotion.id())))
                .thenReturn(List.of(new PromotionTarget(
                        promotion.id(), PromotionTargetType.PRODUCT, productId)));
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var ticket = service.createTicket(
                command(CommercialDocumentType.TICKET, List.of(line(productId, "3", "1.00"))),
                List.of(new PaymentCommand(cash.getId(), new BigDecimal("2.00"), true, null, null)),
                authentication());

        assertThat(ticket.getTotal()).isEqualByComparingTo("2.00");
        assertThat(ticket.getLineas()).extracting(DocumentLine::getLineType)
                .containsExactly(DocumentLineType.PRODUCT, DocumentLineType.PROMOTION);
        assertThat(ticket.getLineas().get(1).getTotal()).isEqualByComparingTo("-1.00");
        assertThat(ticket.getPagos()).singleElement()
                .satisfies(payment -> assertThat(payment.getImporte()).isEqualByComparingTo("2.00"));
        verify(stockGateway).confirm(ticket);
    }

    @Test
    void confirmedEligibleSalesDocumentGeneratesCouponsAfterSave() {
        var document = draft(CommercialDocumentType.ALBARAN_VENTA);
        var promotion = purchaseThresholdCoupon();
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(promotionRepository.findByEmpresaIdAndEstado(store.getEmpresa().getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(promotion));

        service.confirm(document.getId(), authentication());

        var command = ArgumentCaptor.forClass(PromotionalCouponService.CreationCommand.class);
        var ordered = inOrder(documentRepository, promotionalCoupons);
        ordered.verify(documentRepository).save(document);
        ordered.verify(promotionalCoupons).generateAfterTicketConfirmation(command.capture());
        assertThat(command.getValue().generatedDocumentId()).isEqualTo(document.getId());
        assertThat(command.getValue().generatedStoreId()).isEqualTo(store.getId());
        assertThat(command.getValue().promotionId()).isEqualTo(promotion.id());
        assertThat(command.getValue().benefitType()).isEqualTo(PromotionalCouponBenefitType.AMOUNT);
        assertThat(command.getValue().amount()).isEqualByComparingTo("5.00");
    }

    @Test
    void oldDatedDocumentDoesNotGenerateCouponFromPromotionInactiveOnCurrentDate() {
        var document = draft(CommercialDocumentType.ALBARAN_VENTA, TODAY.minusDays(10), UUID.randomUUID());
        var promotion = purchaseThresholdCoupon();
        promotion.configureManagementFields(TODAY.minusDays(10), TODAY.minusDays(1), PromotionScope.SALE, null);
        promotion.activate();
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(promotionRepository.findByEmpresaIdAndEstado(store.getEmpresa().getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(promotion));

        service.confirm(document.getId(), authentication());

        verify(promotionalCoupons, never()).generateAfterTicketConfirmation(any());
    }

    @Test
    void futureDatedDocumentDoesNotGenerateCouponFromPromotionInactiveOnCurrentDate() {
        var document = draft(CommercialDocumentType.ALBARAN_VENTA, TODAY.plusDays(10), UUID.randomUUID());
        var promotion = purchaseThresholdCoupon();
        promotion.configureManagementFields(TODAY.plusDays(1), null, PromotionScope.SALE, null);
        promotion.activate();
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(promotionRepository.findByEmpresaIdAndEstado(store.getEmpresa().getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(promotion));

        service.confirm(document.getId(), authentication());

        verify(promotionalCoupons, never()).generateAfterTicketConfirmation(any());
    }

    @Test
    void targetedCouponPromotionIsNotGeneratedForUnrelatedProduct() {
        var documentProductId = UUID.randomUUID();
        var targetProductId = UUID.randomUUID();
        var document = draft(CommercialDocumentType.ALBARAN_VENTA, TODAY, documentProductId);
        var product = product(documentProductId, UUID.randomUUID(), null);
        var promotion = purchaseThresholdCoupon();
        promotion.configureManagementFields(TODAY.minusDays(1), null, PromotionScope.PRODUCT_LIST, null);
        promotion.activate();
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(productRepository.findAllByStoreIdAndIdIn(store.getId(), List.of(documentProductId)))
                .thenReturn(List.of(product));
        when(promotionRepository.findByEmpresaIdAndEstado(store.getEmpresa().getId(), PromotionStatus.ACTIVE))
                .thenReturn(List.of(promotion));
        when(promotionTargetRepository.findByPromocionIdIn(List.of(promotion.id())))
                .thenReturn(List.of(new PromotionTarget(
                        promotion.id(), PromotionTargetType.PRODUCT, targetProductId)));

        service.confirm(document.getId(), authentication());

        verify(promotionalCoupons, never()).generateAfterTicketConfirmation(any());
    }

    @Test
    void confirmedPurchaseDocumentDoesNotGenerateCoupons() {
        var supplier = new Supplier(
                store.getEmpresa(), "Proveedor", null, DocumentType.CIF, "B00000001",
                null, null, null, null);
        var document = draft(CommercialDocumentType.ALBARAN_COMPRA);
        document.setParties(null, supplier.getId(), null);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(supplierRepository.findByIdAndCompanyId(supplier.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(supplier));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.confirm(document.getId(), authentication());

        verify(promotionalCoupons, never()).generateAfterTicketConfirmation(any());
    }

    private Product product(UUID productId, UUID familyId, UUID subfamilyId) {
        var product = org.mockito.Mockito.mock(Product.class);
        lenient().when(product.getId()).thenReturn(productId);
        lenient().when(product.getStoreId()).thenReturn(store.getId());
        lenient().when(product.getFamilyId()).thenReturn(familyId);
        lenient().when(product.getSubfamilyId()).thenReturn(subfamilyId);
        lenient().when(product.getProductType()).thenReturn(ProductType.UNIT);
        lenient().when(product.getDiscountType()).thenReturn(DiscountType.NORMAL);
        return product;
    }

    private Promotion buyXPayY(String name, int buy, int pay) {
        var promotion = Promotion.draft(
                store.getEmpresa().getId(), name, PromotionType.BUY_X_PAY_Y, TODAY.minusDays(1));
        promotion.configureBuyXPayY(BigDecimal.valueOf(buy), BigDecimal.valueOf(pay));
        promotion.activate();
        return promotion;
    }

    private Promotion purchaseThresholdCoupon() {
        var promotion = Promotion.draft(
                store.getEmpresa().getId(), "Cupon por compra", PromotionType.PURCHASE_THRESHOLD_COUPON,
                TODAY.minusDays(1));
        promotion.configureGeneratedAmountCoupon(
                new BigDecimal("5.00"), new BigDecimal("10.00"), TODAY, TODAY.plusDays(30));
        promotion.activate();
        return promotion;
    }

    private CommercialDocument draft(CommercialDocumentType type) {
        return draft(type, TODAY, UUID.randomUUID());
    }

    private CommercialDocument draft(CommercialDocumentType type, LocalDate date, UUID productId) {
        var command = command(type, date, List.of(line(productId, "1", "10.00")));
        var document = new CommercialDocument(
                store.getId(), command.almacenId(), type, command.fecha(),
                user.getId(), command.descuentoGlobal());
        command.lineas().forEach(line -> document.addLine(line.toEntity(document)));
        return document;
    }

    private DocumentCommand command(CommercialDocumentType type, List<DocumentLineCommand> lines) {
        return command(type, TODAY, lines);
    }

    private DocumentCommand command(CommercialDocumentType type, LocalDate date, List<DocumentLineCommand> lines) {
        return new DocumentCommand(
                UUID.randomUUID(),
                type,
                date,
                null,
                null,
                null,
                BigDecimal.ZERO,
                false,
                lines);
    }

    private DocumentLineCommand line(UUID productId, String quantity, String price) {
        return new DocumentLineCommand(
                productId, new BigDecimal(quantity), "P-1", "Producto", "VENTA",
                new BigDecimal(price), BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "n/a");
    }
}
