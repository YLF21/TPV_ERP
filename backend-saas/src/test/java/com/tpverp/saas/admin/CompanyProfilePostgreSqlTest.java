package com.tpverp.saas.admin;

import static com.tpverp.saas.SaasTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "spring.flyway.default-schema=company_profile_test",
        "spring.datasource.hikari.schema=company_profile_test",
        "spring.jpa.properties.hibernate.default_schema=company_profile_test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CompanyProfilePostgreSqlTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9760000);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void creationAndListExposeExplicitOwnersAndContactsWithoutInventingACompanyActivity() throws Exception {
        var request = creation();
        request.put("name", "  Sociedad   unificada  ");
        request.put("contactName", " Responsable contacto ");
        request.put("contactEmail", "contacto@example.test");
        request.put("contactPhone", " +34 600000001 ");
        request.put("supportStatus", "ATENCION");
        request.put("notes", " Nota privada ");
        request.set("owners", mapper.valueToTree(List.of(
                new CompanyOwner("  Propietario   principal ", " 1234-5678z ", "+34 600000002", "owner@example.test"),
                new CompanyOwner("Segundo propietario", "X1234567L", null, null))));
        JsonNode created = response(create(request).andExpect(status().isOk()));
        assertThat(created.get("companyName").asText()).isEqualTo("Sociedad unificada");
        assertThat(created.get("commercialProfile").isNull()).isTrue();
        assertThat(created.get("contactName").asText()).isEqualTo("Responsable contacto");
        assertThat(created.get("contactPhone").asText()).isEqualTo("+34 600000001");
        assertThat(created.get("owners")).hasSize(2);
        assertThat(created.at("/owners/0/name").asText()).isEqualTo("Propietario principal");
        assertThat(created.at("/owners/0/taxId").asText()).isEqualTo("12345678Z");
        assertThat(created.at("/owners/1/taxId").asText()).isEqualTo("X1234567L");
        assertThat(created.at("/owners/1/email").isNull()).isTrue();
        UUID id = id(created);
        assertThat(profile(id)).isEqualTo(created);
        JsonNode listed = mapper.readTree(mvc.perform(get("/api/v1/admin/companies").header("Authorization", auth("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(listed).anySatisfy(row -> assertThat(row).isEqualTo(created));
        assertThat(jdbc.queryForObject("select count(*) from saas_store where company_id=?", Integer.class, id)).isZero();
        String audit = jdbc.queryForList("select action,target_id,details from saas_admin_audit_log where target_id=?", id.toString()).toString();
        assertThat(audit).contains("ADD_COMPANY").doesNotContain("12345678Z", "X1234567L", "owner@example.test", "Nota privada");
    }

    @Test
    void missingEmptyOrInvalidOwnersAreRejectedAndDoNotCreatePartialCompanies() throws Exception {
        var noOwners = creation(); noOwners.remove("owners");
        assertInvalidCreation(noOwners);
        var emptyOwners = creation(); emptyOwners.putArray("owners");
        assertInvalidCreation(emptyOwners);
        for (var owner : List.of(
                new CompanyOwner("", "12345678Z", null, null),
                new CompanyOwner("Propietario", "", null, null),
                new CompanyOwner("Propietario", "12345678A", null, null),
                new CompanyOwner("Propietario", validCif("B12345678"), null, null),
                new CompanyOwner("Propietario", "12345678Z", null, "correo-invalido"),
                new CompanyOwner("Propietario", "12345678Z", "1".repeat(41), null))) {
            var request = creation(); request.set("owners", mapper.valueToTree(List.of(owner)));
            assertInvalidCreation(request);
        }
        var invalidContact = creation(); invalidContact.put("contactEmail", "correo-invalido");
        assertInvalidCreation(invalidContact);
        var oversizedNotes = creation(); oversizedNotes.put("notes", "x".repeat(4001));
        assertInvalidCreation(oversizedNotes);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void rejectedOwnerAndContactValuesAreAbsentFromValidationResponsesAndLogs(CapturedOutput output) throws Exception {
        String privateDocument = "12345678Z".repeat(4);
        String privateEmail = "owner.private.invalid-email";
        String privatePhone = "600000009".repeat(5);
        var invalidOwner = new CompanyOwner("Titular de prueba", privateDocument, privatePhone, privateEmail);
        var createRequest = creation();
        createRequest.set("owners", mapper.valueToTree(List.of(invalidOwner)));
        createRequest.put("contactEmail", "contact.private.invalid-email");
        String createError = create(createRequest).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(createError).doesNotContain(privateDocument, privateEmail, privatePhone, "contact.private.invalid-email");

        UUID id = createCompany(); JsonNode before = profile(id);
        var updateRequest = update("No debe persistir", "Contacto de prueba", "12345678Z");
        updateRequest.set("owners", mapper.valueToTree(List.of(invalidOwner)));
        updateRequest.put("contactPhone", privatePhone);
        String updateError = save(id, updateRequest, "admin").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(updateError).doesNotContain(privateDocument, privateEmail, privatePhone);
        assertThat(profile(id)).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain(privateDocument, privateEmail, privatePhone,
                "contact.private.invalid-email", "rejected value", "MethodArgumentNotValidException");
    }

    @Test
    void oneProfileWriteUpdatesEverythingAndPreservesFiscalIdentityAndHistoricalBilling() throws Exception {
        UUID id = createCompany();
        jdbc.update("update saas_company set commercial_profile='MINORISTA',tax_regime='IGIC' where id=?", id);
        jdbc.update("update saas_company_operations set billing_status='PAGADO',monthly_price='199.99',renewal_date='2099-01-01T00:00:00Z' where company_id=?", id);
        var billingBefore = jdbc.queryForMap("select plan_name,billing_status,monthly_price,renewal_date from saas_company_operations where company_id=?", id);
        JsonNode original = profile(id);
        var request = update("Nombre actualizado", "Contacto actualizado", "11111111H");
        var address = new LinkedHashMap<>(fiscalAddress()); address.put("linea1", "Calle nueva 2");
        request.set("companyAddress", mapper.valueToTree(address));
        request.put("contactEmail", "nuevo@example.test"); request.put("contactPhone", "600000003");
        request.put("notes", "Notas nuevas"); request.put("supportStatus", "ATENCION");
        request.put("taxId", "22222222J"); request.put("taxpayerType", "AUTONOMO"); request.put("commercialProfile", "MAYORISTA");
        JsonNode saved = response(save(id, request, "admin").andExpect(status().isOk()));
        assertThat(saved.get("companyName").asText()).isEqualTo("Nombre actualizado");
        assertThat(saved.get("companyAddress")).isEqualTo(mapper.valueToTree(address));
        assertThat(saved.get("contactName").asText()).isEqualTo("Contacto actualizado");
        assertThat(saved.get("contactEmail").asText()).isEqualTo("nuevo@example.test");
        assertThat(saved.get("contactPhone").asText()).isEqualTo("600000003");
        assertThat(saved.get("notes").asText()).isEqualTo("Notas nuevas");
        assertThat(saved.at("/owners/0/taxId").asText()).isEqualTo("11111111H");
        assertThat(saved.get("taxId")).isEqualTo(original.get("taxId"));
        assertThat(saved.get("taxpayerType").asText()).isEqualTo("SOCIEDAD");
        assertThat(saved.get("commercialProfile").asText()).isEqualTo("MINORISTA");
        assertThat(jdbc.queryForObject("select tax_regime from saas_company where id=?", String.class, id)).isEqualTo("IGIC");
        assertThat(jdbc.queryForMap("select plan_name,billing_status,monthly_price,renewal_date from saas_company_operations where company_id=?", id))
                .isEqualTo(billingBefore);
        assertThat(auditCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForList("select details from saas_admin_audit_log where target_id=?", id.toString()).toString())
                .doesNotContain("11111111H", "nuevo@example.test", "Notas nuevas");
    }

    @Test
    void optionalContactsCanBeClearedAndOwnersAreReplacedAsAWhole() throws Exception {
        UUID id = createCompany();
        var first = update("Primera ficha", "Contacto temporal", "12345678Z");
        first.put("contactPhone", "600000004"); first.put("contactEmail", "temporal@example.test"); first.put("notes", "Temporal");
        first.set("owners", mapper.valueToTree(List.of(new CompanyOwner("Uno", "12345678Z", null, null),
                new CompanyOwner("Dos", "X1234567L", null, null))));
        save(id, first, "admin").andExpect(status().isOk());
        var cleared = update("Segunda ficha", null, "11111111H");
        cleared.remove("contactName");
        JsonNode result = response(save(id, cleared, "admin").andExpect(status().isOk()));
        for (String field : List.of("contactName", "contactEmail", "contactPhone", "notes")) assertThat(result.get(field).isNull()).as(field).isTrue();
        assertThat(result.get("owners")).hasSize(1);
        assertThat(result.at("/owners/0/taxId").asText()).isEqualTo("11111111H");
    }

    @Test
    void invalidOwnerRollsBackAlreadyFlushedCorporateChangesAndAllOtherFields() throws Exception {
        UUID id = createCompany();
        JsonNode before = profile(id);
        var request = update("No debe persistir", "Tampoco contacto", "12345678A");
        save(id, request, "admin").andExpect(status().isBadRequest());
        assertThat(profile(id)).isEqualTo(before);
        assertThat(auditCount(id)).isZero();
        request.set("owners", mapper.valueToTree(List.of(new CompanyOwner("Nuevo", "12345678Z", null, null))));
        request.put("contactEmail", "incorrecto");
        save(id, request, "admin").andExpect(status().isBadRequest());
        assertThat(profile(id)).isEqualTo(before);
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void legacyCompaniesStayReadableWithoutInventedOwnersUntilTheFullProfileIsCompleted() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into saas_company(id,name,tax_id,taxpayer_type,commercial_profile,created_at) values (?,? ,?,'SOCIEDAD','MINORISTA',now())",
                id, "Sociedad historica", nextTaxId());
        JsonNode before = profile(id);
        assertThat(before.get("owners")).isEmpty();
        assertThat(before.get("commercialProfile").asText()).isEqualTo("MINORISTA");
        var empty = update("Sociedad historica", null, "12345678Z"); empty.putArray("owners");
        save(id, empty, "admin").andExpect(status().isBadRequest());
        assertThat(profile(id)).isEqualTo(before);
        var completed = update("Sociedad completada", "Contacto", "X1234567L");
        JsonNode saved = response(save(id, completed, "admin").andExpect(status().isOk()));
        assertThat(saved.at("/owners/0/taxId").asText()).isEqualTo("X1234567L");
        assertThat(saved.get("commercialProfile").asText()).isEqualTo("MINORISTA");
        mvc.perform(put("/api/v1/admin/companies/{id}/operations", id).header("Authorization", auth("admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"contactName\":\"Contacto compatible\"}"))
                .andExpect(status().isOk());
        assertThat(profile(id).get("owners")).isEqualTo(saved.get("owners"));
    }

    @Test
    void backendEnforcesEditPermissionAndUnknownCompaniesCannotBeCreatedByProfilePut() throws Exception {
        UUID id = createCompany(); JsonNode before = profile(id);
        var request = update("Bloqueado", "No permitido", "12345678Z");
        save(id, request, "viewer").andExpect(status().isForbidden());
        assertThat(profile(id)).isEqualTo(before);
        save(UUID.randomUUID(), request, "admin").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/companies/{id}/profile", id)).andExpect(status().isUnauthorized());
    }

    @Test
    void simultaneousCompleteProfileWritesCannotMixCorporateAndContactData() throws Exception {
        UUID id = createCompany(); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return response(save(id, update("Perfil A", "Contacto A", "12345678Z"), "admin").andExpect(status().isOk())); });
            var second = executor.submit(() -> { start.await(); return response(save(id, update("Perfil B", "Contacto B", "X1234567L"), "admin").andExpect(status().isOk())); });
            start.countDown();
            JsonNode a = first.get(30, TimeUnit.SECONDS), b = second.get(30, TimeUnit.SECONDS);
            assertThat(a.get("companyName").asText()).isEqualTo("Perfil A");
            assertThat(a.get("contactName").asText()).isEqualTo("Contacto A");
            assertThat(b.get("companyName").asText()).isEqualTo("Perfil B");
            assertThat(b.get("contactName").asText()).isEqualTo("Contacto B");
            assertThat(profile(id)).isIn(a, b);
        }
        assertThat(auditCount(id)).isEqualTo(2);
    }

    private ObjectNode creation() {
        var request = mapper.createObjectNode();
        request.put("name", "Sociedad de prueba"); request.put("taxId", nextTaxId()); request.put("taxpayerType", "SOCIEDAD");
        request.set("companyAddress", mapper.valueToTree(fiscalAddress())); request.set("owners", mapper.valueToTree(companyOwners()));
        return request;
    }

    private ObjectNode update(String name, String contact, String ownerTaxId) {
        var request = mapper.createObjectNode(); request.put("name", name); request.put("contactName", contact); request.put("supportStatus", "NORMAL");
        request.set("companyAddress", mapper.valueToTree(fiscalAddress()));
        request.set("owners", mapper.valueToTree(List.of(new CompanyOwner("Titular", ownerTaxId, null, null))));
        return request;
    }

    private ResultActions create(ObjectNode request) throws Exception {
        return mvc.perform(post("/api/v1/admin/companies").header("Authorization", auth("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)));
    }

    private UUID createCompany() throws Exception { return id(response(create(creation()).andExpect(status().isOk()))); }
    private UUID id(JsonNode company) { return UUID.fromString(company.get("companyId").asText()); }
    private String nextTaxId() { return validCif("B" + COMPANY_NUMBER.incrementAndGet() + "0"); }

    private JsonNode profile(UUID id) throws Exception {
        return response(mvc.perform(get("/api/v1/admin/companies/{id}/profile", id).header("Authorization", auth("admin"))).andExpect(status().isOk()));
    }

    private ResultActions save(UUID id, ObjectNode request, String user) throws Exception {
        return mvc.perform(put("/api/v1/admin/companies/{id}/profile", id).header("Authorization", auth(user))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)));
    }

    private JsonNode response(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()); }
    private void assertInvalidCreation(ObjectNode request) throws Exception {
        create(request).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from saas_company where tax_id=?", Integer.class, request.get("taxId").asText())).isZero();
    }
    private int auditCount(UUID id) { return jdbc.queryForObject("select count(*) from saas_admin_audit_log where target_id=? and action='UPDATE_COMPANY_PROFILE'", Integer.class, id.toString()); }
    private String auth(String username) { return "Basic " + Base64.getEncoder().encodeToString((username + ":admin").getBytes(StandardCharsets.UTF_8)); }
}
