package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.tpverp.backend.inventory.SaasProductSalesHistoryTestData.*;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaasProductSalesHistoryServiceTest {
    @Test void validatesFiltersWithoutRemovingLeadingZerosOrAcceptingArbitrarySorts() {
        var query = SaasProductSalesHistoryService.query(new SaasProductSalesHistoryApi.Filters(
                LocalDate.parse("2026-09-19"), LocalDate.parse("2026-09-01"), "ANULADO", List.of(), "total", "asc"));
        assertThat(query).containsEntry("from", LocalDate.parse("2026-09-01")).containsEntry("to", LocalDate.parse("2026-09-19"));
        assertThat(SaasProductSalesHistoryService.query(null)).containsEntry("sortBy", "occurredAt");
        assertThatThrownBy(() -> SaasProductSalesHistoryService.query(new SaasProductSalesHistoryApi.Filters(null, null, null, List.of(), "total;drop", "asc")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void remoteHistoryUsesOnlyTheProductOfTheCurrentStore() {
        var organization = mock(CurrentOrganization.class); var products = mock(ProductRepository.class);
        var client = mock(SaasProductSalesHistoryClient.class); var exports = mock(SaasProductSalesHistoryExports.class);
        var company = mock(Company.class); var store = mock(Store.class); var product = mock(Product.class);
        var productId = UUID.randomUUID(); when(organization.currentCompany()).thenReturn(company); when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(STORE); when(store.getEmpresa()).thenReturn(company); when(company.getId()).thenReturn(COMPANY);
        when(product.getCode()).thenReturn("00042"); when(products.findWithIdentifiersByStoreIdAndId(STORE, productId)).thenReturn(Optional.of(product));
        when(client.query(eq("page"), eq(COMPANY), eq(STORE), eq("00042"), anyMap())).thenReturn(response());
        var service = new SaasProductSalesHistoryService(organization, products, client, exports);
        assertThat(service.page(productId, null, 100, null).path("productCode").asText()).isEqualTo("00042");
        when(products.findWithIdentifiersByStoreIdAndId(STORE, productId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.page(productId, null, 100, null)).isInstanceOf(java.util.NoSuchElementException.class);
        verify(client, times(1)).query(anyString(), any(), any(), anyString(), anyMap());
    }
    @Test void validatesCompositeKeysDecimalStringsAndPagination() {
        SaasProductSalesHistoryService.validateResponse(response(), 100, "00042", null);
        var invalid = response(); invalid.withArray("items").add(invalid.path("items").get(0).deepCopy());
        assertThatThrownBy(() -> SaasProductSalesHistoryService.validateResponse(invalid, 100, "00042", null)).hasMessage("SAAS_PRODUCT_HISTORY_INVALID_RESPONSE");
        var floatAmount = response(); ((com.fasterxml.jackson.databind.node.ObjectNode) floatAmount.path("items").get(0)).put("lineTotal", 3.75);
        assertThatThrownBy(() -> SaasProductSalesHistoryService.validateResponse(floatAmount, 100, "00042", null)).hasMessage("SAAS_PRODUCT_HISTORY_INVALID_RESPONSE");
        var brokenCursor = response(); brokenCursor.put("hasMore", true); brokenCursor.put("nextCursor", "previous");
        assertThatThrownBy(() -> SaasProductSalesHistoryService.validateResponse(brokenCursor, 100, "00042", "previous")).hasMessage("SAAS_PRODUCT_HISTORY_INVALID_RESPONSE");
    }
    @Test void rejectsUnknownStoresAndInconsistentSummaryWithoutLocalFallback() {
        var invalid = response(); ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("items").get(0)).put("storeId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> SaasProductSalesHistoryService.validateResponse(invalid, 100, "00042", null)).hasMessage("SAAS_PRODUCT_HISTORY_INVALID_RESPONSE");
        var totals = response(); ((com.fasterxml.jackson.databind.node.ObjectNode) totals.path("totals").get(0)).put("netQuantity", "100");
        assertThatThrownBy(() -> SaasProductSalesHistoryService.validateResponse(totals, 100, "00042", null)).hasMessage("SAAS_PRODUCT_HISTORY_INVALID_RESPONSE");
    }
}
