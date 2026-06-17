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
    private final CurrentOrganization organization;
    private final Clock clock;

    public ParkedSaleService(
            ParkedSaleRepository sales, CurrentOrganization organization, Clock clock) {
        this.sales = sales;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional
    public ParkedSale park(
            DocumentCommand command, String comment, Authentication authentication) {
        if (command.tipo() != TipoDocumento.TICKET) {
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
    public ParkedSaleOpened openAndRemove(UUID id) {
        var sale = find(id);
        sales.delete(sale);
        return new ParkedSaleOpened(sale.documentCommand(), sale.getComment());
    }
    // Al abrirla en un terminal deja de existir como venta aparcada.

    private ParkedSale find(UUID id) {
        return sales.findByIdAndTiendaId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "venta aparcada no encontrada"));
    }
}
