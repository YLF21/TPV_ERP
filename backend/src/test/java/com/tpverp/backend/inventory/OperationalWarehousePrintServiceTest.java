package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.document.template.OperationalDocumentJasperRenderer;
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
import org.mockito.ArgumentCaptor;

class OperationalWarehousePrintServiceTest {

    private static final Map<String, String> ADDRESS = Map.of("linea1", "Calle Ficticia 1", "ciudad", "Las Palmas",
            "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
    private final Company company = new Company("B00000000", "Empresa ficticia", ADDRESS);
    private final Store store = new Store(company, "001", "Tienda ficticia", ADDRESS,
            UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
    private final UUID productId = UUID.randomUUID();
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final StockSalesHistoryService history = mock(StockSalesHistoryService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final OperationalDocumentJasperRenderer renderer = mock(OperationalDocumentJasperRenderer.class);

    @Test
    void preservesRequestedColumnsFiltersOrderAndPersistedAmountsInPdfData() {
        var service = service();
        var from = LocalDate.of(2026, 9, 1);
        var to = LocalDate.of(2026, 9, 19);
        when(history.history(productId, from, to)).thenReturn(List.of(
                row("T-001", DocumentStatus.CONFIRMADO, "10.10"),
                row("T-002", DocumentStatus.ANULADO, "999.99"),
                row("T-003", DocumentStatus.CONFIRMADO, "-24.69")));

        service.salesHistory(productId, new OperationalWarehousePrintService.HistoryPrintCommand(
                from, to, List.of("total", "document", "customer", "discount"), "total", "asc", "CONFIRMADO"));

        var data = capturedData();
        assertThat(data.path("visibleColumns").toString()).isEqualTo("[\"total\",\"document\",\"customer\",\"discount\"]");
        assertThat(data.path("lines")).hasSize(2);
        assertThat(data.at("/lines/0/document").asText()).isEqualTo("T-003");
        assertThat(data.at("/lines/0/total").decimalValue()).isEqualByComparingTo("-24.69");
        assertThat(data.at("/lines/0/quantity").decimalValue()).isEqualByComparingTo("-2.125");
        assertThat(data.at("/lines/0/unitPrice").decimalValue()).isEqualByComparingTo("12.345");
        assertThat(data.at("/lines/0/discount").decimalValue()).isEqualByComparingTo("7.5");
        assertThat(data.at("/lines/0/date").asText()).isEqualTo("2026-09-19T09:00:00Z");
        assertThat(data.at("/lines/0/occurredAt").asText()).isEqualTo("2026-09-19T09:00:00Z");
        assertThat(data.at("/lines/0/occurredAtLabel").asText()).isEqualTo("19/09/2026 10:00");
        assertThat(data.at("/issuer/details").asText()).contains("PROD-001", "Producto ficticio")
                .doesNotContain(productId.toString());
        assertThat(data.at("/document/concept").asText()).contains("01/09/2026", "19/09/2026", "CONFIRMADO");
        verify(products).findAllByStoreIdAndIdIn(store.getId(), List.of(productId));
        verify(history).history(productId, from, to);
    }

    @Test
    void emptyHistoryKeepsDefaultColumnsAndProductIdentity() {
        var service = service();
        when(history.history(productId, null, null)).thenReturn(List.of());
        service.salesHistory(productId, null);
        var data = capturedData();
        assertThat(data.path("lines")).isEmpty();
        assertThat(data.path("visibleColumns").toString())
                .isEqualTo("[\"occurredAt\",\"document\",\"customer\",\"quantity\",\"unitPrice\",\"total\"]");
        assertThat(data.at("/issuer/details").asText()).contains("PROD-001", "Producto ficticio");
        assertThat(data.at("/document/concept").asText()).contains("Estado: Todos");
    }

    @Test
    void rejectsUnknownColumnsAndProductOutsideCurrentStore() {
        var service = service();
        assertThatThrownBy(() -> service.salesHistory(productId,
                new OperationalWarehousePrintService.HistoryPrintCommand(null, null, List.of("secret"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("sales_history_column_not_allowed");
        when(history.history(productId, null, null)).thenReturn(List.of());
        when(products.findAllByStoreIdAndIdIn(store.getId(), List.of(productId))).thenReturn(List.of());
        assertThatThrownBy(() -> service.salesHistory(productId, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Producto no encontrado");
    }

    private OperationalWarehousePrintService service() {
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        var product = mock(Product.class);
        when(product.getCode()).thenReturn("PROD-001");
        when(product.getName()).thenReturn("Producto ficticio");
        when(products.findAllByStoreIdAndIdIn(store.getId(), List.of(productId))).thenReturn(List.of(product));
        return new OperationalWarehousePrintService(new ObjectMapper(), organization,
                mock(WarehouseInputService.class), mock(WarehouseOutputService.class), history, renderer, products);
    }

    private ObjectNode capturedData() {
        var data = ArgumentCaptor.forClass(ObjectNode.class);
        verify(renderer).render(eq(DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO),
                eq(DocumentTemplateFormat.A4), data.capture(), eq("historial-ventas-producto.pdf"));
        return data.getValue();
    }

    private StockSalesHistoryRow row(String number, DocumentStatus status, String total) {
        return new StockSalesHistoryRow(UUID.randomUUID(), CommercialDocumentType.TICKET, number, status,
                Instant.parse("2026-09-19T09:00:00Z"), UUID.randomUUID(), "Cliente ficticio",
                new BigDecimal("-2.125"), new BigDecimal("12.345"), new BigDecimal("7.5"), new BigDecimal(total),
                UUID.randomUUID(), "Usuario", store.getId(), "Tienda ficticia", UUID.randomUUID(), "GENERAL");
    }
}
