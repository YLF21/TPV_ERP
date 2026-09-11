package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.shared.api.PagedResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageRequest;

class CustomerDocumentReportServiceTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 9, 9);
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-09T10:00:00Z");

    private final CommercialDocumentRepository documents = mock(CommercialDocumentRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final DocumentAttributionResolver attributions = mock(DocumentAttributionResolver.class);
    private final DocumentMemberBalanceResolver balances = mock(DocumentMemberBalanceResolver.class);
    private final CustomerDocumentReportQueryRepository orderedReports = mock(CustomerDocumentReportQueryRepository.class);
    private final TicketReportService tickets = new TicketReportService(documents, organization,
            customers, attributions, mock(DocumentRelationRepository.class),
            mock(RefundTenderRepository.class), mock(SalesInvoiceRectificationRepository.class), balances, orderedReports);
    private final DocumentReportService reports = new DocumentReportService(documents, organization,
            customers, mock(SupplierRepository.class), mock(WarehouseRepository.class),
            attributions, mock(RefundTenderRepository.class), balances, orderedReports);

    CustomerDocumentReportServiceTest() {
        var store = mock(Store.class);
        var company = mock(Company.class);
        when(store.getId()).thenReturn(STORE_ID);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(attributions.resolve(anyCollection())).thenReturn(Map.of());
        when(balances.resolve(anyCollection())).thenReturn(DocumentMemberBalanceResolver.Resolution.empty());
    }

    @ParameterizedTest
    @EnumSource(ReportKind.class)
    void rejectsMissingOrForeignCompanyCustomerBeforeReadingDocuments(ReportKind kind) {
        when(customers.findByIdAndCompanyId(CUSTOMER_ID, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> query(kind, 50, null, CUSTOMER_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Cliente no encontrado");

        verify(customers).findByIdAndCompanyId(CUSTOMER_ID, COMPANY_ID);
        verifyNoInteractions(documents);
    }

    @ParameterizedTest
    @EnumSource(ReportKind.class)
    void filtersBeforePagingAndRetainsCustomerScopeOnTheNextPage(ReportKind kind) {
        allowCustomer();
        var first = document(kind.types().iterator().next());
        var second = document(kind.types().iterator().next());
        var pageRequest = PageRequest.of(0, 2);
        when(documents.findCustomerReportDocuments(STORE_ID, CUSTOMER_ID, kind.types(),
                null, null, null, pageRequest)).thenReturn(List.of(first, second));

        var result = query(kind, 1, null, CUSTOMER_ID);
        var expectedCursor = DATE + "|" + OCCURRED_AT + "|" + first.getId();
        assertThat(result.items()).hasSize(1);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(expectedCursor);

        when(documents.findCustomerReportDocuments(STORE_ID, CUSTOMER_ID, kind.types(),
                DATE, OCCURRED_AT, first.getId().toString(), pageRequest)).thenReturn(List.of(second));
        var next = query(kind, 1, result.nextCursor(), CUSTOMER_ID);
        assertThat(next.items()).hasSize(1);
        assertThat(next.hasMore()).isFalse();
        assertThat(next.nextCursor()).isNull();
        verify(documents).findCustomerReportDocuments(STORE_ID, CUSTOMER_ID, kind.types(),
                DATE, OCCURRED_AT, first.getId().toString(), pageRequest);
        verify(documents, never()).findReportDocuments(any(), anyCollection(), any());
        verify(documents, never()).findCustomerReceivables(any());
    }

    @ParameterizedTest
    @EnumSource(ReportKind.class)
    void allowsInactiveCustomerHistoryAndKeepsTheServerLimit(ReportKind kind) {
        allowCustomer(); // The customer is inactive by default; activity is not required for history.
        var result = query(kind, 100000, null, CUSTOMER_ID);
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        verify(documents).findCustomerReportDocuments(STORE_ID, CUSTOMER_ID, kind.types(),
                null, null, null, PageRequest.of(0, 501));
    }

    @Test
    void noSalesDocumentPermissionStillReturnsNoSalesDocuments() {
        allowCustomer();
        assertThat(reports.listInvoices(50, null, false, false, CUSTOMER_ID).items()).isEmpty();
        assertThat(reports.listDeliveryNotes(50, null, false, false, CUSTOMER_ID).items()).isEmpty();
        verifyNoInteractions(documents);
    }

    @ParameterizedTest
    @EnumSource(ReportKind.class)
    void preservesTheFilteredGlobalOrderAndOpaqueCursor(ReportKind kind) {
        allowCustomer();
        var filter = new CustomerDocumentReportFilter("FV", DocumentStatus.CONFIRMADO,
                DATE, DATE, "total", "asc");
        var first = document(kind.types().iterator().next());
        var second = document(kind.types().iterator().next());
        var ids = List.of(second.getId(), first.getId());
        when(orderedReports.findPage(STORE_ID, CUSTOMER_ID, kind.types(), filter, "cdr1.previous", 2))
                .thenReturn(new CustomerDocumentReportQueryRepository.Page(ids, "cdr1.next", true));
        when(documents.loadCustomerReportDocumentsByIds(STORE_ID, CUSTOMER_ID, kind.types(), ids))
                .thenReturn(List.of(second, first));

        var result = query(kind, 2, "cdr1.previous", CUSTOMER_ID, filter);

        assertThat(result.items()).extracting(item -> item instanceof TicketReportView ticket
                        ? ticket.id() : ((DocumentReportView) item).id()).containsExactlyElementsOf(ids);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isEqualTo("cdr1.next");
        verify(documents).loadCustomerReportDocumentsByIds(STORE_ID, CUSTOMER_ID, kind.types(), ids);
        verify(documents, never()).findCustomerReportDocuments(any(), any(), anyCollection(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(ReportKind.class)
    void advancedQueriesRequireACustomerAndCannotFallBackToAllDocuments(ReportKind kind) {
        var filter = new CustomerDocumentReportFilter(null, null, null, null, "number", "asc");
        assertThatThrownBy(() -> query(kind, 50, null, null, filter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El cliente es obligatorio para filtrar documentos");
        verifyNoInteractions(customers, documents, orderedReports);
    }

    @ParameterizedTest
    @EnumSource(ReportKind.class)
    void generalDateRangesFilterBeforePagingAndPreserveLegacyCursorFormat(ReportKind kind) {
        var from = DATE.minusMonths(1);
        var filter = new CustomerDocumentReportFilter(null, null, from, DATE, null, null);
        var first = document(kind.types().iterator().next());
        var second = document(kind.types().iterator().next());
        var pageable = PageRequest.of(0, 2);
        when(documents.findReportDocumentsInRange(STORE_ID, kind.types(), from, DATE,
                null, null, null, pageable)).thenReturn(List.of(first, second));

        var result = query(kind, 1, null, null, filter);
        assertThat(result.items()).hasSize(1);
        assertThat(result.nextCursor()).isEqualTo(DATE + "|" + OCCURRED_AT + "|" + first.getId());
        assertThat(result.hasMore()).isTrue();
        query(kind, 1, result.nextCursor(), null, filter);
        verify(documents).findReportDocumentsInRange(STORE_ID, kind.types(), from, DATE,
                DATE, OCCURRED_AT, first.getId().toString(), pageable);
        verify(documents, never()).findReportDocuments(any(), anyCollection(), any());
        verifyNoInteractions(orderedReports);
    }

    private void allowCustomer() {
        when(customers.findByIdAndCompanyId(CUSTOMER_ID, COMPANY_ID))
                .thenReturn(Optional.of(mock(Customer.class)));
    }

    private PagedResult<?> query(ReportKind kind, int limit, String cursor, UUID customerId) {
        return query(kind, limit, cursor, customerId, null);
    }

    private PagedResult<?> query(ReportKind kind, int limit, String cursor, UUID customerId,
            CustomerDocumentReportFilter filter) {
        return switch (kind) {
            case TICKETS -> tickets.list(limit, cursor, customerId, filter);
            case INVOICES -> reports.listInvoices(limit, cursor, true, false, customerId, filter);
            case DELIVERY_NOTES -> reports.listDeliveryNotes(limit, cursor, true, false, customerId, filter);
        };
    }

    private CommercialDocument document(CommercialDocumentType type) {
        var document = mock(CommercialDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getTipo()).thenReturn(type);
        when(document.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(document.getClienteId()).thenReturn(CUSTOMER_ID);
        when(document.getFecha()).thenReturn(DATE);
        when(document.getOperationalOccurredAt()).thenReturn(OCCURRED_AT);
        when(document.getTotal()).thenReturn(new BigDecimal("10.00"));
        when(document.getBaseTotal()).thenReturn(new BigDecimal("10.00"));
        when(document.getImpuestoTotal()).thenReturn(BigDecimal.ZERO);
        when(document.getDescuentoGlobal()).thenReturn(BigDecimal.ZERO);
        when(document.getPendingTotal()).thenReturn(BigDecimal.ZERO);
        return document;
    }

    private enum ReportKind {
        TICKETS, INVOICES, DELIVERY_NOTES;

        EnumSet<CommercialDocumentType> types() {
            return switch (this) {
                case TICKETS -> EnumSet.of(CommercialDocumentType.TICKET);
                case INVOICES -> EnumSet.of(CommercialDocumentType.FACTURA_VENTA,
                        CommercialDocumentType.RECTIFICATIVA_VENTA);
                case DELIVERY_NOTES -> EnumSet.of(CommercialDocumentType.ALBARAN_VENTA);
            };
        }
    }
}
