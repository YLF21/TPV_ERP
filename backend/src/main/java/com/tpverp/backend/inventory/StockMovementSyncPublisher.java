package com.tpverp.backend.inventory;

import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StockMovementSyncPublisher {

    private final SyncOutboxService syncOutbox;

    public StockMovementSyncPublisher(SyncOutboxService syncOutbox) {
        this.syncOutbox = syncOutbox;
    }

    public void enqueue(UUID companyId, UUID storeId, StockMovement movement) {
        syncOutbox.enqueue(new SyncOutboundEventCommand(
                companyId,
                storeId,
                null,
                "STOCK_MOVEMENT",
                movement.getId(),
                SyncOperation.CREAR,
                payload(movement)));
    }

    private static Map<String, Object> payload(StockMovement movement) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("productoId", movement.getProductId().toString());
        payload.put("almacenId", movement.getWarehouseId().toString());
        payload.put("usuarioId", movement.getUserId().toString());
        payload.put("documentoId", nullableUuid(movement.getDocumentId()));
        payload.put("salidaAlmacenId", nullableUuid(movement.getWarehouseOutputId()));
        payload.put("tipo", movement.getType().name());
        payload.put("cantidad", movement.getQuantity());
        payload.put("motivo", movement.getReason());
        payload.put("compensacionDeId", nullableUuid(movement.getCompensationOfId()));
        payload.put("transferenciaId", nullableUuid(movement.getTransferId()));
        payload.put("creadoEn", movement.getCreatedAt().toString());
        return payload;
    }

    private static String nullableUuid(UUID value) {
        return value == null ? null : value.toString();
    }
}
