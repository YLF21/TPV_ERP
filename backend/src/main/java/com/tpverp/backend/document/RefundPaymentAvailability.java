package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentCardMode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Backend-authoritative balance of the original ticket payments that can still
 * be used by a return. Voucher issuance is intentionally excluded: a new
 * voucher may cover any refundable amount regardless of the original tender.
 */
public final class RefundPaymentAvailability {

    private RefundPaymentAvailability() {
    }

    static List<View> calculate(
            CommercialDocument ticket,
            RefundTenderRepository refundTenders,
            List<SalePaymentAllocation> activeAllocations) {
        var groups = new LinkedHashMap<Key, MutableView>();
        var allocations = List.copyOf(activeAllocations == null ? List.of() : activeAllocations);
        for (var payment : ticket.getPagos().stream()
                .filter(RefundPaymentAvailability::isMonetaryRefundSource)
                .sorted(java.util.Comparator.comparingInt(DocumentPayment::getPosicion))
                .toList()) {
            var kind = allocationKind(payment);
            var method = payment.getMetodoPago().getNombre();
            var key = new Key(method, kind);
            var original = Money.euros(payment.getImporte());
            var refunded = refundTenders == null
                    ? Money.euros(BigDecimal.ZERO)
                    : Money.euros(refundTenders.refundedAmountByOriginalPaymentId(payment.getId()));
            var reserved = allocations.stream()
                    .filter(allocation -> payment.getId().equals(allocation.getOriginalPaymentId()))
                    .filter(RefundPaymentAvailability::reservesBalance)
                    .map(SalePaymentAllocation::getAmount)
                    .reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
            var available = Money.euros(original.subtract(refunded).subtract(reserved));
            if (available.signum() < 0) {
                available = Money.euros(BigDecimal.ZERO);
            }
            groups.computeIfAbsent(key, ignored -> new MutableView(method, kind))
                    .add(original, refunded, reserved, available);
        }
        return groups.values().stream().map(MutableView::toView).toList();
    }

    static BigDecimal availableFor(
            CommercialDocument ticket,
            RefundTenderRepository refundTenders,
            List<SalePaymentAllocation> activeAllocations,
            SalePaymentAllocationKind kind) {
        return calculate(ticket, refundTenders, activeAllocations).stream()
                .filter(view -> view.kind() == kind)
                .map(View::availableAmount)
                .reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
    }

    static SalePaymentAllocationKind allocationKind(DocumentPayment payment) {
        var method = payment.getMetodoPago().getNombre().trim().toUpperCase(Locale.ROOT);
        return switch (method) {
            case "EFECTIVO" -> SalePaymentAllocationKind.CASH;
            case "TARJETA" -> payment.getCardMode() == PaymentCardMode.INTEGRATED
                    ? SalePaymentAllocationKind.INTEGRATED_CARD
                    : SalePaymentAllocationKind.MANUAL_CARD;
            case "TRANSFERENCIA" -> SalePaymentAllocationKind.TRANSFER;
            case "VALE" -> SalePaymentAllocationKind.VOUCHER;
            default -> null;
        };
    }

    /**
     * The exchange compensation is accounting provenance, not money actually
     * paid by the customer. It can therefore only be returned as a new voucher.
     */
    static boolean isMonetaryRefundSource(DocumentPayment payment) {
        var method = payment.getMetodoPago().getNombre();
        return method == null || !PaymentMethodService.EXCHANGE_COMPENSATION_METHOD
                .equalsIgnoreCase(method.trim());
    }

    private static boolean reservesBalance(SalePaymentAllocation allocation) {
        return allocation.getStatus() != PaymentTerminalOperationStatus.CANCELLED
                && allocation.getStatus() != PaymentTerminalOperationStatus.DECLINED
                && allocation.getStatus() != PaymentTerminalOperationStatus.ERROR;
    }

    public record View(
            String paymentMethod,
            SalePaymentAllocationKind kind,
            BigDecimal originalAmount,
            BigDecimal refundedAmount,
            BigDecimal reservedAmount,
            BigDecimal availableAmount) {
    }

    private record Key(String paymentMethod, SalePaymentAllocationKind kind) {
        private Key {
            Objects.requireNonNull(paymentMethod);
        }
    }

    private static final class MutableView {
        private final String paymentMethod;
        private final SalePaymentAllocationKind kind;
        private final List<BigDecimal> original = new ArrayList<>();
        private final List<BigDecimal> refunded = new ArrayList<>();
        private final List<BigDecimal> reserved = new ArrayList<>();
        private final List<BigDecimal> available = new ArrayList<>();

        private MutableView(String paymentMethod, SalePaymentAllocationKind kind) {
            this.paymentMethod = paymentMethod;
            this.kind = kind;
        }

        private void add(
                BigDecimal originalAmount,
                BigDecimal refundedAmount,
                BigDecimal reservedAmount,
                BigDecimal availableAmount) {
            original.add(originalAmount);
            refunded.add(refundedAmount);
            reserved.add(reservedAmount);
            available.add(availableAmount);
        }

        private View toView() {
            return new View(
                    paymentMethod,
                    kind,
                    sum(original),
                    sum(refunded),
                    sum(reserved),
                    sum(available));
        }

        private static BigDecimal sum(List<BigDecimal> values) {
            return values.stream().reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
        }
    }
}
