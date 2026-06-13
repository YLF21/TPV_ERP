package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.document.Documento;
import com.tpverp.backend.document.TipoDocumento;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryDocumentGatewayTest {

    @Mock
    private StockLevelRepository stockRepository;
    @Mock
    private StockMovementRepository movementRepository;

    private InventoryDocumentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new InventoryDocumentGateway(
                stockRepository,
                movementRepository,
                Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void salesDeliveryNoteRemovesStockAndRecordsDocumentMovement() {
        var document = confirmed(TipoDocumento.ALBARAN_VENTA, 3);
        var line = document.getLineas().getFirst();
        var stock = new StockLevel(line.getProductoId(), document.getAlmacenId());
        when(stockRepository.findByProductIdAndWarehouseId(
                line.getProductoId(), document.getAlmacenId())).thenReturn(Optional.of(stock));
        when(movementRepository.existsByDocumentId(document.getId())).thenReturn(false);

        assertThat(gateway.confirm(document)).isTrue();

        assertThat(stock.getQuantity()).isEqualTo(-3);
        verify(movementRepository).save(any(StockMovement.class));
    }

    @Test
    void purchaseDeliveryNoteAddsStock() {
        var document = confirmed(TipoDocumento.ALBARAN_COMPRA, 2);
        var line = document.getLineas().getFirst();
        var stock = new StockLevel(line.getProductoId(), document.getAlmacenId());
        when(stockRepository.findByProductIdAndWarehouseId(
                line.getProductoId(), document.getAlmacenId())).thenReturn(Optional.of(stock));
        when(movementRepository.existsByDocumentId(document.getId())).thenReturn(false);

        gateway.confirm(document);

        assertThat(stock.getQuantity()).isEqualTo(2);
    }

    @Test
    void salesCreditInvoiceRestoresStock() {
        var document = confirmed(TipoDocumento.RECTIFICATIVA_VENTA, 2);
        var line = document.getLineas().getFirst();
        var stock = new StockLevel(line.getProductoId(), document.getAlmacenId());
        stock.apply(-2);
        when(stockRepository.findByProductIdAndWarehouseId(
                line.getProductoId(), document.getAlmacenId())).thenReturn(Optional.of(stock));
        when(movementRepository.existsByDocumentId(document.getId())).thenReturn(false);

        gateway.confirm(document);

        assertThat(stock.getQuantity()).isZero();
    }

    @Test
    void cancellationAppliesTheOppositeQuantityOnce() {
        var document = confirmed(TipoDocumento.TICKET, 4);
        var original = StockMovement.document(
                document.getLineas().getFirst().getProductoId(),
                document.getAlmacenId(),
                document.getStockUserId(),
                document.getId(),
                StockMovementType.TICKET,
                -4,
                Instant.parse("2026-06-08T12:00:00Z"));
        var stock = new StockLevel(original.getProductId(), original.getWarehouseId());
        stock.apply(-4);
        when(movementRepository.findByDocumentIdAndCompensationOfIdIsNull(document.getId()))
                .thenReturn(java.util.List.of(original));
        when(movementRepository.existsByCompensationOfId(original.getId())).thenReturn(false);
        when(stockRepository.findByProductIdAndWarehouseId(
                original.getProductId(), original.getWarehouseId())).thenReturn(Optional.of(stock));

        assertThat(gateway.cancel(document)).isTrue();

        assertThat(stock.getQuantity()).isZero();
    }

    private Documento confirmed(TipoDocumento type, int quantity) {
        var userId = UUID.randomUUID();
        var document = new Documento(
                UUID.randomUUID(), UUID.randomUUID(), type,
                LocalDate.of(2026, 6, 8), userId, BigDecimal.ZERO);
        document.addLine(new DocumentLineCommand(
                UUID.randomUUID(), quantity, "P-1", "Producto", "VENTA",
                BigDecimal.TEN, BigDecimal.ZERO, true, "IVA", new BigDecimal("21"))
                .toEntity(document, 1));
        document.confirm("NUM-1", userId, Instant.parse("2026-06-08T12:00:00Z"), true);
        return document;
    }
}
