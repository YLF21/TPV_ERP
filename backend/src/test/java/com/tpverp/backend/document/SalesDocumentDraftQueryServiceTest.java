package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class SalesDocumentDraftQueryServiceTest {

    @Mock private CommercialDocumentRepository documents;
    @Mock private CurrentOrganization organization;
    @Mock private CustomerRepository customers;
    @Mock private Store store;

    private SalesDocumentDraftQueryService service;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        service = new SalesDocumentDraftQueryService(documents, organization, customers);
    }

    @Test
    void listsOnlyTheRequestedSaleDraftTypesWithCustomerNames() {
        var customerId = UUID.randomUUID();
        var document = saleDraft(CommercialDocumentType.FACTURA_VENTA, customerId);
        var customer = mock(Customer.class);
        when(customer.getId()).thenReturn(customerId);
        when(customer.getFiscalName()).thenReturn("Cliente Borrador SL");
        var allowed = EnumSet.of(CommercialDocumentType.FACTURA_VENTA);
        when(documents.findSalesDraftIds(storeId, allowed, PageRequest.of(0, 200)))
                .thenReturn(List.of(document.getId()));
        when(documents.findSalesDraftsWithLines(storeId, List.of(document.getId())))
                .thenReturn(List.of(document));
        when(customers.findAllById(java.util.Set.of(customerId)))
                .thenReturn(List.of(customer));

        var result = service.list(allowed);

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(document.getId());
            assertThat(value.type()).isEqualTo(CommercialDocumentType.FACTURA_VENTA);
            assertThat(value.customerName()).isEqualTo("Cliente Borrador SL");
            assertThat(value.total()).isEqualByComparingTo("20.00");
        });
        verify(documents).findSalesDraftIds(storeId, allowed, PageRequest.of(0, 200));
        verify(documents).findSalesDraftsWithLines(storeId, List.of(document.getId()));
    }

    @Test
    void loadsEditableLineMetadataAndRejectsAnUnauthorizedDocumentType() {
        var customerId = UUID.randomUUID();
        var document = saleDraft(CommercialDocumentType.ALBARAN_VENTA, customerId);
        var line = document.getLineas().getFirst();
        line.assignTemporaryOverrides(true, true);
        when(documents.findByIdAndTiendaId(document.getId(), storeId))
                .thenReturn(Optional.of(document));

        var detail = service.detail(
                document.getId(), EnumSet.of(CommercialDocumentType.ALBARAN_VENTA));

        assertThat(detail.id()).isEqualTo(document.getId());
        assertThat(detail.version()).isEqualTo(document.getVersion());
        assertThat(detail.lines()).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(line.getId());
            assertThat(value.temporaryNameOverride()).isTrue();
            assertThat(value.temporaryPriceOverride()).isTrue();
        });
        assertThatThrownBy(() -> service.detail(
                document.getId(), EnumSet.of(CommercialDocumentType.FACTURA_VENTA)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("documento no encontrado");
    }

    private CommercialDocument saleDraft(
            CommercialDocumentType type, UUID customerId) {
        var document = new CommercialDocument(
                storeId, UUID.randomUUID(), type, LocalDate.of(2026, 8, 12),
                UUID.randomUUID(), BigDecimal.ZERO);
        document.setParties(customerId, null, null);
        document.setDueDate(LocalDate.of(2026, 9, 11));
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-1", "Producto", null, new BigDecimal("20.00"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21")));
        return document;
    }
}
