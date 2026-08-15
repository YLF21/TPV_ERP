package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductQuantityPolicy;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.promotion.AuthoritativePromotionPricing;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PosCashService {

    static final String SALE_OPERATION_AUTHORIZED = "SALE_OPERATION_AUTHORIZED";

    private final DocumentService documents;
    private final ProductRepository products;
    private final StoreTaxRepository taxes;
    private final WarehouseRepository warehouses;
    private final PaymentMethodRepository paymentMethods;
    private final CurrentOrganization organization;
    private final PosCashCheckoutRepository checkouts;
    private final PosCashTicketSnapshot snapshots;
    private final CurrentTerminal currentTerminal;
    private final DiscountAuthorizationService discountAuthorizations;
    private final AuthoritativePromotionPricing promotionPricing;
    private final SaleOperationSecurityService operationSecurity;
    private final AuditService audit;
    private final TransactionOperations transactions;
    private GiftReceiptService giftReceipts;
    private TemporaryPriceAuthorizationService temporaryPriceAuthorizations;
    private ReturnAwareSaleQuoteService returnAwareQuotes;
    private PreviousTicketImportService previousTicketImports;

    @org.springframework.beans.factory.annotation.Autowired
    public PosCashService(
            DocumentService documents,
            ProductRepository products,
            StoreTaxRepository taxes,
            WarehouseRepository warehouses,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization,
            PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots,
            CurrentTerminal currentTerminal,
            DiscountAuthorizationService discountAuthorizations,
            AuthoritativePromotionPricing promotionPricing,
            SaleOperationSecurityService operationSecurity,
            AuditService audit,
            PlatformTransactionManager transactionManager) {
        this(documents, products, taxes, warehouses, paymentMethods, organization,
                checkouts, snapshots, currentTerminal, discountAuthorizations,
                promotionPricing, operationSecurity, audit,
                new TransactionTemplate(transactionManager));
    }

    PosCashService(
            DocumentService documents,
            ProductRepository products,
            StoreTaxRepository taxes,
            WarehouseRepository warehouses,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization,
            PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots,
            CurrentTerminal currentTerminal,
            DiscountAuthorizationService discountAuthorizations,
            AuthoritativePromotionPricing promotionPricing,
            SaleOperationSecurityService operationSecurity,
            AuditService audit) {
        this(documents, products, taxes, warehouses, paymentMethods, organization,
                checkouts, snapshots, currentTerminal, discountAuthorizations,
                promotionPricing, operationSecurity, audit,
                (TransactionOperations) null);
    }

    PosCashService(
            DocumentService documents,
            ProductRepository products,
            StoreTaxRepository taxes,
            WarehouseRepository warehouses,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization,
            PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots,
            CurrentTerminal currentTerminal,
            DiscountAuthorizationService discountAuthorizations,
            AuthoritativePromotionPricing promotionPricing,
            SaleOperationSecurityService operationSecurity,
            AuditService audit,
            TransactionOperations transactions) {
        this.documents = documents;
        this.products = products;
        this.taxes = taxes;
        this.warehouses = warehouses;
        this.paymentMethods = paymentMethods;
        this.organization = organization;
        this.checkouts = checkouts;
        this.snapshots = snapshots;
        this.currentTerminal = currentTerminal;
        this.discountAuthorizations = discountAuthorizations;
        this.promotionPricing = promotionPricing;
        this.operationSecurity = operationSecurity;
        this.audit = audit;
        this.transactions = transactions;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setGiftReceiptService(GiftReceiptService giftReceipts) {
        this.giftReceipts = giftReceipts;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setTemporaryPriceAuthorizationService(
            TemporaryPriceAuthorizationService temporaryPriceAuthorizations) {
        this.temporaryPriceAuthorizations = temporaryPriceAuthorizations;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setReturnAwareSaleQuoteService(ReturnAwareSaleQuoteService returnAwareQuotes) {
        this.returnAwareQuotes = returnAwareQuotes;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPreviousTicketImportService(PreviousTicketImportService previousTicketImports) {
        this.previousTicketImports = previousTicketImports;
    }

    PosCashService(
            DocumentService documents,
            ProductRepository products,
            StoreTaxRepository taxes,
            WarehouseRepository warehouses,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization,
            PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots,
            CurrentTerminal currentTerminal,
            DiscountAuthorizationService discountAuthorizations,
            AuthoritativePromotionPricing promotionPricing) {
        this(documents, products, taxes, warehouses, paymentMethods, organization,
                checkouts, snapshots, currentTerminal, discountAuthorizations,
                promotionPricing, null, null);
    }

    PosCashService(
            DocumentService documents,
            ProductRepository products,
            StoreTaxRepository taxes,
            WarehouseRepository warehouses,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization,
            PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots,
            CurrentTerminal currentTerminal,
            DiscountAuthorizationService discountAuthorizations) {
        this(documents, products, taxes, warehouses, paymentMethods, organization,
                checkouts, snapshots, currentTerminal, discountAuthorizations,
                null, null, null);
    }

    PosCashService(
            DocumentService documents,
            ProductRepository products,
            StoreTaxRepository taxes,
            WarehouseRepository warehouses,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization,
            PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots,
            CurrentTerminal currentTerminal) {
        this(documents, products, taxes, warehouses, paymentMethods, organization,
                checkouts, snapshots, currentTerminal, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public Quote quote(PosCashController.SaleRequest request, Authentication authentication) {
        var prepared = prepareSale(request, authentication);
        var ticket = quotePreparedSale(prepared, request, authentication);
        authorizeCheckoutDiscount(
                prepared, request,
                currentDiscountedTotal(prepared, ticket.getTotal()), authentication);
        var catalog = products.findAllByStoreIdAndIdIn(
                        ticket.getTiendaId(),
                        ticket.getLineas().stream()
                                .filter(line -> line.getProductoId() != null)
                                .map(DocumentLine::getProductoId)
                                .distinct()
                                .toList())
                .stream().collect(java.util.stream.Collectors.toMap(Product::getId, value -> value));
        var customer = promotionPricing == null
                ? AuthoritativePromotionPricing.CustomerContext.anonymous()
                : promotionPricing.customerContext(
                organization.currentCompany().getId(), ticket.getClienteId());
        return Quote.from(
                ticket, request, catalog, customer,
                prepared.replay() == null ? 0 : prepared.replay().productLineCount(),
                prepared.replay() == null || prepared.replay().currentRepricing()
                        ? 0 : prepared.replay().commands().size(),
                prepared.replay() != null && prepared.replay().frozenExact());
    }

    CommercialDocument quotePreparedSale(
            PreparedSale prepared,
            PosCashController.SaleRequest request,
            Authentication authentication) {
        var command = prepared.command();
        var checkoutDiscount = effectiveCheckoutDiscount(request, prepared.replay());
        CommercialDocument ticket = null;
        if (!command.lineas().isEmpty()) {
            ticket = checkoutDiscount.signum() > 0
                    ? documents.quoteTicket(command, request.promotionalCouponCode(),
                            checkoutDiscount, authentication)
                    : hasText(request.promotionalCouponCode())
                            ? documents.quoteTicket(command, request.promotionalCouponCode(), authentication)
                            : documents.quoteTicket(command, authentication);
        } else if (checkoutDiscount.signum() > 0
                || hasText(request.promotionalCouponCode())) {
            throw new IllegalArgumentException(
                    "Los descuentos y cupones nuevos necesitan articulos actuales");
        }
        if (prepared.replay() != null && prepared.replay().frozenExact()) {
            return combineHistoricalReplay(prepared, ticket, request, authentication);
        }
        if (ticket == null) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        var hasTicketReturn = command.lineas().stream().anyMatch(line ->
                line.originalDocumentLineId() != null && line.cantidad().signum() < 0);
        if (!hasTicketReturn) {
            return ticket;
        }
        if (returnAwareQuotes == null) {
            throw new IllegalStateException("return_quote_service_unavailable");
        }
        return returnAwareQuotes.apply(ticket, command.lineas());
    }

    private CommercialDocument combineHistoricalReplay(
            PreparedSale prepared,
            CommercialDocument currentQuote,
            PosCashController.SaleRequest request,
            Authentication authentication) {
        var replay = Objects.requireNonNull(prepared.replay(), "historical replay");
        var command = prepared.command();
        var combined = new CommercialDocument(
                organization.currentStore().getId(), command.almacenId(), CommercialDocumentType.TICKET,
                command.fecha(), requireUser(authentication).getId(), BigDecimal.ZERO);
        combined.setParties(replay.customerId(), null, null);
        combined.setInternalComment(request.internalComment());
        replay.commands().forEach(line -> combined.addLine(line.toEntity(combined)));
        if (currentQuote != null) {
            currentQuote.getLineas().stream()
                    .map(DocumentLineCommand::from)
                    .forEach(line -> combined.addLine(line.toEntity(combined)));
            materializeCurrentGlobalDiscount(currentQuote, combined);
        }
        var expectedBase = Money.euros(replay.baseTotal().add(
                currentQuote == null ? BigDecimal.ZERO : currentQuote.getBaseTotal()));
        var expectedTax = Money.euros(replay.taxTotal().add(
                currentQuote == null ? BigDecimal.ZERO : currentQuote.getImpuestoTotal()));
        var expectedTotal = Money.euros(replay.total().add(
                currentQuote == null ? BigDecimal.ZERO : currentQuote.getTotal()));
        if (combined.getBaseTotal().compareTo(expectedBase) != 0
                || combined.getImpuestoTotal().compareTo(expectedTax) != 0
                || combined.getTotal().compareTo(expectedTotal) != 0) {
            throw new IllegalStateException(
                    "message.document.previous_ticket_snapshot_mismatch");
        }
        return combined;
    }

    /**
     * The combined replay document deliberately has a zero global discount so the
     * historical block cannot alter newly scanned lines (or vice versa). Materialize
     * the discount that belongs to the current segment as frozen fiscal adjustments.
     */
    static void materializeCurrentGlobalDiscount(
            CommercialDocument currentQuote,
            CommercialDocument combined) {
        if (currentQuote.getDescuentoGlobal().signum() == 0) {
            return;
        }
        var groups = new LinkedHashMap<ReplayTaxKey, ReplayMutableTotals>();
        currentQuote.getLineas().stream()
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(line -> groups.computeIfAbsent(
                                new ReplayTaxKey(
                                        line.isImpuestosIncluidos(),
                                        line.getRegimenImpuesto(),
                                        line.getPorcentajeImpuesto()),
                                ignored -> new ReplayMutableTotals())
                        .add(line.getBase(), line.getImpuesto(), line.getTotal()));
        var factor = BigDecimal.ONE.subtract(
                currentQuote.getDescuentoGlobal().movePointLeft(2));
        var remainingBase = currentQuote.getBaseTotal();
        var remainingTax = currentQuote.getImpuestoTotal();
        var remainingTotal = currentQuote.getTotal();
        var entries = List.copyOf(groups.entrySet());
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            var source = entry.getValue();
            var last = index == entries.size() - 1;
            var targetBase = last
                    ? remainingBase : Money.euros(source.base.multiply(factor));
            var targetTax = last
                    ? remainingTax : Money.euros(source.tax.multiply(factor));
            var targetTotal = last
                    ? remainingTotal : Money.euros(source.total.multiply(factor));
            remainingBase = Money.euros(remainingBase.subtract(targetBase));
            remainingTax = Money.euros(remainingTax.subtract(targetTax));
            remainingTotal = Money.euros(remainingTotal.subtract(targetTotal));
            var adjustmentBase = Money.euros(targetBase.subtract(source.base));
            var adjustmentTax = Money.euros(targetTax.subtract(source.tax));
            var adjustmentTotal = Money.euros(targetTotal.subtract(source.total));
            if (adjustmentBase.signum() == 0 && adjustmentTax.signum() == 0
                    && adjustmentTotal.signum() == 0) {
                continue;
            }
            var key = entry.getKey();
            combined.addLine(DocumentLine.frozenSpecial(
                    combined,
                    combined.getLineas().size() + 1,
                    DocumentLineType.MANUAL_DISCOUNT,
                    "Descuento global de esta venta",
                    adjustmentTotal,
                    key.taxesIncluded(),
                    key.regime(),
                    key.percentage(),
                    null,
                    null,
                    null,
                    adjustmentBase,
                    adjustmentTax,
                    adjustmentTotal));
        }
    }

    private record ReplayTaxKey(
            boolean taxesIncluded,
            String regime,
            BigDecimal percentage) {
    }

    private static final class ReplayMutableTotals {
        private BigDecimal base = Money.euros(BigDecimal.ZERO);
        private BigDecimal tax = Money.euros(BigDecimal.ZERO);
        private BigDecimal total = Money.euros(BigDecimal.ZERO);

        private void add(
                BigDecimal lineBase,
                BigDecimal lineTax,
                BigDecimal lineTotal) {
            base = Money.euros(base.add(lineBase));
            tax = Money.euros(tax.add(lineTax));
            total = Money.euros(total.add(lineTotal));
        }
    }

    public Result charge(PosCashController.CashRequest request, Authentication authentication) {
        var completed = transactions == null
                ? chargeTransaction(request, authentication)
                : Objects.requireNonNull(transactions.execute(
                        ignored -> chargeTransaction(request, authentication)));
        var result = completed.result();
        var ticket = completed.ticket() == null
                ? documents.loadForPrint(result.id()) : completed.ticket();
        var printTicket = documents.renderTicketPrintView(ticket, result.printTicket());
        return new Result(result.id(), result.number(), result.total(), result.received(),
                result.change(), printTicket);
    }

    private TransactionalCharge chargeTransaction(
            PosCashController.CashRequest request, Authentication authentication) {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var terminalId = currentTerminal.terminalId(authentication);
        var userId = requireUser(authentication).getId();
        var requestHash = requestHash(request);
        var now = Instant.now();
        var reserved = PosCashCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), companyId, storeId, terminalId,
                userId, requestHash, now);
        var inserted = checkouts.reserve(
                reserved.getId(), request.checkoutId(), companyId, storeId, terminalId,
                userId, requestHash, now);
        if (inserted == 0) {
            var existing = checkouts.findScopedForUpdate(
                    request.checkoutId(), companyId, storeId, terminalId, userId)
                    .orElseThrow();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IllegalStateException("cash_checkout_idempotency_conflict");
            }
            if (!existing.isCompleted()) {
                throw new IllegalStateException("cash_checkout_in_progress");
            }
            return new TransactionalCharge(resultFrom(existing), null);
        }
        var prepared = prepareSale(request.sale(), authentication);
        var command = prepared.command();
        var quote = quotePreparedSale(prepared, request.sale(), authentication);
        validateQuoteFingerprint(request.sale(), quote);
        var total = quote.getTotal();
        var received = Money.euros(request.received());
        if (received.compareTo(total) < 0) {
            throw new IllegalArgumentException("El importe recibido no cubre el total");
        }
        if (request.quotedTotal() != null && Money.euros(request.quotedTotal()).compareTo(total) != 0) {
            throw new IllegalStateException("El total de la venta ha cambiado; vuelve a abrir el cobro");
        }
        var cash = paymentMethods.findByEmpresaIdAndNombreAndActivoTrue(
                        organization.currentCompany().getId(), "EFECTIVO")
                .orElseThrow(() -> new IllegalStateException("El metodo EFECTIVO no esta activo"));
        var change = Money.euros(received.subtract(total));
        var payment = List.of(new PaymentCommand(cash.getId(), total, true, received, change));
        authorizeSensitiveOperations(
                prepared,
                request.sale(),
                total,
                authentication,
                "POS_CASH",
                request.checkoutId());
        var ticket = prepared.replay() == null
                ? hasCheckoutDiscount(request.sale())
                        ? documents.createTicket(command, payment,
                                request.sale().promotionalCouponCode(),
                                request.sale().checkoutDiscountAmount(), authentication)
                        : hasText(request.sale().promotionalCouponCode())
                                ? documents.createTicket(command, payment,
                                        request.sale().promotionalCouponCode(), authentication)
                                : documents.createTicket(command, payment, authentication)
                : documents.createApprovedCardTicketFromSnapshot(
                        snapshot(quote, cash.getId(), prepared), payment, authentication);
        completeTemporaryPriceAuthorizations("POS_CASH", request.checkoutId());
        var printTicket = documents.ticketPrintView(ticket);
        reserved.complete(ticket.getId(), ticket.getNumero(), total, received, change,
                snapshots.serialize(printTicket), Instant.now());
        checkouts.save(reserved);
        return new TransactionalCharge(
                new Result(ticket.getId(), ticket.getNumero(), total, received, change,
                        printTicket),
                ticket);
    }

    @Transactional(readOnly = true)
    DocumentCommand authoritativeCommand(
            PosCashController.SaleRequest request,
            Authentication authentication) {
        return prepareSale(request, authentication).command();
    }

    @Transactional(readOnly = true)
    PreparedSale prepareSale(
            PosCashController.SaleRequest request,
            Authentication authentication) {
        var store = organization.currentStore();
        var warehouse = warehouses.findByStoreIdAndPredeterminadoTrue(store.getId())
                .filter(value -> value.isActive())
                .orElseThrow(() -> new IllegalStateException("No hay un almacen predeterminado activo"));
        if ((request.lines() == null || request.lines().isEmpty())
                && request.previousTicketImport() == null) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        var replay = request.previousTicketImport() == null
                ? null
                : requirePreviousTicketImports().resolve(
                        request.previousTicketImport(), request.customerId(), authentication);
        if (replay != null && request.lines().stream().anyMatch(line ->
                line.returnOrigin() != null || line.quantity() == null
                        || line.quantity().signum() <= 0)) {
            throw new IllegalArgumentException(
                    "message.document.previous_ticket_positive_current_lines_only");
        }
        var maximumDiscount = request.lines().stream()
                .map(PosCashController.LineRequest::discount)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        if (operationSecurity == null && discountAuthorizations != null) {
            discountAuthorizations.enforce(
                    maximumDiscount, request.discountAuthorizationToken(), authentication);
        }
        var sensitiveOperations = EnumSet.noneOf(SaleOperationCode.class);
        var temporaryPriceClaims = new ArrayList<TemporaryPriceAuthorizationService.ClaimRequest>();
        var returnOrigins = new HashSet<UUID>();
        var lines = request.lines().stream().map(line -> {
            if (line.returnOrigin() != null) {
                if (!returnOrigins.add(line.returnOrigin().sourceLineId())) {
                    throw new IllegalArgumentException(
                            "Una linea de origen solo puede aparecer una vez en el carrito");
                }
                sensitiveOperations.add(
                        line.returnOrigin().sourceType()
                                == TicketReturnService.ReturnSourceType.SALES_INVOICE
                                ? SaleOperationCode.RETURN_SALES_INVOICE
                                : SaleOperationCode.RETURN_TICKET);
                return authoritativeReturnLine(line, store.getId());
            }
            var product = products.findById(line.productId())
                    .filter(value -> value.getStoreId().equals(store.getId()))
                    .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
            var tax = taxes.findById(product.getTaxId())
                    .filter(value -> value.getStoreId().equals(store.getId()) && value.isActive())
                    .orElseThrow(() -> new IllegalStateException("El impuesto del producto no esta activo"));
            var unitPrice = authoritativeUnitPrice(product.getSalePrice(), line.openUnitPrice());
            var temporaryName = normalizedTemporaryName(
                    product.getName(), line.temporaryName());
            var temporaryNameOverride = !temporaryName.equals(product.getName());
            var temporaryPriceOverride = Money.euros(product.getSalePrice()).signum() != 0
                    && line.openUnitPrice() != null;
            if (line.quantity().compareTo(BigDecimal.ONE.negate()) == 0) {
                sensitiveOperations.add(SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET);
            }
            if (line.discount().signum() > 0) {
                sensitiveOperations.add(SaleOperationCode.APPLY_SALE_DISCOUNT);
            }
            if (temporaryNameOverride) {
                sensitiveOperations.add(SaleOperationCode.TEMPORARY_NAME);
            }
            if (line.openUnitPrice() != null) {
                sensitiveOperations.add(temporaryPriceOverride
                        ? SaleOperationCode.TEMPORARY_PRICE_CHANGE
                        : SaleOperationCode.OPEN_PRICE_PRODUCT);
                if (temporaryPriceOverride) {
                    temporaryPriceClaims.add(new TemporaryPriceAuthorizationService.ClaimRequest(
                            line.cartLineId(), product.getId(), unitPrice,
                            line.temporaryPriceAuthorizationToken()));
                }
            }
            return new DocumentLineCommand(
                    product.getId(), line.quantity(), product.getCode(), temporaryName, null,
                    unitPrice, line.discount(), product.isTaxesIncluded(),
                    currentTaxRegime(), tax.getPercentage(), DocumentLineType.PRODUCT,
                    null, null, null, line.serialNumbers(),
                    temporaryNameOverride, temporaryPriceOverride);
        }).toList();
        validateCombinedReplaySerialNumbers(replay, lines);
        var commandLines = new ArrayList<DocumentLineCommand>();
        if (replay != null && replay.currentRepricing()) {
            commandLines.addAll(replay.commands());
            if (replay.hasTemporaryPriceOverride()) {
                sensitiveOperations.add(SaleOperationCode.TEMPORARY_PRICE_CHANGE);
            }
            if (replay.preservedManualDiscountAmount().signum() > 0) {
                sensitiveOperations.add(SaleOperationCode.APPLY_CHECKOUT_DISCOUNT);
            }
        }
        commandLines.addAll(lines);
        if (hasCheckoutDiscount(request)) {
            sensitiveOperations.add(SaleOperationCode.APPLY_CHECKOUT_DISCOUNT);
        }
        var command = new DocumentCommand(
                warehouse.getId(), CommercialDocumentType.TICKET,
                LocalDate.now(ZoneId.of(store.getTimezone())), request.customerId(), null, null,
                BigDecimal.ZERO.setScale(2), true,
                List.copyOf(commandLines), request.internalComment());
        return new PreparedSale(command, sensitiveOperations, temporaryPriceClaims, replay);
    }

    private static void validateCombinedReplaySerialNumbers(
            PreviousTicketImportService.ResolvedImport replay,
            List<DocumentLineCommand> currentLines) {
        if (replay == null) {
            return;
        }
        var unique = new HashSet<String>();
        var allLines = new ArrayList<DocumentLineCommand>(replay.commands());
        allLines.addAll(currentLines);
        for (var line : allLines) {
            for (var serial : line.serialNumbers()) {
                var normalized = serial.trim().toUpperCase(Locale.ROOT);
                if (!unique.add(normalized)) {
                    throw new IllegalArgumentException(
                            "message.document.previous_ticket_serial_duplicated");
                }
            }
        }
    }

    private PreviousTicketImportService requirePreviousTicketImports() {
        if (previousTicketImports == null) {
            throw new IllegalStateException("previous_ticket_import_service_unavailable");
        }
        return previousTicketImports;
    }

    private String currentTaxRegime() {
        return previousTicketImports == null
                ? "IVA" : previousTicketImports.currentTaxRegime();
    }

    private DocumentLineCommand authoritativeReturnLine(
            PosCashController.LineRequest request,
            UUID storeId) {
        if (giftReceipts == null) {
            throw new IllegalStateException("gift_receipt_service_unavailable");
        }
        var origin = request.returnOrigin();
        CommercialDocument ticket;
        BigDecimal available;
        ProductType productType;
        List<String> availableSerials;
        UUID giftReceiptLineId = null;
        if (origin.sourceType() == TicketReturnService.ReturnSourceType.GIFT_RECEIPT) {
            var context = giftReceipts.returnContext(origin.sourceCode());
            ticket = context.ticket();
            var option = context.lines().stream()
                    .filter(line -> line.sourceLineId().equals(origin.sourceLineId())
                            && line.giftReceiptLineId().equals(origin.giftReceiptLineId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "La linea ya no esta disponible en el ticket regalo"));
            available = option.refundableQuantity();
            productType = option.productType();
            availableSerials = option.serialNumbers();
            giftReceiptLineId = option.giftReceiptLineId();
        } else {
            var invoiceOrigin = origin.sourceType()
                    == TicketReturnService.ReturnSourceType.SALES_INVOICE;
            ticket = invoiceOrigin
                    ? documents.invoiceForReturnByNumber(origin.sourceCode())
                    : documents.ticketForReturnByNumber(origin.sourceCode());
            var option = documents.cardRefundLineOptions(ticket.getId()).stream()
                    .filter(line -> line.lineId().equals(origin.sourceLineId())
                            && line.lineType() == DocumentLineType.PRODUCT)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "La linea ya no esta disponible para devolucion"));
            available = option.refundableQuantity();
            productType = option.productType();
            availableSerials = option.refundableSerialNumbers();
        }
        if (!ticket.getTiendaId().equals(storeId)
                || !ticket.getId().equals(origin.sourceTicketId())) {
            throw new IllegalArgumentException(
                    "El origen de devolucion no pertenece al documento indicado");
        }
        var requestedQuantity = request.quantity().abs();
        ProductQuantityPolicy.requireValid(productType, requestedQuantity);
        var quantity = requestedQuantity.setScale(3, Money.ROUNDING);
        if (quantity.signum() <= 0 || quantity.compareTo(available) > 0) {
            throw new IllegalArgumentException(
                    "La cantidad supera el saldo pendiente de devolucion");
        }
        var source = ticket.getLineas().stream()
                .filter(line -> line.getId().equals(origin.sourceLineId())
                        && line.getLineType() == DocumentLineType.PRODUCT)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "La linea no pertenece al documento original"));
        if (!source.getProductoId().equals(request.productId())) {
            throw new IllegalArgumentException("El producto no coincide con la linea original");
        }
        var serials = request.serialNumbers() == null
                ? List.<String>of()
                : request.serialNumbers().stream().map(String::trim).toList();
        validateReturnSerials(quantity, serials, availableSerials);
        if (request.openUnitPrice() != null || request.temporaryName() != null
                || request.discount().compareTo(source.getDescuento()) != 0) {
            throw new IllegalArgumentException(
                    "Las condiciones fiscales de una devolucion no se pueden modificar");
        }
        return new DocumentLineCommand(
                source.getProductoId(), quantity.negate(), source.getCodigo(),
                source.getNombre(), source.getTarifa(), source.getPrecioUnitario(),
                source.getDescuento(), source.isImpuestosIncluidos(),
                source.getRegimenImpuesto(), source.getPorcentajeImpuesto(),
                DocumentLineType.PRODUCT, null, null, null, serials,
                false, false, origin.sourceType(), origin.sourceCode(),
                ticket.getId(), source.getId(), giftReceiptLineId)
                .withBarcode(source.getCodigoBarras());
    }

    private static void validateReturnSerials(
            BigDecimal quantity,
            List<String> requested,
            List<String> available) {
        if (available == null || available.isEmpty()) {
            if (!requested.isEmpty()) {
                throw new IllegalArgumentException(
                        "La linea original no tiene numeros de serie pendientes");
            }
            return;
        }
        var normalizedAvailable = available.stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        var normalizedRequested = requested.stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        if (quantity.stripTrailingZeros().scale() > 0
                || quantity.intValueExact() != requested.size()
                || normalizedRequested.size() != requested.size()
                || !normalizedAvailable.containsAll(normalizedRequested)) {
            throw new IllegalArgumentException(
                    "Los numeros de serie no coinciden con la devolucion seleccionada");
        }
    }

    @Transactional(readOnly = true)
    DocumentCommand authoritativeCommand(PosCashController.SaleRequest request) {
        return authoritativeCommand(request, null);
    }

    record PreparedSale(
            DocumentCommand command,
            Set<SaleOperationCode> sensitiveOperations,
            List<TemporaryPriceAuthorizationService.ClaimRequest> temporaryPriceClaims,
            PreviousTicketImportService.ResolvedImport replay) {

        PreparedSale(
                DocumentCommand command,
                Set<SaleOperationCode> sensitiveOperations) {
            this(command, sensitiveOperations, List.of(), null);
        }

        PreparedSale(
                DocumentCommand command,
                Set<SaleOperationCode> sensitiveOperations,
                List<TemporaryPriceAuthorizationService.ClaimRequest> temporaryPriceClaims) {
            this(command, sensitiveOperations, temporaryPriceClaims, null);
        }

        PreparedSale {
            sensitiveOperations = Set.copyOf(sensitiveOperations);
            temporaryPriceClaims = List.copyOf(temporaryPriceClaims);
        }
    }

    ApprovedCardTicketSnapshot snapshot(
            CommercialDocument quoted,
            UUID paymentMethodId,
            PreparedSale prepared) {
        return ApprovedCardTicketSnapshot.from(
                quoted, paymentMethodId, prepared.command().lineas(),
                historicalReplayMetadata(quoted, prepared));
    }

    private HistoricalTicketReplayMetadata historicalReplayMetadata(
            CommercialDocument quoted,
            PreparedSale prepared) {
        var replay = prepared.replay();
        if (replay == null) {
            return null;
        }
        var historicalLineCount = replay.frozenExact()
                ? replay.commands().size() : 0;
        var currentCouponDiscount = quoted.getLineas().stream()
                .filter(line -> line.getPosicion() > historicalLineCount)
                .filter(line -> line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON)
                .filter(line -> line.getPromotionalCouponId() != null)
                .map(DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();
        var currentCheckoutDiscount = quoted.getLineas().stream()
                .filter(line -> line.getPosicion() > historicalLineCount)
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .map(DocumentLine::getTotal)
                .filter(total -> total.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();
        var currentPendingBeforeCoupon = Money.euros(
                quoted.getTotal().subtract(
                                replay.frozenExact()
                                        ? replay.total() : BigDecimal.ZERO)
                        .add(currentCouponDiscount)
                        .add(currentCheckoutDiscount));
        var currentManualDiscounts = new ArrayList<
                HistoricalTicketReplayMetadata.ManualLineDiscount>();
        for (int index = 0; index < prepared.command().lineas().size(); index++) {
            var line = prepared.command().lineas().get(index);
            if (line.productoId() == null) {
                continue;
            }
            currentManualDiscounts.add(
                    new HistoricalTicketReplayMetadata.ManualLineDiscount(
                            historicalLineCount + index + 1,
                            line.productoId(), line.descuento()));
        }
        var currentGeneratedCoupons = documents.historicalReplayGeneratedCoupons(
                quoted, historicalLineCount, prepared.command().lineas());
        return replay.metadata(
                currentPendingBeforeCoupon,
                currentManualDiscounts,
                currentGeneratedCoupons);
    }

    ApprovedCardTicketSnapshot snapshot(
            CommercialDocument quoted,
            UUID paymentMethodId,
            PosCashController.SaleRequest request,
            Authentication authentication) {
        return snapshot(quoted, paymentMethodId, prepareSale(request, authentication));
    }

    @Transactional
    public DocumentCommand authorizeCommandForMutation(
            PosCashController.SaleRequest request,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        if (request.previousTicketImport() != null) {
            throw new IllegalArgumentException(
                    "message.document.previous_ticket_cannot_be_parked");
        }
        var prepared = prepareSale(request, authentication);
        var quoted = quotePreparedSale(prepared, request, authentication);
        authorizeSensitiveOperations(
                prepared,
                request,
                quoted.getTotal(),
                authentication,
                sourceType,
                sourceId);
        completeTemporaryPriceAuthorizations(sourceType, sourceId);
        return prepared.command();
    }

    void authorizeSensitiveOperations(
            PreparedSale prepared,
            PosCashController.SaleRequest request,
            BigDecimal discountedTotal,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        var currentDiscountedTotal = currentDiscountedTotal(prepared, discountedTotal);
        if (operationSecurity == null) {
            enforceLegacyDiscountAuthorization(
                    prepared, request, currentDiscountedTotal, authentication);
            return;
        }
        var discountPercentages = new EnumMap<SaleOperationCode, BigDecimal>(
                SaleOperationCode.class);
        discountPercentages.put(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                maximumLineDiscount(request));
        if (effectiveCheckoutDiscount(request, prepared.replay()).signum() > 0) {
            discountPercentages.put(
                    SaleOperationCode.APPLY_CHECKOUT_DISCOUNT,
                    checkoutDiscountPercent(
                            request, prepared.replay(), currentDiscountedTotal));
        }
        var remainingOperations = EnumSet.noneOf(SaleOperationCode.class);
        remainingOperations.addAll(prepared.sensitiveOperations());
        if (remainingOperations.remove(SaleOperationCode.TEMPORARY_PRICE_CHANGE)) {
            if (temporaryPriceAuthorizations != null
                    && !prepared.temporaryPriceClaims().isEmpty()) {
                temporaryPriceAuthorizations.claimAll(
                        prepared.temporaryPriceClaims(), authentication, sourceType, sourceId);
            }
            if ((prepared.replay() != null
                    && prepared.replay().hasTemporaryPriceOverride())
                    || prepared.temporaryPriceClaims().isEmpty()) {
                remainingOperations.add(SaleOperationCode.TEMPORARY_PRICE_CHANGE);
            }
        }
        authorizeOperations(
                remainingOperations,
                request.operationAuthorizations(),
                discountPercentages,
                authentication,
                sourceType,
                sourceId);
    }

    private static BigDecimal currentDiscountedTotal(
            PreparedSale prepared,
            BigDecimal combinedDiscountedTotal) {
        var combined = Money.euros(combinedDiscountedTotal);
        if (prepared == null || prepared.replay() == null) {
            return combined;
        }
        var current = Money.euros(combined.subtract(
                prepared.replay().frozenExact()
                        ? prepared.replay().total() : BigDecimal.ZERO));
        if (current.signum() < 0) {
            throw new IllegalStateException(
                    "message.document.previous_ticket_snapshot_mismatch");
        }
        return current;
    }

    void validateQuoteFingerprint(
            PosCashController.SaleRequest request,
            CommercialDocument quoted) {
        var expected = request.quoteFingerprint();
        if (!hasText(expected)) {
            if (request.previousTicketImport() != null) {
                throw new IllegalArgumentException(
                        "message.document.previous_ticket_quote_required");
            }
            return;
        }
        var actual = quoteFingerprint(
                quoted, quoted.getLineas().stream().map(QuoteLine::from).toList());
        if (!MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                    "message.document.previous_ticket_quote_changed");
        }
    }

    void completeTemporaryPriceAuthorizations(String sourceType, UUID sourceId) {
        if (temporaryPriceAuthorizations != null && sourceId != null) {
            temporaryPriceAuthorizations.consume(sourceType, sourceId);
        }
    }

    void releaseTemporaryPriceAuthorizations(String sourceType, UUID sourceId) {
        if (temporaryPriceAuthorizations != null && sourceId != null) {
            temporaryPriceAuthorizations.release(sourceType, sourceId);
        }
    }

    void authorizeLegacyTicketMutation(
            DocumentCommand command,
            Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations,
            Authentication authentication,
            String sourceType) {
        authorizeLegacyTicketMutation(
                command,
                List.of(),
                operationAuthorizations,
                authentication,
                sourceType);
    }

    void authorizeLegacyTicketMutation(
            DocumentCommand command,
            List<PaymentCommand> payments,
            Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations,
            Authentication authentication,
            String sourceType) {
        if (operationSecurity == null) {
            return;
        }
        for (var line : command.lineas()) {
            if (line.cantidad().signum() < 0
                    && line.cantidad().compareTo(BigDecimal.ONE.negate()) != 0) {
                throw new IllegalArgumentException(
                        "manual_return_quantity_must_be_minus_one");
            }
        }
        var storeId = organization.currentStore().getId();
        var operations = EnumSet.noneOf(SaleOperationCode.class);
        var maximumDiscount = command.descuentoGlobal() == null
                ? BigDecimal.ZERO
                : command.descuentoGlobal();
        for (var line : command.lineas()) {
            if (line.cantidad().signum() < 0) {
                operations.add(SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET);
            }
            if (line.descuento().signum() > 0) {
                operations.add(SaleOperationCode.APPLY_SALE_DISCOUNT);
                maximumDiscount = maximumDiscount.max(line.descuento());
            }
            if (line.temporaryNameOverride()) {
                operations.add(SaleOperationCode.TEMPORARY_NAME);
            }
            if (line.temporaryPriceOverride()) {
                operations.add(SaleOperationCode.TEMPORARY_PRICE_CHANGE);
            }
            var product = products.findById(line.productoId())
                    .filter(value -> value.getStoreId().equals(storeId))
                    .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
            if (Money.euros(product.getSalePrice()).signum() == 0) {
                authoritativeUnitPrice(product.getSalePrice(), line.precioUnitario());
                operations.add(SaleOperationCode.OPEN_PRICE_PRODUCT);
            }
        }
        if (command.descuentoGlobal() != null
                && command.descuentoGlobal().signum() > 0) {
            operations.add(SaleOperationCode.APPLY_SALE_DISCOUNT);
        }
        operations.addAll(manualPaymentOperations(payments));
        authorizeOperations(
                operations,
                operationAuthorizations,
                Map.of(SaleOperationCode.APPLY_SALE_DISCOUNT, maximumDiscount),
                authentication,
                sourceType,
                null);
    }

    private Set<SaleOperationCode> manualPaymentOperations(
            List<PaymentCommand> payments) {
        var operations = EnumSet.noneOf(SaleOperationCode.class);
        if (payments == null || payments.isEmpty()) {
            return operations;
        }
        var companyId = organization.currentCompany().getId();
        for (var payment : payments) {
            var method = paymentMethods.findByIdAndEmpresaId(
                            payment.metodoPagoId(), companyId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Metodo de pago no encontrado"));
            if ("TARJETA".equals(method.getNombre())
                    && payment.cardMode()
                    != com.tpverp.backend.terminal.PaymentCardMode.INTEGRATED) {
                operations.add(SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT);
            } else if ("TRANSFERENCIA".equals(method.getNombre())) {
                operations.add(SaleOperationCode.CONFIRM_TRANSFER_PAYMENT);
            }
        }
        return operations;
    }

    private void authorizeOperations(
            Set<SaleOperationCode> operations,
            Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations,
            Map<SaleOperationCode, BigDecimal> discountPercentages,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        var credentials = operationAuthorizations == null
                ? Map.<SaleOperationCode, OperationAuthorizationRequest>of()
                : operationAuthorizations;
        for (var operationCode : operations) {
            var policy = operationSecurity.resolve(operationCode);
            var request = credentials.getOrDefault(
                    operationCode, OperationAuthorizationRequest.empty());
            var requestedDiscount = discountPercentages.get(operationCode);
            var authorization = authorizeOperation(
                    operationCode,
                    policy,
                    request,
                    requestedDiscount,
                    authentication);
            if (requestedDiscount != null
                    && policy.requirePermission()
                    && discountAuthorizations != null) {
                discountAuthorizations.enforceAuthorizerLimit(
                        requestedDiscount, authorization.authorizer());
            }
            auditAuthorization(
                    operationCode,
                    policy,
                    authorization,
                    sourceType,
                    sourceId);
        }
    }

    private Authorization authorizeOperation(
            SaleOperationCode operationCode,
            SaleOperationSecurityService.ResolvedOperation policy,
            OperationAuthorizationRequest request,
            BigDecimal requestedDiscount,
            Authentication authentication) {
        if (requestedDiscount != null && policy.requirePermission()) {
            var operator = requireUser(authentication);
            var operatorHasPermission = PermissionChecks.hasRole(authentication, "ADMIN")
                    || policy.permissions().stream().anyMatch(permission ->
                            PermissionChecks.hasAuthority(authentication, permission));
            var operatorCoversDiscount = discountAuthorizations == null
                    || operator.getMaxDiscountPercent().compareTo(requestedDiscount) >= 0;
            if (!operatorHasPermission || !operatorCoversDiscount) {
                return operationSecurity.authorizeNamed(
                        operationCode,
                        request.authorizerUsername(),
                        request.authorizerPassword(),
                        authentication);
            }
        }
        return operationSecurity.authorize(
                operationCode,
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication);
    }

    private void auditAuthorization(
            SaleOperationCode operationCode,
            SaleOperationSecurityService.ResolvedOperation policy,
            Authorization authorization,
            String sourceType,
            UUID sourceId) {
        if (audit == null
                || (operationCode == SaleOperationCode.OPEN_PRICE_PRODUCT
                && !policy.requirePermission()
                && !policy.requirePassword())) {
            return;
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("operationCode", operationCode.name());
        details.put("policyVersion", policy.version());
        details.put("requirePermission", policy.requirePermission());
        details.put("requirePassword", policy.requirePassword());
        details.put("operatorId", authorization.operator().getId().toString());
        details.put("operatorUsername", authorization.operator().getUserName());
        details.put("authorizerId", authorization.authorizer().getId().toString());
        details.put("authorizerUsername", authorization.authorizer().getUserName());
        details.put("delegated", authorization.delegated());
        if (hasText(sourceType)) {
            details.put("sourceType", sourceType);
        }
        if (sourceId != null) {
            details.put("sourceId", sourceId.toString());
        }
        audit.record(SALE_OPERATION_AUTHORIZED, AuditResult.EXITO, Map.copyOf(details));
    }

    private void enforceLegacyDiscountAuthorization(
            PreparedSale prepared,
            PosCashController.SaleRequest request,
            BigDecimal discountedTotal,
            Authentication authentication) {
        if (discountAuthorizations == null) {
            return;
        }
        discountAuthorizations.enforce(
                maximumLineDiscount(request),
                request.discountAuthorizationToken(),
                authentication);
        authorizeCheckoutDiscount(
                prepared, request, discountedTotal, authentication);
    }

    private static BigDecimal maximumLineDiscount(
            PosCashController.SaleRequest request) {
        return request.lines().stream()
                .map(PosCashController.LineRequest::discount)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private static BigDecimal checkoutDiscountPercent(
            PosCashController.SaleRequest request,
            PreviousTicketImportService.ResolvedImport replay,
            BigDecimal discountedTotal) {
        var discount = effectiveCheckoutDiscount(request, replay);
        var totalBeforeCheckoutDiscount = Money.euros(discountedTotal).add(discount);
        if (totalBeforeCheckoutDiscount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "El descuento de cobro no puede cubrir todo el ticket");
        }
        return discount
                .multiply(new BigDecimal("100"))
                .divide(totalBeforeCheckoutDiscount, 2, RoundingMode.HALF_UP);
    }

    void authorizeCheckoutDiscount(
            PosCashController.SaleRequest request,
            BigDecimal discountedTotal,
            Authentication authentication) {
        authorizeCheckoutDiscount(null, request, discountedTotal, authentication);
    }

    private void authorizeCheckoutDiscount(
            PreparedSale prepared,
            PosCashController.SaleRequest request,
            BigDecimal discountedTotal,
            Authentication authentication) {
        var replay = prepared == null ? null : prepared.replay();
        if (discountAuthorizations == null
                || operationSecurity != null
                || effectiveCheckoutDiscount(request, replay).signum() == 0) {
            return;
        }
        discountAuthorizations.enforce(
                checkoutDiscountPercent(request, replay, discountedTotal),
                request.discountAuthorizationToken(),
                authentication);
    }

    private static BigDecimal effectiveCheckoutDiscount(
            PosCashController.SaleRequest request,
            PreviousTicketImportService.ResolvedImport replay) {
        var requested = request.checkoutDiscountAmount() == null
                ? BigDecimal.ZERO : request.checkoutDiscountAmount();
        var preserved = replay != null && replay.currentRepricing()
                ? replay.preservedManualDiscountAmount() : BigDecimal.ZERO;
        return Money.euros(requested.add(preserved));
    }

    static BigDecimal authoritativeUnitPrice(
            BigDecimal catalogSalePrice,
            BigDecimal requestedOpenUnitPrice) {
        if (catalogSalePrice == null) {
            throw new IllegalStateException("El precio de venta del producto no esta configurado");
        }
        var normalizedCatalogPrice = Money.euros(catalogSalePrice);
        if (normalizedCatalogPrice.signum() != 0) {
            if (requestedOpenUnitPrice == null) {
                return normalizedCatalogPrice;
            }
            if (requestedOpenUnitPrice.scale() > 2) {
                throw new IllegalArgumentException(
                        "El precio temporal admite un maximo de 2 decimales");
            }
            var temporaryPrice = Money.euros(requestedOpenUnitPrice);
            if (temporaryPrice.signum() <= 0) {
                throw new IllegalArgumentException(
                        "El precio temporal debe ser mayor que 0");
            }
            return temporaryPrice;
        }
        if (requestedOpenUnitPrice == null) {
            throw new IllegalArgumentException(
                    "Debe indicar el precio para el producto con precio de venta 0");
        }
        if (requestedOpenUnitPrice.scale() > 2) {
            throw new IllegalArgumentException("El precio abierto admite un maximo de 2 decimales");
        }
        var normalizedOpenPrice = Money.euros(requestedOpenUnitPrice);
        if (normalizedOpenPrice.signum() <= 0) {
            throw new IllegalArgumentException("El precio abierto debe ser mayor que 0");
        }
        return normalizedOpenPrice;
    }

    private static String normalizedTemporaryName(
            String catalogName,
            String requestedTemporaryName) {
        if (requestedTemporaryName == null || requestedTemporaryName.isBlank()) {
            return catalogName;
        }
        return requestedTemporaryName.trim();
    }

    public record Quote(
            BigDecimal total,
            BigDecimal productTotal,
            PromotionPreviewView promotionPreview,
            BigDecimal baseTotal,
            BigDecimal taxTotal,
            BigDecimal discountTotal,
            String currency,
            UUID storeId,
            UUID customerId,
            String quoteFingerprint,
            List<QuoteLine> lines,
            List<TaxBreakdown> taxes,
            int pricingVersion,
            List<AuthoritativeLineBreakdown> lineBreakdown) {

        static Quote from(
                CommercialDocument ticket,
                PosCashController.SaleRequest request,
                Map<UUID, Product> catalog,
                AuthoritativePromotionPricing.CustomerContext customer) {
            return from(ticket, request, catalog, customer, 0, 0, false);
        }

        static Quote from(
                CommercialDocument ticket,
                PosCashController.SaleRequest request,
                Map<UUID, Product> catalog,
                AuthoritativePromotionPricing.CustomerContext customer,
                int historicalProductCount,
                int historicalLineCount,
                boolean frozenHistoricalBlock) {
            var productTotal = Money.euros(ticket.getLineas().stream()
                    .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                    .map(DocumentLine::getTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            var promotions = ticket.getLineas().stream()
                    .filter(line -> line.getLineType() != DocumentLineType.PRODUCT)
                    .filter(line -> line.getPosicion() > historicalLineCount)
                    .filter(line -> line.getPromotionId() != null || line.getPromotionalCouponId() != null)
                    .map(line -> new AppliedPromotion(
                            line.getPromotionId(), line.getPromotionalCouponId(),
                            line.getLineType().name(), line.getNombre(), line.getTotal().abs()))
                    .toList();
            var quoteLines = ticket.getLineas().stream().map(QuoteLine::from).toList();
            var listTotal = Money.euros(ticket.getLineas().stream()
                    .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                    .map(QuoteLine::grossTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            var taxGroups = new LinkedHashMap<String, MutableTaxBreakdown>();
            ticket.getLineas().forEach(line -> taxGroups.computeIfAbsent(
                            line.getRegimenImpuesto() + "|" + line.getPorcentajeImpuesto(),
                            ignored -> new MutableTaxBreakdown(
                                    line.getRegimenImpuesto(), line.getPorcentajeImpuesto()))
                    .add(line));
            var taxes = taxGroups.values().stream()
                    .map(MutableTaxBreakdown::view)
                    .sorted(Comparator.comparing(TaxBreakdown::regime)
                            .thenComparing(TaxBreakdown::percentage))
                    .toList();
            var discountTotal = Money.euros(listTotal.subtract(ticket.getTotal()).max(BigDecimal.ZERO));
            var fingerprint = PosCashService.quoteFingerprint(ticket, quoteLines);
            var breakdown = authoritativeLineBreakdown(
                    ticket, request, catalog, customer,
                    historicalProductCount, historicalLineCount,
                    frozenHistoricalBlock);
            return new Quote(
                    ticket.getTotal(), productTotal, new PromotionPreviewView(promotions),
                    ticket.getBaseTotal(), ticket.getImpuestoTotal(), discountTotal,
                    ticket.getMoneda(), ticket.getTiendaId(), ticket.getClienteId(),
                    fingerprint, quoteLines, taxes, 1, breakdown);
        }

        public Quote(BigDecimal total) {
            this(total, total, new PromotionPreviewView(List.of()), total, BigDecimal.ZERO,
                    BigDecimal.ZERO, "EUR", null, null, "", List.of(), List.of(), 1, List.of());
        }
    }

    public record PromotionPreviewView(List<AppliedPromotion> appliedPromotions) {
        public PromotionPreviewView {
            appliedPromotions = List.copyOf(appliedPromotions == null ? List.of() : appliedPromotions);
        }
    }

    public record AppliedPromotion(
            UUID id,
            UUID couponId,
            String kind,
            String name,
            BigDecimal discountAmount) {
    }

    public record QuoteLine(
            int position,
            UUID productId,
            DocumentLineType type,
            String code,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            String rate,
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercent,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total,
            UUID promotionId,
            UUID promotionVersionId,
            UUID promotionalCouponId) {

        static QuoteLine from(DocumentLine line) {
            return new QuoteLine(
                    line.getPosicion(), line.getProductoId(), line.getLineType(),
                    line.getCodigo(), line.getNombre(), line.getCantidad(),
                    line.getPrecioUnitario(), line.getDescuento(), line.getTarifa(),
                    line.isImpuestosIncluidos(),
                    line.getRegimenImpuesto(), line.getPorcentajeImpuesto(),
                    line.getBase(), line.getImpuesto(), line.getTotal(),
                    line.getPromotionId(), line.getPromotionVersionId(),
                    line.getPromotionalCouponId());
        }

        static BigDecimal grossTotal(DocumentLine line) {
            var gross = line.getPrecioUnitario().multiply(line.getCantidad());
            if (!line.isImpuestosIncluidos()) {
                gross = gross.multiply(BigDecimal.ONE.add(line.getPorcentajeImpuesto().movePointLeft(2)));
            }
            return Money.euros(gross);
        }
    }

    public record TaxBreakdown(
            String regime,
            BigDecimal percentage,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total) {
    }

    /**
     * Stable economic contract for APP VENTA. Product rows keep their own benefits;
     * document-level discounts are emitted as summary rows. All components reconcile
     * exactly to {@link #finalSubtotal()}.
     */
    public record AuthoritativeLineBreakdown(
            String lineId,
            int position,
            DocumentLineType lineType,
            UUID productId,
            String code,
            String name,
            BigDecimal quantity,
            BigDecimal normalUnitPrice,
            BigDecimal memberUnitPrice,
            BigDecimal baseUnitPrice,
            String priceSource,
            BigDecimal memberPriceSaving,
            BigDecimal memberDiscountPercent,
            BigDecimal memberDiscount,
            BigDecimal manualDiscountPercent,
            BigDecimal manualDiscount,
            BigDecimal promotionDiscount,
            BigDecimal couponDiscount,
            boolean taxIncluded,
            String taxRegime,
            BigDecimal taxPercent,
            BigDecimal taxBase,
            BigDecimal tax,
            BigDecimal baseSubtotal,
            BigDecimal roundingAdjustment,
            BigDecimal finalSubtotal) {
    }

    private static List<AuthoritativeLineBreakdown> authoritativeLineBreakdown(
            CommercialDocument ticket,
            PosCashController.SaleRequest request,
            Map<UUID, Product> catalog,
            AuthoritativePromotionPricing.CustomerContext customer,
            int historicalProductCount,
            int historicalLineCount,
            boolean frozenHistoricalBlock) {
        var productLines = ticket.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .toList();
        var builders = new ArrayList<MutableAuthoritativeLine>();
        var occurrences = new LinkedHashMap<UUID, Integer>();
        for (int index = 0; index < productLines.size(); index++) {
            var line = productLines.get(index);
            var product = catalog.get(line.getProductoId());
            if (product == null) {
                throw new IllegalStateException("Producto de cotizacion no encontrado en el catalogo");
            }
            var currentIndex = index - historicalProductCount;
            var requestedDiscount = currentIndex >= 0
                    && currentIndex < request.lines().size()
                    ? request.lines().get(currentIndex).discount()
                    : BigDecimal.ZERO;
            var occurrence = occurrences.merge(line.getProductoId(), 1, Integer::sum);
            builders.add(new MutableAuthoritativeLine(
                    line, product, requestedDiscount,
                    customer.categoryDiscountPercent(), occurrence,
                    frozenHistoricalBlock && index < historicalProductCount));
        }

        ticket.getLineas().stream()
                .filter(line -> line.getLineType() != DocumentLineType.PRODUCT)
                .filter(line -> line.getLineType() != DocumentLineType.RETURN_ADJUSTMENT)
                .filter(line -> line.getLineType() != DocumentLineType.MANUAL_DISCOUNT)
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(adjustment -> allocateAdjustment(
                        builders, adjustment,
                        frozenHistoricalBlock
                                && adjustment.getPosicion() <= historicalLineCount));

        var result = new ArrayList<>(builders.stream()
                .map(MutableAuthoritativeLine::view)
                .toList());
        manualDiscountBreakdown(ticket.getLineas()).ifPresent(result::add);
        ticket.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.RETURN_ADJUSTMENT)
                .map(PosCashService::returnAdjustmentBreakdown)
                .forEach(result::add);
        result.sort(Comparator.comparingInt(AuthoritativeLineBreakdown::position));
        var reconciled = Money.euros(result.stream()
                .map(AuthoritativeLineBreakdown::finalSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (reconciled.compareTo(ticket.getTotal()) != 0) {
            throw new IllegalStateException("authoritative_quote_line_total_mismatch");
        }
        return List.copyOf(result);
    }

    private static Optional<AuthoritativeLineBreakdown> manualDiscountBreakdown(
            List<DocumentLine> lines) {
        var discounts = lines.stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .toList();
        if (discounts.isEmpty()) {
            return Optional.empty();
        }
        var zero = Money.euros(BigDecimal.ZERO);
        var base = Money.euros(discounts.stream()
                .map(DocumentLine::getBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        var tax = Money.euros(discounts.stream()
                .map(DocumentLine::getImpuesto)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        var total = Money.euros(discounts.stream()
                .map(DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return Optional.of(new AuthoritativeLineBreakdown(
                "manual-discount",
                discounts.getFirst().getPosicion(),
                DocumentLineType.MANUAL_DISCOUNT,
                null,
                "DESCUENTO",
                "DESCUENTO",
                BigDecimal.ONE,
                zero,
                null,
                zero,
                null,
                zero,
                zero,
                zero,
                zero,
                total.abs(),
                zero,
                zero,
                true,
                "MIXED",
                zero,
                base,
                tax,
                zero,
                zero,
                total));
    }

    private static AuthoritativeLineBreakdown returnAdjustmentBreakdown(
            DocumentLine line) {
        var zero = Money.euros(BigDecimal.ZERO);
        return new AuthoritativeLineBreakdown(
                "return-adjustment:" + line.getPosicion(),
                line.getPosicion(),
                DocumentLineType.RETURN_ADJUSTMENT,
                null,
                line.getCodigo(),
                line.getNombre(),
                line.getCantidad(),
                zero,
                null,
                line.getPrecioUnitario(),
                null,
                zero,
                zero,
                zero,
                zero,
                zero,
                zero,
                zero,
                line.isImpuestosIncluidos(),
                line.getRegimenImpuesto(),
                line.getPorcentajeImpuesto(),
                line.getBase(),
                line.getImpuesto(),
                line.getTotal(),
                zero,
                line.getTotal());
    }

    private static void allocateAdjustment(
            List<MutableAuthoritativeLine> builders,
            DocumentLine adjustment,
            boolean historical) {
        var affectedPositions = adjustment.getPromotionAffectedPositions();
        var candidates = builders.stream()
                .filter(line -> line.historical == historical)
                .filter(line -> affectedPositions.isEmpty()
                        || affectedPositions.contains(line.line.getPosicion()))
                .toList();
        var eligible = candidates.stream()
                .filter(line -> line.matchesTax(adjustment))
                .toList();
        if (eligible.isEmpty()) {
            eligible = candidates;
        }
        if (eligible.isEmpty()) {
            throw new IllegalStateException("authoritative_quote_adjustment_without_product");
        }
        var weightTotal = eligible.stream()
                .map(MutableAuthoritativeLine::allocationWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightTotal.signum() == 0) {
            weightTotal = BigDecimal.valueOf(eligible.size());
        }
        var remainingBase = adjustment.getBase();
        var remainingTax = adjustment.getImpuesto();
        var remainingTotal = adjustment.getTotal();
        for (int index = 0; index < eligible.size(); index++) {
            var target = eligible.get(index);
            var last = index == eligible.size() - 1;
            var weight = target.allocationWeight().signum() == 0
                    ? BigDecimal.ONE : target.allocationWeight();
            var base = last ? remainingBase : proportional(adjustment.getBase(), weight, weightTotal);
            var tax = last ? remainingTax : proportional(adjustment.getImpuesto(), weight, weightTotal);
            var total = last ? remainingTotal : proportional(adjustment.getTotal(), weight, weightTotal);
            target.applyAdjustment(adjustment.getLineType(), base, tax, total);
            remainingBase = Money.euros(remainingBase.subtract(base));
            remainingTax = Money.euros(remainingTax.subtract(tax));
            remainingTotal = Money.euros(remainingTotal.subtract(total));
        }
    }

    private static BigDecimal proportional(
            BigDecimal amount,
            BigDecimal weight,
            BigDecimal weightTotal) {
        return Money.euros(amount.multiply(weight)
                .divide(weightTotal, Money.SCALE + 4, Money.ROUNDING));
    }

    private static final class MutableAuthoritativeLine {
        private final DocumentLine line;
        private final Product product;
        private final BigDecimal normalUnitPrice;
        private final BigDecimal memberUnitPrice;
        private final BigDecimal baseSubtotal;
        private final BigDecimal memberPriceSaving;
        private final BigDecimal memberDiscountPercent;
        private final BigDecimal memberDiscount;
        private final BigDecimal manualDiscountPercent;
        private final BigDecimal manualDiscount;
        private final String lineId;
        private final boolean historical;
        private BigDecimal promotionDiscount = Money.euros(BigDecimal.ZERO);
        private BigDecimal couponDiscount = Money.euros(BigDecimal.ZERO);
        private BigDecimal finalBase;
        private BigDecimal finalTax;
        private BigDecimal finalTotal;

        private MutableAuthoritativeLine(
                DocumentLine line,
                Product product,
                BigDecimal requestedDiscount,
                BigDecimal categoryDiscount,
                int occurrence,
                boolean historical) {
            this.line = line;
            this.product = product;
            this.lineId = "product:" + line.getProductoId() + ":" + occurrence;
            this.historical = historical;
            this.normalUnitPrice = Money.euros(product.getSalePrice());
            this.memberUnitPrice = product.getMemberPrice() == null
                    ? null : Money.euros(product.getMemberPrice());
            this.baseSubtotal = QuoteLine.grossTotal(line);
            this.memberPriceSaving = memberPriceSaving(line, normalUnitPrice);
            var lineDiscount = Money.euros(baseSubtotal.subtract(line.getTotal()).max(BigDecimal.ZERO));
            var normalizedRequested = requestedDiscount == null ? BigDecimal.ZERO : requestedDiscount;
            var normalizedCategory = categoryDiscount == null ? BigDecimal.ZERO : categoryDiscount;
            var categoryWins = normalizedCategory.signum() > 0
                    && normalizedCategory.compareTo(normalizedRequested) >= 0
                    && line.getDescuento().compareTo(normalizedCategory) == 0;
            this.memberDiscountPercent = categoryWins ? line.getDescuento() : BigDecimal.ZERO;
            this.memberDiscount = categoryWins ? lineDiscount : Money.euros(BigDecimal.ZERO);
            this.manualDiscountPercent = categoryWins ? BigDecimal.ZERO : line.getDescuento();
            this.manualDiscount = categoryWins ? Money.euros(BigDecimal.ZERO) : lineDiscount;
            this.finalBase = line.getBase();
            this.finalTax = line.getImpuesto();
            this.finalTotal = line.getTotal();
        }

        private static BigDecimal memberPriceSaving(
                DocumentLine line,
                BigDecimal normalUnitPrice) {
            if (!"MEMBER".equals(line.getTarifa())
                    || normalUnitPrice.compareTo(line.getPrecioUnitario()) <= 0) {
                return Money.euros(BigDecimal.ZERO);
            }
            var saving = normalUnitPrice.subtract(line.getPrecioUnitario())
                    .multiply(line.getCantidad());
            if (!line.isImpuestosIncluidos()) {
                saving = saving.multiply(BigDecimal.ONE.add(
                        line.getPorcentajeImpuesto().movePointLeft(2)));
            }
            return Money.euros(saving);
        }

        private boolean matchesTax(DocumentLine adjustment) {
            return line.getRegimenImpuesto().equals(adjustment.getRegimenImpuesto())
                    && line.getPorcentajeImpuesto().compareTo(adjustment.getPorcentajeImpuesto()) == 0;
        }

        private BigDecimal allocationWeight() {
            return line.getTotal().abs();
        }

        private void applyAdjustment(
                DocumentLineType type,
                BigDecimal base,
                BigDecimal tax,
                BigDecimal total) {
            finalBase = Money.euros(finalBase.add(base));
            finalTax = Money.euros(finalTax.add(tax));
            finalTotal = Money.euros(finalTotal.add(total));
            if (type == DocumentLineType.PROMOTIONAL_COUPON) {
                couponDiscount = Money.euros(couponDiscount.add(total.abs()));
            } else {
                promotionDiscount = Money.euros(promotionDiscount.add(total.abs()));
            }
        }

        private AuthoritativeLineBreakdown view() {
            var expected = Money.euros(baseSubtotal
                    .subtract(memberDiscount)
                    .subtract(manualDiscount)
                    .subtract(promotionDiscount)
                    .subtract(couponDiscount));
            var rounding = Money.euros(finalTotal.subtract(expected));
            return new AuthoritativeLineBreakdown(
                    lineId, line.getPosicion(), line.getLineType(), line.getProductoId(), line.getCodigo(),
                    line.getNombre(), line.getCantidad(), normalUnitPrice, memberUnitPrice,
                    line.getPrecioUnitario(), line.getTarifa(), memberPriceSaving,
                    memberDiscountPercent, memberDiscount, manualDiscountPercent,
                    manualDiscount, promotionDiscount, couponDiscount,
                    line.isImpuestosIncluidos(), line.getRegimenImpuesto(),
                    line.getPorcentajeImpuesto(), finalBase, finalTax, baseSubtotal,
                    rounding, finalTotal);
        }
    }

    private static final class MutableTaxBreakdown {
        private final String regime;
        private final BigDecimal percentage;
        private BigDecimal base = BigDecimal.ZERO;
        private BigDecimal tax = BigDecimal.ZERO;
        private BigDecimal total = BigDecimal.ZERO;

        private MutableTaxBreakdown(String regime, BigDecimal percentage) {
            this.regime = regime;
            this.percentage = percentage;
        }

        private void add(DocumentLine line) {
            base = base.add(line.getBase());
            tax = tax.add(line.getImpuesto());
            total = total.add(line.getTotal());
        }

        private TaxBreakdown view() {
            return new TaxBreakdown(
                    regime, percentage, Money.euros(base), Money.euros(tax), Money.euros(total));
        }
    }

    private static String quoteFingerprint(
            CommercialDocument ticket,
            List<QuoteLine> lines) {
        var canonical = new StringBuilder("authoritative-sale-quote-v1|")
                .append(ticket.getTiendaId()).append('|')
                .append(ticket.getClienteId()).append('|')
                .append(ticket.getFecha()).append('|')
                .append(ticket.getMoneda()).append('|')
                .append(ticket.getBaseTotal()).append('|')
                .append(ticket.getImpuestoTotal()).append('|')
                .append(ticket.getTotal());
        lines.forEach(line -> canonical.append('|')
                .append(line.position()).append(':')
                .append(line.productId()).append(':')
                .append(line.type()).append(':')
                .append(line.quantity()).append(':')
                .append(line.unitPrice()).append(':')
                .append(line.discountPercent()).append(':')
                .append(line.rate()).append(':')
                .append(line.taxesIncluded()).append(':')
                .append(line.taxRegime()).append(':')
                .append(line.taxPercent()).append(':')
                .append(line.base()).append(':')
                .append(line.tax()).append(':')
                .append(line.total()).append(':')
                .append(line.promotionId()).append(':')
                .append(line.promotionVersionId()).append(':')
                .append(line.promotionalCouponId()));
        return hashText(canonical.toString());
    }

    private static String hashText(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String requestHash(PosCashController.CashRequest request) {
        var internalComment = normalize(request.sale().internalComment());
        if (request.sale().previousTicketImport() == null
                && !hasCheckoutDiscount(request.sale()) && internalComment.isEmpty()
                && normalize(request.sale().quoteFingerprint()).isEmpty()) {
            return legacyRequestHash(request);
        }
        var couponCode = normalize(request.sale().promotionalCouponCode());
        var hasOpenPrice = request.sale().lines().stream()
                .anyMatch(line -> line.openUnitPrice() != null);
        var hasSerialNumbers = request.sale().lines().stream()
                .anyMatch(line -> line.serialNumbers() != null && !line.serialNumbers().isEmpty());
        var hasTemporaryNames = request.sale().lines().stream()
                .anyMatch(line -> line.temporaryName() != null
                        && !line.temporaryName().isBlank());
        var canonical = new StringBuilder(request.sale().previousTicketImport() != null
                ? "v8-previous-ticket-import|"
                : hasTemporaryNames
                ? "v7-temporary-name|"
                : !internalComment.isEmpty()
                ? "v6-internal-comment|"
                : hasSerialNumbers
                ? "v5-checkout-serials|"
                : hasOpenPrice
                ? "v4-checkout-discount-open-price|"
                : "v4-checkout-discount|")
                .append(request.sale().customerId()).append('|')
                .append(canonicalPreviousTicketImport(
                        request.sale().previousTicketImport())).append('|');
        if (!internalComment.isEmpty()) {
            canonical.append(internalComment.length()).append(':').append(internalComment).append('|');
        }
        if (!couponCode.isEmpty()) {
            canonical.append(couponCode).append('|');
        }
        canonical
                .append(request.sale().checkoutDiscountAmount() == null
                        ? "0.00" : Money.euros(request.sale().checkoutDiscountAmount()))
                .append('|')
                .append(normalize(request.sale().quoteFingerprint())).append('|')
                .append(Money.euros(request.received())).append('|')
                .append(Money.euros(request.quotedTotal()));
        request.sale().lines().forEach(line -> {
            canonical.append('|')
                    .append(line.productId()).append(':')
                    .append(line.quantity().stripTrailingZeros().toPlainString()).append(':')
                    .append(line.discount().stripTrailingZeros().toPlainString())
                    .append(hasOpenPrice ? ":" + normalizedOpenPrice(line.openUnitPrice()) : "");
            if (hasSerialNumbers) {
                canonical.append(':').append(canonicalSerialNumbers(line.serialNumbers()));
            }
            if (hasTemporaryNames) {
                canonical.append(':').append(canonicalText(line.temporaryName()));
            }
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String legacyRequestHash(PosCashController.CashRequest request) {
        var couponCode = normalize(request.sale().promotionalCouponCode());
        var hasOpenPrice = request.sale().lines().stream()
                .anyMatch(line -> line.openUnitPrice() != null);
        var hasSerialNumbers = request.sale().lines().stream()
                .anyMatch(line -> line.serialNumbers() != null && !line.serialNumbers().isEmpty());
        var hasTemporaryNames = request.sale().lines().stream()
                .anyMatch(line -> line.temporaryName() != null
                        && !line.temporaryName().isBlank());
        var canonical = new StringBuilder(hasTemporaryNames
                ? "v7-temporary-name|"
                : hasSerialNumbers
                ? "v4-serials|"
                : hasOpenPrice
                ? "v3-open-price|"
                : couponCode.isEmpty() ? "v1|" : "v2-coupon|")
                .append(request.sale().customerId()).append('|');
        if (!couponCode.isEmpty()) canonical.append(couponCode).append('|');
        canonical.append(Money.euros(request.received())).append('|')
                .append(Money.euros(request.quotedTotal()));
        request.sale().lines().forEach(line -> {
            canonical.append('|')
                    .append(line.productId()).append(':')
                    .append(line.quantity().stripTrailingZeros().toPlainString()).append(':')
                    .append(line.discount().stripTrailingZeros().toPlainString())
                    .append(hasOpenPrice ? ":" + normalizedOpenPrice(line.openUnitPrice()) : "");
            if (hasSerialNumbers) {
                canonical.append(':').append(canonicalSerialNumbers(line.serialNumbers()));
            }
            if (hasTemporaryNames) {
                canonical.append(':').append(canonicalText(line.temporaryName()));
            }
        });
        return hashText(canonical.toString());
    }

    static String canonicalSerialNumbers(List<String> values) {
        if (values == null || values.isEmpty()) return "-";
        return values.stream()
                .map(value -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        value.trim().getBytes(StandardCharsets.UTF_8)))
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    static String canonicalText(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.trim().getBytes(StandardCharsets.UTF_8));
    }

    static String canonicalPreviousTicketImport(
            PosCashController.PreviousTicketImportRequest request) {
        if (request == null) {
            return "-";
        }
        var canonical = new StringBuilder()
                .append(request.ticketId()).append(':')
                .append(request.fingerprint().trim());
        request.serialNumbersBySourceLineId().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(':')
                        .append(entry.getKey()).append('=')
                        .append(canonicalSerialNumbers(entry.getValue())));
        return canonical.toString();
    }

    private static String normalizedOpenPrice(BigDecimal value) {
        return value == null ? "-" : Money.euros(value).toPlainString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasCheckoutDiscount(PosCashController.SaleRequest request) {
        return request.checkoutDiscountAmount() != null
                && Money.euros(request.checkoutDiscountAmount()).signum() > 0;
    }

    private Result resultFrom(PosCashCheckout checkout) {
        return new Result(checkout.getDocumentId(), checkout.getTicketNumber(), checkout.getTotal(),
                checkout.getReceived(), checkout.getChange(),
                snapshots.deserialize(checkout.getTicketSnapshot()));
    }

    private static UserAccount requireUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user) {
            return user;
        }
        throw new IllegalStateException("user_required");
    }

    public record Result(
            UUID id,
            String number,
            BigDecimal total,
            BigDecimal received,
            BigDecimal change,
            TicketPrintView printTicket) {}

    private record TransactionalCharge(Result result, CommercialDocument ticket) {}
}
