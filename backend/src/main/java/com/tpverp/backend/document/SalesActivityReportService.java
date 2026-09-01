package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesActivityReportService {

    static final int DEFAULT_LIMIT = 250;
    static final int MAX_LIMIT = 500;
    static final int MAX_EXPORT_ROWS = 50_000;

    private final CommercialDocumentRepository documents;
    private final DocumentRelationRepository relations;
    private final RefundTenderRepository refunds;
    private final DocumentPaymentRepository payments;
    private final DocumentAttributionResolver attributions;
    private final CurrentOrganization organization;
    private final DailyCommercialReportService dailyReports;

    public SalesActivityReportService(
            CommercialDocumentRepository documents,
            DocumentRelationRepository relations,
            RefundTenderRepository refunds,
            DocumentPaymentRepository payments,
            DocumentAttributionResolver attributions,
            CurrentOrganization organization) {
        this(documents, relations, refunds, payments, attributions, organization, null);
    }

    @Autowired
    public SalesActivityReportService(
            CommercialDocumentRepository documents,
            DocumentRelationRepository relations,
            RefundTenderRepository refunds,
            DocumentPaymentRepository payments,
            DocumentAttributionResolver attributions,
            CurrentOrganization organization,
            DailyCommercialReportService dailyReports) {
        this.documents = documents;
        this.relations = relations;
        this.refunds = refunds;
        this.payments = payments;
        this.attributions = attributions;
        this.organization = organization;
        this.dailyReports = dailyReports;
    }

    @Transactional(readOnly = true)
    public SalesDailySummaryView daily(LocalDate date) {
        return daily(date, false);
    }

    @Transactional(readOnly = true)
    public SalesDailySummaryView daily(LocalDate date, boolean includeSensitiveCashTotals) {
        if (dailyReports != null) {
            var authoritative = dailyReports.report(date, date, includeSensitiveCashTotals);
            var legacy = legacyDaily(date);
            return new SalesDailySummaryView(
                    legacy.storeId(), legacy.companyName(), legacy.storeCode(), legacy.date(),
                    legacy.netSalesTotal(), legacy.paymentMethods(),
                    legacy.counts(), legacy.users(),
                    DailyOperationsSupplement.from(authoritative),
                    LocalDate.now(ZoneId.of(organization.currentStore().getTimezone())));
        }
        return legacyDaily(date);
    }

    private SalesDailySummaryView legacyDaily(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date es obligatorio");
        }
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var zone = ZoneId.of(store.getTimezone());
        var dayStart = date.atStartOfDay(zone).toInstant();
        var dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        var issued = documents.findAllByTiendaIdAndFecha(store.getId(), date).stream()
                .filter(SalesActivityReportService::isSalesActivityDocument)
                .filter(document -> document.getEstado() != DocumentStatus.BORRADOR)
                .toList();
        var derivedInvoiceIds = relations.findDerivedSalesInvoiceIds(store.getId(), date);
        var attributionIndex = attributions.resolve(issued);
        var returnDocuments = issued.stream()
                .filter(SalesActivityReportService::isReturnDocument)
                .filter(document -> document.getEstado() != DocumentStatus.ANULADO)
                .toList();
        var refundIndex = refundIndex(
                store.getId(), returnDocuments.stream().map(CommercialDocument::getId).toList());
        var originalMethods = originalPaymentMethods(refundIndex.values().stream()
                .flatMap(Collection::stream).toList());

        var total = new SummaryAccumulator(null, "TOTAL");
        var byUser = new LinkedHashMap<UserKey, SummaryAccumulator>();
        for (var document : issued) {
            var attribution = attributionIndex.getOrDefault(
                    document.getId(), DocumentAttributionResolver.Attribution.empty(document));
            var key = new UserKey(attribution.userId(), normalizedUserName(attribution.userName()));
            var user = byUser.computeIfAbsent(key,
                    ignored -> new SummaryAccumulator(key.id(), key.name()));
            if (document.getEstado() == DocumentStatus.ANULADO) {
                total.markCancelled();
                user.markCancelled();
                continue;
            }
            if (document.getTipo() == CommercialDocumentType.FACTURA_VENTA
                    && derivedInvoiceIds.contains(document.getId())) {
                continue;
            }
            if (isReturnDocument(document)) {
                // Rectifications preserve their economic sign. A negative
                // ticket remains a return, while a positive rectification
                // increases the period total.
                var value = Money.euros(document.getTotal());
                total.netTotal = total.netTotal.add(value);
                user.netTotal = user.netTotal.add(value);
                total.markReturn();
                user.markReturn();
                applyReturn(total, refundIndex.getOrDefault(document.getId(), List.of()),
                        originalMethods, value, document.getId());
                applyReturn(user, refundIndex.getOrDefault(document.getId(), List.of()),
                        originalMethods, value, document.getId());
                continue;
            }
            if (document.getTotal().signum() <= 0) {
                continue;
            }
            var value = Money.euros(document.getTotal());
            total.netTotal = total.netTotal.add(value);
            user.netTotal = user.netTotal.add(value);
            var hasPending = false;
            if (document.getTipo() == CommercialDocumentType.RECTIFICATIVA_VENTA) {
                applyPositiveRectification(total, document, dayStart, dayEnd, value);
                applyPositiveRectification(user, document, dayStart, dayEnd, value);
            } else {
                hasPending = applySale(total, document, dayStart, dayEnd, value);
                applySale(user, document, dayStart, dayEnd, value);
            }
            total.markSale(hasPending);
            user.markSale(hasPending);
        }
        total.reconcile();
        byUser.values().forEach(SummaryAccumulator::reconcile);
        var users = byUser.values().stream()
                .filter(SummaryAccumulator::hasActivity)
                .sorted(Comparator.comparing(value -> value.userName, String.CASE_INSENSITIVE_ORDER))
                .map(SummaryAccumulator::toUserView)
                .toList();
        return new SalesDailySummaryView(
                store.getId(), company.getRazonSocial(), store.getCodigoTienda(), date,
                total.netTotal, total.paymentViews(), total.counts(), users);
    }

    @Transactional(readOnly = true)
    public SalesActivityDocumentPageView documents(
            LocalDate dateFrom,
            LocalDate dateTo,
            Integer requestedLimit,
            String cursor) {
        var range = range(dateFrom, dateTo);
        var store = organization.currentStore();
        int limit = normalizedLimit(requestedLimit);
        var parsed = Cursor.parse(cursor);
        var pageable = PageRequest.of(0, limit + 1);
        var values = parsed.date() == null
                ? documents.findSalesActivityDocuments(
                        store.getId(), range.from(), range.to(), pageable)
                : documents.findSalesActivityDocumentsAfter(
                        store.getId(), range.from(), range.to(), parsed.date(),
                        parsed.occurredAt(), parsed.id(), pageable);
        boolean hasMore = values.size() > limit;
        var page = hasMore ? new ArrayList<>(values.subList(0, limit)) : values;
        var rows = rows(store.getId(), page);
        String nextCursor = hasMore ? cursorFor(page.get(page.size() - 1)) : null;
        long ticketCount = documents.countSalesActivityTickets(
                store.getId(), range.from(), range.to());
        long invoiceCount = documents.countSalesActivityInvoiceDocuments(
                store.getId(), range.from(), range.to())
                + relations.countActiveInvoicesForSalesActivityTickets(
                        store.getId(), range.from(), range.to());
        var total = Money.euros(documents.sumSalesActivityTotal(
                store.getId(), range.from(), range.to()));
        return new SalesActivityDocumentPageView(
                rows, nextCursor, hasMore, ticketCount, invoiceCount, total,
                range.from(), range.to(), LocalDate.now(ZoneId.of(store.getTimezone())));
    }


    /**
     * Returns the authoritative document-book totals grouped by issue date.
     * The repository performs both logical-document filtering and grouping, so
     * pagination never requires loading the detail book into application memory.
     */
    @Transactional(readOnly = true)
    public SalesActivityDailyDocumentPageView dailyDocuments(
            LocalDate dateFrom,
            LocalDate dateTo,
            Integer requestedLimit,
            String cursor) {
        var range = range(dateFrom, dateTo);
        var store = organization.currentStore();
        var limit = normalizedLimit(requestedLimit);
        var cursorDate = parseDailyCursor(cursor);
        var values = cursorDate == null
                ? documents.findSalesActivityDaily(
                        store.getId(), range.from(), range.to(), PageRequest.of(0, limit + 1))
                : documents.findSalesActivityDailyAfter(
                        store.getId(), range.from(), range.to(), cursorDate,
                        PageRequest.of(0, limit + 1));
        var hasMore = values.size() > limit;
        var pageValues = hasMore ? values.subList(0, limit) : values;
        var items = pageValues.stream()
                .map(value -> new SalesActivityDailyRowView(
                        value.getDate(),
                        projectionTicketCount(value),
                        projectionInvoiceCount(value),
                        Money.euros(value.getTotal() == null
                                ? BigDecimal.ZERO : value.getTotal())))
                .toList();
        var totals = documents.sumSalesActivityDaily(
                store.getId(), range.from(), range.to());
        return new SalesActivityDailyDocumentPageView(
                items,
                hasMore ? items.get(items.size() - 1).date().toString() : null,
                hasMore,
                totalTicketCount(totals),
                totalInvoiceCount(totals),
                Money.euros(totals == null || totals.getTotal() == null
                        ? BigDecimal.ZERO : totals.getTotal()),
                range.from(),
                range.to(),
                LocalDate.now(ZoneId.of(store.getTimezone())));
    }

    private static long projectionTicketCount(SalesActivityDailyProjection value) {
        return nullToZero(value.getTicketCount());
    }

    private static long projectionInvoiceCount(SalesActivityDailyProjection value) {
        return value.getInvoiceCount() == null ? 0L : value.getInvoiceCount();
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static long totalTicketCount(SalesActivityDailyTotalsProjection totals) {
        return totals == null ? 0L : nullToZero(totals.getTicketCount());
    }

    private static long totalInvoiceCount(SalesActivityDailyTotalsProjection totals) {
        return totals == null ? 0L : nullToZero(totals.getInvoiceCount());
    }

    @Transactional(readOnly = true)
    public List<SalesActivityDocumentRowView> allDocuments(
            LocalDate dateFrom, LocalDate dateTo) {
        var range = range(dateFrom, dateTo);
        var store = organization.currentStore();
        var result = new ArrayList<SalesActivityDocumentRowView>();
        String cursor = null;
        do {
            var parsed = Cursor.parse(cursor);
            var values = parsed.date() == null
                    ? documents.findSalesActivityDocuments(store.getId(), range.from(), range.to(),
                            PageRequest.of(0, MAX_LIMIT + 1))
                    : documents.findSalesActivityDocumentsAfter(
                            store.getId(), range.from(), range.to(), parsed.date(),
                            parsed.occurredAt(), parsed.id(), PageRequest.of(0, MAX_LIMIT + 1));
            boolean hasMore = values.size() > MAX_LIMIT;
            var page = hasMore ? new ArrayList<>(values.subList(0, MAX_LIMIT)) : values;
            result.addAll(rows(store.getId(), page));
            if (result.size() > MAX_EXPORT_ROWS) {
                throw new IllegalArgumentException(
                        "El informe supera el limite de 50000 filas exportables");
            }
            cursor = hasMore ? cursorFor(page.get(page.size() - 1)) : null;
        } while (cursor != null);
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public List<SalesActivityDailyRowView> allDailyDocuments(LocalDate dateFrom, LocalDate dateTo) {
        var range = range(dateFrom, dateTo);
        var store = organization.currentStore();
        var result = new ArrayList<SalesActivityDailyRowView>();
        LocalDate cursor = null;
        do {
            var values = cursor == null
                    ? documents.findSalesActivityDaily(store.getId(), range.from(), range.to(),
                            PageRequest.of(0, MAX_EXPORT_ROWS + 1))
                    : documents.findSalesActivityDailyAfter(store.getId(), range.from(), range.to(),
                            cursor, PageRequest.of(0, MAX_EXPORT_ROWS + 1));
            var hasMore = values.size() > MAX_EXPORT_ROWS;
            var page = hasMore ? values.subList(0, MAX_EXPORT_ROWS) : values;
            for (var value : page) {
                result.add(new SalesActivityDailyRowView(
                        value.getDate(), projectionTicketCount(value), projectionInvoiceCount(value),
                        Money.euros(value.getTotal() == null ? BigDecimal.ZERO : value.getTotal())));
            }
            if (result.size() > MAX_EXPORT_ROWS) {
                throw new IllegalArgumentException("El informe supera el limite de 50000 dias exportables");
            }
            cursor = hasMore ? result.get(result.size() - 1).date() : null;
        } while (cursor != null);
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public SalesActivityFilterOptionsView filterOptions() {
        var store = organization.currentStore();
        var current = LocalDate.now(ZoneId.of(store.getTimezone()));
        var earliest = documents.findFirstSalesActivityDate(store.getId());
        return new SalesActivityFilterOptionsView(earliest == null ? current : earliest, current);
    }

    private List<SalesActivityDocumentRowView> rows(
            UUID storeId, List<CommercialDocument> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        var attributionIndex = attributions.resolve(values);
        var invoiceNumbers = activeInvoiceNumbers(storeId, values);
        var returnIds = values.stream()
                .filter(SalesActivityReportService::isReturnDocument)
                .filter(document -> document.getEstado() != DocumentStatus.ANULADO)
                .map(CommercialDocument::getId).toList();
        var refundIndex = refundIndex(storeId, returnIds);
        return values.stream().map(document -> {
            var attribution = attributionIndex.getOrDefault(
                    document.getId(), DocumentAttributionResolver.Attribution.empty(document));
            var kind = kind(document);
            return new SalesActivityDocumentRowView(
                    document.getId(), document.getFecha(), document.getOperationalOccurredAt(),
                    document.getTipo() == CommercialDocumentType.TICKET
                            ? text(document.getNumero(), document.getNumTicket()) : "",
                    document.getTipo() == CommercialDocumentType.TICKET
                            ? invoiceNumbers.getOrDefault(document.getId(), "")
                            : text(document.getNumero(), ""),
                    attribution.userId(), normalizedUserName(attribution.userName()),
                    rowPaymentMethods(document, refundIndex.getOrDefault(document.getId(), List.of())),
                    kind, document.getEstado(), effectiveTotal(document));
        }).toList();
    }

    private Map<UUID, String> activeInvoiceNumbers(
            UUID storeId, Collection<CommercialDocument> values) {
        var ticketIds = values.stream()
                .filter(document -> document.getTipo() == CommercialDocumentType.TICKET)
                .map(CommercialDocument::getId).toList();
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        var result = new LinkedHashMap<UUID, String>();
        relations.findActiveRelatedDocuments(
                        storeId, ticketIds, DocumentRelationType.FACTURA_DE)
                .forEach(relation -> result.putIfAbsent(
                        relation.getOriginId(), text(relation.getDocumentNumber(), "")));
        return Map.copyOf(result);
    }

    private Map<UUID, List<RefundTender>> refundIndex(
            UUID storeId, Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        return refunds.findAllByRefundDocumentIds(storeId, documentIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        tender -> tender.getRefundDocument().getId(), LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
    }

    private Map<UUID, String> originalPaymentMethods(List<RefundTender> tenders) {
        var ids = tenders.stream().map(RefundTender::getOriginalPaymentId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<UUID, String>();
        payments.findAllById(ids).forEach(payment -> result.put(
                payment.getId(), payment.getMetodoPago().getNombre()));
        return Map.copyOf(result);
    }

    private static boolean applySale(
            SummaryAccumulator target,
            CommercialDocument document,
            Instant dayStart,
            Instant dayEnd,
            BigDecimal documentTotal) {
        var paid = Money.euros("0");
        for (var payment : document.getPagos()) {
            if (payment.getCreadoEn().isBefore(dayStart)
                    || !payment.getCreadoEn().isBefore(dayEnd)) {
                continue;
            }
            var amount = Money.euros(payment.getImporte());
            var bucket = paymentBucket(payment);
            if (bucket != null) {
                target.add(bucket, amount, document.getId());
                paid = paid.add(amount);
            }
        }
        var pending = Money.euros(documentTotal.subtract(paid).max(BigDecimal.ZERO));
        target.add(SalesActivityPaymentMethod.PENDIENTE, pending, document.getId());
        return pending.signum() > 0;
    }

    private static void applyPositiveRectification(
            SummaryAccumulator target,
            CommercialDocument document,
            Instant dayStart,
            Instant dayEnd,
            BigDecimal documentTotal) {
        var paid = Money.euros("0");
        for (var payment : document.getPagos()) {
            if (payment.getCreadoEn().isBefore(dayStart)
                    || !payment.getCreadoEn().isBefore(dayEnd)) {
                continue;
            }
            var bucket = paymentBucket(payment);
            if (bucket != null) {
                var amount = Money.euros(payment.getImporte());
                target.add(bucket, amount, document.getId());
                paid = paid.add(amount);
            }
        }
        target.add(SalesActivityPaymentMethod.OTROS,
                Money.euros(documentTotal.subtract(paid)), document.getId());
    }

    private static void applyReturn(
            SummaryAccumulator target,
            List<RefundTender> tenders,
            Map<UUID, String> originalMethods,
            BigDecimal returnTotal,
            UUID documentId) {
        var allocated = Money.euros("0");
        for (var tender : tenders) {
            var amount = Money.euros(tender.getAmount()).negate();
            target.add(refundBucket(tender, originalMethods), amount, documentId);
            allocated = allocated.add(amount);
        }
        target.add(SalesActivityPaymentMethod.OTROS,
                Money.euros(returnTotal.subtract(allocated)), documentId);
    }

    private static SalesActivityPaymentMethod paymentBucket(DocumentPayment payment) {
        return switch (payment.getMetodoPago().getNombre()) {
            case "EFECTIVO" -> SalesActivityPaymentMethod.EFECTIVO;
            case "TARJETA" -> SalesActivityPaymentMethod.TARJETA;
            case "TRANSFERENCIA" -> SalesActivityPaymentMethod.TRANSFERENCIA;
            case "VALE" -> SalesActivityPaymentMethod.VALE;
            case PaymentMethodService.EXCHANGE_COMPENSATION_METHOD -> null;
            default -> SalesActivityPaymentMethod.OTROS;
        };
    }

    private static SalesActivityPaymentMethod refundBucket(
            RefundTender tender, Map<UUID, String> originalMethods) {
        return switch (tender.getType()) {
            case CASH -> SalesActivityPaymentMethod.EFECTIVO;
            case VOUCHER -> SalesActivityPaymentMethod.VALE;
            case TRANSFER -> SalesActivityPaymentMethod.TRANSFERENCIA;
            case EXCHANGE -> SalesActivityPaymentMethod.OTROS;
            case MEMBER_CREDIT -> SalesActivityPaymentMethod.OTROS;
            case CARD -> tender.getTerminalOperationId() == null
                    && "TRANSFERENCIA".equals(originalMethods.get(tender.getOriginalPaymentId()))
                    ? SalesActivityPaymentMethod.TRANSFERENCIA
                    : SalesActivityPaymentMethod.TARJETA;
        };
    }

    private static List<String> rowPaymentMethods(
            CommercialDocument document, List<RefundTender> tenders) {
        if (document.getEstado() == DocumentStatus.ANULADO) {
            return List.of();
        }
        var values = new LinkedHashSet<String>();
        if (isReturnDocument(document)) {
            tenders.forEach(tender -> values.add(switch (tender.getType()) {
                case CASH -> "EFECTIVO";
                case CARD -> "TARJETA";
                case TRANSFER -> "TRANSFERENCIA";
                case VOUCHER -> "VALE";
                case EXCHANGE -> "OTROS";
                case MEMBER_CREDIT -> "OTROS";
            }));
            if (values.isEmpty()) values.add("OTROS");
            return List.copyOf(values);
        }
        document.getPagos().stream()
                .map(payment -> payment.getMetodoPago().getNombre())
                .filter(method -> !PaymentMethodService.EXCHANGE_COMPENSATION_METHOD.equals(method))
                .forEach(values::add);
        if (document.getPendingTotal().signum() > 0) values.add("PENDIENTE");
        return List.copyOf(values);
    }

    private static SalesActivityDocumentRowView.SalesActivityKind kind(
            CommercialDocument document) {
        if (document.getEstado() == DocumentStatus.ANULADO) {
            return SalesActivityDocumentRowView.SalesActivityKind.CANCELLED;
        }
        if (isReturnDocument(document)) {
            return SalesActivityDocumentRowView.SalesActivityKind.RETURN;
        }
        return SalesActivityDocumentRowView.SalesActivityKind.SALE;
    }

    private static BigDecimal effectiveTotal(CommercialDocument document) {
        if (document.getEstado() == DocumentStatus.ANULADO) {
            return Money.euros("0");
        }
        return Money.euros(document.getTotal());
    }

    private static boolean isReturnDocument(CommercialDocument document) {
        return document.getTotal().signum() < 0;
    }

    private static boolean isSalesActivityDocument(CommercialDocument document) {
        return document.getTipo() == CommercialDocumentType.TICKET
                || document.getTipo() == CommercialDocumentType.FACTURA_VENTA
                || document.getTipo() == CommercialDocumentType.RECTIFICATIVA_VENTA;
    }

    private static String normalizedUserName(String value) {
        return value == null || value.isBlank() ? "SIN USUARIO" : value.trim();
    }

    private static String text(String primary, String fallback) {
        return primary == null || primary.isBlank()
                ? Objects.toString(fallback, "") : primary.trim();
    }

    private static int normalizedLimit(Integer value) {
        return value == null || value <= 0 ? DEFAULT_LIMIT : Math.min(value, MAX_LIMIT);
    }

    private static LocalDate parseDailyCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor invalido", exception);
        }
    }

    private static DateRange range(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("dateFrom y dateTo son obligatorios");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("dateTo no puede ser anterior a dateFrom");
        }
        return new DateRange(from, to);
    }

    private static String cursorFor(CommercialDocument document) {
        return document.getFecha() + "|" + document.getOperationalOccurredAt()
                + "|" + document.getId();
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private record UserKey(UUID id, String name) {
    }

    private record Cursor(LocalDate date, Instant occurredAt, String id) {
        static Cursor parse(String value) {
            if (value == null || value.isBlank()) return new Cursor(null, null, null);
            var parts = value.split("\\|", 3);
            if (parts.length != 3) throw new IllegalArgumentException("cursor invalido");
            return new Cursor(LocalDate.parse(parts[0]), Instant.parse(parts[1]),
                    UUID.fromString(parts[2]).toString());
        }
    }

    private static final class SummaryAccumulator {
        private final UUID userId;
        private final String userName;
        private BigDecimal netTotal = Money.euros("0");
        private final EnumMap<SalesActivityPaymentMethod, BigDecimal> methods =
                new EnumMap<>(SalesActivityPaymentMethod.class);
        private final EnumMap<SalesActivityPaymentMethod, LinkedHashSet<UUID>> methodDocuments =
                new EnumMap<>(SalesActivityPaymentMethod.class);
        private long sales;
        private long returns;
        private long cancelled;
        private long pending;

        private SummaryAccumulator(UUID userId, String userName) {
            this.userId = userId;
            this.userName = userName;
            for (var method : SalesActivityPaymentMethod.values()) {
                methods.put(method, Money.euros("0"));
                methodDocuments.put(method, new LinkedHashSet<>());
            }
        }

        private void add(
                SalesActivityPaymentMethod method, BigDecimal value, UUID documentId) {
            if (method == null || value == null || value.signum() == 0) return;
            methods.put(method, Money.euros(methods.get(method).add(value)));
            if (documentId != null) methodDocuments.get(method).add(documentId);
        }

        private void markSale(boolean isPending) {
            sales++;
            if (isPending) pending++;
        }

        private void markReturn() {
            returns++;
        }

        private void markCancelled() {
            cancelled++;
        }

        private void reconcile() {
            var methodTotal = methods.values().stream()
                    .reduce(Money.euros("0"), BigDecimal::add);
            add(SalesActivityPaymentMethod.OTROS,
                    Money.euros(netTotal.subtract(methodTotal)), null);
            var reconciled = methods.values().stream()
                    .reduce(Money.euros("0"), BigDecimal::add);
            if (Money.euros(reconciled).compareTo(Money.euros(netTotal)) != 0) {
                throw new IllegalStateException("sales_activity_payment_reconciliation_failed");
            }
        }

        private boolean hasActivity() {
            return netTotal.signum() != 0 || sales > 0 || returns > 0 || cancelled > 0;
        }

        private List<SalesDailySummaryView.PaymentTotalView> paymentViews() {
            return methods.entrySet().stream()
                    .filter(entry -> entry.getValue().signum() != 0)
                    .map(entry -> new SalesDailySummaryView.PaymentTotalView(
                            entry.getKey(), methodDocuments.get(entry.getKey()).size(),
                            Money.euros(entry.getValue())))
                    .toList();
        }

        private SalesDailySummaryView.ActivityCountsView counts() {
            return new SalesDailySummaryView.ActivityCountsView(
                    sales, returns, cancelled, pending);
        }

        private SalesDailySummaryView.UserSummaryView toUserView() {
            return new SalesDailySummaryView.UserSummaryView(
                    userId, userName, netTotal, paymentViews(), counts());
        }
    }
}
