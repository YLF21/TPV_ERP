package com.tpverp.backend.document;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.promotion.AuthoritativePromotionPricing;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCashService {

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
            AuthoritativePromotionPricing promotionPricing) {
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
                checkouts, snapshots, currentTerminal, discountAuthorizations, null);
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
                checkouts, snapshots, currentTerminal, null, null);
    }

    @Transactional(readOnly = true)
    public Quote quote(PosCashController.SaleRequest request, Authentication authentication) {
        var command = authoritativeCommand(request, authentication);
        var ticket = hasText(request.promotionalCouponCode())
                ? documents.quoteTicket(command, request.promotionalCouponCode(), authentication)
                : documents.quoteTicket(command, authentication);
        var catalog = products.findAllByStoreIdAndIdIn(
                        ticket.getTiendaId(),
                        request.lines().stream().map(PosCashController.LineRequest::productId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(Product::getId, value -> value));
        var customer = promotionPricing == null
                ? AuthoritativePromotionPricing.CustomerContext.anonymous()
                : promotionPricing.customerContext(
                organization.currentCompany().getId(), request.customerId());
        return Quote.from(ticket, request, catalog, customer);
    }

    @Transactional
    public Result charge(PosCashController.CashRequest request, Authentication authentication) {
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
            return resultFrom(existing);
        }
        var command = authoritativeCommand(request.sale(), authentication);
        var quote = hasText(request.sale().promotionalCouponCode())
                ? documents.quoteTicket(
                command, request.sale().promotionalCouponCode(), authentication)
                : documents.quoteTicket(command, authentication);
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
        var ticket = hasText(request.sale().promotionalCouponCode())
                ? documents.createTicket(
                command, payment, request.sale().promotionalCouponCode(), authentication)
                : documents.createTicket(command, payment, authentication);
        var printTicket = TicketPrintView.from(ticket);
        reserved.complete(ticket.getId(), ticket.getNumero(), total, received, change,
                snapshots.serialize(printTicket), Instant.now());
        checkouts.save(reserved);
        return new Result(ticket.getId(), ticket.getNumero(), total, received, change, printTicket);
    }

    @Transactional(readOnly = true)
    DocumentCommand authoritativeCommand(
            PosCashController.SaleRequest request,
            Authentication authentication) {
        var store = organization.currentStore();
        var warehouse = warehouses.findByStoreIdAndPredeterminadoTrue(store.getId())
                .filter(value -> value.isActive())
                .orElseThrow(() -> new IllegalStateException("No hay un almacen predeterminado activo"));
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        var maximumDiscount = request.lines().stream()
                .map(PosCashController.LineRequest::discount)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        if (discountAuthorizations != null) {
            discountAuthorizations.enforce(
                    maximumDiscount, request.discountAuthorizationToken(), authentication);
        }
        var lines = request.lines().stream().map(line -> {
            var product = products.findById(line.productId())
                    .filter(value -> value.getStoreId().equals(store.getId()))
                    .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
            var tax = taxes.findById(product.getTaxId())
                    .filter(value -> value.getStoreId().equals(store.getId()) && value.isActive())
                    .orElseThrow(() -> new IllegalStateException("El impuesto del producto no esta activo"));
            return new DocumentLineCommand(
                    product.getId(), line.quantity(), product.getCode(), product.getName(), null,
                    product.getSalePrice(), line.discount(), product.isTaxesIncluded(),
                    "IVA", tax.getPercentage());
        }).toList();
        return new DocumentCommand(
                warehouse.getId(), CommercialDocumentType.TICKET,
                LocalDate.now(ZoneId.of(store.getTimezone())), request.customerId(), null, null,
                BigDecimal.ZERO.setScale(2), true, lines);
    }

    @Transactional(readOnly = true)
    DocumentCommand authoritativeCommand(PosCashController.SaleRequest request) {
        return authoritativeCommand(request, null);
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
            var productTotal = Money.euros(ticket.getLineas().stream()
                    .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                    .map(DocumentLine::getTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            var promotions = ticket.getLineas().stream()
                    .filter(line -> line.getLineType() != DocumentLineType.PRODUCT)
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
                    ticket, request, catalog, customer);
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
     * Stable per-product economic contract for APP VENTA. All monetary components are
     * mutually exclusive and reconcile exactly to {@link #finalSubtotal()}.
     */
    public record AuthoritativeLineBreakdown(
            String lineId,
            int position,
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
            AuthoritativePromotionPricing.CustomerContext customer) {
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
            var requestedDiscount = index < request.lines().size()
                    ? request.lines().get(index).discount()
                    : BigDecimal.ZERO;
            var occurrence = occurrences.merge(line.getProductoId(), 1, Integer::sum);
            builders.add(new MutableAuthoritativeLine(
                    line, product, requestedDiscount, customer.categoryDiscountPercent(), occurrence));
        }

        ticket.getLineas().stream()
                .filter(line -> line.getLineType() != DocumentLineType.PRODUCT)
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(adjustment -> allocateAdjustment(builders, adjustment));

        var result = builders.stream().map(MutableAuthoritativeLine::view).toList();
        var reconciled = Money.euros(result.stream()
                .map(AuthoritativeLineBreakdown::finalSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (reconciled.compareTo(ticket.getTotal()) != 0) {
            throw new IllegalStateException("authoritative_quote_line_total_mismatch");
        }
        return result;
    }

    private static void allocateAdjustment(
            List<MutableAuthoritativeLine> builders,
            DocumentLine adjustment) {
        var eligible = builders.stream()
                .filter(line -> line.matchesTax(adjustment))
                .toList();
        if (eligible.isEmpty()) {
            eligible = List.copyOf(builders);
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
                int occurrence) {
            this.line = line;
            this.product = product;
            this.lineId = "product:" + line.getProductoId() + ":" + occurrence;
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
                    lineId, line.getPosicion(), line.getProductoId(), line.getCodigo(),
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
                .append(line.taxPercent()).append(':')
                .append(line.total()).append(':')
                .append(line.promotionId()).append(':')
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
        var couponCode = normalize(request.sale().promotionalCouponCode());
        var canonical = new StringBuilder(couponCode.isEmpty() ? "v1|" : "v2-coupon|")
                .append(request.sale().customerId()).append('|');
        if (!couponCode.isEmpty()) {
            canonical.append(couponCode).append('|');
        }
        canonical
                .append(Money.euros(request.received())).append('|')
                .append(Money.euros(request.quotedTotal()));
        request.sale().lines().forEach(line -> canonical.append('|')
                .append(line.productId()).append(':')
                .append(line.quantity().stripTrailingZeros().toPlainString()).append(':')
                .append(line.discount().stripTrailingZeros().toPlainString()));
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
}
