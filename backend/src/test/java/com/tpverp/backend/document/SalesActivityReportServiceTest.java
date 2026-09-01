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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SalesActivityReportServiceTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 8, 16);

    @Test
    void dailyDeduplicatesDerivedInvoiceAndReconcilesPaymentsForStoreAndUsers() {
        var fixture = fixture();
        var cashierA = UUID.randomUUID();
        var cashierB = UUID.randomUUID();
        var ticket = confirmed(
                fixture.store(), CommercialDocumentType.TICKET, "T-001", "60.00", cashierA);
        addPayment(fixture, ticket, "EFECTIVO", "60.00");
        var derivedInvoice = confirmed(
                fixture.store(), CommercialDocumentType.FACTURA_VENTA,
                "FV-100", "60.00", cashierB);
        var directInvoice = confirmed(
                fixture.store(), CommercialDocumentType.FACTURA_VENTA,
                "FV-101", "40.00", cashierB);
        addPayment(fixture, directInvoice, "TARJETA", "40.00");
        var refund = confirmed(
                fixture.store(), CommercialDocumentType.TICKET,
                "T-003", "-10.00", cashierA);
        var refundTender = new RefundTender(
                refund, RefundTenderType.CASH, new BigDecimal("10.00"),
                null, null, null, start(REPORT_DATE).plusSeconds(600));
        var cancelled = confirmed(
                fixture.store(), CommercialDocumentType.TICKET, "T-002", "15.00", cashierA);
        cancelled.cancel(cashierA, start(REPORT_DATE).plusSeconds(1200), "Prueba");

        var issued = List.of(ticket, derivedInvoice, directInvoice, refund, cancelled);
        when(fixture.documents().findAllByTiendaIdAndFecha(
                fixture.store().getId(), REPORT_DATE)).thenReturn(issued);
        when(fixture.relations().findDerivedSalesInvoiceIds(
                fixture.store().getId(), REPORT_DATE)).thenReturn(Set.of(derivedInvoice.getId()));
        var relatedInvoice = mock(DocumentRelationRepository.RelatedDocument.class);
        when(relatedInvoice.getOriginId()).thenReturn(ticket.getId());
        when(relatedInvoice.getDocumentNumber()).thenReturn("FV-100");
        when(fixture.relations().findActiveRelatedDocuments(
                eq(fixture.store().getId()), anyCollection(), eq(DocumentRelationType.FACTURA_DE)))
                .thenReturn(List.of(relatedInvoice));
        when(fixture.refunds().findAllByRefundDocumentIds(
                fixture.store().getId(), List.of(refund.getId())))
                .thenReturn(List.of(refundTender));
        when(fixture.attributions().resolve(issued)).thenReturn(Map.of(
                ticket.getId(), attribution(ticket, cashierA, "ANA"),
                derivedInvoice.getId(), attribution(derivedInvoice, cashierB, "BRUNO"),
                directInvoice.getId(), attribution(directInvoice, cashierB, "BRUNO"),
                refund.getId(), attribution(refund, cashierA, "ANA"),
                cancelled.getId(), attribution(cancelled, cashierA, "ANA")));

        var result = fixture.service().daily(REPORT_DATE);

        assertThat(result.netSalesTotal()).isEqualByComparingTo("90.00");
        assertThat(result.paymentMethods())
                .extracting(SalesDailySummaryView.PaymentTotalView::method,
                        SalesDailySummaryView.PaymentTotalView::operationCount,
                        SalesDailySummaryView.PaymentTotalView::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                SalesActivityPaymentMethod.EFECTIVO, 2L, new BigDecimal("50.00")),
                        org.assertj.core.groups.Tuple.tuple(
                                SalesActivityPaymentMethod.TARJETA, 1L, new BigDecimal("40.00")));
        assertThat(result.paymentMethods()).extracting(
                SalesDailySummaryView.PaymentTotalView::amount)
                .containsExactlyInAnyOrder(new BigDecimal("50.00"), new BigDecimal("40.00"));
        assertThat(result.counts()).isEqualTo(
                new SalesDailySummaryView.ActivityCountsView(2, 1, 1, 0));
        assertThat(result.users()).extracting(SalesDailySummaryView.UserSummaryView::userName)
                .containsExactly("ANA", "BRUNO");
        assertThat(result.users().get(0).netSalesTotal()).isEqualByComparingTo("50.00");
        assertThat(result.users().get(1).netSalesTotal()).isEqualByComparingTo("40.00");
        assertThat(result.paymentMethods().stream()
                .map(SalesDailySummaryView.PaymentTotalView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(result.netSalesTotal());
    }

    @Test
    void dailyShowsPendingAndOmitsZeroPaymentMethods() {
        var fixture = fixture();
        var cashier = UUID.randomUUID();
        var ticket = confirmed(
                fixture.store(), CommercialDocumentType.TICKET, "T-010", "100.00", cashier);
        addPayment(fixture, ticket, "EFECTIVO", "70.00");
        var issued = List.of(ticket);
        when(fixture.documents().findAllByTiendaIdAndFecha(
                fixture.store().getId(), REPORT_DATE)).thenReturn(issued);
        when(fixture.relations().findDerivedSalesInvoiceIds(
                fixture.store().getId(), REPORT_DATE)).thenReturn(Set.of());
        when(fixture.relations().findActiveRelatedDocuments(
                eq(fixture.store().getId()), anyCollection(), eq(DocumentRelationType.FACTURA_DE)))
                .thenReturn(List.of());
        when(fixture.attributions().resolve(issued)).thenReturn(Map.of(
                ticket.getId(), attribution(ticket, cashier, "CAJA")));

        var result = fixture.service().daily(REPORT_DATE);

        assertThat(result.paymentMethods())
                .extracting(SalesDailySummaryView.PaymentTotalView::method,
                        SalesDailySummaryView.PaymentTotalView::operationCount,
                        SalesDailySummaryView.PaymentTotalView::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                SalesActivityPaymentMethod.EFECTIVO, 1L, new BigDecimal("70.00")),
                        org.assertj.core.groups.Tuple.tuple(
                                SalesActivityPaymentMethod.PENDIENTE, 1L, new BigDecimal("30.00")));
        assertThat(result.paymentMethods()).noneMatch(value -> value.amount().signum() == 0);
        assertThat(result.counts()).isEqualTo(
                new SalesDailySummaryView.ActivityCountsView(1, 0, 0, 1));
    }

    @Test
    void dailyPositiveRectificationUsesRealPaymentsAndPutsOnlyRemainderInOther() {
        var fixture = fixture();
        var cashier = UUID.randomUUID();
        var rectification = confirmed(
                fixture.store(), CommercialDocumentType.RECTIFICATIVA_VENTA,
                "R-001", "10.00", cashier);
        addPayment(fixture, rectification, "EFECTIVO", "7.00");
        var issued = List.of(rectification);
        when(fixture.documents().findAllByTiendaIdAndFecha(
                fixture.store().getId(), REPORT_DATE)).thenReturn(issued);
        when(fixture.relations().findDerivedSalesInvoiceIds(
                fixture.store().getId(), REPORT_DATE)).thenReturn(Set.of());
        when(fixture.relations().findActiveRelatedDocuments(
                eq(fixture.store().getId()), anyCollection(), eq(DocumentRelationType.FACTURA_DE)))
                .thenReturn(List.of());
        when(fixture.attributions().resolve(issued)).thenReturn(Map.of(
                rectification.getId(), attribution(rectification, cashier, "CAJA")));

        var result = fixture.service().daily(REPORT_DATE);

        assertThat(result.netSalesTotal()).isEqualByComparingTo("10.00");
        assertThat(result.paymentMethods())
                .extracting(SalesDailySummaryView.PaymentTotalView::method,
                        SalesDailySummaryView.PaymentTotalView::amount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                SalesActivityPaymentMethod.EFECTIVO, new BigDecimal("7.00")),
                        org.assertj.core.groups.Tuple.tuple(
                                SalesActivityPaymentMethod.OTROS, new BigDecimal("3.00")));
        assertThat(result.paymentMethods())
                .noneMatch(value -> value.method() == SalesActivityPaymentMethod.PENDIENTE);
        assertThat(result.counts()).isEqualTo(
                new SalesDailySummaryView.ActivityCountsView(1, 0, 0, 0));
    }

    @Test
    void documentsClassifiesAnyNegativeTicketAsReturn() {
        var fixture = fixture();
        var cashier = UUID.randomUUID();
        var refund = confirmed(
                fixture.store(), CommercialDocumentType.TICKET, "T-020", "-16.40", cashier);
        var tender = new RefundTender(
                refund, RefundTenderType.VOUCHER, new BigDecimal("16.40"),
                null, null, null, start(REPORT_DATE).plusSeconds(600));
        var issued = List.of(refund);
        when(fixture.documents().findSalesActivityDocuments(
                eq(fixture.store().getId()), eq(REPORT_DATE), eq(REPORT_DATE), any()))
                .thenReturn(issued);
        when(fixture.relations().findActiveRelatedDocuments(
                eq(fixture.store().getId()), anyCollection(), eq(DocumentRelationType.FACTURA_DE)))
                .thenReturn(List.of());
        when(fixture.refunds().findAllByRefundDocumentIds(
                fixture.store().getId(), List.of(refund.getId())))
                .thenReturn(List.of(tender));
        when(fixture.attributions().resolve(issued)).thenReturn(Map.of(
                refund.getId(), attribution(refund, cashier, "CAJA")));
        when(fixture.documents().countSalesActivityTickets(
                fixture.store().getId(), REPORT_DATE, REPORT_DATE)).thenReturn(1L);
        when(fixture.documents().countSalesActivityInvoiceDocuments(
                fixture.store().getId(), REPORT_DATE, REPORT_DATE)).thenReturn(0L);
        when(fixture.relations().countActiveInvoicesForSalesActivityTickets(
                fixture.store().getId(), REPORT_DATE, REPORT_DATE)).thenReturn(0L);
        when(fixture.documents().sumSalesActivityTotal(
                fixture.store().getId(), REPORT_DATE, REPORT_DATE))
                .thenReturn(new BigDecimal("-16.40"));

        var result = fixture.service().documents(REPORT_DATE, REPORT_DATE, 250, null);

        assertThat(result.items()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(
                    SalesActivityDocumentRowView.SalesActivityKind.RETURN);
            assertThat(row.total()).isEqualByComparingTo("-16.40");
            assertThat(row.paymentMethods()).containsExactly("VALE");
        });
        assertThat(result.total()).isEqualByComparingTo("-16.40");
        assertThat(result.currentDate()).isEqualTo(
                LocalDate.now(ZoneId.of(fixture.store().getTimezone())));
    }

    @Test
    void dailyDocumentsUsesDatabaseGroupingAndPaginatesByDate() {
        var fixture = fixture();
        var secondDate = REPORT_DATE.minusDays(1);
        var first = mock(SalesActivityDailyProjection.class);
        var second = mock(SalesActivityDailyProjection.class);
        var totals = mock(SalesActivityDailyTotalsProjection.class);
        when(first.getDate()).thenReturn(REPORT_DATE);
        when(first.getTicketCount()).thenReturn(3L);
        when(first.getInvoiceCount()).thenReturn(0L);
        when(first.getTotal()).thenReturn(new BigDecimal("25.10"));
        when(second.getDate()).thenReturn(secondDate);
        when(second.getTicketCount()).thenReturn(2L);
        when(second.getInvoiceCount()).thenReturn(0L);
        when(second.getTotal()).thenReturn(new BigDecimal("-4.20"));
        when(fixture.documents().findSalesActivityDaily(
                eq(fixture.store().getId()), eq(REPORT_DATE.minusDays(1)), eq(REPORT_DATE), any()))
                .thenReturn(List.of(first, second));
        when(fixture.documents().sumSalesActivityDaily(
                fixture.store().getId(), REPORT_DATE.minusDays(1), REPORT_DATE))
                .thenReturn(totals);
        when(totals.getTicketCount()).thenReturn(5L);
        when(totals.getInvoiceCount()).thenReturn(0L);
        when(totals.getTotal()).thenReturn(new BigDecimal("20.90"));

        var result = fixture.service().dailyDocuments(
                REPORT_DATE.minusDays(1), REPORT_DATE, 1, null);

        assertThat(result.items()).singleElement().satisfies(row -> {
            assertThat(row.date()).isEqualTo(REPORT_DATE);
            assertThat(row.ticketCount()).isEqualTo(3L);
            assertThat(row.invoiceCount()).isZero();
            assertThat(row.total()).isEqualByComparingTo("25.10");
        });
        assertThat(result.nextCursor()).isEqualTo(REPORT_DATE.toString());
        assertThat(result.hasMore()).isTrue();
        assertThat(result.ticketCount()).isEqualTo(5L);
        assertThat(result.invoiceCount()).isZero();
        assertThat(result.total()).isEqualByComparingTo("20.90");
        assertThat(result.dateFrom()).isEqualTo(REPORT_DATE.minusDays(1));
        assertThat(result.dateTo()).isEqualTo(REPORT_DATE);
    }

    @Test
    void dailyDocumentsUsesDateCursorForTheNextPage() {
        var fixture = fixture();
        var totals = mock(SalesActivityDailyTotalsProjection.class);
        when(fixture.documents().findSalesActivityDailyAfter(
                eq(fixture.store().getId()), eq(REPORT_DATE.minusDays(2)), eq(REPORT_DATE),
                eq(REPORT_DATE), any())).thenReturn(List.of());
        when(fixture.documents().sumSalesActivityDaily(
                fixture.store().getId(), REPORT_DATE.minusDays(2), REPORT_DATE))
                .thenReturn(totals);

        var result = fixture.service().dailyDocuments(
                REPORT_DATE.minusDays(2), REPORT_DATE, 10, REPORT_DATE.toString());

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    private static DocumentAttributionResolver.Attribution attribution(
            CommercialDocument document, UUID userId, String userName) {
        return new DocumentAttributionResolver.Attribution(
                userId, userName, null, "", document.getOperationalOccurredAt());
    }

    private static void addPayment(
            Fixture fixture,
            CommercialDocument document,
            String methodName,
            String amount) {
        var method = new PaymentMethod(
                fixture.store().getEmpresa().getId(), methodName, true);
        var payment = new DocumentPayment(
                document, method, document.getPagos().size() + 1,
                new BigDecimal(amount), document.getPagos().isEmpty(),
                null, null, null, null, start(REPORT_DATE).plusSeconds(300));
        document.addPayment(payment);
    }

    private static CommercialDocument confirmed(
            Store store,
            CommercialDocumentType type,
            String number,
            String amount,
            UUID userId) {
        var signedAmount = new BigDecimal(amount);
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), type, REPORT_DATE, userId, BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1,
                signedAmount.signum() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE,
                "P1", "Producto", "VENTA", signedAmount.abs(), BigDecimal.ZERO,
                true, "IVA", BigDecimal.ZERO));
        document.confirm(number, userId, start(REPORT_DATE), false);
        return document;
    }

    private static Instant start(LocalDate date) {
        return date.atStartOfDay(ZoneId.of("Atlantic/Canary")).toInstant();
    }

    private static Fixture fixture() {
        var company = new Company("B00000000", "EMPRESA PRUEBA", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"));
        var store = new Store(
                company, "001", "Tienda", company.getDomicilioFiscal(),
                UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
        var documents = mock(CommercialDocumentRepository.class);
        var relations = mock(DocumentRelationRepository.class);
        var refunds = mock(RefundTenderRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var attributions = mock(DocumentAttributionResolver.class);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        return new Fixture(
                new SalesActivityReportService(
                        documents, relations, refunds, payments, attributions, organization),
                documents, relations, refunds, attributions, store);
    }

    private record Fixture(
            SalesActivityReportService service,
            CommercialDocumentRepository documents,
            DocumentRelationRepository relations,
            RefundTenderRepository refunds,
            DocumentAttributionResolver attributions,
            Store store) {
    }
}
