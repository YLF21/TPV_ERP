package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherService {

    private final VoucherRepository vouchers;
    private final VoucherEventRepository events;
    private final CurrentOrganization organization;
    private final Clock clock;
    private VoucherPresentationSnapshotFactory printSnapshots;

    public VoucherService(
            VoucherRepository vouchers,
            VoucherEventRepository events,
            CurrentOrganization organization,
            Clock clock) {
        this.vouchers = vouchers;
        this.events = events;
        this.organization = organization;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPrintSnapshots(VoucherPresentationSnapshotFactory printSnapshots) {
        this.printSnapshots = printSnapshots;
    }

    @Transactional
    public Voucher issueFromNegativeTicket(CommercialDocument ticket) {
        return issueFromNegativeTicket(ticket, ticket == null ? null : ticket.getTotal().abs());
    }

    @Transactional
    public Voucher issueFromNegativeTicket(CommercialDocument ticket, BigDecimal amount) {
        requireCurrentStore(ticket);
        if (ticket.getTipo() != CommercialDocumentType.TICKET || ticket.getTotal().signum() >= 0) {
            throw new IllegalArgumentException("solo un ticket negativo genera vale");
        }
        if (ticket.getNumero() == null || ticket.getNumero().isBlank()) {
            throw new IllegalArgumentException("el ticket necesita numero para generar vale");
        }
        if (alreadyIssued(ticket)) {
            throw new IllegalStateException("el ticket ya tiene vale generado");
        }
        var voucherAmount = Money.euros(amount);
        if (voucherAmount.signum() <= 0 || voucherAmount.compareTo(ticket.getTotal().abs()) > 0) {
            throw new IllegalArgumentException("el importe del vale no puede superar la devolucion");
        }
        var voucher = new Voucher(
                ticket.getTiendaId(), nextCode(), voucherAmount,
                List.of(ticket.getNumero()), Instant.now(clock));
        capturePrintSnapshot(voucher, ticket, null);
        return vouchers.save(voucher);
    }

    @Transactional
    public Voucher issueOrFindFromNegativeTicket(CommercialDocument ticket, BigDecimal amount) {
        requireCurrentStore(ticket);
        var existing = issuedFor(ticket);
        if (existing.isPresent()) {
            if (existing.orElseThrow().initialAmount().compareTo(Money.euros(amount)) != 0) {
                throw new IllegalStateException("el vale existente no coincide con el importe solicitado");
            }
            return existing.orElseThrow();
        }
        return issueFromNegativeTicket(ticket, amount);
    }
    // Emite un vale por el importe absoluto de un ticket negativo confirmado.

    @Transactional
    public VoucherConsumptionResult consume(
            String code, BigDecimal pendingAmount, CommercialDocument purchaseTicket) {
        return consume(code, pendingAmount, purchaseTicket, false);
    }

    @Transactional
    public VoucherConsumptionResult consumeExact(
            String code, BigDecimal amount, CommercialDocument purchaseTicket) {
        return consume(code, amount, purchaseTicket, true);
    }

    private VoucherConsumptionResult consume(
            String code, BigDecimal pendingAmount, CommercialDocument purchaseTicket,
            boolean exactAmountRequired) {
        requireCurrentStore(purchaseTicket);
        requireNumberedPurchaseTicket(purchaseTicket);
        var voucher = findActive(code);
        var requested = Money.euros(pendingAmount);
        if (requested.signum() <= 0) {
            throw new IllegalArgumentException("importe de vale debe ser positivo");
        }
        if (exactAmountRequired && voucher.balance().compareTo(requested) < 0) {
            throw new IllegalStateException("saldo de vale insuficiente");
        }
        var consumed = requested.min(voucher.balance());
        var replacement = Optional.<Voucher>empty();
        if (voucher.balance().compareTo(requested) > 0) {
            var remaining = Money.euros(voucher.balance().subtract(requested));
            voucher.closeForReplacement();
            var renewed = new Voucher(
                    purchaseTicket.getTiendaId(), nextCode(), remaining,
                    origins(voucher, purchaseTicket), Instant.now(clock));
            capturePrintSnapshot(renewed, purchaseTicket, voucher);
            replacement = Optional.of(vouchers.save(renewed));
        } else {
            voucher.consume(requested);
        }
        vouchers.save(voucher);
        return new VoucherConsumptionResult(voucher, consumed, replacement);
    }
    // El consumo parcial cierra el vale original y reemite el sobrante con un codigo nuevo.

    @Transactional(readOnly = true)
    public List<Voucher> list() {
        return vouchers.findAllByTiendaIdOrderByCreatedAtDesc(
                organization.currentStore().getId());
    }

    @Transactional(readOnly = true)
    public Optional<Voucher> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return vouchers.findByTiendaIdAndCodeIgnoreCase(
                organization.currentStore().getId(), code.trim());
    }

    @Transactional(readOnly = true)
    public boolean hasVoucherImpact(CommercialDocument ticket) {
        if (ticket == null || ticket.getTipo() != CommercialDocumentType.TICKET) {
            return false;
        }
        return ticket.getPagos().stream().anyMatch(payment -> payment.getVoucherCode() != null)
                || generatedVoucherExists(ticket);
    }
    // Detecta tickets que han usado o generado vales para evitar anulaciones incoherentes.

    @Transactional(readOnly = true)
    public VoucherCancellationPlan cancellationPlan(CommercialDocument ticket) {
        requireCurrentStore(ticket);
        if (ticket.getNumero() == null || ticket.getNumero().isBlank()) {
            throw new IllegalArgumentException("el ticket necesita numero para compensar vales");
        }
        var consumedCodes = ticket.getPagos().stream()
                .map(DocumentPayment::getVoucherCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        var generated = vouchers.findAllByOriginTicket(
                ticket.getTiendaId(), ticket.getNumero());
        requireGeneratedVouchersUnused(ticket, generated);
        return new VoucherCancellationPlan(
                !consumedCodes.isEmpty() || !generated.isEmpty(),
                consumedCodes,
                generated.stream().map(Voucher::code).toList());
    }

    @Transactional
    public VoucherCancellationResult compensateCancellation(
            CommercialDocument ticket,
            UUID authorizerUserId) {
        requireCurrentStore(ticket);
        var plan = cancellationPlan(ticket);
        var restored = new ArrayList<Voucher>();
        var now = Instant.now(clock);
        for (var code : plan.consumedVoucherCodes()) {
            var voucher = vouchers.findLockedByTiendaIdAndCode(ticket.getTiendaId(), code)
                    .orElseThrow(() -> new IllegalStateException(
                            "el vale consumido por el ticket no existe: " + code));
            if (voucher.status() == VoucherStatus.INVALIDATED) {
                throw new IllegalStateException("el vale consumido esta invalidado: " + code);
            }
            if (!events.existsByVoucher_IdAndDocumentIdAndType(
                    voucher.id(), ticket.getId(), VoucherEventType.RESTORED)) {
                voucher.restoreAfterTicketCancellation();
                vouchers.save(voucher);
                events.save(new VoucherEvent(
                        voucher, ticket, VoucherEventType.RESTORED,
                        voucher.balance(), authorizerUserId, now,
                        Map.of("codigo", voucher.code())));
            }
            restored.add(voucher);
        }

        var invalidated = new ArrayList<Voucher>();
        var generated = vouchers.findAllLockedByOriginTicket(
                ticket.getTiendaId(), ticket.getNumero());
        requireGeneratedVouchersUnused(ticket, generated);
        for (var voucher : generated) {
            if (voucher.status() == VoucherStatus.INVALIDATED) {
                invalidated.add(voucher);
                continue;
            }
            if (!events.existsByVoucher_IdAndDocumentIdAndType(
                    voucher.id(), ticket.getId(), VoucherEventType.INVALIDATED)) {
                var priorBalance = voucher.balance();
                voucher.invalidateAfterTicketCancellation();
                vouchers.save(voucher);
                var detail = new LinkedHashMap<String, Object>();
                detail.put("codigo", voucher.code());
                detail.put("saldoAnterior", priorBalance.toPlainString());
                events.save(new VoucherEvent(
                        voucher, ticket, VoucherEventType.INVALIDATED,
                        priorBalance, authorizerUserId, now, detail));
            }
            invalidated.add(voucher);
        }
        return new VoucherCancellationResult(
                List.copyOf(restored), List.copyOf(invalidated));
    }

    private static void requireGeneratedVouchersUnused(
            CommercialDocument ticket,
            List<Voucher> generated) {
        var used = generated.stream()
                .filter(voucher -> voucher.status() == VoucherStatus.CONSUMED)
                .map(Voucher::code)
                .toList();
        if (!used.isEmpty()) {
            throw new IllegalStateException(
                    "no se puede anular el ticket porque un vale generado fue utilizado: "
                            + String.join(", ", used)
                            + ". Anule primero el ticket dependiente");
        }
    }

    private Voucher findActive(String code) {
        return vouchers.findLockedByTiendaIdAndCode(organization.currentStore().getId(), code)
                .filter(voucher -> voucher.status() == VoucherStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("vale activo no encontrado"));
    }

    private boolean alreadyIssued(CommercialDocument ticket) {
        return generatedVoucherExists(ticket);
    }

    @Transactional(readOnly = true)
    public Optional<Voucher> issuedFromNegativeTicket(CommercialDocument ticket) {
        requireCurrentStore(ticket);
        return issuedFor(ticket);
    }

    @Transactional(readOnly = true)
    public Optional<Voucher> issuedFromTicket(CommercialDocument ticket) {
        requireCurrentStore(ticket);
        return issuedFor(ticket);
    }

    private Optional<Voucher> issuedFor(CommercialDocument ticket) {
        if (ticket == null || ticket.getNumero() == null || ticket.getNumero().isBlank()) {
            return Optional.empty();
        }
        return vouchers.findAllByOriginTicket(ticket.getTiendaId(), ticket.getNumero()).stream()
                .max(java.util.Comparator.comparing(Voucher::createdAt));
    }

    private boolean generatedVoucherExists(CommercialDocument ticket) {
        if (ticket.getNumero() == null || ticket.getNumero().isBlank()) {
            return false;
        }
        return vouchers.findAllByTiendaIdOrderByCreatedAtDesc(ticket.getTiendaId()).stream()
                .anyMatch(voucher -> voucher.originTickets().contains(ticket.getNumero()));
    }

    private static void requireNumberedPurchaseTicket(CommercialDocument ticket) {
        if (ticket == null || ticket.getTipo() != CommercialDocumentType.TICKET
                || ticket.getNumero() == null || ticket.getNumero().isBlank()) {
            throw new IllegalArgumentException("el consumo de vale necesita un ticket numerado");
        }
    }

    @Transactional(readOnly = true)
    public BigDecimal availableBalance(String code) {
        return vouchers.findByTiendaIdAndCode(organization.currentStore().getId(), code)
                .filter(voucher -> voucher.status() == VoucherStatus.ACTIVE)
                .map(Voucher::balance)
                .orElseThrow(() -> new IllegalArgumentException("vale activo no encontrado"));
    }

    private void requireCurrentStore(CommercialDocument ticket) {
        if (ticket == null || !organization.currentStore().getId().equals(ticket.getTiendaId())) {
            throw new IllegalArgumentException("documento no encontrado");
        }
    }

    private void capturePrintSnapshot(
            Voucher voucher, CommercialDocument sourceDocument, Voucher predecessor) {
        if (printSnapshots != null && voucher.printSnapshot() == null) {
            voucher.capturePrintSnapshot(
                    printSnapshots.create(voucher, sourceDocument, predecessor));
        }
    }

    private static List<String> origins(Voucher voucher, CommercialDocument ticket) {
        var origins = new ArrayList<>(voucher.originTickets());
        if (!origins.contains(ticket.getNumero())) {
            origins.add(ticket.getNumero());
        }
        return List.copyOf(origins);
    }

    private static String nextCode() {
        return "V" + java.util.UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(java.util.Locale.ROOT);
    }

    public record VoucherCancellationPlan(
            boolean voucherImpact,
            List<String> consumedVoucherCodes,
            List<String> generatedVoucherCodes) {
    }

    public record VoucherCancellationResult(
            List<Voucher> restored,
            List<Voucher> invalidated) {
    }
}
