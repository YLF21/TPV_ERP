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
    public Voucher issueFromNegativeTicket(Documento ticket) {
        if (ticket.getTipo() != TipoDocumento.TICKET || ticket.getTotal().signum() >= 0) {
            throw new IllegalArgumentException("solo un ticket negativo genera vale");
        }
        if (ticket.getNumero() == null || ticket.getNumero().isBlank()) {
            throw new IllegalArgumentException("el ticket necesita numero para generar vale");
        }
        if (alreadyIssued(ticket)) {
            throw new IllegalStateException("el ticket ya tiene vale generado");
        }
        return vouchers.save(new Voucher(
                ticket.getTiendaId(), nextCode(), ticket.getTotal().abs(),
                List.of(ticket.getNumero()), Instant.now(clock)));
    }
    // Emite un vale por el importe absoluto de un ticket negativo confirmado.

    @Transactional
    public VoucherConsumptionResult consume(
            String code, BigDecimal pendingAmount, Documento purchaseTicket) {
        requireNumberedPurchaseTicket(purchaseTicket);
        var voucher = findActive(code);
        var requested = Money.euros(pendingAmount);
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

    private Voucher findActive(String code) {
        return vouchers.findByTiendaIdAndCode(organization.currentStore().getId(), code)
                .filter(voucher -> voucher.status() == VoucherStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("vale activo no encontrado"));
    }

    private boolean alreadyIssued(Documento ticket) {
        return vouchers.findAllByTiendaIdOrderByCreatedAtDesc(ticket.getTiendaId()).stream()
                .anyMatch(voucher -> voucher.originTickets().contains(ticket.getNumero()));
    }

    private static void requireNumberedPurchaseTicket(Documento ticket) {
        if (ticket == null || ticket.getTipo() != TipoDocumento.TICKET
                || ticket.getNumero() == null || ticket.getNumero().isBlank()) {
            throw new IllegalArgumentException("el consumo de vale necesita un ticket numerado");
        }
    }

    private static List<String> origins(Voucher voucher, Documento ticket) {
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
