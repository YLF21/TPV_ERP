package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.sync.SyncOutboxService;
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
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CurrentOrganization organization;
    @Mock
    private Company company;
    @Mock
    private SyncOutboxService syncOutbox;

    private InventoryDocumentGateway gateway;
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(company.getId()).thenReturn(companyId);
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(movementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        gateway = new InventoryDocumentGateway(
                stockRepository,
                movementRepository,
                productRepository,
                organization,
                new StockMovementSyncPublisher(syncOutbox),
                Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void salesDeliveryNoteRemovesStockAndRecordsDocumentMovement() {
        var document = confirmed(CommercialDocumentType.ALBARAN_VENTA, 3);
        var line = document.getLineas().getFirst();
        var stock = new StockLevel(line.getProductoId(), document.getAlmacenId());
        when(stockRepository.findByProductIdAndWarehouseId(
                line.getProductoId(), document.getAlmacenId())).thenReturn(Optional.of(stock));
        when(movementRepository.existsByDocumentId(document.getId())).thenReturn(false);

        assertThat(gateway.confirm(document)).isTrue();

        assertThat(stock.getQuantity()).isEqualByComparingTo("-3.000");
        verify(movementRepository).save(any(StockMovement.class));
        verify(syncOutbox).enqueue(any());
    }

    @Test
    void purchaseDeliveryNoteAddsStock() {
        var document = confirmed(CommercialDocumentType.ALBARAN_COMPRA, 2);
        var line = document.getLineas().getFirst();
        var stock = new StockLevel(line.getProductoId(), document.getAlmacenId());
        when(stockRepository.findByProductIdAndWarehouseId(
                line.getProductoId(), document.getAlmacenId())).thenReturn(Optional.of(stock));
        when(movementRepository.existsByDocumentId(document.getId())).thenReturn(false);

        gateway.confirm(document);

        assertThat(stock.getQuantity()).isEqualByComparingTo("2.000");
    }

    @Test
    void serviceLineDoesNotMoveStock() {
        var document = confirmed(CommercialDocumentType.ALBARAN_COMPRA, 2);
        var line = document.getLineas().getFirst();
        var product = org.mockito.Mockito.mock(Product.class);
        when(product.getProductType()).thenReturn(ProductType.SERVICE);
        when(productRepository.findById(line.getProductoId())).thenReturn(Optional.of(product));
        when(movementRepository.existsByDocumentId(document.getId())).thenReturn(false);

        assertThat(gateway.confirm(document)).isTrue();

        verify(stockRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
    }

    @Test
    void salesCreditInvoiceRestoresStock() {
        var document = confirmed(CommercialDocumentType.RECTIFICATIVA_VENTA, 2);
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
        var document = confirmed(CommercialDocumentType.TICKET, 4);
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
        verify(syncOutbox).enqueue(any());
    }

    private CommercialDocument confirmed(CommercialDocumentType type, int quantity) {
        var userId = UUID.randomUUID();
        var document = new CommercialDocument(
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
