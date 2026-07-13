package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class StockSalesHistoryServiceTest {

    @Test
    void mapsDocumentLineHistoryForCurrentStoreAndNormalizesDateRange() {
        var organization = mock(CurrentOrganization.class);
        var documents = mock(CommercialDocumentRepository.class);
        var store = store();
        var productId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var occurredAt = Instant.parse("2026-07-10T12:15:00Z");
        var projection = mock(CommercialDocumentRepository.SalesHistoryProjection.class);
        when(organization.currentStore()).thenReturn(store);
        when(documents.findProductSalesHistory(
                store.getId(),
                productId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 10)))
                .thenReturn(List.of(projection));
        when(projection.getDocumentId()).thenReturn(documentId);
        when(projection.getDocumentType()).thenReturn("RECTIFICATIVA_VENTA");
        when(projection.getDocumentNumber()).thenReturn("FRV-2026-000004");
        when(projection.getStatus()).thenReturn("ANULADO");
        when(projection.getOccurredAt()).thenReturn(occurredAt);
        when(projection.getCustomerId()).thenReturn(customerId);
        when(projection.getCustomerName()).thenReturn("Cliente SL");
        when(projection.getQuantity()).thenReturn(new BigDecimal("-2.000"));
        when(projection.getUnitPrice()).thenReturn(new BigDecimal("3.50"));
        when(projection.getDiscountPercent()).thenReturn(new BigDecimal("10.00"));
        when(projection.getLineTotal()).thenReturn(new BigDecimal("-6.30"));
        when(projection.getUserId()).thenReturn(userId);
        when(projection.getUserName()).thenReturn("Usuario Caja");
        when(projection.getStoreId()).thenReturn(store.getId());
        when(projection.getStoreName()).thenReturn(store.getNombreEfectivo());
        when(projection.getWarehouseId()).thenReturn(warehouseId);
        when(projection.getWarehouseName()).thenReturn("GENERAL");

        var rows = new StockSalesHistoryService(organization, documents).history(
                productId, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 1));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.documentId()).isEqualTo(documentId);
            assertThat(row.documentType()).isEqualTo(CommercialDocumentType.RECTIFICATIVA_VENTA);
            assertThat(row.status()).isEqualTo(DocumentStatus.ANULADO);
            assertThat(row.occurredAt()).isEqualTo(occurredAt);
            assertThat(row.customerName()).isEqualTo("Cliente SL");
            assertThat(row.quantity()).isEqualByComparingTo("-2.000");
            assertThat(row.unitPrice()).isEqualByComparingTo("3.50");
            assertThat(row.discountPercent()).isEqualByComparingTo("10.00");
            assertThat(row.lineTotal()).isEqualByComparingTo("-6.30");
            assertThat(row.userName()).isEqualTo("Usuario Caja");
            assertThat(row.storeName()).isEqualTo("Store");
            assertThat(row.warehouseName()).isEqualTo("GENERAL");
        });
        verify(documents).findProductSalesHistory(
                store.getId(),
                productId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 10));
    }

    @Test
    void repositoryQueryIncludesAllSaleTypesAndCancelledDocumentsButNotDrafts()
            throws NoSuchMethodException {
        var method = CommercialDocumentRepository.class.getDeclaredMethod(
                "findProductSalesHistory", UUID.class, UUID.class, LocalDate.class, LocalDate.class);
        var query = method.getAnnotation(Query.class);

        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains("'TICKET'", "'FACTURA_VENTA'", "'ALBARAN_VENTA'", "'RECTIFICATIVA_VENTA'")
                .contains("document.estado <> 'BORRADOR'")
                .doesNotContain("document.estado <> 'ANULADO'")
                .contains("document.tienda_id = :storeId")
                .contains("line.producto_id = :productId")
                .contains("cast(:fromDate as date)", "cast(:toDate as date)")
                .contains("document.fecha::timestamp at time zone store.timezone")
                .doesNotContain("then coalesce(document.anulado_en")
                .contains("order by document.fecha desc");
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "001",
                "Store",
                address,
                "hash",
                "Atlantic/Canary",
                "EUR",
                "es-ES");
    }
}
