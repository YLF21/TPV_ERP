package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ApprovedCardTicketSnapshot(
        UUID storeId, UUID warehouseId, LocalDate date, UUID customerId, UUID paymentMethodId,
        BigDecimal globalDiscount, BigDecimal baseTotal, BigDecimal taxTotal,
        BigDecimal total, List<DocumentLineCommand> lines) {
    public static ApprovedCardTicketSnapshot from(CommercialDocument quoted,UUID paymentMethodId) {
        return new ApprovedCardTicketSnapshot(quoted.getTiendaId(),quoted.getAlmacenId(),quoted.getFecha(),
                quoted.getClienteId(),paymentMethodId,quoted.getDescuentoGlobal(),quoted.getBaseTotal(),quoted.getImpuestoTotal(),
                quoted.getTotal(),quoted.getLineas().stream().map(DocumentLineCommand::from).toList());
    }
}
