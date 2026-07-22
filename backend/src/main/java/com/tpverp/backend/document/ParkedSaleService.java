package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParkedSaleService {

    private final ParkedSaleRepository sales;
    private final ParkedSaleRecoveryRepository recoveries;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ParkedSaleService(
            ParkedSaleRepository sales, ParkedSaleRecoveryRepository recoveries,
            CurrentOrganization organization, Clock clock) {
        this.sales = sales;
        this.recoveries = recoveries;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional
    public ParkedSale park(
            DocumentCommand command, String comment, Authentication authentication) {
        if (command.tipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException("solo se aparcan tickets");
        }
        if (command.lineas() == null || command.lineas().isEmpty()) {
            throw new IllegalArgumentException("la venta aparcada necesita lineas");
        }
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        return sales.save(new ParkedSale(
                store.getId(), user.getId(), Instant.now(clock), command, comment));
    }
    // Guarda una venta sin numeracion fiscal ni pagos para recuperarla despues.

    @Transactional(readOnly = true)
    public List<ParkedSale> list() {
        return sales.findAllByTiendaIdOrderByCreadoEnDesc(
                organization.currentStore().getId());
    }

    @Transactional
    public ParkedSaleOpened open(UUID id) {
        var sale = find(id);
        return new ParkedSaleOpened(sale.documentCommand(), sale.getComment());
    }

    @Transactional
    public ParkedSaleRecoveryView recover(
            UUID saleId, UUID recoveryId, Authentication authentication) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var replay = recoveries.findByRecoveryIdAndStoreIdAndCompanyId(
                recoveryId, store.getId(), company.getId());
        if (replay.isPresent()) {
            return recoveryView(requireIdentity(
                    replay.orElseThrow(), saleId, store.getId(), company.getId()));
        }

        var sale = sales.findLockedByIdAndStoreId(saleId, store.getId())
                .orElseThrow(() -> new IllegalArgumentException("venta aparcada no encontrada"));
        replay = recoveries.findByRecoveryIdAndStoreIdAndCompanyId(
                recoveryId, store.getId(), company.getId());
        if (replay.isPresent()) {
            return recoveryView(requireIdentity(
                    replay.orElseThrow(), saleId, store.getId(), company.getId()));
        }
        var active = recoveries.findByParkedSaleIdAndStoreIdAndCompanyId(
                saleId, store.getId(), company.getId());
        if (active.isPresent()) {
            throw new IllegalStateException("parked_sale_recovery_already_claimed");
        }
        var recovery = new ParkedSaleRecovery(
                recoveryId, sale, company.getId(),
                organization.currentUser(authentication).getId(), Instant.now(clock));
        return recoveryView(recoveries.save(recovery));
    }

    @Transactional
    public ParkedSaleRecoveryView acknowledge(
            UUID saleId, UUID recoveryId) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var recovery = recoveries.findLocked(
                        recoveryId, store.getId(), company.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "parked_sale_recovery_not_found"));
        requireIdentity(recovery, saleId, store.getId(), company.getId());
        if (recovery.getStatus() == ParkedSaleRecovery.Status.CLAIMED) {
            sales.findLockedByIdAndStoreId(saleId, store.getId())
                    .ifPresent(sales::delete);
            recovery.acknowledge(Instant.now(clock));
            recoveries.save(recovery);
        }
        return recoveryView(recovery);
    }

    @Transactional
    public void delete(UUID id) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        if (recoveries.findByParkedSaleIdAndStoreIdAndCompanyId(
                id, store.getId(), company.getId()).isPresent()) {
            throw new IllegalStateException("parked_sale_recovery_already_claimed");
        }
        sales.delete(find(id));
    }
    // El borrador solo se elimina al confirmar la reconstruccion local. La recuperacion
    // conserva una instantanea y una clave idempotente para que un reintento no duplique
    // ni pierda la venta.

    private ParkedSale find(UUID id) {
        return sales.findByIdAndTiendaId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "venta aparcada no encontrada"));
    }

    private static ParkedSaleRecovery requireIdentity(
            ParkedSaleRecovery recovery, UUID saleId, UUID storeId, UUID companyId) {
        if (!recovery.matches(saleId, storeId, companyId)) {
            throw new IllegalStateException("parked_sale_recovery_idempotency_conflict");
        }
        return recovery;
    }

    private static ParkedSaleRecoveryView recoveryView(ParkedSaleRecovery recovery) {
        return new ParkedSaleRecoveryView(
                recovery.getRecoveryId(), recovery.getParkedSaleId(),
                recovery.getStatus(), recovery.opened());
    }

    public record ParkedSaleRecoveryView(
            UUID recoveryId, UUID parkedSaleId, ParkedSaleRecovery.Status status,
            ParkedSaleOpened sale) {}
}
