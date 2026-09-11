package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentApi.*;
import static com.tpverp.saas.document.CommercialDocumentQuery.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class CommercialDocumentQueryServiceTest {
    private final SaasInstallationRepository installations = mock(SaasInstallationRepository.class);
    private final InstallationAuthenticator authenticator = mock(InstallationAuthenticator.class);
    private final CommercialDocumentReadService reads = mock(CommercialDocumentReadService.class);
    private final CommercialDocumentReadRepository documents = mock(CommercialDocumentReadRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final CommercialDocumentQueryService service = new CommercialDocumentQueryService(
            installations, authenticator, reads, documents, jdbc, new ObjectMapper());
    private final SaasCompany company = new SaasCompany(UUID.randomUUID(), "Issuer", "TEST-ISSUER",
            TaxpayerType.SOCIEDAD, TaxRegime.IVA, Instant.EPOCH);
    private final UUID store = UUID.randomUUID();
    private final UUID localCustomer = UUID.randomUUID();
    private final CustomerProfile customer = new CustomerProfile(UUID.randomUUID(), "C-1", "Customer", "TEST-CUSTOMER", "Street");
    private final SaasInstallation installation = mock(SaasInstallation.class);

    @BeforeEach
    void authenticateFixture() {
        when(installation.getId()).thenReturn(UUID.randomUUID());
        when(installation.getCompany()).thenReturn(company);
        when(installations.findByCompany_IdAndStore_Id(company.getId(), store)).thenReturn(List.of(installation));
        when(authenticator.requireLinkedInstallation(company.getId(), store, List.of(installation), "token"))
                .thenReturn(installation);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<CustomerProfile>>any()))
                .thenReturn(List.of(customer));
    }

    @Test
    void pageUsesVerifiedCompanyCustomerAndSerializesExactMoneyAsStrings() throws Exception {
        Row row = row("9007199254740993.01", "EUR");
        when(reads.page(any(), any(), any())).thenReturn(new Page(List.of(row), null, false));
        var result = service.page(new CommercialDocumentApi.PageRequest(company.getId(), store, localCustomer,
                customer.id(), "tickets", null, "date", "desc", 50, null), "token");
        assertThat(result.customer()).isEqualTo(customer);
        assertThat(result.items().getFirst().total()).isEqualTo("9007199254740993.01");
        assertThat(result.items().getFirst().id()).isEqualTo(row.storeId() + "/" + row.documentId());
        assertThat(result.coverage()).isEqualTo(COVERAGE);
        assertThat(new ObjectMapper().findAndRegisterModules().valueToTree(result.items().getFirst()).get("total").isTextual()).isTrue();
        var filters = ArgumentCaptor.forClass(Filter.class);
        verify(reads).page(eq(Scope.company(company.getId())), filters.capture(), any());
        assertThat(filters.getValue().customerId()).isEqualTo(customer.id());
        assertThat(filters.getValue().types()).containsExactly(Type.TICKET);
        assertThat(filters.getValue().statuses()).containsExactlyInAnyOrder(Status.values());
    }

    @Test
    void missingBindingFailsInsteadOfReturningEmptyHistory() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<CustomerProfile>>any()))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.annual(annual(2024), "token"))
                .isInstanceOfSatisfying(CommercialDocumentQueryService.QueryFailure.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getCode()).isEqualTo("SAAS_CUSTOMER_BINDING_REQUIRED");
                });
        verifyNoInteractions(reads, documents);
    }

    @Test
    void filteredExportVisitsAllPagesAndKeepsCurrenciesAndSignedAmountsSeparate() {
        Row first = row("10.00", "EUR");
        Row second = row("-4.00", "EUR");
        Row third = row("7.00", "USD");
        when(reads.page(any(), any(), any())).thenReturn(new Page(List.of(first), "next", true),
                new Page(List.of(second, third), null, false));
        var response = service.export(export(new Filters("A", null, null, null), null), "token");
        assertThat(response.items()).extracting(DocumentRow::total).containsExactly("10.00", "-4.00", "7.00");
        assertThat(response.totals()).extracting(CurrencyTotal::total).containsExactly("6.00", "7.00");
        verify(reads, times(2)).page(any(), any(), any());
        verifyNoInteractions(documents);
    }

    @Test
    void loadedExportPreservesCompositeSelectionOrderAndRejectsMissingOrRepeatedKeys() {
        Row first = row("1.00", "EUR");
        Row second = row("2.00", "EUR");
        var keys = List.of(key(second), key(first));
        when(documents.selected(any(), any(), eq(keys))).thenReturn(List.of(first, second));
        assertThat(service.export(export(null, keys), "token").items()).extracting(DocumentRow::documentId)
                .containsExactly(second.documentId(), first.documentId());
        when(documents.selected(any(), any(), eq(keys))).thenReturn(List.of(first));
        assertThatThrownBy(() -> service.export(export(null, keys), "token"))
                .isInstanceOfSatisfying(CommercialDocumentQueryService.QueryFailure.class,
                        error -> assertThat(error.getCode()).isEqualTo("CUSTOMER_DOCUMENT_SELECTION_UNAVAILABLE"));
        assertThatThrownBy(() -> service.export(export(null, List.of(key(first), key(first))), "token"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.export(export(new Filters("A", null, null, null), keys), "token"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.export(export(null, null), "token"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void exportLimitNeverReturnsPartialDataOrInfiniteCursorLoop() {
        Row row = row("1.00", "EUR");
        when(reads.page(any(), any(), any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(2, CommercialDocumentQuery.PageRequest.class);
            int page = request.cursor() == null ? 0 : Integer.parseInt(request.cursor());
            return new Page(Collections.nCopies(200, row), Integer.toString(page + 1), true);
        });
        assertThatThrownBy(() -> service.export(export(new Filters("A", null, null, null), null), "token"))
                .isInstanceOfSatisfying(CommercialDocumentQueryService.QueryFailure.class,
                        error -> assertThat(error.getCode()).isEqualTo("customer_documents_export_limit_exceeded"));
        verify(reads, times(250)).page(any(), any(), any());
        reset(reads);
        when(reads.page(any(), any(), any())).thenReturn(new Page(List.of(row), "same", true));
        assertThatThrownBy(() -> service.export(export(new Filters("A", null, null, null), null), "token"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void annualUsesSignedInvoiceGroupsAndDoesNotInventCurrencyForEmptyHistory() {
        var grouping = new Aggregation(Period.QUARTER, Set.of());
        var first = total("2024-01-01", "100.00", Type.FACTURA_VENTA);
        var correction = total("2024-01-01", "-30.00", Type.RECTIFICATIVA_VENTA);
        when(reads.documentTotals(any(), any(), eq(grouping))).thenReturn(new Totals(grouping, List.of(first, correction)));
        var response = service.annual(annual(2024), "token");
        assertThat(response.quarters()).extracting(Quarter::total).containsExactly("70.00", "0.00", "0.00", "0.00");
        assertThat(response.totals()).containsExactly(new AnnualTotal("EUR", 2, "70.00"));
        var filters = ArgumentCaptor.forClass(Filter.class);
        verify(reads).documentTotals(eq(Scope.company(company.getId())), filters.capture(), eq(grouping));
        assertThat(filters.getValue().statuses()).doesNotContain(Status.ANULADO);
        assertThat(filters.getValue().from()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(filters.getValue().to()).isEqualTo(LocalDate.of(2024, 12, 31));
        when(reads.documentTotals(any(), any(), eq(grouping))).thenReturn(new Totals(grouping, List.of()));
        assertThat(service.annual(annual(2024), "token").quarters()).isEmpty();
    }

    @Test
    void exportAndAnnualDeclareOneReadOnlyRepeatableReadTransaction() throws Exception {
        for (var entry : Map.of("export", ExportRequest.class, "annual", AnnualRequest.class).entrySet()) {
            var transaction = CommercialDocumentQueryService.class.getMethod(entry.getKey(), entry.getValue(), String.class)
                    .getAnnotation(Transactional.class);
            assertThat(transaction.readOnly()).isTrue();
            assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        }
    }

    private ExportRequest export(Filters filters, List<DocumentKey> keys) {
        return new ExportRequest(company.getId(), store, localCustomer, customer.id(), "tickets", filters, "number", "asc", keys);
    }
    private AnnualRequest annual(int year) { return new AnnualRequest(company.getId(), store, localCustomer, customer.id(), year); }
    private static DocumentKey key(Row row) { return new DocumentKey(row.storeId(), row.documentId()); }
    private Row row(String total, String currency) {
        return new Row(company.getId(), store, UUID.randomUUID(), installation.getId(), 1, Type.TICKET, Status.PAGADO,
                "A", LocalDate.of(2024, 1, 1), currency, new BigDecimal(total), BigDecimal.ZERO, new BigDecimal(total),
                localCustomer, customer.id(), customer.code(), customer.name(), customer.taxId(), null, null, null,
                null, null, null, null, null, null, false, "Usuario", "Caja", "001");
    }
    private Total total(String date, String value, Type type) {
        var amount = new BigDecimal(value);
        return new Total(new Group(LocalDate.parse(date), null, null, null, null, null, null, null, type, Status.PAGADO, "EUR"),
                1, amount, BigDecimal.ZERO, amount);
    }
}
