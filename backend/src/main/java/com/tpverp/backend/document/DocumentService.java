package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.excel.ProductImportLineMetadata;
import com.tpverp.backend.excel.ProductImportLineMetadataRepository;
import com.tpverp.backend.inventory.StockSettingsService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.promotion.Promotion;
import com.tpverp.backend.promotion.AuthoritativePromotionPricing;
import com.tpverp.backend.promotion.PromotionCatalogGateway;
import com.tpverp.backend.promotion.PromotionCustomerSegment;
import com.tpverp.backend.promotion.PromotionEngine;
import com.tpverp.backend.promotion.PromotionEvaluationLine;
import com.tpverp.backend.promotion.PromotionEvaluationRequest;
import com.tpverp.backend.promotion.PromotionRepository;
import com.tpverp.backend.promotion.PromotionScope;
import com.tpverp.backend.promotion.PromotionStatus;
import com.tpverp.backend.promotion.PromotionTarget;
import com.tpverp.backend.promotion.PromotionTargetRepository;
import com.tpverp.backend.promotion.PromotionTargetType;
import com.tpverp.backend.promotion.PromotionType;
import com.tpverp.backend.promotion.PromotionalCouponBenefitType;
import com.tpverp.backend.promotion.PromotionalCouponService;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import com.tpverp.backend.terminal.StorePaymentConfiguration;
import com.tpverp.backend.terminal.StorePaymentConfigurationRepository;
import com.tpverp.backend.terminal.TerminalPaymentConfigurationRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

    private static final EnumSet<CommercialDocumentType> DELIVERY_NOTES = EnumSet.of(
            CommercialDocumentType.ALBARAN_VENTA, CommercialDocumentType.ALBARAN_COMPRA);
    private static final EnumSet<CommercialDocumentType> SALES_DELIVERY_NOTES = EnumSet.of(
            CommercialDocumentType.ALBARAN_VENTA);
    private static final EnumSet<CommercialDocumentType> INVOICES = EnumSet.of(
            CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.FACTURA_COMPRA,
            CommercialDocumentType.RECTIFICATIVA_VENTA, CommercialDocumentType.RECTIFICATIVA_COMPRA);
    private static final EnumSet<CommercialDocumentType> SALES_INVOICES = EnumSet.of(
            CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.RECTIFICATIVA_VENTA);
    private static final EnumSet<CommercialDocumentType> PURCHASE_DELIVERY_NOTES = EnumSet.of(
            CommercialDocumentType.ALBARAN_COMPRA);
    private static final EnumSet<CommercialDocumentType> PURCHASE_INVOICES = EnumSet.of(
            CommercialDocumentType.FACTURA_COMPRA, CommercialDocumentType.RECTIFICATIVA_COMPRA);
    private static final EnumSet<CommercialDocumentType> PURCHASE_DOCUMENTS = EnumSet.of(
            CommercialDocumentType.ALBARAN_COMPRA,
            CommercialDocumentType.FACTURA_COMPRA,
            CommercialDocumentType.RECTIFICATIVA_COMPRA);
    private static final EnumSet<CommercialDocumentType> PROMOTION_SALES_DOCUMENTS = EnumSet.of(
            CommercialDocumentType.TICKET,
            CommercialDocumentType.FACTURA_VENTA,
            CommercialDocumentType.ALBARAN_VENTA);
    private static final String MEMBER_BALANCE_METHOD = "SALDO_MIEMBRO";
    private static final String VOUCHER_METHOD = "VALE";

    private final CommercialDocumentRepository documents;
    private final DocumentCounterRepository counters;
    private final PaymentMethodRepository paymentMethods;
    private final DocumentRelationRepository relations;
    private final StockDocumentGateway stockGateway;
    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final SupplierRepository suppliers;
    private final ProductRepository products;
    private final ConfirmedPurchaseRecorder purchaseRecorder;
    private final DocumentFiscalIntegration fiscalIntegration;
    private final VoucherService vouchers;
    private final CurrentTerminal currentTerminal;
    private final StorePaymentConfigurationRepository storePaymentConfigurations;
    private final TerminalPaymentConfigurationRepository terminalPaymentConfigurations;
    private final CashPaymentRecorder cashPayments;
    private final MemberLoyaltyService memberLoyalty;
    private final SyncOutboxService syncOutbox;
    private final ProductImportLineMetadataRepository importMetadata;
    private final PromotionRepository promotions;
    private final PromotionTargetRepository promotionTargets;
    private final PromotionEngine promotionEngine;
    private final PromotionalCouponService promotionalCoupons;
    private final AuthoritativePromotionPricing promotionPricing;
    private final PromotionCatalogGateway promotionCatalog;
    private final StockSettingsService stockSettings;
    private final Clock clock;

    public DocumentService(
            CommercialDocumentRepository documents,
            DocumentCounterRepository counters,
            PaymentMethodRepository paymentMethods,
            DocumentRelationRepository relations,
            StockDocumentGateway stockGateway,
            CurrentOrganization organization,
            CustomerRepository customers,
            SupplierRepository suppliers,
            ProductRepository products,
            ConfirmedPurchaseRecorder purchaseRecorder,
            DocumentFiscalIntegration fiscalIntegration,
            VoucherService vouchers,
            CurrentTerminal currentTerminal,
            StorePaymentConfigurationRepository storePaymentConfigurations,
            TerminalPaymentConfigurationRepository terminalPaymentConfigurations,
            CashPaymentRecorder cashPayments,
            MemberLoyaltyService memberLoyalty,
            SyncOutboxService syncOutbox,
            ProductImportLineMetadataRepository importMetadata,
            PromotionRepository promotions,
            PromotionTargetRepository promotionTargets,
            PromotionEngine promotionEngine,
            PromotionalCouponService promotionalCoupons,
            AuthoritativePromotionPricing promotionPricing,
            PromotionCatalogGateway promotionCatalog,
            StockSettingsService stockSettings,
            Clock clock) {
        this.documents = documents;
        this.counters = counters;
        this.paymentMethods = paymentMethods;
        this.relations = relations;
        this.stockGateway = stockGateway;
        this.organization = organization;
        this.customers = customers;
        this.suppliers = suppliers;
        this.products = products;
        this.purchaseRecorder = purchaseRecorder;
        this.fiscalIntegration = fiscalIntegration;
        this.vouchers = vouchers;
        this.currentTerminal = currentTerminal;
        this.storePaymentConfigurations = storePaymentConfigurations;
        this.terminalPaymentConfigurations = terminalPaymentConfigurations;
        this.cashPayments = cashPayments;
        this.memberLoyalty = memberLoyalty;
        this.syncOutbox = syncOutbox;
        this.importMetadata = importMetadata;
        this.promotions = promotions;
        this.promotionTargets = promotionTargets;
        this.promotionEngine = promotionEngine;
        this.promotionalCoupons = promotionalCoupons;
        this.promotionPricing = promotionPricing;
        this.promotionCatalog = promotionCatalog;
        this.stockSettings = stockSettings;
        this.clock = clock;
    }

    @Transactional
    public CommercialDocument createDeliveryNote(
            DocumentCommand command, Authentication authentication) {
        requireType(command, DELIVERY_NOTES);
        requireDocumentWritePermission(
                command.tipo(), authentication, CorePermissionBootstrap.DELIVERY_NOTES_WRITE);
        return documents.save(createDraft(command, authentication));
    }

    @Transactional
    public CommercialDocument createAndConfirmDeliveryNote(
            DocumentCommand command, Authentication authentication) {
        var draft = createDeliveryNote(command, authentication);
        return confirm(draft.getId(), authentication);
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listDeliveryNotes() {
        return listDeliveryNotes(true);
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listDeliveryNotes(boolean includePurchaseDocuments) {
        return listDeliveryNotes(true, includePurchaseDocuments);
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listDeliveryNotes(
            boolean includeSalesDocuments,
            boolean includePurchaseDocuments) {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(),
                documentTypes(includeSalesDocuments, includePurchaseDocuments,
                        SALES_DELIVERY_NOTES, PURCHASE_DELIVERY_NOTES));
    }

    // Confirms, numbers, and records stock/purchase in one transaction.
    @Transactional
    public CommercialDocument confirm(UUID id, Authentication authentication) {
        var document = find(id);
        requireDocumentWritePermission(
                document.getTipo(), authentication, confirmPermission(document.getTipo()));
        var userId = organization.currentUser(authentication).getId();
        validateConfirmation(document);
        var promotionContext = promotionContext(document);
        applyDirectPromotions(document, promotionContext);
        validateInactiveSaleProducts(document);
        // Confirmation resets stockOrigin; this flag must be read first.
        boolean recordsPurchase = document.getTipo() == CommercialDocumentType.ALBARAN_COMPRA
                || (document.getTipo() == CommercialDocumentType.FACTURA_COMPRA
                && document.isOrigenStock());
        var requiresStock = requiresStock(document) || document.isOrigenStock();
        Instant confirmedAt = Instant.now(clock);
        document.confirm(nextNumber(document), userId, confirmedAt, false);
        document.setStockOrigin(requiresStock && stockGateway.confirm(document));
        if (recordsPurchase) {
            recordPurchase(document, confirmedAt);
        }
        var saved = documents.save(document);
        generatePromotionalCoupons(saved, promotionContext);
        fiscalIntegration.registerAlta(saved, false);
        enqueueConfirmedDocument(saved, null);
        return saved;
    }

    private void recordPurchase(CommercialDocument document, Instant confirmedAt) {
        var metadata = importMetadata.findByDocumentId(document.getId());
        var references = new LinkedHashMap<UUID, String>();
        for (var value : metadata) {
            references.putIfAbsent(value.productId(), value.supplierReference());
        }
        var lastLineByProduct = new LinkedHashMap<UUID, ConfirmedPurchaseRecorder.PurchaseLine>();
        for (DocumentLine line : document.getLineas()) {
            if (line.getLineType() == DocumentLineType.PRODUCT) {
                lastLineByProduct.put(line.getProductoId(), new ConfirmedPurchaseRecorder.PurchaseLine(
                        line.getProductoId(),
                        references.get(line.getProductoId()),
                        line.getPrecioUnitario(),
                        line.getDescuento()));
            }
        }
        purchaseRecorder.record(
                document.getProveedorId(),
                confirmedAt,
                List.copyOf(lastLineByProduct.values()));
        if (!metadata.isEmpty()) {
            importMetadata.deleteByDocumentId(document.getId());
        }
    }

    // Creates and confirms the ticket in one transaction.
    @Transactional(readOnly = true)
    public CommercialDocument quoteTicket(
            DocumentCommand command,
            Authentication authentication) {
        if (command.tipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException("message.document.invalid_ticket_type");
        }
        var customer = pricingCustomer(command);
        var ticket = createDraft(command, authentication, customer);
        applyDirectPromotions(ticket, promotionContext(ticket, customer));
        validateInactiveSaleProducts(ticket);
        return ticket;
    }

    // Creates and confirms the ticket in one transaction.
    @Transactional
    public CommercialDocument createTicket(
            DocumentCommand command,
            List<PaymentCommand> payments,
            Authentication authentication) {
        if (command.tipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException("message.document.invalid_ticket_type");
        }
        var customer = pricingCustomer(command);
        var ticket = createDraft(command, authentication, customer);
        var promotionContext = promotionContext(ticket, customer);
        applyDirectPromotions(ticket, promotionContext);
        validateInactiveSaleProducts(ticket);
        if (ticket.getTotal().signum() >= 0) {
            requirePaymentsPresent(payments);
            requirePaymentTotal(payments, ticket.getTotal(), "los pagos deben cuadrar con el total del ticket");
        }
        var terminalId = currentTerminal.terminalId(authentication);
        ticket.confirm(
                nextNumber(ticket),
                organization.currentUser(authentication).getId(),
                Instant.now(clock),
                false);
        if (ticket.getTotal().signum() >= 0) {
            addPayments(ticket, payments, "los pagos deben cuadrar con el total del ticket", terminalId);
        }
        // Stock movements reference the document, so its row must exist before the gateway inserts them.
        documents.saveAndFlush(ticket);
        ticket.setStockOrigin(stockGateway.confirm(ticket));
        var saved = documents.save(ticket);
        cashPayments.recordDocumentPayments(terminalId, saved);
        memberLoyalty.recordPaidSale(saved, eligibleAccrualAmount(
                saved, loyaltyAccrualPaymentTotal(saved.getPagos())));
        if (saved.getTotal().signum() < 0) {
            vouchers.issueFromNegativeTicket(saved);
        }
        generatePromotionalCoupons(saved, promotionContext);
        fiscalIntegration.registerAlta(saved, false);
        enqueueConfirmedDocument(saved, terminalId);
        return saved;
    }

    /** Confirms an already authorized card sale from its immutable fiscal snapshot. */
    @Transactional
    public CommercialDocument createApprovedCardTicketFromSnapshot(
            ApprovedCardTicketSnapshot snapshot,
            List<PaymentCommand> payments,
            Authentication authentication) {
        Objects.requireNonNull(snapshot, "snapshot");
        var store = organization.currentStore();
        if (!store.getId().equals(snapshot.storeId())) {
            throw new IllegalStateException("La instantanea pertenece a otra tienda");
        }
        var ticket = new CommercialDocument(snapshot.storeId(), snapshot.warehouseId(),
                CommercialDocumentType.TICKET, snapshot.date(),
                organization.currentUser(authentication).getId(), snapshot.globalDiscount());
        ticket.setParties(snapshot.customerId(), null, null);
        snapshot.lines().forEach(line -> ticket.addLine(line.toEntity(ticket)));
        validateInactiveSaleProducts(ticket);
        if (ticket.getBaseTotal().compareTo(Money.euros(snapshot.baseTotal())) != 0
                || ticket.getImpuestoTotal().compareTo(Money.euros(snapshot.taxTotal())) != 0
                || ticket.getTotal().compareTo(Money.euros(snapshot.total())) != 0) {
            throw new IllegalStateException("La instantanea fiscal no cuadra con sus lineas");
        }
        requirePaymentsPresent(payments);
        requirePaymentTotal(payments, ticket.getTotal(), "los pagos deben cuadrar con el total autorizado");
        var terminalId = currentTerminal.terminalId(authentication);
        ticket.confirm(nextNumber(ticket), organization.currentUser(authentication).getId(), Instant.now(clock), false);
        addPayments(ticket, payments, "los pagos deben cuadrar con el total autorizado", terminalId);
        documents.saveAndFlush(ticket);
        ticket.setStockOrigin(stockGateway.confirm(ticket));
        var saved = documents.save(ticket);
        cashPayments.recordDocumentPayments(terminalId, saved);
        memberLoyalty.recordPaidSale(saved, eligibleAccrualAmount(
                saved, loyaltyAccrualPaymentTotal(saved.getPagos())));
        fiscalIntegration.registerAlta(saved, false);
        enqueueConfirmedDocument(saved, terminalId);
        return saved;
    }

    /** Creates the fiscal/commercial reversal after the acquirer approved a full card refund. */
    @Transactional
    public CommercialDocument createApprovedCardRefund(
            UUID originalDocumentId, BigDecimal approvedAmount, Authentication authentication) {
        return createApprovedCardRefund(UUID.randomUUID(), originalDocumentId, approvedAmount, authentication);
    }

    @Transactional
    public CommercialDocument createApprovedCardRefund(UUID operationId,
            UUID originalDocumentId, BigDecimal approvedAmount, Authentication authentication) {
        var replay = documents.findByPaymentTerminalRefundOperationId(Objects.requireNonNull(operationId));
        if (replay.isPresent()) return replay.orElseThrow();
        var original = find(Objects.requireNonNull(originalDocumentId, "originalDocumentId"));
        if (original.getEstado() == DocumentStatus.BORRADOR || original.getEstado() == DocumentStatus.ANULADO) {
            throw new IllegalStateException("El documento original no admite devolucion");
        }
        if (Money.euros(approvedAmount).compareTo(original.getTotal()) != 0) {
            throw new IllegalArgumentException(
                    "La devolucion parcial requiere desglose explicito de lineas y cantidades");
        }
        var refund = new CommercialDocument(original.getTiendaId(), original.getAlmacenId(),
                CommercialDocumentType.TICKET, LocalDate.now(clock),
                organization.currentUser(authentication).getId(), original.getDescuentoGlobal());
        refund.setParties(original.getClienteId(), null, null);
        refund.identifyPaymentTerminalRefund(operationId);
        original.getLineas().stream().map(DocumentLineCommand::from).forEach(line ->
                refund.addLine(new DocumentLineCommand(line.productoId(), line.cantidad().negate(),
                        line.codigo(), line.nombre(), line.tarifa(), line.precioUnitario(), line.descuento(),
                        line.impuestosIncluidos(), line.regimenImpuesto(), line.porcentajeImpuesto(),
                        line.lineType(), line.promotionId(), line.promotionVersionId(), line.promotionalCouponId())
                        .toEntity(refund)));
        if (refund.getTotal().compareTo(original.getTotal().negate()) != 0) {
            throw new IllegalStateException("La instantanea fiscal de devolucion no cuadra");
        }
        refund.confirm(nextNumber(refund), organization.currentUser(authentication).getId(), Instant.now(clock), false);
        documents.saveAndFlush(refund);
        refund.setStockOrigin(stockGateway.confirm(refund));
        var saved = documents.save(refund);
        relations.save(new DocumentRelation(saved, original, DocumentRelationType.RECTIFICA));
        fiscalIntegration.registerAlta(saved, false);
        enqueueConfirmedDocument(saved, currentTerminal.terminalId(authentication));
        return saved;
    }

    private PromotionContext promotionContext(CommercialDocument document) {
        if (!PROMOTION_SALES_DOCUMENTS.contains(document.getTipo())) {
            return PromotionContext.empty();
        }
        var customer = promotionPricing.customerContext(
                organization.currentCompany().getId(), document.getClienteId());
        return promotionContext(document, customer);
    }

    private PromotionContext promotionContext(
            CommercialDocument document,
            AuthoritativePromotionPricing.CustomerContext customer) {
        if (!PROMOTION_SALES_DOCUMENTS.contains(document.getTipo())) {
            return PromotionContext.empty();
        }
        var active = activePromotions(document, customer);
        if (active.isEmpty()) {
            return new PromotionContext(List.of(), List.of(), List.of(), customer);
        }
        return new PromotionContext(
                active,
                promotionTargets(active),
                promotionEvaluationLines(document),
                customer);
    }

    private void applyDirectPromotions(
            CommercialDocument document,
            PromotionContext context) {
        if (!PROMOTION_SALES_DOCUMENTS.contains(document.getTipo())) {
            return;
        }
        if (document.getLineas().stream()
                .anyMatch(line -> line.getLineType() != DocumentLineType.PRODUCT)) {
            throw new IllegalArgumentException(
                    "El documento contiene lineas automaticas no generadas por esta confirmacion");
        }
        var active = context.promotions().stream()
                .filter(DocumentService::isDirectLinePromotion)
                .toList();
        if (active.isEmpty() || context.lines().isEmpty()) {
            return;
        }
        var preview = promotionEngine.preview(new PromotionEvaluationRequest(
                context.lines(),
                active,
                context.targets()));
        var position = document.getLineas().stream()
                .mapToInt(DocumentLine::getPosicion)
                .max()
                .orElse(0) + 1;
        for (var benefit : preview.appliedPromotions()) {
            document.addLine(DocumentLine.special(
                    document,
                    position++,
                    "PROMOCION " + benefit.name(),
                    benefit.amount().negate(),
                    benefit.taxIncluded(),
                    benefit.taxRegime(),
                    benefit.taxPercent(),
                    benefit.promotionId(),
                    benefit.promotionVersionId(),
                    null));
            active.stream()
                    .filter(promotion -> promotion.id().equals(benefit.promotionVersionId()))
                    .findFirst()
                    .ifPresent(Promotion::markUsed);
        }
    }

    private List<PromotionTarget> promotionTargets(List<Promotion> active) {
        var promotionIds = active.stream().map(Promotion::id).toList();
        if (promotionIds.isEmpty()) {
            return List.of();
        }
        return promotionTargets.findByPromocionIdIn(promotionIds);
    }

    private Map<UUID, Product> productMap(CommercialDocument document, List<DocumentLine> productLines) {
        var ids = productLines.stream()
                .map(DocumentLine::getProductoId)
                .distinct()
                .toList();
        var result = new HashMap<UUID, Product>();
        products.findAllByStoreIdAndIdIn(document.getTiendaId(), ids)
                .forEach(product -> result.put(product.getId(), product));
        return result;
    }

    private static PromotionEvaluationLine evaluationLine(
            CommercialDocument document,
            DocumentLine line,
            Product product) {
        if (product == null) {
            throw new IllegalStateException("Producto de linea no encontrado en la tienda");
        }
        var globalFactor = BigDecimal.ONE.subtract(document.getDescuentoGlobal().movePointLeft(2));
        var payableUnitAmount = line.getTotal().multiply(globalFactor)
                .divide(line.getCantidad(), 6, Money.ROUNDING);
        return new PromotionEvaluationLine(
                line.getPosicion(),
                line.getProductoId(),
                product.getFamilyId(),
                product.getSubfamilyId(),
                line.getCantidad(),
                line.getPrecioUnitario(),
                line.isImpuestosIncluidos(),
                line.getRegimenImpuesto(),
                line.getPorcentajeImpuesto(),
                line.getDescuento().signum() > 0 || document.getDescuentoGlobal().signum() > 0,
                product.getDiscountType() != DiscountType.NONE,
                payableUnitAmount);
    }

    private void generatePromotionalCoupons(
            CommercialDocument document,
            PromotionContext context) {
        if (!PROMOTION_SALES_DOCUMENTS.contains(document.getTipo())) {
            return;
        }
        var couponPromotions = context.promotions().stream()
                .filter(DocumentService::isCouponPromotion)
                .toList();
        couponPromotions.stream()
                .filter(promotion -> matchesAnyEligibleLine(
                        promotion, context.lines(), context.targets()))
                .map(promotion -> couponCommand(document, promotion, context))
                .flatMap(Optional::stream)
                .forEach(promotionalCoupons::generateAfterTicketConfirmation);
    }

    private Optional<PromotionalCouponService.CreationCommand> couponCommand(
            CommercialDocument document,
            Promotion promotion,
            PromotionContext context) {
        if (!isCouponPromotion(promotion)) {
            return Optional.empty();
        }
        var minimum = promotion.minimumAmount();
        var finalPayable = document.getTotal().max(BigDecimal.ZERO);
        if (minimum != null && finalPayable.compareTo(minimum) < 0) {
            return Optional.empty();
        }
        var benefitType = couponBenefitType(promotion);
        if (benefitType.isEmpty()) {
            return Optional.empty();
        }
        var validFrom = couponValidFrom(document.getFecha(), promotion);
        var validUntil = couponValidUntil(validFrom, promotion);
        if (validUntil == null || validUntil.isBefore(validFrom)) {
            return Optional.empty();
        }
        promotion.markUsed();
        return Optional.of(new PromotionalCouponService.CreationCommand(
                organization.currentCompany().getId(),
                document.getTiendaId(),
                promotion.id(),
                document.getId(),
                document.getClienteId(),
                context.customer().memberId(),
                promotion.customerSegment(),
                promotion.memberCategoryId(),
                benefitType.get(),
                promotion.couponAmount(),
                promotion.couponPercent(),
                promotion.couponMaximumDiscount(),
                promotion.couponMinimumAmount(),
                atStartOfDay(validFrom),
                atStartOfDay(validUntil)));
    }

    private static Optional<PromotionalCouponBenefitType> couponBenefitType(Promotion promotion) {
        if (promotion.couponAmount() != null) {
            return Optional.of(PromotionalCouponBenefitType.AMOUNT);
        }
        if (promotion.couponPercent() != null) {
            return Optional.of(PromotionalCouponBenefitType.PERCENT);
        }
        return Optional.empty();
    }

    private LocalDate couponValidFrom(LocalDate documentDate, Promotion promotion) {
        if (promotion.couponValidFromDate() != null) {
            return promotion.couponValidFromDate();
        }
        if (promotion.couponValidFromDays() != null) {
            return documentDate.plusDays(promotion.couponValidFromDays());
        }
        return documentDate;
    }

    private LocalDate couponValidUntil(LocalDate validFrom, Promotion promotion) {
        if (promotion.couponValidUntilDate() != null) {
            return promotion.couponValidUntilDate();
        }
        if (promotion.couponValidDays() != null) {
            return validFrom.plusDays(promotion.couponValidDays());
        }
        return null;
    }

    private Instant atStartOfDay(LocalDate date) {
        return date.atStartOfDay(clock.getZone()).toInstant();
    }

    private List<Promotion> activePromotions(
            CommercialDocument document,
            AuthoritativePromotionPricing.CustomerContext customer) {
        var documentDate = document.getFecha();
        return promotions.findByEmpresaIdAndEstado(
                        organization.currentCompany().getId(), PromotionStatus.ACTIVE).stream()
                .filter(promotion -> appliesOnDate(promotion, documentDate))
                .filter(promotion -> promotionPricing.matchesSegment(promotion, customer))
                .toList();
    }

    private List<PromotionEvaluationLine> promotionEvaluationLines(CommercialDocument document) {
        var productLines = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .filter(line -> line.getCantidad().signum() > 0)
                .toList();
        if (productLines.isEmpty()) {
            return List.of();
        }
        var productsById = productMap(document, productLines);
        return productLines.stream()
                .map(line -> evaluationLine(document, line, productsById.get(line.getProductoId())))
                .toList();
    }

    private static boolean matchesAnyEligibleLine(
            Promotion promotion,
            List<PromotionEvaluationLine> lines,
            List<PromotionTarget> targets) {
        var promotionTargets = targets.stream()
                .filter(target -> target.promotionId().equals(promotion.id()))
                .toList();
        if (promotionTargets.isEmpty()) {
            return promotion.scope() == PromotionScope.SALE
                    && lines.stream().anyMatch(line -> line.discountable() && !line.manualDiscount());
        }
        return lines.stream()
                .filter(PromotionEvaluationLine::discountable)
                .filter(line -> !line.manualDiscount())
                .anyMatch(line -> promotionTargets.stream().anyMatch(target ->
                        (target.type() == PromotionTargetType.PRODUCT
                                && target.targetId().equals(line.productId()))
                                || (target.type() == PromotionTargetType.FAMILY
                                && target.targetId().equals(line.familyId()))
                                || (target.type() == PromotionTargetType.SUBFAMILY
                                && target.targetId().equals(line.subfamilyId()))));
    }

    private static boolean appliesOnDate(Promotion promotion, LocalDate date) {
        return !date.isBefore(promotion.startDate())
                && (promotion.endDate() == null || !date.isAfter(promotion.endDate()));
    }

    private static boolean isDirectLinePromotion(Promotion promotion) {
        return (promotion.scope() == PromotionScope.SALE
                || promotion.scope() == PromotionScope.PRODUCT_LIST
                || promotion.scope() == PromotionScope.FAMILY
                || promotion.scope() == PromotionScope.SUBFAMILY)
                && promotion.type() != PromotionType.PURCHASE_THRESHOLD_COUPON;
    }

    private static boolean isCouponPromotion(Promotion promotion) {
        return promotion.generatesCoupon()
                || promotion.type() == PromotionType.PURCHASE_THRESHOLD_COUPON;
    }

    private record PromotionContext(
            List<Promotion> promotions,
            List<PromotionTarget> targets,
            List<PromotionEvaluationLine> lines,
            AuthoritativePromotionPricing.CustomerContext customer) {

        private static PromotionContext empty() {
            return new PromotionContext(
                    List.of(), List.of(), List.of(),
                    AuthoritativePromotionPricing.CustomerContext.anonymous());
        }
    }

    private void enqueueConfirmedDocument(CommercialDocument document, UUID terminalId) {
        enqueueDocumentEvent(document, terminalId, SyncOperation.CONFIRMAR);
    }

    private void enqueueDocumentEvent(
            CommercialDocument document, UUID terminalId, SyncOperation operation) {
        syncOutbox.enqueue(new SyncOutboundEventCommand(
                organization.currentCompany().getId(),
                document.getTiendaId(),
                terminalId,
                "DOCUMENTO",
                document.getId(),
                operation,
                documentPayload(document)));
    }

    private Map<String, Object> documentPayload(CommercialDocument document) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("tipo", document.getTipo().name());
        payload.put("numero", document.getNumero());
        payload.put("estado", document.getEstado().name());
        payload.put("fecha", document.getFecha().toString());
        payload.put("clienteId", nullableUuid(document.getClienteId()));
        payload.put("proveedorId", nullableUuid(document.getProveedorId()));
        payload.put("almacenId", nullableUuid(document.getAlmacenId()));
        payload.put("descuentoGlobal", document.getDescuentoGlobal().toPlainString());
        payload.put("subtotal", document.getBaseTotal().toPlainString());
        payload.put("impuestos", document.getImpuestoTotal().toPlainString());
        payload.put("total", document.getTotal().toPlainString());
        payload.put("moneda", document.getMoneda());
        payload.put("lineas", document.getLineas().stream()
                .map(DocumentService::linePayload)
                .toList());
        payload.put("pagos", document.getPagos().stream()
                .map(DocumentService::paymentPayload)
                .toList());
        return payload;
    }

    private static Map<String, Object> linePayload(DocumentLine line) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("productoId", nullableUuid(line.getProductoId()));
        payload.put("tipoLinea", line.getLineType().name());
        payload.put("promocionId", nullableUuid(line.getPromotionId()));
        payload.put("cuponPromocionalId", nullableUuid(line.getPromotionalCouponId()));
        payload.put("posicion", line.getPosicion());
        payload.put("codigo", line.getCodigo());
        payload.put("nombre", line.getNombre());
        payload.put("tarifa", line.getTarifa());
        payload.put("cantidad", String.valueOf(line.getCantidad()));
        payload.put("precioUnitario", line.getPrecioUnitario().toPlainString());
        payload.put("descuento", line.getDescuento().toPlainString());
        payload.put("impuestosIncluidos", line.isImpuestosIncluidos());
        payload.put("regimenImpuesto", line.getRegimenImpuesto());
        payload.put("porcentajeImpuesto", line.getPorcentajeImpuesto().toPlainString());
        payload.put("base", line.getBase().toPlainString());
        payload.put("impuesto", line.getImpuesto().toPlainString());
        payload.put("total", line.getTotal().toPlainString());
        return payload;
    }

    private static Map<String, Object> paymentPayload(DocumentPayment payment) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("metodoPagoId", payment.getMetodoPago().getId().toString());
        payload.put("metodoPago", payment.getMetodoPago().getNombre());
        payload.put("posicion", payment.getPosicion());
        payload.put("importe", payment.getImporte().toPlainString());
        payload.put("principal", payment.isPrincipal());
        payload.put("entregado", nullableAmount(payment.getEntregado()));
        payload.put("cambio", nullableAmount(payment.getCambio()));
        payload.put("voucherCode", payment.getVoucherCode());
        payload.put("referencia", payment.getReferencia());
        payload.put("terminalPagoModo", nullableEnum(payment.getCardMode()));
        payload.put("terminalPagoProvider", nullableEnum(payment.getPaymentTerminalProvider()));
        payload.put("terminalPagoEstado", nullableEnum(payment.getPaymentTerminalStatus()));
        payload.put("autorizacionTarjeta", payment.getCardAuthorizationCode());
        payload.put("terminalCobroId", nullableUuid(payment.getPaymentTerminalId()));
        return payload;
    }

    private static String nullableEnum(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String nullableUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String nullableAmount(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    @Transactional(readOnly = true)
    public CommercialDocument loadForPrint(UUID id) {
        return find(id);
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listTickets() {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(), List.of(CommercialDocumentType.TICKET));
    }

    // Cancels the ticket and requests stock reversal only if it was originally applied.
    @Transactional
    public CommercialDocument cancelTicket(
            UUID id, Authentication authentication, String reason) {
        var ticket = find(id);
        if (ticket.getTipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException("el documento no es un ticket");
        }
        if (relations.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)) {
            throw new IllegalStateException(
                    "el ticket facturado debe corregirse con factura rectificativa");
        }
        if (vouchers.hasVoucherImpact(ticket)) {
            throw new IllegalStateException(
                    "el ticket con vale debe corregirse con un documento rectificativo");
        }
        var userId = organization.currentUser(authentication).getId();
        ticket.cancel(userId, Instant.now(clock), reason);
        if (ticket.isOrigenStock()) {
            stockGateway.cancel(ticket);
        }
        var saved = documents.save(ticket);
        fiscalIntegration.registerTicketCancellation(saved);
        enqueueDocumentEvent(saved, null, SyncOperation.ANULAR);
        return saved;
    }

    // Converts a confirmed ticket into an F3 invoice without duplicating stock or payments.
    @Transactional
    public CommercialDocument convertTicketToInvoice(
            UUID ticketId, UUID customerId, Authentication authentication) {
        var ticket = find(ticketId);
        if (ticket.getTipo() != CommercialDocumentType.TICKET
                || ticket.getEstado() != DocumentStatus.CONFIRMADO) {
            throw new IllegalStateException("solo se puede facturar un ticket confirmado");
        }
        if (relations.existsByOrigen_IdAndTipo(ticket.getId(), DocumentRelationType.FACTURA_DE)) {
            throw new IllegalStateException("el ticket ya esta facturado");
        }
        var invoice = invoiceFromTicket(ticket, customerId, authentication);
        validateConfirmation(invoice);
        invoice.confirm(nextNumber(invoice), organization.currentUser(authentication).getId(),
                Instant.now(clock), false);
        var saved = documents.save(invoice);
        relations.save(new DocumentRelation(saved, ticket, DocumentRelationType.FACTURA_DE));
        fiscalIntegration.registerInvoiceFromTicket(saved, ticket);
        enqueueConfirmedDocument(saved, null);
        return saved;
    }

    @Transactional
    public CommercialDocument createInvoice(
            DocumentCommand command, Authentication authentication) {
        requireType(command, INVOICES);
        requireDocumentWritePermission(
                command.tipo(), authentication, CorePermissionBootstrap.INVOICES_WRITE);
        return documents.save(createDraft(command, authentication));
    }

    @Transactional
    public CommercialDocument createAndConfirmInvoice(
            DocumentCommand command, Authentication authentication) {
        var draft = createInvoice(command, authentication);
        return confirm(draft.getId(), authentication);
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listInvoices() {
        return listInvoices(true);
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listInvoices(boolean includePurchaseDocuments) {
        return listInvoices(true, includePurchaseDocuments);
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listInvoices(
            boolean includeSalesDocuments,
            boolean includePurchaseDocuments) {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(),
                documentTypes(includeSalesDocuments, includePurchaseDocuments,
                        SALES_INVOICES, PURCHASE_INVOICES));
    }

    // Records payments only when they exactly cover the pending total.
    @Transactional
    public CommercialDocument payInvoice(UUID id, List<PaymentCommand> payments, Authentication authentication) {
        var invoice = find(id);
        if (!INVOICES.contains(invoice.getTipo())) {
            throw new IllegalArgumentException("el documento no es una factura");
        }
        requirePurchaseDocumentWritePermission(invoice.getTipo(), authentication);
        return payReceivable(invoice, payments, authentication);
    }

    @Transactional
    public CommercialDocument payDeliveryNote(UUID id, List<PaymentCommand> payments, Authentication authentication) {
        var deliveryNote = find(id);
        if (!DELIVERY_NOTES.contains(deliveryNote.getTipo())) {
            throw new IllegalArgumentException("message.document.only_delivery_note_can_be_paid");
        }
        requirePurchaseDocumentWritePermission(deliveryNote.getTipo(), authentication);
        return payReceivable(deliveryNote, payments, authentication);
    }
    // Records actual delivery-note payments without applying stock again.

    private CommercialDocument payReceivable(
            CommercialDocument document, List<PaymentCommand> payments, Authentication authentication) {
        var terminalId = currentTerminal.terminalId(authentication);
        var paidNow = addPartialPayments(document, payments, terminalId);
        document.updatePaymentStatus();
        var saved = documents.save(document);
        cashPayments.recordDocumentPayments(terminalId, saved);
        memberLoyalty.recordPaidSale(saved, eligibleAccrualAmount(saved, paidNow));
        enqueueDocumentEvent(saved, terminalId, SyncOperation.ACTUALIZAR);
        return saved;
    }

    // Exceptionally edits a confirmed ticket or delivery note without stock or audit side effects.
    @Transactional
    public CommercialDocument adminEditConfirmed(
            UUID id,
            BigDecimal globalDiscount,
            UUID customerId,
            UUID supplierId,
            List<DocumentLineCommand> lines) {
        var document = find(id);
        if (fiscalIntegration.hasFiscalRecord(document.getId())) {
            throw new IllegalStateException(
                    "el documento con registro fiscal es inmutable");
        }
        var authoritativeLines = authoritativeClientLines(
                document.getTiendaId(), document.getFecha(), document.getTipo(),
                PROMOTION_SALES_DOCUMENTS.contains(document.getTipo())
                        ? promotionPricing.customerContext(
                        organization.currentCompany().getId(), customerId)
                        : AuthoritativePromotionPricing.CustomerContext.anonymous(),
                globalDiscount, lines);
        document.adminReplace(
                globalDiscount, customerId, supplierId, authoritativeLines);
        return documents.save(document);
    }

    // Explicitly links an invoice to its origin document.
    @Transactional
    public CommercialDocument relate(UUID invoiceId, UUID originId, DocumentRelationType type) {
        var invoice = find(invoiceId);
        if (!INVOICES.contains(invoice.getTipo())) {
            throw new IllegalStateException("solo una factura puede relacionarse con origen");
        }
        Objects.requireNonNull(type, "tipoRelacion");
        var origin = find(originId);
        validateRelationOrigin(type, origin);
        relations.save(new DocumentRelation(invoice, origin, type));
        return invoice;
    }

    private static void validateRelationOrigin(DocumentRelationType type, CommercialDocument origin) {
        if (type == DocumentRelationType.FACTURA_DE && INVOICES.contains(origin.getTipo())) {
            throw new IllegalStateException("origen incompatible para factura agrupada");
        }
    }

    private CommercialDocument createDraft(
            DocumentCommand command, Authentication authentication) {
        return createDraft(command, authentication, pricingCustomer(command));
    }

    private CommercialDocument createDraft(
            DocumentCommand command,
            Authentication authentication,
            AuthoritativePromotionPricing.CustomerContext customer) {
        Objects.requireNonNull(command, "command");
        if (command.lineas() == null || command.lineas().isEmpty()) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        var authoritativeLines = authoritativeClientLines(
                store.getId(), command.fecha(), command.tipo(), customer,
                command.descuentoGlobal(), command.lineas());
        var document = new CommercialDocument(
                store.getId(), command.almacenId(), command.tipo(), command.fecha(),
                user.getId(), command.descuentoGlobal());
        document.setParties(
                command.clienteId(), command.proveedorId(), command.numeroExterno());
        document.setStockOrigin(
                command.directo()
                        || command.tipo() == CommercialDocumentType.TICKET
                        || DELIVERY_NOTES.contains(command.tipo()));
        for (var line : authoritativeLines) {
            document.addLine(line.toEntity(document));
        }
        return document;
    }

    private List<DocumentLineCommand> authoritativeClientLines(
            UUID storeId,
            LocalDate documentDate,
            CommercialDocumentType documentType,
            AuthoritativePromotionPricing.CustomerContext customer,
            BigDecimal globalDiscount,
            List<DocumentLineCommand> lines) {
        Objects.requireNonNull(globalDiscount, "descuentoGlobal");
        var values = List.copyOf(lines == null ? List.of() : lines);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        values.forEach(DocumentLineCommand::requireClientProductLine);
        var snapshots = promotionCatalog.products(
                storeId, values.stream().map(DocumentLineCommand::productoId).toList());
        var salesDocument = PROMOTION_SALES_DOCUMENTS.contains(documentType);
        validateInactiveSaleProducts(storeId, documentType, snapshots);
        if (salesDocument && globalDiscount.signum() > 0
                && snapshots.values().stream()
                .anyMatch(snapshot -> snapshot.product().getDiscountType() == DiscountType.NONE)) {
            throw new IllegalArgumentException(
                    "DiscountType.NONE no admite descuento global manual");
        }
        return values.stream().map(line -> {
            var snapshot = snapshots.get(line.productoId());
            validateLineQuantity(line, snapshot.product());
            var priced = salesDocument
                    ? promotionPricing.priceLine(
                    snapshot.product(), documentDate, customer, line)
                    : line;
            return snapshot.authoritativeSnapshot(priced);
        }).toList();
    }

    private AuthoritativePromotionPricing.CustomerContext pricingCustomer(DocumentCommand command) {
        return PROMOTION_SALES_DOCUMENTS.contains(command.tipo())
                ? promotionPricing.customerContext(
                organization.currentCompany().getId(), command.clienteId())
                : AuthoritativePromotionPricing.CustomerContext.anonymous();
    }

    private void validateLineQuantity(DocumentLineCommand line, Product product) {
        if (product.getProductType() == ProductType.UNIT
                && line.cantidad().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("message.product.unit_quantity_must_be_integer");
        }
    }

    private void validateInactiveSaleProducts(CommercialDocument document) {
        if (!PROMOTION_SALES_DOCUMENTS.contains(document.getTipo())) {
            return;
        }
        var productIds = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(DocumentLine::getProductoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return;
        }
        validateInactiveSaleProducts(
                document.getTiendaId(),
                document.getTipo(),
                promotionCatalog.products(document.getTiendaId(), productIds));
    }

    private void validateInactiveSaleProducts(
            UUID storeId,
            CommercialDocumentType documentType,
            Map<UUID, PromotionCatalogGateway.ProductSnapshot> snapshots) {
        if (!PROMOTION_SALES_DOCUMENTS.contains(documentType)
                || stockSettings.allowsInactiveProductSales(storeId)) {
            return;
        }
        snapshots.values().stream()
                .map(PromotionCatalogGateway.ProductSnapshot::product)
                .filter(product -> !product.isActive())
                .findFirst()
                .ifPresent(product -> {
                    throw new IllegalStateException("message.product.inactive_sale_not_allowed");
                });
    }
    // Enforces product quantity semantics before creating the fiscal line snapshot.

    private CommercialDocument invoiceFromTicket(
            CommercialDocument ticket, UUID customerId, Authentication authentication) {
        var invoice = new CommercialDocument(
                ticket.getTiendaId(), ticket.getAlmacenId(), CommercialDocumentType.FACTURA_VENTA,
                ticket.getFecha(), organization.currentUser(authentication).getId(),
                ticket.getDescuentoGlobal());
        invoice.setParties(Objects.requireNonNull(customerId, "clienteId"), null, null);
        invoice.setNumTicket(ticket.getNumero());
        ticket.getLineas().stream()
                .map(DocumentLineCommand::from)
                .forEach(line -> invoice.addLine(line.toEntity(invoice)));
        invoice.setStockOrigin(false);
        return invoice;
    }

    private void addPayments(
            CommercialDocument document, List<PaymentCommand> commands, String mismatchMessage, UUID terminalId) {
        requirePaymentsPresent(commands);
        requirePaymentTotal(commands, document.getTotal(), mismatchMessage);
        var resolved = commands.stream()
                .map(command -> resolvePayment(document, command, terminalId))
                .toList();
        requirePaymentTotal(resolved, document.getTotal(), mismatchMessage);
        appendPayments(document, resolved);
        if (document.getPagos().stream().noneMatch(DocumentPayment::isPrincipal)) {
            throw new IllegalArgumentException("se requiere un pago principal");
        }
    }

    private BigDecimal addPartialPayments(CommercialDocument document, List<PaymentCommand> commands, UUID terminalId) {
        requirePaymentsPresent(commands);
        var pending = document.getPendingTotal();
        requirePaymentTotalAtMost(commands, pending);
        var resolved = commands.stream()
                .map(command -> resolvePayment(document, command, terminalId))
                .toList();
        requirePaymentTotalAtMost(resolved, pending);
        appendPayments(document, resolved);
        return loyaltyAccrualCommandTotal(resolved);
    }

    private void appendPayments(CommercialDocument document, List<PaymentCommand> commands) {
        var position = document.getPagos().size();
        var hasPrincipal = document.getPagos().stream().anyMatch(DocumentPayment::isPrincipal);
        for (var command : commands) {
            var method = paymentMethods.findById(command.metodoPagoId())
                    .filter(PaymentMethod::isActivo)
                    .filter(value -> value.getEmpresaId().equals(
                            organization.currentCompany().getId()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "message.payment_method.active_not_found"));
            var principal = command.principal() && !hasPrincipal;
            document.addPayment(new DocumentPayment(
                    document, method, ++position, command.importe(), principal,
                    command.entregado(), command.cambio(), command.voucherCode(),
                    command.reference(), Instant.now(clock), command.cardMode(),
                    command.paymentTerminalProvider(), command.paymentTerminalStatus(),
                    command.cardAuthorizationCode(), command.paymentTerminalId()));
            hasPrincipal = hasPrincipal || principal;
        }
    }

    private static void requirePaymentsPresent(List<PaymentCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("se requiere al menos un pago");
        }
    }

    private static void requirePaymentTotal(
            List<PaymentCommand> commands, BigDecimal expected, String message) {
        var total = commands.stream().map(PaymentCommand::importe)
                .map(Money::euros).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Money.euros(total).compareTo(expected) != 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static BigDecimal paymentTotal(List<PaymentCommand> commands) {
        return commands.stream().map(PaymentCommand::importe)
                .map(Money::euros).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void requirePaymentTotalAtMost(List<PaymentCommand> commands, BigDecimal maximum) {
        var total = commands.stream().map(PaymentCommand::importe)
                .map(Money::euros).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Money.euros(total).compareTo(maximum) > 0) {
            throw new IllegalArgumentException("message.document.payment_exceeds_pending_total");
        }
    }

    private PaymentCommand resolvePayment(CommercialDocument document, PaymentCommand command, UUID terminalId) {
        var method = paymentMethods.findById(command.metodoPagoId())
                .filter(PaymentMethod::isActivo)
                .filter(value -> value.getEmpresaId().equals(
                        organization.currentCompany().getId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "metodo de pago activo no encontrado"));
        requireReferenceIfNeeded(method, command);
        requireCashAmountsOnlyForDrawerMethods(method, command);
        command = validateCardTerminalPayment(method, command, terminalId);
        if (MEMBER_BALANCE_METHOD.equals(method.getNombre())) {
            rejectVoucherCode(command, "codigo de vale no permitido con SALDO_MIEMBRO");
            var consumed = memberLoyalty.consumeBalanceForPayment(document, command.importe());
            return new PaymentCommand(
                    command.metodoPagoId(), consumed, command.principal(),
                    command.entregado(), command.cambio(), null, command.reference());
        }
        if (!VOUCHER_METHOD.equals(method.getNombre())) {
            if (command.voucherCode() != null && !command.voucherCode().isBlank()) {
                throw new IllegalArgumentException("codigo de vale solo permitido con metodo VALE");
            }
            return command;
        }
        if (command.voucherCode() == null || command.voucherCode().isBlank()) {
            throw new IllegalArgumentException("el pago con VALE necesita codigo");
        }
        var result = vouchers.consume(command.voucherCode(), command.importe(), document);
        return new PaymentCommand(
                command.metodoPagoId(), result.consumedAmount(), command.principal(),
                command.entregado(), command.cambio(), command.voucherCode(), command.reference(),
                command.cardMode(), command.paymentTerminalProvider(), command.paymentTerminalStatus(),
                command.cardAuthorizationCode(), command.paymentTerminalId());
    }
    // Consumes vouchers before storing payments so the applied amount is exact.

    private static void rejectVoucherCode(PaymentCommand command, String message) {
        if (command.voucherCode() != null && !command.voucherCode().isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static BigDecimal loyaltyAccrualPaymentTotal(List<DocumentPayment> payments) {
        return payments.stream()
                .filter(payment -> !MEMBER_BALANCE_METHOD.equals(payment.getMetodoPago().getNombre()))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal loyaltyAccrualCommandTotal(List<PaymentCommand> commands) {
        return commands.stream()
                .filter(command -> paymentMethods.findById(command.metodoPagoId())
                        .map(PaymentMethod::getNombre)
                        .filter(MEMBER_BALANCE_METHOD::equals)
                        .isEmpty())
                .map(PaymentCommand::importe)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal eligibleAccrualAmount(CommercialDocument document, BigDecimal paidAmount) {
        var paid = Money.euros(paidAmount);
        if (paid.signum() <= 0 || document.getTotal().signum() <= 0) {
            return BigDecimal.ZERO.setScale(Money.SCALE);
        }
        var eligibleTotal = eligibleDocumentTotal(document);
        if (eligibleTotal.compareTo(document.getTotal()) >= 0) {
            return paid;
        }
        return Money.euros(paid.multiply(eligibleTotal)
                .divide(document.getTotal(), Money.SCALE + 4, Money.ROUNDING));
    }
    // Applies loyalty only to the paid proportion of document lines that allow automatic benefits.

    private BigDecimal eligibleDocumentTotal(CommercialDocument document) {
        var ids = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(DocumentLine::getProductoId)
                .distinct()
                .toList();
        var eligibleIds = products.findAllByStoreIdAndIdIn(document.getTiendaId(), ids).stream()
                .filter(product -> product.getDiscountType() != DiscountType.NONE)
                .map(Product::getId)
                .collect(java.util.stream.Collectors.toSet());
        var lineTotal = document.getLineas().stream()
                .filter(line -> eligibleIds.contains(line.getProductoId()))
                .map(DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var globalFactor = BigDecimal.ONE.subtract(document.getDescuentoGlobal().movePointLeft(2));
        return Money.euros(lineTotal.multiply(globalFactor));
    }

    private static void requireReferenceIfNeeded(PaymentMethod method, PaymentCommand command) {
        if (method.isRequiereReferencia()
                && (command.reference() == null || command.reference().isBlank())) {
            throw new IllegalArgumentException("message.payment.reference_required");
        }
    }

    private static void requireCashAmountsOnlyForDrawerMethods(PaymentMethod method, PaymentCommand command) {
        if (!method.isAbreCajaRegistradora()
                && (command.entregado() != null || command.cambio() != null)) {
            throw new IllegalArgumentException("message.payment.cash_amounts_only_for_cash_drawer");
        }
    }

    private PaymentCommand validateCardTerminalPayment(
            PaymentMethod method, PaymentCommand command, UUID currentTerminalId) {
        if (command.cardMode() == null) {
            if (command.paymentTerminalProvider() != null
                    || command.paymentTerminalStatus() != null
                    || command.cardAuthorizationCode() != null
                    || command.paymentTerminalId() != null) {
                throw new IllegalArgumentException("message.payment_terminal.card_mode_required");
            }
            return command;
        }
        if (!"TARJETA".equals(method.getNombre())) {
            throw new IllegalArgumentException("message.payment_terminal.only_card_payment_allows_terminal_metadata");
        }
        if (command.paymentTerminalId() != null && !command.paymentTerminalId().equals(currentTerminalId)) {
            throw new IllegalArgumentException("message.payment_terminal.current_terminal_required");
        }
        var storeRules = storePaymentConfigurations.findByStoreId(organization.currentStore().getId())
                .orElseGet(() -> new StorePaymentConfiguration(organization.currentStore()));
        if (command.cardMode() == PaymentCardMode.MANUAL) {
            if (!storeRules.isCardManualEnabled()) {
                throw new IllegalArgumentException("message.payment_terminal.manual_card_disabled");
            }
            if (storeRules.isCardManualReferenceRequired()
                    && (command.reference() == null || command.reference().isBlank())) {
                throw new IllegalArgumentException("message.payment.reference_required");
            }
            return withCurrentTerminal(command, currentTerminalId);
        }
        if (!storeRules.isIntegratedCardEnabled()) {
            throw new IllegalArgumentException("message.payment_terminal.integrated_card_disabled");
        }
        if (command.paymentTerminalProvider() == null
                || command.paymentTerminalProvider() == PaymentTerminalProvider.NONE) {
            throw new IllegalArgumentException("message.payment_terminal.integrated_provider_required");
        }
        if (!List.of(storeRules.getAllowedPaymentTerminalProviders().split(","))
                .contains(command.paymentTerminalProvider().name())) {
            throw new IllegalArgumentException("message.payment_terminal.provider_not_allowed");
        }
        var configuration = terminalPaymentConfigurations.findByTerminalId(currentTerminalId)
                .orElseThrow(() -> new IllegalArgumentException("message.payment_terminal.configuration_not_found"));
        if (configuration.getCardMode() != PaymentCardMode.INTEGRATED
                || !configuration.isEnabled()
                || configuration.getProvider() != command.paymentTerminalProvider()) {
            throw new IllegalArgumentException("message.payment_terminal.configuration_not_enabled");
        }
        if (command.paymentTerminalStatus() != PaymentTerminalOperationStatus.APPROVED) {
            throw new IllegalArgumentException("message.payment_terminal.integrated_payment_not_approved");
        }
        return withCurrentTerminal(command, currentTerminalId);
    }

    private static PaymentCommand withCurrentTerminal(PaymentCommand command, UUID currentTerminalId) {
        return new PaymentCommand(
                command.metodoPagoId(), command.importe(), command.principal(),
                command.entregado(), command.cambio(), command.voucherCode(), command.reference(),
                command.cardMode(), command.paymentTerminalProvider(), command.paymentTerminalStatus(),
                command.cardAuthorizationCode(), currentTerminalId);
    }

    private String nextNumber(CommercialDocument document) {
        var type = document.getTipo();
        var period = DocumentNumbering.period(type, document.getFecha());
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(
                        document.getTiendaId(), type.prefix(), period)
                .orElseGet(() -> new DocumentCounter(
                        document.getTiendaId(), type, document.getFecha()));
        var number = counter.siguiente(
                type, document.getFecha(), organization.currentStore().getCodigoTienda());
        counters.save(counter);
        return number;
    }

    private CommercialDocument find(UUID id) {
        var storeId = organization.currentStore().getId();
        return documents.findById(id)
                .filter(document -> document.getTiendaId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("documento no encontrado"));
    }

    private boolean requiresStock(CommercialDocument document) {
        return DELIVERY_NOTES.contains(document.getTipo());
    }

    private void validateConfirmation(CommercialDocument document) {
        switch (document.getTipo()) {
            case FACTURA_VENTA, RECTIFICATIVA_VENTA -> {
                if (document.getClienteId() == null) {
                    throw new IllegalStateException(
                            "La factura de venta necesita cliente");
                }
                var customer = customers.findById(document.getClienteId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Cliente de factura no encontrado"));
                if (!customer.hasCompleteFiscalData()) {
                    throw new IllegalStateException(
                            "El cliente no tiene datos fiscales completos");
                }
            }
            case ALBARAN_COMPRA, FACTURA_COMPRA, RECTIFICATIVA_COMPRA -> {
                if (document.getProveedorId() == null) {
                    throw new IllegalStateException(
                            "El documento de compra necesita proveedor");
                }
                var supplier = suppliers.findByIdAndCompanyId(
                                document.getProveedorId(),
                                organization.currentCompany().getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Proveedor de compra no encontrado"));
                if (!supplier.isActive()) {
                    throw new IllegalStateException(
                            "El proveedor de compra esta inactivo");
                }
            }
            default -> {
            }
        }
    }

    private static void requireType(
            DocumentCommand command, EnumSet<CommercialDocumentType> allowedTypes) {
        Objects.requireNonNull(command, "command");
        if (!allowedTypes.contains(command.tipo())) {
            throw new IllegalArgumentException("message.document.invalid_document_type");
        }
    }

    private static void requirePurchaseDocumentWritePermission(
            CommercialDocumentType type,
            Authentication authentication) {
        if (PURCHASE_DOCUMENTS.contains(type) && !PermissionChecks.hasPurchaseDocumentWrite(authentication)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Los documentos de compra requieren permiso de gestion de producto o almacen");
        }
    }

    private static void requireDocumentWritePermission(
            CommercialDocumentType type,
            Authentication authentication,
            String salesPermission) {
        if (PURCHASE_DOCUMENTS.contains(type)) {
            requirePurchaseDocumentWritePermission(type, authentication);
            return;
        }
        if (!PermissionChecks.hasRole(authentication, "ADMIN")
                && !PermissionChecks.hasAnyAuthority(
                        authentication,
                        CorePermissionBootstrap.GESTION_VENTAS,
                        CorePermissionBootstrap.VENTA,
                        salesPermission)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "El documento de venta requiere permiso de ventas");
        }
    }

    private static String confirmPermission(CommercialDocumentType type) {
        return DELIVERY_NOTES.contains(type)
                ? CorePermissionBootstrap.DELIVERY_NOTES_CONFIRM
                : CorePermissionBootstrap.INVOICES_CONFIRM;
    }

    private static EnumSet<CommercialDocumentType> documentTypes(
            boolean includeSalesDocuments,
            boolean includePurchaseDocuments,
            EnumSet<CommercialDocumentType> salesTypes,
            EnumSet<CommercialDocumentType> purchaseTypes) {
        var result = EnumSet.noneOf(CommercialDocumentType.class);
        if (includeSalesDocuments) {
            result.addAll(salesTypes);
        }
        if (includePurchaseDocuments) {
            result.addAll(purchaseTypes);
        }
        return result;
    }
}
