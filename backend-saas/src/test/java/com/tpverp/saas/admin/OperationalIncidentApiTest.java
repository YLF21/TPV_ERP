package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationalIncidentApiTest {
    private static final Instant OLD = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void protegeTodosLosEndpointsAdminV2() throws Exception {
        UUID companyId = UUID.randomUUID();

        mvc.perform(post("/api/v2/admin/companies/{companyId}/member-wallet-bootstrap", companyId))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v2/admin/companies/{companyId}/member-wallet-bootstrap", companyId)
                        .header("Authorization", basic("viewer", "admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listaCancelaAuditaYRepiteElMismoComandoSinDuplicar() throws Exception {
        CompanyFixture company = company();
        UUID baselineId = bootstrap(company.companyId(), "COMPLETED", OLD, OLD, true);
        UUID residualId = bootstrap(company.companyId(), "COLLECTING", OLD, null, false);
        UUID commandId = UUID.randomUUID();
        String body = mapper.writeValueAsString(new OperationalIncidentModels.CancelMemberCategoryBootstrapRequest(
                commandId,
                "COLLECTING",
                "Residual vacio confirmado por soporte"));

        mvc.perform(get("/api/v1/admin/operational-incidents")
                        .param("companyId", company.companyId().toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/operational-incidents")
                        .header("Authorization", basic("viewer", "admin"))
                        .param("companyId", company.companyId().toString()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/operational-incidents")
                        .header("Authorization", basic("admin", "admin"))
                        .param("companyId", company.companyId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].incidentType").value("MEMBER_CATEGORY_BOOTSTRAP_STALLED"))
                .andExpect(jsonPath("$[0].targetId").value(residualId.toString()))
                .andExpect(jsonPath("$[0].completedBaselineId").value(baselineId.toString()))
                .andExpect(jsonPath("$[0].snapshotCount").value(0))
                .andExpect(jsonPath("$[0].chunkCount").value(0))
                .andExpect(jsonPath("$[0].inactive").value(true))
                .andExpect(jsonPath("$[0].cancellable").value(true));

        String endpoint = "/api/v1/admin/operational-incidents/companies/"
                + company.companyId() + "/member-category-bootstraps/" + residualId + "/cancel";
        mvc.perform(post(endpoint)
                        .header("Authorization", basic("viewer", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mvc.perform(post(endpoint)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        assertThat(jdbc.queryForObject(
                "select status from saas_member_category_bootstrap where id=?",
                String.class,
                residualId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "select count(*) from saas_operational_incident_command where command_id=?",
                Integer.class,
                commandId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from saas_admin_audit_log
                where action='CANCEL_MEMBER_CATEGORY_BOOTSTRAP_INCIDENT' and target_id=?
                """, Integer.class, residualId.toString())).isEqualTo(1);

        mvc.perform(post(endpoint)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new OperationalIncidentModels.CancelMemberCategoryBootstrapRequest(
                                        commandId, "COLLECTING", "Otra solicitud diferente"))))
                .andExpect(status().isConflict());
    }

    @Test
    void rechazaCancelarSinBaselineCompletado() throws Exception {
        CompanyFixture company = company();
        UUID residualId = bootstrap(company.companyId(), "COLLECTING", OLD, null, false);

        cancel(company.companyId(), residualId, "COLLECTING")
                .andExpect(status().isConflict());
        assertStatus(residualId, "COLLECTING");
    }

    @Test
    void rechazaCancelarBootstrapConSnapshotOActividadReciente() throws Exception {
        CompanyFixture withSnapshot = company();
        bootstrap(withSnapshot.companyId(), "COMPLETED", OLD, OLD, true);
        UUID residualWithSnapshot = bootstrap(withSnapshot.companyId(), "COLLECTING", OLD, null, false);
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                insert into saas_member_category_bootstrap_snapshot (
                    snapshot_id, bootstrap_id, store_id,
                    category_chunk_count, assignment_chunk_count,
                    category_count, assignment_count,
                    category_hash, assignment_hash, snapshot_checksum, created_at
                ) values (?, ?, ?, 0, 0, 0, 0, ?, ?, ?, ?)
                """, snapshotId, residualWithSnapshot, withSnapshot.storeId(),
                "0".repeat(64), "1".repeat(64), "2".repeat(64), Timestamp.from(OLD));

        cancel(withSnapshot.companyId(), residualWithSnapshot, "COLLECTING")
                .andExpect(status().isConflict());
        assertStatus(residualWithSnapshot, "COLLECTING");

        CompanyFixture recent = company();
        bootstrap(recent.companyId(), "COMPLETED", OLD, OLD, true);
        UUID recentResidual = bootstrap(recent.companyId(), "CONFLICT", Instant.now(), null, false);

        mvc.perform(get("/api/v1/admin/operational-incidents")
                        .header("Authorization", basic("admin", "admin"))
                        .param("companyId", recent.companyId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        cancel(recent.companyId(), recentResidual, "CONFLICT")
                .andExpect(status().isConflict());
        assertStatus(recentResidual, "CONFLICT");
    }

    private org.springframework.test.web.servlet.ResultActions cancel(
            UUID companyId,
            UUID bootstrapId,
            String expectedStatus) throws Exception {
        return mvc.perform(post(
                        "/api/v1/admin/operational-incidents/companies/{companyId}/member-category-bootstraps/{bootstrapId}/cancel",
                        companyId,
                        bootstrapId)
                .header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        new OperationalIncidentModels.CancelMemberCategoryBootstrapRequest(
                                UUID.randomUUID(), expectedStatus, "Revision operativa documentada"))));
    }

    private void assertStatus(UUID bootstrapId, String expected) {
        assertThat(jdbc.queryForObject(
                "select status from saas_member_category_bootstrap where id=?",
                String.class,
                bootstrapId)).isEqualTo(expected);
    }

    private CompanyFixture company() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into saas_company (id, name, tax_id, taxpayer_type, tax_regime, created_at)
                values (?, ?, ?, 'SOCIEDAD', 'IGIC', ?)
                """, companyId, "Empresa incidencias",
                "T" + companyId.toString().replace("-", "").substring(0, 30), Timestamp.from(now));
        jdbc.update("""
                insert into saas_store (id, company_id, code, name, created_at)
                values (?, ?, '001', 'Tienda incidencias', ?)
                """, storeId, companyId, Timestamp.from(now));
        return new CompanyFixture(companyId, storeId);
    }

    private UUID bootstrap(
            UUID companyId,
            String status,
            Instant createdAt,
            Instant completedAt,
            boolean baseline) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_member_category_bootstrap (
                    id, company_id, status, expected_store_count,
                    config_revision, assignment_revision,
                    created_at, completed_at, last_activity_at
                ) values (?, ?, ?, 1, ?, ?, ?, ?, ?)
                """, id, companyId, status,
                baseline ? 1L : null, baseline ? 1L : null,
                Timestamp.from(createdAt),
                completedAt == null ? null : Timestamp.from(completedAt),
                Timestamp.from(createdAt));
        return id;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private record CompanyFixture(UUID companyId, UUID storeId) {
    }
}
