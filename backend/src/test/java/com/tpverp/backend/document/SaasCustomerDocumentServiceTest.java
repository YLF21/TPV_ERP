package com.tpverp.backend.document;

import static com.tpverp.backend.document.SaasCustomerDocumentApi.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.template.CustomerModel347JasperRenderer;
import com.tpverp.backend.excel.CustomerDocumentExcelExportService;
import com.tpverp.backend.excel.CustomerDocumentExportRequest;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SaasCustomerDocumentServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final CurrentOrganization organization = mock(CurrentOrganization.class);
    final CustomerRepository customers = mock(CustomerRepository.class);
    final Customer customer = mock(Customer.class);
    final SaasCustomerDocumentClient client = mock(SaasCustomerDocumentClient.class);
    final CustomerDocumentExcelExportService excel = mock(CustomerDocumentExcelExportService.class);
    final CustomerModel347JasperRenderer pdf = mock(CustomerModel347JasperRenderer.class);
    final SaasCustomerDocumentService service = new SaasCustomerDocumentService(organization, customers, client, excel, pdf);
    final UUID company = UUID.randomUUID(), store = UUID.randomUUID(), localCustomer = UUID.randomUUID(), centralCustomer = UUID.randomUUID();

    @BeforeEach void context() {
        var companyEntity = mock(Company.class); var storeEntity = mock(Store.class);
        when(companyEntity.getId()).thenReturn(company); when(storeEntity.getId()).thenReturn(store);
        when(storeEntity.getEmpresa()).thenReturn(companyEntity);
        when(organization.currentCompany()).thenReturn(companyEntity); when(organization.currentStore()).thenReturn(storeEntity);
        when(customers.findByIdAndCompanyId(localCustomer, company)).thenReturn(Optional.of(customer));
        when(customer.getSaasCustomerId()).thenReturn(centralCustomer);
    }

    @ParameterizedTest @ValueSource(strings = {"INVOICES_READ", "DELIVERY_NOTES_READ", "CUSTOMERS_READ"})
    void deniesWrongTabBeforeAnyDataRead(String authority) {
        assertThatThrownBy(() -> page(auth(authority))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(organization, customers, client, excel, pdf);
    }
    @Test void refusesMissingBindingOrForeignCustomerWithoutRemoteQuery() {
        when(customer.getSaasCustomerId()).thenReturn(null);
        assertThatThrownBy(() -> page(auth("TICKETS_READ"))).hasMessage("SAAS_CUSTOMER_BINDING_REQUIRED");
        when(customers.findByIdAndCompanyId(localCustomer, company)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> page(auth("TICKETS_READ"))).isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(client, excel, pdf);
    }
    @Test void returnsDecimalStringsAndBothLocalAndCentralCustomerIdentity() {
        var response = envelope(); response.putArray("items").add(row(UUID.randomUUID(), UUID.randomUUID(), "9007199254740993.01"));
        response.put("hasMore", false); response.putNull("nextCursor");
        when(client.query(eq("page"), eq(company), eq(store), eq(localCustomer), eq(centralCustomer), anyMap())).thenReturn(response);
        var result = page(auth("TICKETS_READ"));
        assertThat(result.localCustomerId()).isEqualTo(localCustomer);
        assertThat(result.customer().id()).isEqualTo(centralCustomer);
        assertThat(result.items()).singleElement().satisfies(value -> {
            assertThat(value.total()).isEqualTo("9007199254740993.01");
            assertThat(value.id()).isEqualTo(value.storeId() + "/" + value.documentId());
            assertThat(value.storeCode()).isEqualTo("002");
        });
        verifyNoInteractions(excel, pdf);
    }
    @Test void rejectsNumericJsonMoneyAndInvalidContinuationInsteadOfReturningPartialRows() {
        var response = envelope(); var row = row(UUID.randomUUID(), UUID.randomUUID(), "10.00");
        response.putArray("items").add(row); response.put("hasMore", false); response.putNull("nextCursor");
        when(client.query(anyString(), any(), any(), any(), any(), anyMap())).thenReturn(response);
        row.put("total", 10.0);
        assertThatThrownBy(() -> page(auth("TICKETS_READ"))).hasMessage("SAAS_CUSTOMER_DOCUMENTS_INVALID_RESPONSE");
        row.put("total", "10.00"); response.put("hasMore", true);
        assertThatThrownBy(() -> page(auth("TICKETS_READ"))).hasMessage("SAAS_CUSTOMER_DOCUMENTS_INVALID_RESPONSE");
    }
    @Test void acceptsTheCentralStoreCodeLengthWithoutConfusingItWithCustomerCode() {
        var response = envelope(); var row = row(UUID.randomUUID(), UUID.randomUUID(), "10.00");
        row.put("storeCode", "S".repeat(64));
        response.putArray("items").add(row); response.put("hasMore", false); response.putNull("nextCursor");
        when(client.query(anyString(), any(), any(), any(), any(), anyMap())).thenReturn(response);
        assertThat(page(auth("TICKETS_READ")).items().getFirst().storeCode()).hasSize(64);
        row.put("storeCode", "S".repeat(65));
        assertThatThrownBy(() -> page(auth("TICKETS_READ"))).hasMessage("SAAS_CUSTOMER_DOCUMENTS_INVALID_RESPONSE");
    }
    @Test void centralFailureNeverFallsBackToLocalDocumentsOrRenderers() {
        when(client.query(anyString(), any(), any(), any(), any(), anyMap())).thenThrow(SaasCustomerDocumentException.unavailable());
        assertThatThrownBy(() -> page(auth("TICKETS_READ"))).hasMessage("SAAS_CUSTOMER_DOCUMENTS_UNAVAILABLE");
        verifyNoInteractions(excel, pdf);
    }
    @Test void nonNegativeDocumentRevisionRetainsTheCentralSnapshotContract() {
        var response = envelope(); var row = row(UUID.randomUUID(), UUID.randomUUID(), "10.00");
        row.put("sourceRevision", 0);
        response.putArray("items").add(row); response.put("hasMore", false); response.putNull("nextCursor");
        when(client.query(anyString(), any(), any(), any(), any(), anyMap())).thenReturn(response);
        assertThat(page(auth("TICKETS_READ")).items().getFirst().sourceRevision()).isZero();
        row.put("sourceRevision", -1);
        assertThatThrownBy(() -> page(auth("TICKETS_READ"))).hasMessage("SAAS_CUSTOMER_DOCUMENTS_INVALID_RESPONSE");
    }
    @Test void selectedExportMakesOneCallAndRetainsCompositeKeyOrderEvenWhenDocumentUuidRepeats() {
        UUID document = UUID.randomUUID(), firstStore = UUID.randomUUID(), secondStore = UUID.randomUUID();
        var keys = List.of(new DocumentKey(firstStore, document), new DocumentKey(secondStore, document));
        var response = envelope(); response.putArray("items").add(row(firstStore, document, "10.00")).add(row(secondStore, document, "-2.00"));
        response.putArray("totals").add(mapper.valueToTree(Map.of("currency", "EUR", "documentCount", 2,
                "subtotal", "8.00", "taxTotal", "0.00", "total", "8.00")));
        when(client.query(eq("export"), any(), any(), any(), any(), anyMap())).thenReturn(response);
        when(excel.renderReceived(any(), any(), any(), any(), anyList())).thenReturn(new byte[]{1});
        assertThat(service.export(export(keys), auth("TICKETS_READ"))).containsExactly(1);
        ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.captor();
        verify(client).query(eq("export"), eq(company), eq(store), eq(localCustomer), eq(centralCustomer), query.capture());
        assertThat(query.getValue()).containsEntry("documentKeys", keys);
        ArgumentCaptor<List<CustomerDocumentExcelExportService.ExportRow>> rows = ArgumentCaptor.captor();
        verify(excel).renderReceived(any(), eq("C-REMOTE"), eq("TAX-REMOTE"), eq("Remote customer"), rows.capture());
        assertThat(rows.getValue()).extracting(CustomerDocumentExcelExportService.ExportRow::total)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("-2.00"));
        response.withArray("items").remove(0);
        assertThatThrownBy(() -> service.export(export(keys), auth("TICKETS_READ")))
                .hasMessage("SAAS_CUSTOMER_DOCUMENTS_INVALID_RESPONSE");
    }
    @Test void annualUsesOnlyRemoteProfilesAndFourSignedEuroQuarters() {
        var response = annualResponse("EUR");
        when(client.query(eq("annual"), any(), any(), any(), any(), anyMap())).thenReturn(response);
        when(pdf.render(any(), eq("zh"))).thenReturn(new byte[]{7});
        assertThat(service.annual(localCustomer, 2026, "zh", auth("INVOICES_READ"))).containsExactly(7);
        var report = ArgumentCaptor.forClass(CustomerModel347Report.class);
        verify(pdf).render(report.capture(), eq("zh"));
        assertThat(report.getValue().annualTotal()).isEqualByComparingTo("-2.25");
        assertThat(report.getValue().issuer().name()).isEqualTo("Remote issuer");
        assertThat(report.getValue().customer().name()).isEqualTo("Remote customer");
        assertThat(report.getValue().quarters()).extracting(CustomerModel347Report.Quarter::number).containsExactly(1, 2, 3, 4);
    }
    @Test void oversizeLoadedSelectionHasTheSameLimitCodeBeforeAnyRemoteCall() {
        var keys = java.util.Collections.nCopies(50_001, new DocumentKey(UUID.randomUUID(), UUID.randomUUID()));
        assertThatThrownBy(() -> service.export(export(keys), auth("TICKETS_READ")))
                .isInstanceOfSatisfying(SaasCustomerDocumentException.class,
                        error -> assertThat(error.status().value()).isEqualTo(422))
                .hasMessage("customer_documents_export_limit_exceeded");
        verifyNoInteractions(client, excel, pdf);
    }
    @Test void annualRejectsNonEuroAndInconsistentTotalsWithoutRendering() {
        var response = annualResponse("USD");
        when(client.query(anyString(), any(), any(), any(), any(), anyMap())).thenReturn(response);
        assertThatThrownBy(() -> service.annual(localCustomer, 2026, "es", auth("INVOICES_READ")))
                .hasMessage("SAAS_CUSTOMER_DOCUMENTS_CURRENCY_UNSUPPORTED");
        response = annualResponse("EUR"); ((ObjectNode) response.withArray("totals").get(0)).put("total", "99.00");
        when(client.query(anyString(), any(), any(), any(), any(), anyMap())).thenReturn(response);
        assertThatThrownBy(() -> service.annual(localCustomer, 2026, "es", auth("INVOICES_READ")))
                .hasMessage("SAAS_CUSTOMER_DOCUMENTS_INVALID_RESPONSE");
        verifyNoInteractions(pdf);
    }
    private Page page(Authentication authentication) { return service.page(localCustomer, "tickets", null, null, null, 100, null, authentication); }
    private ObjectNode envelope() {
        var value = mapper.createObjectNode(); value.put("companyId", company.toString()); value.put("coverage", COVERAGE);
        value.putObject("customer").put("id", centralCustomer.toString()).put("code", "C-REMOTE")
                .put("name", "Remote customer").put("taxId", "TAX-REMOTE").put("address", "Remote address");
        return value;
    }
    private ObjectNode row(UUID storeId, UUID document, String amount) {
        var value = mapper.createObjectNode(); value.put("id", storeId + "/" + document).put("storeId", storeId.toString())
                .put("storeCode", "002").put("documentId", document.toString()).put("installationId", UUID.randomUUID().toString())
                .put("sourceRevision", 1).put("customerId", centralCustomer.toString()).put("type", "TICKET").put("status", "PAGADO")
                .put("number", "T-001").put("date", "2026-09-10").put("currency", "EUR")
                .put("subtotal", amount).put("taxTotal", "0.00").put("total", amount).putNull("terminalName").putNull("userName");
        return value;
    }
    private ObjectNode annualResponse(String currency) {
        var value = envelope(); value.put("year", 2026);
        value.putObject("issuer").put("id", company.toString()).put("name", "Remote issuer").put("taxId", "ISSUER").put("address", "Issuer address");
        var quarters = value.putArray("quarters");
        for (int number = 1; number <= 4; number++) quarters.add(mapper.valueToTree(Map.of("number", number, "currency", currency,
                "documentCount", number == 2 ? 1 : 0, "total", number == 2 ? "-2.25" : "0.00")));
        value.putArray("totals").add(mapper.valueToTree(Map.of("currency", currency, "documentCount", 1, "total", "-2.25")));
        return value;
    }
    private ExportRequest export(List<DocumentKey> keys) {
        return new ExportRequest(localCustomer, "tickets", null, "date", "desc", keys,
                List.of(new CustomerDocumentExportRequest.Column("number", "Número"),
                        new CustomerDocumentExportRequest.Column("total", "Total"), new CustomerDocumentExportRequest.Column("currency", "Moneda")),
                new CustomerDocumentExportRequest.Labels("Documentos", Map.of(CommercialDocumentType.TICKET, "Ticket"), Map.of()));
    }
    private static Authentication auth(String permission) {
        return new UsernamePasswordAuthenticationToken("operator", "", List.of(new SimpleGrantedAuthority(permission)));
    }
}
