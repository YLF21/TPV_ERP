package com.tpverp.backend.document;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductPriceHistory;
import com.tpverp.backend.catalog.ProductPriceHistoryRepository;
import com.tpverp.backend.catalog.ProductPriceHistoryType;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltyLine;
import com.tpverp.backend.party.MemberDocumentLoyaltyLineRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltySettlementRepository;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductQuantityPolicy;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreviousTicketImportService {

    private final CommercialDocumentRepository documents;
    private final ProductRepository products;
    private final ProductPriceHistoryRepository priceHistory;
    private final StoreTaxRepository taxes;
    private final LicenseRepository licenses;
    private final InstallationStatusService installationStatus;
    private final CustomerRepository customers;
    private final MemberDocumentLoyaltyLineRepository loyaltyLines;
    private final MemberDocumentLoyaltySettlementRepository loyaltySettlements;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;

    public PreviousTicketImportService(
            CommercialDocumentRepository documents,
            ProductRepository products,
            ProductPriceHistoryRepository priceHistory,
            StoreTaxRepository taxes,
            LicenseRepository licenses,
            InstallationStatusService installationStatus,
            CustomerRepository customers,
            MemberDocumentLoyaltyLineRepository loyaltyLines,
            MemberDocumentLoyaltySettlementRepository loyaltySettlements,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal) {
        this.documents = documents;
        this.products = products;
        this.priceHistory = priceHistory;
        this.taxes = taxes;
        this.licenses = licenses;
        this.installationStatus = installationStatus;
        this.customers = customers;
        this.loyaltyLines = loyaltyLines;
        this.loyaltySettlements = loyaltySettlements;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
    }

    @Transactional(readOnly = true)
    public PreviousTicketImportView preview(Authentication authentication) {
        var ticket = latestImportableTicket(authentication);
        var pricingMode = pricingMode(ticket);
        var currentCatalog = validateCurrentCatalog(ticket, pricingMode);
        var fingerprint = fingerprint(ticket, pricingMode, currentCatalog);
        var preservedManualDiscount = pricingMode
                == PreviousTicketImportPricingMode.CURRENT_REPRICING
                        ? preservedManualDiscountAmount(ticket)
                        : Money.euros(BigDecimal.ZERO);
        return new PreviousTicketImportView(
                ticket.getId(), ticket.getNumero(), ticket.getFecha(), ticket.getEstado(),
                pricingMode, ticket.getClienteId(), fingerprint,
                pricingMode == PreviousTicketImportPricingMode.FROZEN_EXACT
                        ? ticket.getDescuentoGlobal() : BigDecimal.ZERO,
                ticket.getBaseTotal(), ticket.getImpuestoTotal(), ticket.getTotal(),
                preservedManualDiscount, preservedManualDiscount.signum() > 0,
                ticket.getMoneda(),
                ticket.getLineas().stream()
                        .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                        .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                        .map(line -> previewLine(
                                ticket, line, currentCatalog.get(line.getProductoId()),
                                pricingMode))
                        .toList(),
                previewAdjustments(ticket, pricingMode));
    }

    @Transactional(readOnly = true)
    public ResolvedImport resolve(
            PosCashController.PreviousTicketImportRequest request,
            UUID requestedCustomerId,
            Authentication authentication) {
        Objects.requireNonNull(request, "previousTicketImport");
        var ticket = latestImportableTicket(authentication);
        if (!ticket.getId().equals(request.ticketId())) {
            throw new IllegalStateException("message.document.previous_ticket_changed");
        }
        var pricingMode = pricingMode(ticket);
        var currentCatalog = validateCurrentCatalog(ticket, pricingMode);
        var currentFingerprint = fingerprint(ticket, pricingMode, currentCatalog);
        if (!MessageDigest.isEqual(
                currentFingerprint.getBytes(StandardCharsets.UTF_8),
                request.fingerprint().trim().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("message.document.previous_ticket_changed");
        }
        if (!Objects.equals(ticket.getClienteId(), requestedCustomerId)) {
            throw new IllegalArgumentException(
                    "message.document.previous_ticket_customer_mismatch");
        }
        var productLineIds = ticket.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(DocumentLine::getId)
                .collect(java.util.stream.Collectors.toSet());
        var requestedSerials = request.serialNumbersBySourceLineId();
        if (!productLineIds.containsAll(requestedSerials.keySet())) {
            throw new IllegalArgumentException(
                    "message.document.previous_ticket_serial_line_invalid");
        }
        validateSerialNumbers(ticket, requestedSerials);
        var commands = new ArrayList<DocumentLineCommand>();
        if (pricingMode == PreviousTicketImportPricingMode.FROZEN_EXACT) {
            ticket.getLineas().stream()
                    .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                    .map(line -> historicalCommand(ticket, line, requestedSerials))
                    .forEach(commands::add);
            commands.addAll(globalDiscountAdjustments(ticket));
        } else {
            ticket.getLineas().stream()
                    .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                    .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                    .map(line -> currentCommand(
                            line, currentCatalog.get(line.getProductoId()),
                            requestedSerials.getOrDefault(line.getId(), List.of())))
                    .forEach(commands::add);
        }
        var preservedManualDiscount = pricingMode
                == PreviousTicketImportPricingMode.CURRENT_REPRICING
                        ? preservedManualDiscountAmount(ticket)
                        : Money.euros(BigDecimal.ZERO);
        List<HistoricalTicketReplayMetadata.HistoricalLoyaltyLine>
                historicalLoyaltyLines = pricingMode
                == PreviousTicketImportPricingMode.FROZEN_EXACT
                        ? historicalLoyaltyLines(ticket)
                        : List.<HistoricalTicketReplayMetadata.HistoricalLoyaltyLine>of();
        return new ResolvedImport(
                ticket.getId(), ticket.getNumero(), ticket.getEstado(), pricingMode,
                ticket.getClienteId(), currentFingerprint, ticket.getBaseTotal(),
                ticket.getImpuestoTotal(),
                pricingMode == PreviousTicketImportPricingMode.FROZEN_EXACT
                        ? ticket.getTotal() : Money.euros(BigDecimal.ZERO),
                commands,
                (int) ticket.getLineas().stream()
                        .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                        .count(),
                preservedManualDiscount, historicalLoyaltyLines);
    }

    private static PreviousTicketImportPricingMode pricingMode(
            CommercialDocument ticket) {
        return ticket.getEstado() == DocumentStatus.ANULADO
                ? PreviousTicketImportPricingMode.FROZEN_EXACT
                : PreviousTicketImportPricingMode.CURRENT_REPRICING;
    }

    private PreviousTicketImportView.LineView previewLine(
            CommercialDocument ticket,
            DocumentLine source,
            CurrentProductSnapshot current,
            PreviousTicketImportPricingMode pricingMode) {
        if (pricingMode == PreviousTicketImportPricingMode.FROZEN_EXACT) {
            return new PreviousTicketImportView.LineView(
                    source.getId(), source.getProductoId(), source.getCodigo(),
                    source.getNombre(), source.getCantidad(), source.getTarifa(),
                    source.getPrecioUnitario(), source.getDescuento(),
                    source.isImpuestosIncluidos(), source.getRegimenImpuesto(),
                    source.getPorcentajeImpuesto(), source.getBase(),
                    source.getImpuesto(), source.getTotal(), source.getSerialNumbers(),
                    current.product().getProductType(), false, false, false);
        }
        var price = currentPriceDecision(source, current);
        var amounts = previewAmounts(
                source.getCantidad(), price.unitPrice(),
                current.product().isTaxesIncluded(), current.tax().getPercentage());
        return new PreviousTicketImportView.LineView(
                source.getId(), source.getProductoId(), current.product().getCode(),
                current.product().getName(), source.getCantidad(),
                price.tariff(), price.unitPrice(), BigDecimal.ZERO,
                current.product().isTaxesIncluded(), current.taxRegime(),
                current.tax().getPercentage(), amounts.base(), amounts.tax(),
                amounts.total(), List.of(), current.product().getProductType(),
                price.manualPricePreserved(), price.temporaryAuthorizationRequired(),
                !source.getSerialNumbers().isEmpty());
    }

    private List<PreviousTicketImportView.AdjustmentView> previewAdjustments(
            CommercialDocument ticket,
            PreviousTicketImportPricingMode pricingMode) {
        if (pricingMode == PreviousTicketImportPricingMode.FROZEN_EXACT) {
            return ticket.getLineas().stream()
                    .filter(line -> line.getLineType() != DocumentLineType.PRODUCT)
                    .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                    .map(PreviousTicketImportService::adjustmentView)
                    .toList();
        }
        var adjustments = new ArrayList<PreviousTicketImportView.AdjustmentView>();
        ticket.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .filter(line -> line.getTotal().signum() < 0)
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .map(PreviousTicketImportService::adjustmentView)
                .forEach(adjustments::add);
        globalDiscountAdjustments(ticket).stream()
                .map(command -> new PreviousTicketImportView.AdjustmentView(
                        DocumentLineType.MANUAL_DISCOUNT, command.nombre(),
                        command.frozenBase(), command.frozenTax(), command.frozenTotal()))
                .forEach(adjustments::add);
        return List.copyOf(adjustments);
    }

    private static PreviousTicketImportView.AdjustmentView adjustmentView(
            DocumentLine line) {
        return new PreviousTicketImportView.AdjustmentView(
                line.getLineType(), line.getNombre(), line.getBase(),
                line.getImpuesto(), line.getTotal());
    }

    private static PreviewAmounts previewAmounts(
            BigDecimal quantity,
            BigDecimal unitPrice,
            boolean taxesIncluded,
            BigDecimal taxPercent) {
        var gross = Money.euros(unitPrice.multiply(quantity));
        if (!taxesIncluded) {
            var tax = Money.euros(gross.multiply(taxPercent.movePointLeft(2)));
            return new PreviewAmounts(gross, tax, Money.euros(gross.add(tax)));
        }
        var divisor = BigDecimal.ONE.add(taxPercent.movePointLeft(2));
        var base = Money.euros(gross.divide(divisor, Money.SCALE + 4, Money.ROUNDING));
        return new PreviewAmounts(base, Money.euros(gross.subtract(base)), gross);
    }

    private DocumentLineCommand currentCommand(
            DocumentLine source,
            CurrentProductSnapshot current,
            List<String> serialNumbers) {
        var price = currentPriceDecision(source, current);
        return new DocumentLineCommand(
                current.product().getId(), source.getCantidad(),
                current.product().getCode(), current.product().getName(),
                price.commandTariff(), price.unitPrice(), BigDecimal.ZERO,
                current.product().isTaxesIncluded(), current.taxRegime(),
                current.tax().getPercentage(), DocumentLineType.PRODUCT,
                null, null, null, serialNumbers, false,
                price.temporaryAuthorizationRequired());
    }

    private CurrentPriceDecision currentPriceDecision(
            DocumentLine source,
            CurrentProductSnapshot current) {
        var sourcePrice = Money.euros(source.getPrecioUnitario());
        var currentSalePrice = Money.euros(current.product().getSalePrice());
        if (isTemporaryPrice(source)) {
            return new CurrentPriceDecision(
                    sourcePrice, "TEMPORAL", true, true, false);
        }
        if (isHistoricalOpenPrice(source)) {
            return new CurrentPriceDecision(
                    sourcePrice, "VENTA", true, false, true);
        }
        if (!isSaleTariff(source)) {
            if (currentSalePrice.signum() == 0) {
                throw new IllegalStateException(
                        "message.document.previous_ticket_open_price_requires_new_entry");
            }
            return CurrentPriceDecision.current(currentSalePrice);
        }

        var evidence = current.historicalSalePrice();
        if (evidence != null) {
            if (evidence.amount().signum() == 0) {
                return new CurrentPriceDecision(
                        sourcePrice, "VENTA", true, false, true);
            }
            if (sourcePrice.compareTo(evidence.amount()) != 0) {
                throw ambiguousPriceOrigin();
            }
            if (currentSalePrice.signum() == 0) {
                throw new IllegalStateException(
                        "message.document.previous_ticket_open_price_requires_new_entry");
            }
            return CurrentPriceDecision.current(currentSalePrice);
        }

        if (currentSalePrice.signum() > 0
                && sourcePrice.compareTo(currentSalePrice) == 0) {
            return CurrentPriceDecision.current(currentSalePrice);
        }
        throw ambiguousPriceOrigin();
    }

    private static boolean isSaleTariff(DocumentLine line) {
        return line.getTarifa() != null
                && "VENTA".equalsIgnoreCase(line.getTarifa().trim());
    }

    private static boolean isHistoricalOpenPrice(DocumentLine line) {
        return DocumentLineCommand.isHistoricalOpenPriceRate(line.getTarifa());
    }

    private static IllegalStateException ambiguousPriceOrigin() {
        return new IllegalStateException(
                "message.document.previous_ticket_price_origin_ambiguous");
    }

    private static boolean isTemporaryPrice(DocumentLine line) {
        return line.getTarifa() != null
                && "TEMPORAL".equalsIgnoreCase(line.getTarifa().trim());
    }

    private BigDecimal preservedManualDiscountAmount(CommercialDocument ticket) {
        var lineDiscounts = ticket.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .map(DocumentLine::getTotal)
                .filter(total -> total.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var globalDiscount = globalDiscountAdjustments(ticket).stream()
                .map(DocumentLineCommand::frozenTotal)
                .filter(Objects::nonNull)
                .filter(total -> total.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Money.euros(lineDiscounts.add(globalDiscount));
    }

    private List<HistoricalTicketReplayMetadata.HistoricalLoyaltyLine>
            historicalLoyaltyLines(CommercialDocument ticket) {
        var storedSnapshots = loyaltyLines.findByDocumentId(ticket.getId());
        var orderedProductLines = ticket.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion)
                        .thenComparing(DocumentLine::getId))
                .toList();
        if (storedSnapshots.isEmpty()) {
            if (loyaltySettlements.findById(ticket.getId()).isPresent()) {
                throw invalidLoyaltySnapshot();
            }
            return orderedProductLines.stream()
                    .map(line -> new HistoricalTicketReplayMetadata.HistoricalLoyaltyLine(
                            line.getPosicion(), false, BigDecimal.ZERO))
                    .toList();
        }

        var snapshots = new LinkedHashMap<UUID, MemberDocumentLoyaltyLine>();
        for (var snapshot : storedSnapshots) {
            if (snapshots.put(snapshot.getDocumentLineId(), snapshot) != null) {
                throw invalidLoyaltySnapshot();
            }
        }
        var productLineIds = orderedProductLines.stream()
                .map(DocumentLine::getId)
                .collect(Collectors.toSet());
        if (!snapshots.keySet().equals(productLineIds)) {
            throw invalidLoyaltySnapshot();
        }
        var settlement = loyaltySettlements.findById(ticket.getId())
                .orElseThrow(PreviousTicketImportService::invalidLoyaltySnapshot);
        var targetEligibleTotal = Money.euros(settlement.getEligibleDocumentAmount());
        if (targetEligibleTotal.compareTo(Money.euros(ticket.getTotal())) > 0) {
            throw invalidLoyaltySnapshot();
        }
        var storedEligibleTotal = Money.euros(storedSnapshots.stream()
                .map(MemberDocumentLoyaltyLine::getEligibleAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (storedEligibleTotal.compareTo(targetEligibleTotal) < 0) {
            throw invalidLoyaltySnapshot();
        }

        var allocatedByLine = new LinkedHashMap<UUID, BigDecimal>();
        if (storedEligibleTotal.compareTo(targetEligibleTotal) > 0) {
            var eligibleSnapshots = orderedProductLines.stream()
                    .map(line -> snapshots.get(line.getId()))
                    .filter(MemberDocumentLoyaltyLine::isEligible)
                    .toList();
            var allocations = Money.allocateByLargestRemainder(
                    targetEligibleTotal,
                    eligibleSnapshots.stream()
                            .map(MemberDocumentLoyaltyLine::getEligibleAmount)
                            .toList());
            for (int index = 0; index < eligibleSnapshots.size(); index++) {
                allocatedByLine.put(
                        eligibleSnapshots.get(index).getDocumentLineId(),
                        allocations.get(index));
            }
        } else {
            storedSnapshots.forEach(snapshot -> allocatedByLine.put(
                    snapshot.getDocumentLineId(), snapshot.getEligibleAmount()));
        }

        var result = new ArrayList<HistoricalTicketReplayMetadata.HistoricalLoyaltyLine>();
        for (var line : orderedProductLines) {
            var snapshot = snapshots.get(line.getId());
            var amount = snapshot.isEligible()
                    ? allocatedByLine.getOrDefault(line.getId(), BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            result.add(new HistoricalTicketReplayMetadata.HistoricalLoyaltyLine(
                    line.getPosicion(), snapshot.isEligible(), amount));
        }
        return List.copyOf(result);
    }

    private static IllegalStateException invalidLoyaltySnapshot() {
        return new IllegalStateException(
                "message.document.previous_ticket_loyalty_snapshot_invalid");
    }

    private void validateSerialNumbers(
            CommercialDocument ticket,
            Map<UUID, List<String>> requestedSerials) {
        if (ticket.getEstado() == DocumentStatus.ANULADO) {
            if (!requestedSerials.isEmpty()) {
                throw new IllegalArgumentException(
                        "message.document.previous_ticket_cancelled_serial_override");
            }
            var originals = ticket.getLineas().stream()
                    .flatMap(line -> line.getSerialNumbers().stream())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toSet());
            if (!originals.isEmpty()
                    && !documents.usedSerialNumbers(
                            ticket.getTiendaId(), originals).isEmpty()) {
                throw new IllegalArgumentException(
                        "message.document.previous_ticket_new_serials_required");
            }
            return;
        }
        var originalSerialsInTicket = ticket.getLineas().stream()
                .flatMap(line -> line.getSerialNumbers().stream())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        var replaySerials = new java.util.HashSet<String>();
        for (var line : ticket.getLineas()) {
            if (line.getLineType() != DocumentLineType.PRODUCT) {
                continue;
            }
            var originalSerials = line.getSerialNumbers();
            var replacements = requestedSerials.getOrDefault(line.getId(), List.of());
            if (originalSerials.isEmpty()) {
                if (!replacements.isEmpty()) {
                    throw new IllegalArgumentException(
                            "message.document.previous_ticket_serial_line_invalid");
                }
                continue;
            }
            var normalized = replacements.stream()
                    .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                    .toList();
            if (normalized.size() != originalSerials.size()
                    || normalized.stream().anyMatch(String::isBlank)
                    || normalized.stream().distinct().count() != normalized.size()
                    || normalized.stream().anyMatch(originalSerialsInTicket::contains)
                    || normalized.stream().anyMatch(value -> !replaySerials.add(value))) {
                throw new IllegalArgumentException(
                        "message.document.previous_ticket_new_serials_required");
            }
        }
        if (!replaySerials.isEmpty()
                && !documents.usedSerialNumbers(
                        ticket.getTiendaId(), replaySerials).isEmpty()) {
            throw new IllegalArgumentException(
                    "message.document.previous_ticket_new_serials_required");
        }
    }

    private CommercialDocument latestImportableTicket(Authentication authentication) {
        var storeId = organization.currentStore().getId();
        var terminalId = currentTerminal.terminalId(authentication);
        var ticketId = documents.findLatestPositiveConfirmedTicketIds(
                        storeId, terminalId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "message.document.previous_ticket_not_found"));
        var ticket = documents.findByIdAndTiendaId(ticketId, storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "message.document.previous_ticket_not_found"));
        validateImportable(ticket);
        validateCustomer(ticket);
        return ticket;
    }

    private void validateCustomer(CommercialDocument ticket) {
        if (ticket.getClienteId() == null) {
            return;
        }
        customers.findByIdAndCompanyId(
                        ticket.getClienteId(), organization.currentCompany().getId())
                .filter(value -> value.isActive())
                .orElseThrow(() -> new IllegalStateException(
                        "message.document.previous_ticket_customer_inactive"));
    }

    private void validateImportable(CommercialDocument ticket) {
        if (ticket.getTipo() != CommercialDocumentType.TICKET
                || (ticket.getEstado() != DocumentStatus.CONFIRMADO
                        && ticket.getEstado() != DocumentStatus.ANULADO)
                || ticket.getTotal().signum() <= 0
                || ticket.getLineas().stream().noneMatch(line ->
                        line.getLineType() == DocumentLineType.PRODUCT)
                || ticket.getLineas().stream().anyMatch(line ->
                        line.getLineType() == DocumentLineType.RETURN_ADJUSTMENT
                                || line.getOriginalDocumentLineId() != null
                                || (line.getLineType() == DocumentLineType.PRODUCT
                                        && line.getCantidad().signum() <= 0))
                || documents.isExchangeSale(ticket.getId())) {
            throw new IllegalStateException(
                    "message.document.previous_ticket_not_importable");
        }
    }

    private Map<UUID, CurrentProductSnapshot> validateCurrentCatalog(
            CommercialDocument ticket,
            PreviousTicketImportPricingMode pricingMode) {
        var productIds = ticket.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(DocumentLine::getProductoId)
                .distinct()
                .toList();
        var historicalSalePrices = pricingMode
                == PreviousTicketImportPricingMode.CURRENT_REPRICING
                        ? historicalSalePrices(ticket, productIds)
                        : Map.<UUID, HistoricalSalePriceEvidence>of();
        var currentProducts = products.findAllByStoreIdAndIdIn(
                        ticket.getTiendaId(), productIds)
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        var taxIds = currentProducts.values().stream()
                .map(Product::getTaxId)
                .distinct()
                .toList();
        var currentTaxes = taxes.findAllById(taxIds).stream()
                .collect(Collectors.toMap(
                        StoreTax::getId, Function.identity()));
        var currentRegime = currentTaxRegime();
        var snapshots = new LinkedHashMap<UUID, CurrentProductSnapshot>();
        for (var line : ticket.getLineas()) {
            if (line.getLineType() != DocumentLineType.PRODUCT) {
                continue;
            }
            var product = currentProducts.get(line.getProductoId());
            if (product == null || !product.isActive()) {
                throw new IllegalStateException(
                        "message.document.previous_ticket_product_changed");
            }
            try {
                ProductQuantityPolicy.requireValid(
                        product.getProductType(), line.getCantidad());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "message.document.previous_ticket_product_changed", exception);
            }
            var tax = currentTaxes.get(product.getTaxId());
            if (tax == null || !tax.getStoreId().equals(ticket.getTiendaId())
                    || !tax.isActive()) {
                throw new IllegalStateException(
                        "message.document.previous_ticket_tax_changed");
            }
            if (pricingMode == PreviousTicketImportPricingMode.FROZEN_EXACT
                    && (line.isImpuestosIncluidos() != product.isTaxesIncluded()
                    || !currentRegime.equals(line.getRegimenImpuesto())
                    || line.getPorcentajeImpuesto().compareTo(tax.getPercentage()) != 0)) {
                throw new IllegalStateException(
                        "message.document.previous_ticket_tax_changed");
            }
            if (pricingMode == PreviousTicketImportPricingMode.CURRENT_REPRICING
                    && line.getDescuento().signum() > 0) {
                throw new IllegalStateException(
                        "message.document.previous_ticket_discount_origin_ambiguous");
            }
            snapshots.put(product.getId(),
                    new CurrentProductSnapshot(
                            product, tax, currentRegime,
                            historicalSalePrices.get(product.getId())));
        }
        if (pricingMode == PreviousTicketImportPricingMode.CURRENT_REPRICING) {
            for (var line : ticket.getLineas()) {
                if (line.getLineType() == DocumentLineType.PRODUCT) {
                    currentPriceDecision(line, snapshots.get(line.getProductoId()));
                }
            }
        }
        return Map.copyOf(snapshots);
    }

    private Map<UUID, HistoricalSalePriceEvidence> historicalSalePrices(
            CommercialDocument ticket,
            List<UUID> productIds) {
        var confirmedAt = ticket.getConfirmadoEn();
        if (confirmedAt == null) {
            throw ambiguousPriceOrigin();
        }
        var rows = priceHistory.findPriceEvidenceAtOrBefore(
                productIds, ProductPriceHistoryType.VENTA, confirmedAt);
        var byProduct = rows.stream().collect(Collectors.groupingBy(
                ProductPriceHistory::getProductId,
                LinkedHashMap::new,
                Collectors.toList()));
        var result = new LinkedHashMap<UUID, HistoricalSalePriceEvidence>();
        for (var productId : productIds) {
            var productRows = byProduct.get(productId);
            if (productRows == null || productRows.isEmpty()) {
                continue;
            }
            var latestAt = productRows.stream()
                    .map(ProductPriceHistory::getUpdatedAt)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            var latestRows = productRows.stream()
                    .filter(row -> latestAt.equals(row.getUpdatedAt()))
                    .sorted(Comparator.comparing(ProductPriceHistory::getId))
                    .toList();
            var amounts = latestRows.stream()
                    .map(ProductPriceHistory::getAmount)
                    .map(amount -> amount == null ? null : Money.euros(amount))
                    .distinct()
                    .toList();
            if (amounts.size() != 1 || amounts.getFirst() == null) {
                throw ambiguousPriceOrigin();
            }
            result.put(productId, new HistoricalSalePriceEvidence(
                    amounts.getFirst(), latestAt,
                    latestRows.stream().map(ProductPriceHistory::getId).toList()));
        }
        return Map.copyOf(result);
    }

    String currentTaxRegime() {
        var storeId = organization.currentStore().getId();
        var license = licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId).stream()
                .filter(License::isActiva)
                .findFirst()
                .orElse(null);
        if (license == null) {
            if (installationStatus.status().mode() == OperationalMode.DEVELOPMENT) {
                return TaxRegime.IVA.name();
            }
            throw new IllegalStateException("No hay licencia activa para la tienda");
        }
        if (!storeId.equals(license.getTiendaId())) {
            throw new IllegalArgumentException(
                    "La licencia no pertenece a la tienda actual");
        }
        return license.getRegimenImpuesto().name();
    }

    private DocumentLineCommand historicalCommand(
            CommercialDocument ticket,
            DocumentLine line,
            Map<UUID, List<String>> requestedSerials) {
        if (line.getLineType() == DocumentLineType.PRODUCT) {
            var serials = ticket.getEstado() == DocumentStatus.ANULADO
                    ? line.getSerialNumbers()
                    : requestedSerials.getOrDefault(line.getId(), List.of());
            return new DocumentLineCommand(
                    line.getProductoId(), line.getCantidad(), line.getCodigo(),
                    line.getNombre(), line.getTarifa(), line.getPrecioUnitario(),
                    line.getDescuento(), line.isImpuestosIncluidos(),
                    line.getRegimenImpuesto(), line.getPorcentajeImpuesto(),
                    DocumentLineType.PRODUCT, null, null, null, serials,
                    false, false, null, null, null, null, null,
                    line.getBase(), line.getImpuesto(), line.getTotal(),
                    line.getCodigoBarras());
        }
        return new DocumentLineCommand(
                null, BigDecimal.ONE, line.getCodigo(), line.getNombre(), null,
                line.getPrecioUnitario(), BigDecimal.ZERO,
                line.isImpuestosIncluidos(), line.getRegimenImpuesto(),
                line.getPorcentajeImpuesto(), line.getLineType(),
                line.getPromotionId(), line.getPromotionVersionId(),
                line.getPromotionalCouponId(),
                List.of(), false, false, null, null, null, null, null,
                line.getBase(), line.getImpuesto(), line.getTotal());
    }

    private List<DocumentLineCommand> globalDiscountAdjustments(
            CommercialDocument ticket) {
        if (ticket.getDescuentoGlobal().signum() == 0) {
            return List.of();
        }
        var groups = new LinkedHashMap<TaxKey, MutableTotals>();
        ticket.getLineas().stream()
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(line -> groups.computeIfAbsent(
                                new TaxKey(line.isImpuestosIncluidos(),
                                        line.getRegimenImpuesto(),
                                        line.getPorcentajeImpuesto()),
                                ignored -> new MutableTotals())
                        .add(line.getBase(), line.getImpuesto(), line.getTotal()));
        var factor = BigDecimal.ONE.subtract(ticket.getDescuentoGlobal().movePointLeft(2));
        var result = new ArrayList<DocumentLineCommand>();
        var remainingBase = ticket.getBaseTotal();
        var remainingTax = ticket.getImpuestoTotal();
        var remainingTotal = ticket.getTotal();
        var entries = List.copyOf(groups.entrySet());
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            var source = entry.getValue();
            var last = index == entries.size() - 1;
            var targetBase = last ? remainingBase : Money.euros(source.base.multiply(factor));
            var targetTax = last ? remainingTax : Money.euros(source.tax.multiply(factor));
            var targetTotal = last ? remainingTotal : Money.euros(source.total.multiply(factor));
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
            result.add(new DocumentLineCommand(
                    null, BigDecimal.ONE, "DESCUENTO_GLOBAL_HISTORICO",
                    "Descuento global historico", null, adjustmentTotal,
                    BigDecimal.ZERO, key.taxesIncluded, key.regime, key.percentage,
                    DocumentLineType.MANUAL_DISCOUNT, null, null, null,
                    List.of(), false, false, null, null, null, null, null,
                    adjustmentBase, adjustmentTax, adjustmentTotal));
        }
        return List.copyOf(result);
    }

    private String fingerprint(
            CommercialDocument ticket,
            PreviousTicketImportPricingMode pricingMode,
            Map<UUID, CurrentProductSnapshot> currentCatalog) {
        var canonical = new StringBuilder("previous-ticket-import-v3|")
                .append(ticket.getId()).append('|')
                .append(fingerprintText(ticket.getNumero())).append('|')
                .append(ticket.getFecha()).append('|')
                .append(ticket.getEstado()).append('|')
                .append(ticket.getTerminalOrigenId()).append('|')
                .append(ticket.getClienteId()).append('|')
                .append(ticket.getDescuentoGlobal()).append('|')
                .append(ticket.getBaseTotal()).append('|')
                .append(ticket.getImpuestoTotal()).append('|')
                .append(ticket.getTotal()).append('|')
                .append(fingerprintText(ticket.getMoneda()));
        ticket.getLineas().stream()
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(line -> canonical.append('|')
                        .append(line.getId()).append(':')
                        .append(line.getPosicion()).append(':')
                        .append(line.getProductoId()).append(':')
                        .append(line.getLineType()).append(':')
                        .append(line.getCantidad()).append(':')
                        .append(fingerprintText(line.getCodigo())).append(':')
                        .append(fingerprintText(line.getNombre())).append(':')
                        .append(fingerprintText(line.getTarifa())).append(':')
                        .append(line.getPrecioUnitario()).append(':')
                        .append(line.getDescuento()).append(':')
                        .append(line.isImpuestosIncluidos()).append(':')
                        .append(fingerprintText(line.getRegimenImpuesto())).append(':')
                        .append(line.getPorcentajeImpuesto()).append(':')
                        .append(line.getBase()).append(':')
                        .append(line.getImpuesto()).append(':')
                        .append(line.getTotal()).append(':')
                        .append(line.getPromotionId()).append(':')
                        .append(line.getPromotionVersionId()).append(':')
                        .append(line.getPromotionalCouponId()).append(':')
                        .append(PosCashService.canonicalSerialNumbers(
                                line.getSerialNumbers())));
        if (pricingMode == PreviousTicketImportPricingMode.CURRENT_REPRICING) {
            ticket.getLineas().stream()
                    .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                    .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                    .forEach(line -> {
                        var current = currentCatalog.get(line.getProductoId());
                        var evidence = current.historicalSalePrice();
                        canonical.append("|current-price:")
                                .append(line.getId()).append(':')
                                .append(Money.euros(current.product().getSalePrice()))
                                .append(':');
                        if (evidence == null) {
                            canonical.append('~');
                        } else {
                            canonical.append(evidence.amount()).append(':')
                                    .append(evidence.updatedAt()).append(':')
                                    .append(evidence.historyIds());
                        }
                    });
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String fingerprintText(String value) {
        if (value == null) {
            return "~";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    public record ResolvedImport(
            UUID ticketId,
            String ticketNumber,
            DocumentStatus status,
            PreviousTicketImportPricingMode pricingMode,
            UUID customerId,
            String fingerprint,
            BigDecimal baseTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            List<DocumentLineCommand> commands,
            int productLineCount,
            BigDecimal preservedManualDiscountAmount,
            List<HistoricalTicketReplayMetadata.HistoricalLoyaltyLine>
                    historicalLoyaltyLines) {

        public ResolvedImport {
            commands = List.copyOf(commands);
            preservedManualDiscountAmount = Money.euros(
                    preservedManualDiscountAmount == null
                            ? BigDecimal.ZERO : preservedManualDiscountAmount);
            historicalLoyaltyLines = List.copyOf(historicalLoyaltyLines);
        }

        boolean frozenExact() {
            return pricingMode == PreviousTicketImportPricingMode.FROZEN_EXACT;
        }

        boolean currentRepricing() {
            return pricingMode == PreviousTicketImportPricingMode.CURRENT_REPRICING;
        }

        boolean hasTemporaryPriceOverride() {
            return currentRepricing() && commands.stream()
                    .anyMatch(DocumentLineCommand::temporaryPriceOverride);
        }

        HistoricalTicketReplayMetadata metadata(
                BigDecimal currentPendingBeforeCoupon,
                List<HistoricalTicketReplayMetadata.ManualLineDiscount>
                        currentManualLineDiscounts,
                List<HistoricalTicketReplayMetadata.GeneratedCoupon>
                        currentGeneratedCoupons) {
            return new HistoricalTicketReplayMetadata(
                    ticketId, ticketNumber, fingerprint,
                    frozenExact() ? commands.size() : 0,
                    frozenExact() ? total : Money.euros(BigDecimal.ZERO),
                    currentPendingBeforeCoupon, currentManualLineDiscounts,
                    currentGeneratedCoupons,
                    frozenExact() ? historicalLoyaltyLines : List.of());
        }
    }

    private record TaxKey(
            boolean taxesIncluded,
            String regime,
            BigDecimal percentage) {
    }

    private record CurrentProductSnapshot(
            Product product,
            StoreTax tax,
            String taxRegime,
            HistoricalSalePriceEvidence historicalSalePrice) {
    }

    private record HistoricalSalePriceEvidence(
            BigDecimal amount,
            Instant updatedAt,
            List<UUID> historyIds) {

        private HistoricalSalePriceEvidence {
            amount = Money.euros(amount);
            historyIds = List.copyOf(historyIds);
        }
    }

    private record CurrentPriceDecision(
            BigDecimal unitPrice,
            String tariff,
            boolean manualPricePreserved,
            boolean temporaryAuthorizationRequired,
            boolean historicalOpenPrice) {

        private CurrentPriceDecision {
            unitPrice = Money.euros(unitPrice);
        }

        private static CurrentPriceDecision current(BigDecimal unitPrice) {
            return new CurrentPriceDecision(
                    unitPrice, "VENTA", false, false, false);
        }

        private String commandTariff() {
            return historicalOpenPrice
                    ? DocumentLineCommand.historicalOpenPriceRate()
                    : tariff;
        }
    }

    private record PreviewAmounts(
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total) {
    }

    private static final class MutableTotals {
        private BigDecimal base = Money.euros(BigDecimal.ZERO);
        private BigDecimal tax = Money.euros(BigDecimal.ZERO);
        private BigDecimal total = Money.euros(BigDecimal.ZERO);

        private void add(BigDecimal lineBase, BigDecimal lineTax, BigDecimal lineTotal) {
            base = Money.euros(base.add(lineBase));
            tax = Money.euros(tax.add(lineTax));
            total = Money.euros(total.add(lineTotal));
        }
    }
}
