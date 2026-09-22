package com.tpverp.saas.admin;

import static com.tpverp.saas.SaasTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.TaxpayerType;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "spring.flyway.default-schema=invoice_regime_stages_test",
        "spring.datasource.hikari.schema=invoice_regime_stages_test",
        "spring.jpa.properties.hibernate.default_schema=invoice_regime_stages_test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class InvoiceRegimeStagesTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9750000);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void newCompanyInvoiceRequiresExplicitRegimeBeforeDecisionAndPayment() throws Exception {
        UUID company = company();
        postJson("/api/v1/admin/companies/" + company + "/stores", Map.of(
                "code", "001", "name", "Fiscal fixture", "storeAddress", fiscalAddress(),
                "timeZoneId", "Atlantic/Canary", "taxRegime", "IGIC", "servicePrice", "25.00",
                "billingPeriod", "MONTHLY", "validUntil", "2099-01-01T00:00:00Z", "maxWindows", 1, "maxPda", 0));
        UUID invoice = invoice(company, "121.00");
        JsonNode pending = detail(invoice);
        assertThat(pending.get("taxRegime").isNull()).isTrue();
        assertThat(pending.get("fiscalStatus").asText()).isEqualTo("PENDING_TAX_DATA");
        payment(invoice, "121.00").andExpect(status().isConflict());
        decision(invoice, calculated(null, "21.00"), "admin").andExpect(status().isBadRequest());
        assertThat(auditCount(invoice)).isZero();

        decision(invoice, calculated("IVA", "21.00"), "admin")
                .andExpect(status().isOk()).andExpect(jsonPath("$.taxRegime").value("IVA"))
                .andExpect(jsonPath("$.taxBase").value("100.00"))
                .andExpect(jsonPath("$.taxAmount").value("21.00"))
                .andExpect(jsonPath("$.total").value("121.00"));
        var audit = jdbc.queryForMap("select previous_tax_regime,new_tax_regime from saas_invoice_fiscal_decision_audit where invoice_id=?", invoice);
        assertThat(audit.get("previous_tax_regime")).isNull();
        assertThat(audit.get("new_tax_regime")).isEqualTo("IVA");
        payment(invoice, "121.00").andExpect(status().isOk());
    }

    @Test
    void legacyRegimeAndAmountsRemainStableAndOldClientsMayOmitRegime() throws Exception {
        UUID company = company();
        jdbc.update("update saas_company set tax_regime='IGIC' where id=?", company);
        UUID invoice = invoice(company, "107.00");
        assertThat(detail(invoice).get("taxRegime").asText()).isEqualTo("IGIC");
        decision(invoice, calculated(null, "7.00"), "admin").andExpect(status().isOk());
        JsonNode before = detail(invoice);
        decision(invoice, calculated("IVA", "7.00"), "admin").andExpect(status().isConflict());
        assertThat(detail(invoice)).isEqualTo(before);
        assertThat(auditCount(invoice)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select previous_tax_regime,new_tax_regime from saas_invoice_fiscal_decision_audit where invoice_id=?", invoice))
                .containsEntry("previous_tax_regime", "IGIC").containsEntry("new_tax_regime", "IGIC");
        decision(invoice, calculated(null, "7.00"), "admin").andExpect(status().isOk());
        assertThat(detail(invoice)).isEqualTo(before);
    }

    @Test
    void notApplicableStillRequiresRegimeAndEvidenceWithoutInventedTaxAmounts() throws Exception {
        UUID invoice = invoice(company(), "35.00");
        var decision = new LinkedHashMap<String, Object>(Map.of("fiscalStatus", "NOT_APPLICABLE",
                "reason", "Operacion exenta documentada", "legalBasis", "Referencia normativa de prueba",
                "evidenceReference", "EXPEDIENTE-TEST-001"));
        decision(invoice, decision, "admin").andExpect(status().isBadRequest());
        decision.put("taxRegime", "IGIC");
        decision(invoice, decision, "admin").andExpect(status().isOk())
                .andExpect(jsonPath("$.fiscalStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.taxRegime").value("IGIC"));
        assertThat(detail(invoice).get("taxBase").isNull()).isTrue();
        assertThat(detail(invoice).get("taxRate").isNull()).isTrue();
        assertThat(detail(invoice).get("taxAmount").isNull()).isTrue();
        assertThat(auditCount(invoice)).isEqualTo(1);
    }

    @Test
    void databaseAndApiRejectIncompleteOrUnauthorizedDecisions() throws Exception {
        UUID invoice = invoice(company(), "121.00");
        assertThatThrownBy(() -> jdbc.update("update saas_billing_invoice set fiscal_status='CALCULATED' where id=?", invoice))
                .isInstanceOf(DataIntegrityViolationException.class);
        decision(invoice, calculated("IVA", "21.00"), "viewer").andExpect(status().isForbidden());
        decision(invoice, calculated("INVALID", "21.00"), "admin").andExpect(status().isBadRequest());
        var inconsistent = calculated("IVA", "21.00"); inconsistent.put("taxAmount", "20.99");
        decision(invoice, inconsistent, "admin").andExpect(status().isBadRequest());
        var unsupported = Map.of("fiscalStatus", "NOT_APPLICABLE", "taxRegime", "IVA");
        decision(invoice, unsupported, "admin").andExpect(status().isBadRequest());
        assertThat(detail(invoice).get("taxRegime").isNull()).isTrue();
        assertThat(auditCount(invoice)).isZero();
    }

    @Test
    void competingFirstDecisionsCannotReplaceTheWinningRegime() throws Exception {
        UUID invoice = invoice(company(), "121.00");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return decision(invoice, calculated("IVA", "21.00"), "admin").andReturn().getResponse().getStatus(); });
            var second = executor.submit(() -> { start.await(); return decision(invoice, calculated("IGIC", "21.00"), "admin").andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(auditCount(invoice)).isEqualTo(1);
        assertThat(detail(invoice).get("taxRegime").asText()).isIn("IVA", "IGIC");
    }

    private UUID company() throws Exception {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo("invoice_regime_stages_test");
        JsonNode company = postJson("/api/v1/admin/companies", new CreateCompanyRequest("Invoice review fixture",
                validCif("B" + COMPANY_NUMBER.incrementAndGet() + "0"), TaxpayerType.SOCIEDAD,
                CommercialProfile.MAYORISTA, fiscalAddress(), companyOwners()));
        return UUID.fromString(company.get("companyId").asText());
    }

    private UUID invoice(UUID company, String amount) throws Exception {
        JsonNode invoice = postJson("/api/v1/admin/companies/" + company + "/invoices", Map.of(
                "number", "F-" + UUID.randomUUID(), "concept", "Servicio de prueba", "amount", amount, "currency", "EUR",
                "issuedAt", "2026-09-20T10:00:00Z", "dueAt", "2026-10-20T10:00:00Z"));
        return UUID.fromString(invoice.get("id").asText());
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        return mapper.readTree(mvc.perform(post(path).header("Authorization", basic("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode detail(UUID invoice) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/admin/invoices/{id}/fiscal", invoice)
                .header("Authorization", basic("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions decision(UUID invoice, Map<String, ?> body, String user) throws Exception {
        return mvc.perform(put("/api/v1/admin/invoices/{id}/fiscal", invoice).header("Authorization", basic(user))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)));
    }

    private ResultActions payment(UUID invoice, String amount) throws Exception {
        return mvc.perform(post("/api/v1/admin/invoices/{id}/payments", invoice).header("Authorization", basic("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of(
                        "amount", amount, "method", "TRANSFERENCIA", "paidAt", "2026-09-20T10:05:00Z", "reference", "TEST"))));
    }

    private Map<String, Object> calculated(String regime, String rate) {
        var values = new LinkedHashMap<String, Object>(Map.of("fiscalStatus", "CALCULATED",
                "taxBase", "100.00", "taxRate", rate, "taxAmount", rate));
        if (regime != null) values.put("taxRegime", regime);
        return values;
    }

    private int auditCount(UUID invoice) {
        return jdbc.queryForObject("select count(*) from saas_invoice_fiscal_decision_audit where invoice_id=?", Integer.class, invoice);
    }

    private String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":admin").getBytes(StandardCharsets.UTF_8));
    }
}
