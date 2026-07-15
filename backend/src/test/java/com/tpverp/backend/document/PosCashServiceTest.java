package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class PosCashServiceTest {

    @Test
    void chargeReturnsSnapshotOfTheConfirmedDocumentCreatedByDocumentService() {
        var documents = mock(DocumentService.class);
        var products = mock(ProductRepository.class);
        var taxes = mock(StoreTaxRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var paymentMethods = mock(PaymentMethodRepository.class);
        var organization = mock(CurrentOrganization.class);
        var authentication = mock(Authentication.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var product = mock(Product.class);
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var warehouse = Warehouse.general(storeId);
        var tax = new StoreTax(storeId, BigDecimal.valueOf(21), true);
        var cash = new PaymentMethod(companyId, "EFECTIVO", true);
        var issuedAt = Instant.parse("2026-07-15T10:15:30Z");
        var ticket = confirmedTicket(storeId, warehouse.getId(), productId, cash, issuedAt);
        var quoted = mock(CommercialDocument.class);
        when(quoted.getTotal()).thenReturn(new BigDecimal("7.00"));
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(warehouses.findByStoreIdAndPredeterminadoTrue(storeId))
                .thenReturn(Optional.of(warehouse));
        when(product.getId()).thenReturn(productId);
        when(product.getStoreId()).thenReturn(storeId);
        when(product.getTaxId()).thenReturn(tax.getId());
        when(product.getCode()).thenReturn("REQUEST-CODE");
        when(product.getName()).thenReturn("Request name");
        when(product.getSalePrice()).thenReturn(new BigDecimal("99.00"));
        when(product.isTaxesIncluded()).thenReturn(true);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(taxes.findById(tax.getId())).thenReturn(Optional.of(tax));
        when(paymentMethods.findByEmpresaIdAndNombreAndActivoTrue(companyId, "EFECTIVO"))
                .thenReturn(Optional.of(cash));
        when(documents.quoteTicket(any(DocumentCommand.class), any())).thenReturn(quoted);
        when(documents.createTicket(any(DocumentCommand.class), anyList(), any()))
                .thenReturn(ticket);
        var service = new PosCashService(
                documents, products, taxes, warehouses, paymentMethods, organization);
        var sale = new PosCashController.SaleRequest(
                null, List.of(new PosCashController.LineRequest(
                        productId, BigDecimal.valueOf(2), BigDecimal.ZERO)));

        var result = service.charge(new PosCashController.CashRequest(
                UUID.randomUUID(), sale, BigDecimal.TEN, new BigDecimal("7.00")),
                authentication);

        assertThat(result.printTicket()).isNotNull();
        assertThat(result.printTicket().documentId()).isEqualTo(ticket.getId());
        assertThat(result.printTicket().documentNumber()).isEqualTo("001-260715-000001");
        assertThat(result.printTicket().issuedAt()).isEqualTo(issuedAt);
        assertThat(result.printTicket().lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Authoritative Cafe");
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.price()).isEqualByComparingTo("3.50");
            assertThat(line.total()).isEqualByComparingTo("7.00");
        });
        assertThat(result.printTicket().payments()).singleElement().satisfies(payment -> {
            assertThat(payment.method()).isEqualTo("EFECTIVO");
            assertThat(payment.amount()).isEqualByComparingTo("7.00");
        });
        assertThat(result.printTicket().total()).isEqualByComparingTo("7.00");
        verify(documents).createTicket(any(DocumentCommand.class), anyList(),
                org.mockito.ArgumentMatchers.same(authentication));
    }

    private static CommercialDocument confirmedTicket(
            UUID storeId,
            UUID warehouseId,
            UUID productId,
            PaymentMethod cash,
            Instant issuedAt) {
        var ticket = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 15), UUID.randomUUID(), BigDecimal.ZERO);
        ticket.addLine(new DocumentLine(
                ticket, productId, 1, BigDecimal.valueOf(2), "AUTHORITATIVE-CODE",
                "Authoritative Cafe", null, new BigDecimal("3.50"), BigDecimal.ZERO,
                true, "IVA", BigDecimal.valueOf(21)));
        ticket.confirm("001-260715-000001", UUID.randomUUID(), issuedAt, false);
        ticket.addPayment(new DocumentPayment(
                ticket, cash, 1, new BigDecimal("7.00"), true,
                BigDecimal.TEN, new BigDecimal("3.00"), issuedAt));
        return ticket;
    }
}
