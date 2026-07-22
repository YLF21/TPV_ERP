package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherService {

    private final VoucherRepository vouchers;
    private final CurrentOrganization organization;
    private final Clock clock;

    public VoucherService(
            VoucherRepository vouchers, CurrentOrganization organization, Clock clock) {
        this.vouchers = vouchers;
        this.organization = organization;
        this.clock = clock;
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
        return vouchers.save(new Voucher(
                ticket.getTiendaId(), nextCode(), voucherAmount,
                List.of(ticket.getNumero()), Instant.now(clock)));
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
            replacement = Optional.of(vouchers.save(new Voucher(
                    organization.currentStore().getId(), nextCode(), remaining,
                    origins(voucher, purchaseTicket), Instant.now(clock))));
        } else {
            voucher.consume(requested);
        }
        vouchers.save(voucher);
        return new VoucherConsumptionResult(voucher, consumed, replacement);
    }
    // Consume un vale y reemite otro codigo cuando queda saldo sobrante.

    @Transactional(readOnly = true)
    public List<Voucher> list() {
        return vouchers.findAllByTiendaIdOrderByCreatedAtDesc(
                organization.currentStore().getId());
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

    private Optional<Voucher> issuedFor(CommercialDocument ticket) {
        if (ticket == null || ticket.getNumero() == null || ticket.getNumero().isBlank()) {
            return Optional.empty();
        }
        return vouchers.findAllByTiendaIdOrderByCreatedAtDesc(ticket.getTiendaId()).stream()
                .filter(voucher -> voucher.originTickets().contains(ticket.getNumero()))
                .findFirst();
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

    private static List<String> origins(Voucher voucher, CommercialDocument ticket) {
        var origins = new ArrayList<>(voucher.originTickets());
        if (ticket.getNumero() != null && !origins.contains(ticket.getNumero())) {
            origins.add(ticket.getNumero());
        }
        return origins;
    }

    private static String nextCode() {
        return "V" + java.util.UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(java.util.Locale.ROOT);
    }
}
