package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.cash.CashMovementRepository;
import com.tpverp.backend.cash.CashPeriodPositionQueryRepository;
import com.tpverp.backend.cash.CashMovement;
import com.tpverp.backend.cash.CashMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyCommercialReportServiceTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 7, 16);

    @Test
    void separatesCurrentSalesNewDebtPriorDebtAndRealCashInflow() {
        var fixture = fixture();
        var current = receivable(CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "100.00");
        var prior = receivable(CommercialDocumentType.ALBARAN_VENTA, REPORT_DATE.minusDays(3), "80.00");
        var currentPayment = payment(fixture, current, "30.00", start(REPORT_DATE).plusSeconds(30));
        var priorPayment = payment(fixture, prior, "20.00", start(REPORT_DATE).plusSeconds(60));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(current));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(currentPayment, priorPayment));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.invoiced()).isEqualByComparingTo("100.00");
        assertThat(report.ticketSales()).isZero();
        assertThat(report.collectedCurrent()).isEqualByComparingTo("30.00");
        assertThat(report.newPending()).isEqualByComparingTo("70.00");
        assertThat(report.priorDebtCollected()).isEqualByComparingTo("20.00");
        assertThat(report.refunds()).isZero();
        assertThat(report.cashInflow()).isEqualByComparingTo("50.00");
    }

    @Test
    void reportsTicketSalesAndCashWithoutTurningThemIntoCustomerDebt() {
        var fixture = fixture();
        var invoice = receivable(CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "100.00");
        var ticket = confirmed(CommercialDocumentType.TICKET, REPORT_DATE, "40.00");
        var creditNote = confirmed(CommercialDocumentType.RECTIFICATIVA_VENTA, REPORT_DATE, "-10.00");
        var invoicePayment = payment(fixture, invoice, "30.00", start(REPORT_DATE).plusSeconds(1));
        var ticketPayment = payment(fixture, ticket, "40.00", start(REPORT_DATE).plusSeconds(2));
        var creditPayment = payment(fixture, creditNote, "10.00", start(REPORT_DATE).plusSeconds(3));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(invoice, ticket, creditNote));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(invoicePayment, ticketPayment, creditPayment));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.invoiced()).isEqualByComparingTo("90.00");
        assertThat(report.ticketSales()).isEqualByComparingTo("40.00");
        assertThat(report.collectedCurrent()).isEqualByComparingTo("70.00");
        assertThat(report.newPending()).isEqualByComparingTo("60.00");
        assertThat(report.priorDebtCollected()).isZero();
        assertThat(report.refunds()).isZero();
        assertThat(report.cashInflow()).isEqualByComparingTo("70.00");
    }

    @Test
    void netsAnInvoicedTicketRectificationAndItsCashRefundWithoutVoidingTheTicket() {
        var fixture = fixture();
        var ticket = confirmed(CommercialDocumentType.TICKET, REPORT_DATE, "1000000.00");
        var invoice = receivable(CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "1000000.00");
        var rectification = confirmed(
                CommercialDocumentType.RECTIFICATIVA_VENTA, REPORT_DATE, "-1000000.00");
        var ticketPayment = payment(
                fixture, ticket, "1000000.00", start(REPORT_DATE).plusSeconds(1));
        var cashRefund = new RefundTender(
                rectification, RefundTenderType.CASH, new BigDecimal("1000000.00"),
                ticketPayment.getId(), null, null, start(REPORT_DATE).plusSeconds(2));
        when(fixture.relations().findInvoicedOriginIds(fixture.store().getId(), REPORT_DATE))
                .thenReturn(java.util.Set.of(ticket.getId()));
        when(fixture.relations().findDerivedSalesInvoiceIds(fixture.store().getId(), REPORT_DATE))
                .thenReturn(java.util.Set.of(invoice.getId()));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(ticket, invoice, rectification));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(ticketPayment));
        when(fixture.refunds().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(cashRefund));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(ticket.getEstado()).isEqualTo(DocumentStatus.CONFIRMADO);
        assertThat(report.invoiced()).isZero();
        assertThat(report.ticketSales()).isZero();
        assertThat(report.collectedCurrent()).isEqualByComparingTo("1000000.00");
        assertThat(report.newPending()).isZero();
        assertThat(report.refunds()).isEqualByComparingTo("1000000.00");
        assertThat(report.cashInflow()).isZero();
        assertThat(report.ticketCount()).isEqualTo(1);
        assertThat(report.invoiceCount()).isEqualTo(1);
        assertThat(report.salesTotal()).isZero();
        assertThat(report.salesByPaymentMethod().total()).isZero();
    }

    @Test
    void unifiesTicketsAndDirectInvoicesWithoutCountingAnInvoiceFromTicketTwice() {
        var fixture = fixture();
        var ticket = confirmed(CommercialDocumentType.TICKET, REPORT_DATE, "60.00");
        var invoiceFromTicket = receivable(
                CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "60.00");
        var directInvoice = receivable(
                CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "40.00");
        var ticketPayment = payment(
                fixture, ticket, "60.00", start(REPORT_DATE).plusSeconds(1), "EFECTIVO");
        var invoicePayment = payment(
                fixture, directInvoice, "20.00", start(REPORT_DATE).plusSeconds(2), "TARJETA");
        when(fixture.relations().findDerivedSalesInvoiceIds(
                fixture.store().getId(), REPORT_DATE))
                .thenReturn(Set.of(invoiceFromTicket.getId()));
        when(fixture.documents().findAllByTiendaIdAndFecha(
                fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(ticket, invoiceFromTicket, directInvoice));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(ticketPayment, invoicePayment));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.ticketCount()).isEqualTo(1);
        assertThat(report.invoiceCount()).isEqualTo(2);
        assertThat(report.salesTotal()).isEqualByComparingTo("100.00");
        assertThat(report.salesByPaymentMethod().cash()).isEqualByComparingTo("60.00");
        assertThat(report.salesByPaymentMethod().card()).isEqualByComparingTo("20.00");
        assertThat(report.salesByPaymentMethod().pending()).isEqualByComparingTo("20.00");
        assertThat(report.salesByPaymentMethod().total()).isEqualByComparingTo("100.00");
    }

    @Test
    void netsTransferRefundsAndKeepsTheirInformativeBreakdown() {
        var fixture = fixture();
        var invoice = receivable(CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "100.00");
        var payment = payment(
                fixture, invoice, "100.00", start(REPORT_DATE).plusSeconds(1), "TRANSFERENCIA");
        var refundDocument = confirmed(
                CommercialDocumentType.RECTIFICATIVA_VENTA, REPORT_DATE, "-20.00");
        var transferRefund = new RefundTender(
                refundDocument, RefundTenderType.TRANSFER, new BigDecimal("20.00"),
                payment.getId(), null, "TR-20", start(REPORT_DATE).plusSeconds(2));
        when(fixture.documents().findAllByTiendaIdAndFecha(
                fixture.store().getId(), REPORT_DATE)).thenReturn(List.of(invoice, refundDocument));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(payment));
        when(fixture.payments().findAllById(List.of(payment.getId())))
                .thenReturn(List.of(payment));
        when(fixture.refunds().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(transferRefund));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.salesTotal()).isEqualByComparingTo("80.00");
        assertThat(report.salesByPaymentMethod().transfer()).isEqualByComparingTo("80.00");
        assertThat(report.refundsByPaymentMethod().transfer()).isEqualByComparingTo("20.00");
        assertThat(report.salesByPaymentMethod().total()).isEqualByComparingTo(report.salesTotal());
    }

    @Test
    void reclassifiesHistoricalCardRefundsLinkedToTransferPayments() {
        var fixture = fixture();
        var invoice = receivable(CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "100.00");
        var payment = payment(
                fixture, invoice, "100.00", start(REPORT_DATE).plusSeconds(1), "TRANSFERENCIA");
        var refundDocument = confirmed(
                CommercialDocumentType.RECTIFICATIVA_VENTA, REPORT_DATE, "-20.00");
        var historicalRefund = new RefundTender(
                refundDocument, RefundTenderType.CARD, new BigDecimal("20.00"),
                payment.getId(), null, "TR-HISTORICA", start(REPORT_DATE).plusSeconds(2));
        when(fixture.documents().findAllByTiendaIdAndFecha(
                fixture.store().getId(), REPORT_DATE)).thenReturn(List.of(invoice, refundDocument));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(payment));
        when(fixture.payments().findAllById(List.of(payment.getId())))
                .thenReturn(List.of(payment));
        when(fixture.refunds().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(historicalRefund));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.refundsByPaymentMethod().card()).isZero();
        assertThat(report.refundsByPaymentMethod().transfer()).isEqualByComparingTo("20.00");
        assertThat(report.salesByPaymentMethod().transfer()).isEqualByComparingTo("80.00");
        assertThat(report.salesByPaymentMethod().total()).isEqualByComparingTo(report.salesTotal());
    }

    @Test
    void includesOpeningCashManualEntriesAndWithdrawalsInTheCashSection() {
        var fixture = fixture();
        var entry = mock(CashMovement.class);
        var withdrawal = mock(CashMovement.class);
        when(entry.getType()).thenReturn(CashMovementType.ENTRADA);
        when(entry.getAmount()).thenReturn(new BigDecimal("10.00"));
        when(withdrawal.getType()).thenReturn(CashMovementType.RETIRADA);
        when(withdrawal.getAmount()).thenReturn(new BigDecimal("5.00"));
        when(fixture.cashMovements().findAllByTiendaIdAndCreadoEnBetweenOrderByCreadoEnAsc(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(entry, withdrawal));
        when(fixture.cashPositions().positionAt(fixture.store().getId(), end(REPORT_DATE)))
                .thenReturn(new BigDecimal("55.00"));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.openingCashFund()).isEqualByComparingTo("50.00");
        assertThat(report.cashEntries()).isEqualByComparingTo("10.00");
        assertThat(report.cashWithdrawals()).isEqualByComparingTo("5.00");
        assertThat(report.expectedCash()).isEqualByComparingTo("55.00");
    }

    @Test
    void omitsSensitiveCashTotalsWhenTheCallerLacksPermission() {
        var fixture = fixture();

        var report = fixture.service().report(REPORT_DATE, REPORT_DATE, false);

        assertThat(report.openingCashFund()).isNull();
        assertThat(report.expectedCash()).isNull();
        assertThat(report.cashEntries()).isZero();
        assertThat(report.cashWithdrawals()).isZero();
        verify(fixture.cashPositions(), org.mockito.Mockito.never())
                .positionAt(fixture.store().getId(), end(REPORT_DATE));
    }

    @Test
    void doesNotTreatVoucherRefundAsCashLeavingTheBusiness() {
        var fixture = fixture();
        var ticket = confirmed(CommercialDocumentType.TICKET, REPORT_DATE, "25.00");
        var rectification = confirmed(
                CommercialDocumentType.RECTIFICATIVA_VENTA, REPORT_DATE, "-25.00");
        var ticketPayment = payment(
                fixture, ticket, "25.00", start(REPORT_DATE).plusSeconds(1));
        var voucherRefund = new RefundTender(
                rectification, RefundTenderType.VOUCHER, new BigDecimal("25.00"),
                ticketPayment.getId(), null, null, start(REPORT_DATE).plusSeconds(2));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(ticket, rectification));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(ticketPayment));
        when(fixture.refunds().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(voucherRefund));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.refunds()).isZero();
        assertThat(report.cashInflow()).isEqualByComparingTo("25.00");
    }

    @Test
    void recordsALaterRectificationAndCashRefundOnTheDayTheyActuallyOccur() {
        var fixture = fixture();
        var original = receivable(
                CommercialDocumentType.FACTURA_VENTA,
                REPORT_DATE.minusDays(1), "100.00");
        var rectification = confirmed(
                CommercialDocumentType.RECTIFICATIVA_VENTA, REPORT_DATE, "-100.00");
        var originalPayment = payment(
                fixture, original, "100.00", start(REPORT_DATE.minusDays(1)).plusSeconds(1));
        var cashRefund = new RefundTender(
                rectification, RefundTenderType.CASH, new BigDecimal("100.00"),
                originalPayment.getId(), null, null, start(REPORT_DATE).plusSeconds(2));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(rectification));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of());
        when(fixture.refunds().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(cashRefund));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.invoiced()).isEqualByComparingTo("-100.00");
        assertThat(report.collectedCurrent()).isZero();
        assertThat(report.newPending()).isZero();
        assertThat(report.refunds()).isEqualByComparingTo("100.00");
        assertThat(report.cashInflow()).isEqualByComparingTo("-100.00");
    }

    @Test
    void queriesOnlyCurrentStoreAndHalfOpenLocalDateInterval() {
        var fixture = fixture();
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of());
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of());

        fixture.service().report(REPORT_DATE);

        verify(fixture.documents()).findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE);
        verify(fixture.payments()).findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE));
        verify(fixture.refunds()).findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE));
    }

    @Test
    void aggregatesEveryDayInTheSelectedDateRange() {
        var fixture = fixture();
        var secondDate = REPORT_DATE.plusDays(1);
        var firstTicket = confirmed(CommercialDocumentType.TICKET, REPORT_DATE, "10.00");
        var secondTicket = confirmed(CommercialDocumentType.TICKET, secondDate, "25.00");
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(firstTicket));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), secondDate))
                .thenReturn(List.of(secondTicket));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of());
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(secondDate), end(secondDate)))
                .thenReturn(List.of());

        var report = fixture.service().report(REPORT_DATE, secondDate);

        assertThat(report.date()).isEqualTo(REPORT_DATE);
        assertThat(report.ticketSales()).isEqualByComparingTo("35.00");
        assertThat(report.days()).hasSize(2);
        assertThat(report.days().get(0).date()).isEqualTo(REPORT_DATE);
        assertThat(report.days().get(0).ticketSales()).isEqualByComparingTo("10.00");
        assertThat(report.days().get(1).date()).isEqualTo(secondDate);
        assertThat(report.days().get(1).ticketSales()).isEqualByComparingTo("25.00");
        verify(fixture.documents()).findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE);
        verify(fixture.documents()).findAllByTiendaIdAndFecha(fixture.store().getId(), secondDate);
    }

    @Test
    void limitsDocumentsAndPaymentsToTheSelectedWarehouse() {
        var fixture = fixture();
        var selectedWarehouse = UUID.randomUUID();
        var otherWarehouse = UUID.randomUUID();
        var selected = confirmed(
                CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "75.00", selectedWarehouse);
        var other = confirmed(
                CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "125.00", otherWarehouse);
        var selectedPayment = payment(fixture, selected, "25.00", start(REPORT_DATE).plusSeconds(1));
        var otherPayment = payment(fixture, other, "50.00", start(REPORT_DATE).plusSeconds(2));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(selected, other));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(selectedPayment, otherPayment));

        var report = fixture.service().report(REPORT_DATE, selectedWarehouse);

        assertThat(report.invoiced()).isEqualByComparingTo("75.00");
        assertThat(report.collectedCurrent()).isEqualByComparingTo("25.00");
        assertThat(report.newPending()).isEqualByComparingTo("50.00");
        assertThat(report.cashInflow()).isEqualByComparingTo("25.00");
    }

    @Test
    void excludesInvoicedDeliveryNoteButKeepsItsRealPaymentOnThePaymentDate() {
        var fixture = fixture();
        var deliveryNote = receivable(CommercialDocumentType.ALBARAN_VENTA, REPORT_DATE.minusDays(2), "100.00");
        var invoice = receivable(CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "100.00");
        var deliveryPayment = payment(fixture, deliveryNote, "20.00", start(REPORT_DATE).plusSeconds(1));
        var invoicePayment = payment(fixture, invoice, "30.00", start(REPORT_DATE).plusSeconds(2));
        when(fixture.relations().findInvoicedOriginIds(fixture.store().getId(), REPORT_DATE))
                .thenReturn(java.util.Set.of(deliveryNote.getId()));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(invoice));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(deliveryPayment, invoicePayment));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.invoiced()).isEqualByComparingTo("100.00");
        assertThat(report.ticketSales()).isZero();
        assertThat(report.collectedCurrent()).isEqualByComparingTo("30.00");
        assertThat(report.priorDebtCollected()).isEqualByComparingTo("20.00");
        assertThat(report.cashInflow()).isEqualByComparingTo("50.00");
    }

    @Test
    void invoicingLaterDoesNotEraseDeliveryNoteFromItsHistoricalIssueDate() throws Exception {
        var method = DocumentRelationRepository.class.getDeclaredMethod(
                "findInvoicedOriginIds", UUID.class, LocalDate.class);
        var query = method.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();

        assertThat(query).contains("relation.documento.fecha <= :asOfDate");

        var fixture = fixture();
        var deliveryNote = receivable(
                CommercialDocumentType.ALBARAN_VENTA, REPORT_DATE.minusDays(2), "100.00");
        when(fixture.relations().findInvoicedOriginIds(
                fixture.store().getId(), deliveryNote.getFecha())).thenReturn(java.util.Set.of());
        when(fixture.documents().findAllByTiendaIdAndFecha(
                fixture.store().getId(), deliveryNote.getFecha())).thenReturn(List.of(deliveryNote));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(deliveryNote.getFecha()), end(deliveryNote.getFecha())))
                .thenReturn(List.of());

        var historical = fixture.service().report(deliveryNote.getFecha());

        assertThat(historical.invoiced()).isEqualByComparingTo("100.00");
        assertThat(historical.ticketSales()).isZero();
        assertThat(historical.newPending()).isEqualByComparingTo("100.00");
    }

    private static DocumentPayment payment(
            Fixture fixture, CommercialDocument document, String amount, Instant createdAt) {
        return payment(fixture, document, amount, createdAt, "EFECTIVO");
    }

    private static DocumentPayment payment(
            Fixture fixture,
            CommercialDocument document,
            String amount,
            Instant createdAt,
            String methodName) {
        var method = new PaymentMethod(
                fixture.store().getEmpresa().getId(), methodName, true);
        return new DocumentPayment(
                document, method, document.getPagos().size() + 1,
                new BigDecimal(amount), document.getPagos().isEmpty(),
                null, null, null, null, createdAt);
    }

    private static CommercialDocument receivable(
            CommercialDocumentType type, LocalDate date, String amount) {
        return confirmed(type, date, amount);
    }

    private static CommercialDocument confirmed(
            CommercialDocumentType type, LocalDate date, String amount) {
        return confirmed(type, date, amount, UUID.randomUUID());
    }

    private static CommercialDocument confirmed(
            CommercialDocumentType type, LocalDate date, String amount, UUID warehouseId) {
        var signedAmount = new BigDecimal(amount);
        var document = new CommercialDocument(
                UUID.randomUUID(), warehouseId, type,
                date, UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1,
                signedAmount.signum() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE,
                "P1", "Producto", "VENTA", signedAmount.abs(), BigDecimal.ZERO,
                true, "IVA", BigDecimal.ZERO));
        document.confirm("DOC-001", UUID.randomUUID(), start(date), false);
        return document;
    }

    private static Instant start(LocalDate date) {
        return date.atStartOfDay(ZoneId.of("Atlantic/Canary")).toInstant();
    }

    private static Instant end(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneId.of("Atlantic/Canary")).toInstant();
    }

    private static Fixture fixture() {
        var store = store();
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var refunds = mock(RefundTenderRepository.class);
        var relations = mock(DocumentRelationRepository.class);
        var cashMovements = mock(CashMovementRepository.class);
        var cashPositions = mock(CashPeriodPositionQueryRepository.class);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        return new Fixture(
                new DailyCommercialReportService(
                        documents, payments, refunds, relations,
                        cashMovements, cashPositions, organization),
                documents, payments, refunds, relations,
                cashMovements, cashPositions, store);
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "001", "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record Fixture(
            DailyCommercialReportService service,
            CommercialDocumentRepository documents,
            DocumentPaymentRepository payments,
            RefundTenderRepository refunds,
            DocumentRelationRepository relations,
            CashMovementRepository cashMovements,
            CashPeriodPositionQueryRepository cashPositions,
            Store store) {
    }
}
