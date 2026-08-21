package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class TicketReportServiceTest {

    @Test
    void reportsCustomerInvoiceAndFullInvoiceReturnWithoutChangingTheTicketStatus() {
        var fixture = fixture(new BigDecimal("100.00"));
        var customer = mock(Customer.class);
        when(customer.getId()).thenReturn(fixture.customerId());
        when(customer.getClientId()).thenReturn("C-0005");
        when(customer.getFiscalName()).thenReturn("CLIENTE CINCO");
        when(fixture.customers().findByCompanyIdAndIdIn(
                eq(fixture.companyId()), anyCollection())).thenReturn(List.of(customer));

        var invoiceId = UUID.randomUUID();
        var invoice = related(
                fixture.ticketId(), invoiceId, "FV-2026-00042",
                new BigDecimal("100.00"), CommercialDocumentType.FACTURA_VENTA);
        var rectificationId = UUID.randomUUID();
        var rectification = related(
                invoiceId, rectificationId, "RV-2026-00009",
                new BigDecimal("-100.00"), CommercialDocumentType.RECTIFICATIVA_VENTA);
        when(fixture.relations().findActiveRelatedDocuments(
                eq(fixture.storeId()), anyCollection(), eq(DocumentRelationType.FACTURA_DE)))
                .thenReturn(List.of(invoice));
        when(fixture.relations().findActiveRelatedDocuments(
                eq(fixture.storeId()), anyCollection(), eq(DocumentRelationType.RECTIFICA)))
                .thenReturn(List.of(rectification));
        var metadata = mock(SalesInvoiceRectification.class);
        when(metadata.getDocumentId()).thenReturn(rectificationId);
        when(metadata.isAffectsStock()).thenReturn(true);
        when(fixture.invoiceRectifications().findByDocumentIdIn(anyCollection()))
                .thenReturn(List.of(metadata));

        var result = fixture.service().list(500, null);

        assertThat(result.items()).singleElement().satisfies(view -> {
            assertThat(view.customerCode()).isEqualTo("C-0005");
            assertThat(view.customerName()).isEqualTo("CLIENTE CINCO");
            assertThat(view.invoiceNumber()).isEqualTo("FV-2026-00042");
            assertThat(view.lifecycleStatus()).isEqualTo(TicketReportLifecycleStatus.RETURNED);
            assertThat(view.effectiveTotal()).isEqualByComparingTo("0.00");
            assertThat(view.estado()).isEqualTo(DocumentStatus.CONFIRMADO);
        });
    }

    @Test
    void reportsTheRealRefundMethodsForANegativeTicket() {
        var fixture = fixture(new BigDecimal("-25.00"));
        when(fixture.customers().findByCompanyIdAndIdIn(
                eq(fixture.companyId()), anyCollection())).thenReturn(List.of());
        var cash = refundTender(fixture.ticket(), RefundTenderType.CASH);
        var card = refundTender(fixture.ticket(), RefundTenderType.CARD);
        when(fixture.refundTenders().findAllByRefundDocumentIds(
                eq(fixture.storeId()), anyCollection()))
                .thenReturn(List.of(cash, card));

        var result = fixture.service().list(500, null);

        assertThat(result.items()).singleElement().satisfies(view -> {
            assertThat(view.lifecycleStatus()).isEqualTo(TicketReportLifecycleStatus.RETURNED);
            assertThat(view.refundMethods()).containsExactly(
                    RefundTenderType.CASH, RefundTenderType.CARD);
            assertThat(view.paymentMethods()).isEmpty();
            assertThat(view.effectiveTotal()).isEqualByComparingTo("-25.00");
        });
    }

    @Test
    void distinguishesAPartialInvoiceReturn() {
        var fixture = fixture(new BigDecimal("100.00"));
        when(fixture.customers().findByCompanyIdAndIdIn(
                eq(fixture.companyId()), anyCollection())).thenReturn(List.of());
        var invoiceId = UUID.randomUUID();
        var invoice = related(
                fixture.ticketId(), invoiceId, "FV-2026-00043",
                new BigDecimal("100.00"), CommercialDocumentType.FACTURA_VENTA);
        var rectificationId = UUID.randomUUID();
        var rectification = related(
                invoiceId, rectificationId, "RV-2026-00010",
                new BigDecimal("-40.00"), CommercialDocumentType.RECTIFICATIVA_VENTA);
        when(fixture.relations().findActiveRelatedDocuments(
                eq(fixture.storeId()), anyCollection(), eq(DocumentRelationType.FACTURA_DE)))
                .thenReturn(List.of(invoice));
        when(fixture.relations().findActiveRelatedDocuments(
                eq(fixture.storeId()), anyCollection(), eq(DocumentRelationType.RECTIFICA)))
                .thenReturn(List.of(rectification));
        var metadata = mock(SalesInvoiceRectification.class);
        when(metadata.getDocumentId()).thenReturn(rectificationId);
        when(metadata.isAffectsStock()).thenReturn(true);
        when(fixture.invoiceRectifications().findByDocumentIdIn(anyCollection()))
                .thenReturn(List.of(metadata));

        assertThat(fixture.service().list(500, null).items()).singleElement()
                .satisfies(view -> {
                    assertThat(view.lifecycleStatus())
                            .isEqualTo(TicketReportLifecycleStatus.PARTIALLY_RETURNED);
                    assertThat(view.effectiveTotal()).isEqualByComparingTo("60.00");
                });
    }

    @Test
    void excludesCancelledTicketsFromTheEffectiveTotal() {
        var fixture = fixture(new BigDecimal("100.00"));
        when(fixture.ticket().getEstado()).thenReturn(DocumentStatus.ANULADO);
        when(fixture.customers().findByCompanyIdAndIdIn(
                eq(fixture.companyId()), anyCollection())).thenReturn(List.of());

        assertThat(fixture.service().list(500, null).items()).singleElement()
                .satisfies(view -> {
                    assertThat(view.lifecycleStatus())
                            .isEqualTo(TicketReportLifecycleStatus.CANCELLED);
                    assertThat(view.effectiveTotal()).isEqualByComparingTo("0.00");
                });
    }

    @Test
    void reportsPersistedMemberBalanceAppliedAtCheckout() {
        var fixture = fixture(new BigDecimal("45.00"));
        when(fixture.customers().findByCompanyIdAndIdIn(
                eq(fixture.companyId()), anyCollection())).thenReturn(List.of());
        when(fixture.memberBalanceResolution().amountFor(fixture.ticket()))
                .thenReturn(new BigDecimal("5.00"));

        assertThat(fixture.service().list(500, null).items()).singleElement()
                .extracting(TicketReportView::saldoSocio)
                .isEqualTo(new BigDecimal("5.00"));
    }

    private static Fixture fixture(BigDecimal total) {
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        var store = mock(Store.class);
        var company = mock(Company.class);
        var ticket = mock(CommercialDocument.class);
        var documents = mock(CommercialDocumentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var attributions = mock(DocumentAttributionResolver.class);
        var relations = mock(DocumentRelationRepository.class);
        var refundTenders = mock(RefundTenderRepository.class);
        var invoiceRectifications = mock(SalesInvoiceRectificationRepository.class);
        var memberBalances = mock(DocumentMemberBalanceResolver.class);
        var memberBalanceResolution = mock(DocumentMemberBalanceResolver.Resolution.class);

        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(ticket.getId()).thenReturn(ticketId);
        when(ticket.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(ticket.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(ticket.getNumero()).thenReturn("001-260810-00005");
        when(ticket.getFecha()).thenReturn(LocalDate.of(2026, 8, 10));
        when(ticket.getBaseTotal()).thenReturn(total);
        when(ticket.getImpuestoTotal()).thenReturn(BigDecimal.ZERO);
        when(ticket.getTotal()).thenReturn(total);
        when(ticket.getDescuentoGlobal()).thenReturn(BigDecimal.ZERO);
        when(ticket.getClienteId()).thenReturn(customerId);
        when(ticket.getLineas()).thenReturn(List.of());
        when(ticket.getPagos()).thenReturn(List.of());
        when(ticket.getOperationalOccurredAt()).thenReturn(Instant.parse("2026-08-10T15:09:00Z"));
        when(documents.findReportDocuments(eq(storeId), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(ticket));
        when(attributions.resolve(anyCollection())).thenReturn(Map.of());
        when(relations.findActiveRelatedDocuments(
                eq(storeId), anyCollection(), eq(DocumentRelationType.FACTURA_DE)))
                .thenReturn(List.of());
        when(relations.findActiveRelatedDocuments(
                eq(storeId), anyCollection(), eq(DocumentRelationType.RECTIFICA)))
                .thenReturn(List.of());
        when(refundTenders.findAllByRefundDocumentIds(eq(storeId), anyCollection()))
                .thenReturn(List.of());
        when(memberBalances.resolve(anyCollection())).thenReturn(memberBalanceResolution);

        var service = new TicketReportService(
                documents, organization, customers, attributions, relations,
                refundTenders, invoiceRectifications, memberBalances);
        return new Fixture(
                service, ticket, ticketId, customerId, storeId, companyId,
                customers, relations, refundTenders, invoiceRectifications,
                memberBalanceResolution);
    }

    private static DocumentRelationRepository.RelatedDocument related(
            UUID originId,
            UUID documentId,
            String number,
            BigDecimal total,
            CommercialDocumentType type) {
        var relation = mock(DocumentRelationRepository.RelatedDocument.class);
        when(relation.getOriginId()).thenReturn(originId);
        when(relation.getDocumentId()).thenReturn(documentId);
        when(relation.getDocumentNumber()).thenReturn(number);
        when(relation.getDocumentTotal()).thenReturn(total);
        when(relation.getDocumentType()).thenReturn(type);
        return relation;
    }

    private static RefundTender refundTender(
            CommercialDocument ticket, RefundTenderType type) {
        var tender = mock(RefundTender.class);
        when(tender.getRefundDocument()).thenReturn(ticket);
        when(tender.getType()).thenReturn(type);
        return tender;
    }

    private record Fixture(
            TicketReportService service,
            CommercialDocument ticket,
            UUID ticketId,
            UUID customerId,
            UUID storeId,
            UUID companyId,
            CustomerRepository customers,
            DocumentRelationRepository relations,
            RefundTenderRepository refundTenders,
            SalesInvoiceRectificationRepository invoiceRectifications,
            DocumentMemberBalanceResolver.Resolution memberBalanceResolution) {
    }
}
