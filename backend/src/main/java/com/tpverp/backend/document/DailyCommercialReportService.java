package com.tpverp.backend.document;

import com.tpverp.backend.cash.CashMovement;
import com.tpverp.backend.cash.CashMovementRepository;
import com.tpverp.backend.cash.CashMovementType;
import com.tpverp.backend.cash.CashPeriodPositionQueryRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyCommercialReportService {

    private final CommercialDocumentRepository documents;
    private final DocumentPaymentRepository payments;
    private final RefundTenderRepository refunds;
    private final DocumentRelationRepository relations;
    private final CashMovementRepository cashMovements;
    private final CashPeriodPositionQueryRepository cashPositions;
    private final CurrentOrganization organization;

    public DailyCommercialReportService(
            CommercialDocumentRepository documents,
            DocumentPaymentRepository payments,
            RefundTenderRepository refunds,
            DocumentRelationRepository relations,
            CashMovementRepository cashMovements,
            CashPeriodPositionQueryRepository cashPositions,
            CurrentOrganization organization) {
        this.documents = documents;
        this.payments = payments;
        this.refunds = refunds;
        this.relations = relations;
        this.cashMovements = cashMovements;
        this.cashPositions = cashPositions;
        this.organization = organization;
    }

    // Calculates commercial activity by issue date and real payment date.
    @Transactional(readOnly = true)
    public DailyCommercialReportView report(LocalDate date) {
        return report(date, (UUID) null);
    }

    @Transactional(readOnly = true)
    public DailyCommercialReportView report(LocalDate dateFrom, LocalDate dateTo) {
        return report(dateFrom, dateTo, true);
    }

    @Transactional(readOnly = true)
    public DailyCommercialReportView report(
            LocalDate dateFrom,
            LocalDate dateTo,
            boolean includeSensitiveCashTotals) {
        if (dateFrom == null) {
            throw new IllegalArgumentException("dateFrom es obligatorio");
        }
        if (dateTo == null) {
            dateTo = dateFrom;
        }
        if (dateTo.isBefore(dateFrom)) {
            throw new IllegalArgumentException("dateTo no puede ser anterior a dateFrom");
        }
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var invoiced = BigDecimal.ZERO;
        var ticketSales = BigDecimal.ZERO;
        var collectedCurrent = BigDecimal.ZERO;
        var newPending = BigDecimal.ZERO;
        var priorDebtCollected = BigDecimal.ZERO;
        var refunded = BigDecimal.ZERO;
        var cashInflow = BigDecimal.ZERO;
        long ticketCount = 0;
        long invoiceCount = 0;
        var salesTotal = BigDecimal.ZERO;
        var salesByMethod = DailyPaymentBreakdownView.zero();
        var pendingCollectionsByMethod = DailyPaymentBreakdownView.zero();
        var refundsByMethod = DailyPaymentBreakdownView.zero();
        var cashEntries = BigDecimal.ZERO;
        var cashWithdrawals = BigDecimal.ZERO;
        var days = new ArrayList<DailyCommercialReportDayView>();
        for (var date = dateFrom; !date.isAfter(dateTo); date = date.plusDays(1)) {
            var daily = reportDay(date, null, false);
            invoiced = invoiced.add(daily.invoiced());
            ticketSales = ticketSales.add(daily.ticketSales());
            collectedCurrent = collectedCurrent.add(daily.collectedCurrent());
            newPending = newPending.add(daily.newPending());
            priorDebtCollected = priorDebtCollected.add(daily.priorDebtCollected());
            refunded = refunded.add(daily.refunds());
            cashInflow = cashInflow.add(daily.cashInflow());
            ticketCount += daily.ticketCount();
            invoiceCount += daily.invoiceCount();
            salesTotal = salesTotal.add(daily.salesTotal());
            salesByMethod = salesByMethod.add(daily.salesByPaymentMethod());
            pendingCollectionsByMethod = pendingCollectionsByMethod.add(
                    daily.pendingCollectionsByPaymentMethod());
            refundsByMethod = refundsByMethod.add(daily.refundsByPaymentMethod());
            cashEntries = cashEntries.add(daily.cashEntries());
            cashWithdrawals = cashWithdrawals.add(daily.cashWithdrawals());
            days.add(new DailyCommercialReportDayView(
                    daily.date(), daily.invoiced(), daily.ticketSales(), daily.collectedCurrent(),
                    daily.newPending(), daily.priorDebtCollected(), daily.refunds(), daily.cashInflow(),
                    daily.ticketCount(), daily.invoiceCount(), daily.salesTotal()));
        }
        var from = dateFrom.atStartOfDay(zone).toInstant();
        var to = dateTo.plusDays(1).atStartOfDay(zone).toInstant();
        var expectedCash = includeSensitiveCashTotals
                ? cashPosition(store.getId(), to)
                : null;
        var periodCashMovements = cashMovements
                .findAllByTiendaIdAndCreadoEnBetweenOrderByCreadoEnAsc(store.getId(), from, to);
        var openingCashFund = includeSensitiveCashTotals
                ? Money.euros(expectedCash.subtract(cashMovementBalance(periodCashMovements)))
                : null;
        return new DailyCommercialReportView(
                store.getId(), dateFrom, Money.euros(invoiced), Money.euros(ticketSales),
                Money.euros(collectedCurrent), Money.euros(newPending),
                Money.euros(priorDebtCollected), Money.euros(refunded), Money.euros(cashInflow),
                ticketCount, invoiceCount, Money.euros(salesTotal), salesByMethod,
                pendingCollectionsByMethod, refundsByMethod,
                openingCashFund, Money.euros(cashEntries),
                Money.euros(cashWithdrawals), expectedCash, List.copyOf(days));
    }

    @Transactional(readOnly = true)
    public DailyCommercialReportView report(LocalDate date, UUID warehouseId) {
        if (date == null) {
            throw new IllegalArgumentException("date es obligatorio");
        }
        return reportDay(date, warehouseId, warehouseId == null);
    }

    private DailyCommercialReportView reportDay(
            LocalDate date,
            UUID warehouseId,
            boolean includeCashPosition) {
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var from = date.atStartOfDay(zone).toInstant();
        var to = date.plusDays(1).atStartOfDay(zone).toInstant();
        var issued = documents.findAllByTiendaIdAndFecha(store.getId(), date).stream()
                .filter(document -> warehouseId == null || warehouseId.equals(document.getAlmacenId()))
                .toList();
        var collected = payments.findAllByStoreAndCreatedBetween(store.getId(), from, to).stream()
                .filter(payment -> warehouseId == null
                        || warehouseId.equals(payment.getDocumento().getAlmacenId()))
                .toList();
        var refundTenders = refunds.findAllByStoreAndCreatedBetween(store.getId(), from, to).stream()
                .filter(tender -> warehouseId == null
                        || warehouseId.equals(tender.getRefundDocument().getAlmacenId()))
                .toList();

        // Keep the legacy fields stable for existing dashboard consumers.
        var invoicedOrigins = safeSet(relations.findInvoicedOriginIds(store.getId(), date));
        var invoiced = issued.stream()
                .filter(DailyCommercialReportService::isInvoicedActivity)
                .filter(document -> !invoicedOrigins.contains(document.getId()))
                .map(CommercialDocument::getTotal)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var ticketSales = issued.stream()
                .filter(DailyCommercialReportService::isTicketActivity)
                .filter(document -> !invoicedOrigins.contains(document.getId()))
                .map(CommercialDocument::getTotal)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var receivableCollectedCurrent = collected.stream()
                .filter(payment -> isLegacyCustomerReceivableSale(payment.getDocumento()))
                .filter(payment -> payment.getDocumento().getFecha().equals(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var ticketCollectedCurrent = collected.stream()
                .filter(payment -> isTicketActivity(payment.getDocumento()))
                .filter(payment -> payment.getDocumento().getFecha().equals(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var collectedCurrent = receivableCollectedCurrent.add(ticketCollectedCurrent);
        var priorDebtCollected = collected.stream()
                .filter(payment -> isReceivableSale(payment.getDocumento()))
                .filter(payment -> payment.getDocumento().getFecha().isBefore(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var legacyNewPending = invoiced.subtract(receivableCollectedCurrent).max(BigDecimal.ZERO);
        var monetaryRefunded = refundTenders.stream()
                .filter(DailyCommercialReportService::isMonetaryRefund)
                .map(RefundTender::getAmount)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var cashInflow = collectedCurrent.add(priorDebtCollected).subtract(monetaryRefunded);

        // New authoritative daily sales: positive tickets plus direct invoices only.
        var derivedInvoiceIds = safeSet(relations.findDerivedSalesInvoiceIds(store.getId(), date));
        var saleDocuments = issued.stream()
                .filter(DailyCommercialReportService::isValidDocument)
                .filter(document -> document.getTotal().signum() > 0)
                .filter(document -> document.getTipo() == CommercialDocumentType.TICKET
                        || (document.getTipo() == CommercialDocumentType.FACTURA_VENTA
                        && !derivedInvoiceIds.contains(document.getId())))
                .toList();
        var saleDocumentIds = saleDocuments.stream()
                .map(CommercialDocument::getId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        var grossSales = saleDocuments.stream()
                .map(CommercialDocument::getTotal)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var currentSalePayments = collected.stream()
                .filter(payment -> saleDocumentIds.contains(payment.getDocumento().getId()))
                .filter(payment -> payment.getDocumento().getFecha().equals(date))
                .toList();
        var currentSalePaid = currentSalePayments.stream()
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var pending = Money.euros(grossSales.subtract(currentSalePaid).max(BigDecimal.ZERO));

        var originalPaymentMethods = originalPaymentMethods(refundTenders);
        var salesBuckets = new PaymentBreakdownAccumulator();
        currentSalePayments.forEach(salesBuckets::addIncomingPayment);
        salesBuckets.pending = pending;
        var refundBuckets = new PaymentBreakdownAccumulator();
        for (var tender : refundTenders) {
            var bucket = refundBucket(tender, originalPaymentMethods);
            refundBuckets.add(bucket, tender.getAmount());
            salesBuckets.subtract(bucket, tender.getAmount());
        }
        var totalReturnValue = refundTenders.stream()
                .map(RefundTender::getAmount)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var salesTotal = Money.euros(grossSales.subtract(totalReturnValue));
        // Exchange compensation and its return normally cancel. Any durable
        // historical anomaly is surfaced as Other instead of breaking the total.
        salesBuckets.other = salesBuckets.other.add(
                salesTotal.subtract(salesBuckets.toView().total()));

        var pendingCollectionBuckets = new PaymentBreakdownAccumulator();
        collected.stream()
                .filter(payment -> isReceivableSale(payment.getDocumento()))
                .filter(payment -> payment.getDocumento().getFecha().isBefore(date))
                .forEach(pendingCollectionBuckets::addIncomingPayment);

        var periodCashMovements = warehouseId == null
                ? cashMovements.findAllByTiendaIdAndCreadoEnBetweenOrderByCreadoEnAsc(
                        store.getId(), from, to)
                : List.<CashMovement>of();
        var cashEntries = sumCashMovements(periodCashMovements,
                Set.of(CashMovementType.ENTRADA, CashMovementType.ENTRADA_ENTRE_SESIONES));
        var cashWithdrawals = sumCashMovements(periodCashMovements,
                Set.of(CashMovementType.RETIRADA, CashMovementType.RETIRADA_CIERRE,
                        CashMovementType.RETIRADA_ENTRE_SESIONES));
        var expectedCash = includeCashPosition ? cashPosition(store.getId(), to) : Money.euros("0");
        var openingCash = includeCashPosition
                ? Money.euros(expectedCash.subtract(cashMovementBalance(periodCashMovements)))
                : Money.euros("0");

        var ticketCount = issued.stream()
                .filter(DailyCommercialReportService::isValidDocument)
                .filter(document -> document.getTipo() == CommercialDocumentType.TICKET)
                .filter(document -> document.getTotal().signum() >= 0)
                .count();
        var invoiceCount = issued.stream()
                .filter(DailyCommercialReportService::isValidDocument)
                .filter(document -> document.getTipo() == CommercialDocumentType.FACTURA_VENTA)
                .filter(document -> document.getTotal().signum() >= 0)
                .count();
        return new DailyCommercialReportView(
                store.getId(), date, Money.euros(invoiced), Money.euros(ticketSales),
                Money.euros(collectedCurrent), Money.euros(legacyNewPending),
                Money.euros(priorDebtCollected), Money.euros(monetaryRefunded),
                Money.euros(cashInflow), ticketCount, invoiceCount, salesTotal,
                salesBuckets.toView(), pendingCollectionBuckets.toView(), refundBuckets.toView(),
                openingCash, cashEntries, cashWithdrawals, expectedCash, List.of());
    }

    private Map<UUID, String> originalPaymentMethods(List<RefundTender> tenders) {
        var ids = tenders.stream()
                .map(RefundTender::getOriginalPaymentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<UUID, String>();
        for (var payment : payments.findAllById(ids)) {
            result.put(payment.getId(), payment.getMetodoPago().getNombre());
        }
        return Map.copyOf(result);
    }

    private static PaymentBucket refundBucket(
            RefundTender tender,
            Map<UUID, String> originalPaymentMethods) {
        return switch (tender.getType()) {
            case CASH -> PaymentBucket.CASH;
            case VOUCHER -> PaymentBucket.VOUCHER;
            case TRANSFER -> PaymentBucket.TRANSFER;
            case EXCHANGE -> PaymentBucket.COMPENSATION;
            case CARD -> tender.getTerminalOperationId() == null
                    && "TRANSFERENCIA".equals(originalPaymentMethods.get(tender.getOriginalPaymentId()))
                    ? PaymentBucket.TRANSFER
                    : PaymentBucket.CARD;
        };
    }

    private BigDecimal cashPosition(UUID storeId, Instant boundary) {
        var amount = cashPositions.positionAt(storeId, boundary);
        return Money.euros(amount == null ? BigDecimal.ZERO : amount);
    }

    private static BigDecimal sumCashMovements(
            List<CashMovement> movements,
            Set<CashMovementType> types) {
        return movements.stream()
                .filter(movement -> types.contains(movement.getType()))
                .map(CashMovement::getAmount)
                .map(Money::euros)
                .reduce(Money.euros("0"), BigDecimal::add);
    }

    private static BigDecimal cashMovementBalance(List<CashMovement> movements) {
        var balance = Money.euros("0");
        for (var movement : movements) {
            balance = switch (movement.getType()) {
                case COBRO_EFECTIVO, ENTRADA, ENTRADA_ENTRE_SESIONES ->
                        balance.add(movement.getAmount());
                case DEVOLUCION_EFECTIVO, RETIRADA, RETIRADA_CIERRE,
                        RETIRADA_ENTRE_SESIONES -> balance.subtract(movement.getAmount());
            };
        }
        return Money.euros(balance);
    }

    private static Set<UUID> safeSet(Set<UUID> values) {
        return values == null ? Set.of() : values;
    }

    private static boolean isReceivableSale(CommercialDocument document) {
        return isValidDocument(document)
                && (document.getTipo() == CommercialDocumentType.ALBARAN_VENTA
                || document.getTipo() == CommercialDocumentType.FACTURA_VENTA
                || (document.getTipo() == CommercialDocumentType.TICKET
                && document.isCuentaCobrar()));
    }

    private static boolean isLegacyCustomerReceivableSale(CommercialDocument document) {
        return isValidDocument(document)
                && (document.getTipo() == CommercialDocumentType.ALBARAN_VENTA
                || document.getTipo() == CommercialDocumentType.FACTURA_VENTA);
    }

    private static boolean isInvoicedActivity(CommercialDocument document) {
        return isLegacyCustomerReceivableSale(document)
                || (isValidDocument(document)
                && document.getTipo() == CommercialDocumentType.RECTIFICATIVA_VENTA);
    }

    private static boolean isMonetaryRefund(RefundTender tender) {
        return tender.getType() == RefundTenderType.CASH
                || tender.getType() == RefundTenderType.CARD
                || tender.getType() == RefundTenderType.TRANSFER;
    }

    private static boolean isTicketActivity(CommercialDocument document) {
        return isValidDocument(document) && document.getTipo() == CommercialDocumentType.TICKET;
    }

    private static boolean isValidDocument(CommercialDocument document) {
        return document.getEstado() != DocumentStatus.BORRADOR
                && document.getEstado() != DocumentStatus.ANULADO;
    }

    private enum PaymentBucket {
        CASH,
        CARD,
        TRANSFER,
        VOUCHER,
        OTHER,
        COMPENSATION
    }

    private static PaymentBucket paymentBucket(DocumentPayment payment) {
        return switch (payment.getMetodoPago().getNombre()) {
            case "EFECTIVO" -> PaymentBucket.CASH;
            case "TARJETA" -> PaymentBucket.CARD;
            case "TRANSFERENCIA" -> PaymentBucket.TRANSFER;
            case "VALE" -> PaymentBucket.VOUCHER;
            case PaymentMethodService.EXCHANGE_COMPENSATION_METHOD -> PaymentBucket.COMPENSATION;
            default -> PaymentBucket.OTHER;
        };
    }

    private static final class PaymentBreakdownAccumulator {
        private BigDecimal cash = Money.euros("0");
        private BigDecimal card = Money.euros("0");
        private BigDecimal transfer = Money.euros("0");
        private BigDecimal voucher = Money.euros("0");
        private BigDecimal pending = Money.euros("0");
        private BigDecimal other = Money.euros("0");

        void addIncomingPayment(DocumentPayment payment) {
            add(paymentBucket(payment), payment.getImporte());
        }

        void add(PaymentBucket bucket, BigDecimal amount) {
            var value = Money.euros(amount);
            switch (bucket) {
                case CASH -> cash = cash.add(value);
                case CARD -> card = card.add(value);
                case TRANSFER -> transfer = transfer.add(value);
                case VOUCHER -> voucher = voucher.add(value);
                case OTHER -> other = other.add(value);
                case COMPENSATION -> {
                    // Internal exchange compensation is not an incoming method.
                }
            }
        }

        void subtract(PaymentBucket bucket, BigDecimal amount) {
            var value = Money.euros(amount);
            switch (bucket) {
                case CASH -> cash = cash.subtract(value);
                case CARD -> card = card.subtract(value);
                case TRANSFER -> transfer = transfer.subtract(value);
                case VOUCHER -> voucher = voucher.subtract(value);
                case OTHER -> other = other.subtract(value);
                case COMPENSATION -> {
                    // The matching internal payment was also excluded.
                }
            }
        }

        DailyPaymentBreakdownView toView() {
            return new DailyPaymentBreakdownView(
                    cash, card, transfer, voucher, pending, other);
        }
    }
}
