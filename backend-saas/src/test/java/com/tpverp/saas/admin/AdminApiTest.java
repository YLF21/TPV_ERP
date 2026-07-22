package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.LicenseSaasLinkRequest;
import com.tpverp.saas.license.LicenseSaasLinkResponse;
import com.tpverp.saas.license.LicenseSaasStatus;
import com.tpverp.saas.license.LicenseSaasValidationRequest;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void creaEmpresaLicenciaYCodigoDeEnlace() throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request("B12345678"))))
                .andExpect(status().isOk())
                .andReturn();

        CreateCompanyResponse response = mapper.readValue(
                result.getResponse().getContentAsString(),
                CreateCompanyResponse.class);
        assertThat(response.companyId()).isNotNull();
        assertThat(response.storeId()).isNotNull();
        assertThat(response.licenseReference()).isEqualTo("LIC-B12345678-TIENDA-1");
        assertThat(response.pairingCode()).startsWith("TPV-");
    }

    @Test
    void consultaYActualizaPoliticaGlobalVerifactuConAuditoria() throws Exception {
        var listResult = mvc.perform(get("/api/v1/admin/verifactu-activation-policies")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        VerifactuActivationPolicyResponse[] policies = mapper.readValue(
                listResult.getResponse().getContentAsString(),
                VerifactuActivationPolicyResponse[].class);
        assertThat(policies)
                .extracting(VerifactuActivationPolicyResponse::taxpayerType)
                .containsExactly(TaxpayerType.SOCIEDAD, TaxpayerType.AUTONOMO);

        var updateResult = mvc.perform(put("/api/v1/admin/verifactu-activation-policies/SOCIEDAD")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateVerifactuActivationPolicyRequest(
                                java.time.LocalDate.of(2027, 1, 1),
                                "Prueba de distribucion centralizada"))))
                .andExpect(status().isOk())
                .andReturn();

        VerifactuActivationPolicyResponse updated = mapper.readValue(
                updateResult.getResponse().getContentAsString(),
                VerifactuActivationPolicyResponse.class);
        assertThat(updated.taxpayerType()).isEqualTo(TaxpayerType.SOCIEDAD);
        assertThat(updated.version()).isGreaterThan(0);
        assertThat(updated.updatedBy()).isEqualTo("admin");
        assertThat(updated.reason()).isEqualTo("Prueba de distribucion centralizada");

        var auditResult = mvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        AdminAuditLogResponse[] audit = mapper.readValue(
                auditResult.getResponse().getContentAsString(),
                AdminAuditLogResponse[].class);
        assertThat(audit)
                .filteredOn(value -> value.action().equals("UPDATE_VERIFACTU_ACTIVATION_POLICY"))
                .anySatisfy(value -> assertThat(value.details())
                        .contains("newDate=2027-01-01", "reason=Prueba de distribucion centralizada"));
    }

    @Test
    void impideModificarPoliticaVerifactuSinPermisoFiscal() throws Exception {
        mvc.perform(put("/api/v1/admin/verifactu-activation-policies/AUTONOMO")
                        .header("Authorization", basic("viewer", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateVerifactuActivationPolicyRequest(
                                java.time.LocalDate.of(2027, 7, 1),
                                "Intento sin permiso fiscal"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditaAccionesAdmin() throws Exception {
        CreateCompanyResponse company = createCompany("B91919191");

        var result = mvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        AdminAuditLogResponse[] audit = mapper.readValue(
                result.getResponse().getContentAsString(),
                AdminAuditLogResponse[].class);
        assertThat(audit)
                .filteredOn(value -> value.action().equals("ADD_COMPANY")
                        && value.targetId().equals(company.companyId().toString()))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.username()).isEqualTo("admin");
                    assertThat(value.targetType()).isEqualTo("COMPANY");
                    assertThat(value.createdAt()).isNotNull();
                });
    }

    @Test
    void cambiaPasswordUsuarioAdmin() throws Exception {
        mvc.perform(put("/api/v1/admin/users/{username}/password", "viewer")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ChangeAdminPasswordRequest("viewer-new-password"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", basic("viewer", "admin")))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", basic("viewer", "viewer-new-password")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/admin/users/{username}/password", "viewer")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ChangeAdminPasswordRequest("123"))))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/admin/users/{username}/password", "viewer")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ChangeAdminPasswordRequest("admin"))))
                .andExpect(status().isOk());
    }

    @Test
    void creaListaYDesactivaUsuarioAdmin() throws Exception {
        mvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateAdminUserRequest(
                                "support1",
                                "supportpass",
                                "VIEWER"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", basic("support1", "supportpass")))
                .andExpect(status().isOk());

        var listResult = mvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        AdminUserResponse[] users = mapper.readValue(
                listResult.getResponse().getContentAsString(),
                AdminUserResponse[].class);
        assertThat(users)
                .filteredOn(value -> value.username().equals("support1"))
                .singleElement()
                .satisfies(value -> assertThat(value.active()).isTrue());

        mvc.perform(delete("/api/v1/admin/users/{username}", "support1")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", basic("support1", "supportpass")))
                .andExpect(status().isUnauthorized());

        var inactiveResult = mvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        AdminUserResponse[] inactiveUsers = mapper.readValue(
                inactiveResult.getResponse().getContentAsString(),
                AdminUserResponse[].class);
        assertThat(inactiveUsers)
                .filteredOn(value -> value.username().equals("support1"))
                .singleElement()
                .satisfies(value -> assertThat(value.active()).isFalse());
    }

    @Test
    void rechazaAdminSinCredenciales() throws Exception {
        mvc.perform(post("/api/v1/admin/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request("B87654321"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rechazaBasicAuthMalFormado() throws Exception {
        mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", "Basic ???")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request("B88776655"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listaLicencias() throws Exception {
        CreateCompanyResponse company = createCompany("B11223344");

        var result = mvc.perform(get("/api/v1/admin/licenses")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSummaryResponse[] licenses = mapper.readValue(
                result.getResponse().getContentAsString(),
                LicenseSummaryResponse[].class);
        assertThat(licenses)
                .extracting(LicenseSummaryResponse::licenseReference)
                .contains(company.licenseReference());
    }

    @Test
    void editaDatosEmpresa() throws Exception {
        CreateCompanyResponse company = createCompany("B66554433");

        mvc.perform(put("/api/v1/admin/companies/{companyId}", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new EditCompanyDataRequest(
                                "Empresa Editada",
                                TaxpayerType.AUTONOMO,
                                TaxRegime.IVA))))
                .andExpect(status().isOk());

        var result = mvc.perform(get("/api/v1/admin/licenses")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSummaryResponse[] licenses = mapper.readValue(
                result.getResponse().getContentAsString(),
                LicenseSummaryResponse[].class);
        assertThat(licenses)
                .filteredOn(value -> value.companyId().equals(company.companyId()))
                .singleElement()
                .satisfies(value -> assertThat(value.companyName()).isEqualTo("Empresa Editada"));
    }

    @Test
    void rechazaEditarEmpresaSinPermiso() throws Exception {
        CreateCompanyResponse company = createCompany("B55443322");

        mvc.perform(put("/api/v1/admin/companies/{companyId}", company.companyId())
                        .header("Authorization", basic("viewer", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new EditCompanyDataRequest(
                                "Empresa Editada",
                                TaxpayerType.AUTONOMO,
                                TaxRegime.IVA))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listaInstalacionesVinculadas() throws Exception {
        CreateCompanyResponse company = createCompany("B44556677");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse link = link(company, installationId);

        mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                installationId,
                                "INST-1",
                                company.storeId(),
                                company.licenseReference(),
                                "hash-local"))))
                .andExpect(status().isOk());

        var result = mvc.perform(get("/api/v1/admin/installations")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        InstallationSummaryResponse[] installations = mapper.readValue(
                result.getResponse().getContentAsString(),
                InstallationSummaryResponse[].class);
        assertThat(installations)
                .filteredOn(value -> value.installationId().equals(installationId))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.companyId()).isEqualTo(company.companyId());
                    assertThat(value.storeId()).isEqualTo(company.storeId());
                    assertThat(value.licenseReference()).isEqualTo(company.licenseReference());
                    assertThat(value.lastValidatedAt()).isNotNull();
                });
    }

    @Test
    void renuevaLicenciaYCambiaLimites() throws Exception {
        CreateCompanyResponse company = createCompany("B22334455");

        var result = mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", company.licenseReference())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RenewLicenseRequest(
                                Instant.parse("2028-01-01T00:00:00Z"),
                                5,
                                2))))
                .andExpect(status().isOk())
                .andReturn();

        AdminLicenseResponse response = mapper.readValue(
                result.getResponse().getContentAsString(),
                AdminLicenseResponse.class);
        assertThat(response.status()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(response.validUntil()).isEqualTo(Instant.parse("2028-01-01T00:00:00Z"));
        assertThat(response.maxWindows()).isEqualTo(5);
        assertThat(response.maxPda()).isEqualTo(2);
    }

    @Test
    void rechazaRenovarSinPermiso() throws Exception {
        CreateCompanyResponse company = createCompany("B55667788");

        mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", company.licenseReference())
                        .header("Authorization", basic("viewer", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RenewLicenseRequest(
                                Instant.parse("2028-01-01T00:00:00Z"),
                                5,
                                2))))
                .andExpect(status().isForbidden());
    }

    @Test
    void regeneraCodigoDeEnlaceEInvalidaElAnterior() throws Exception {
        CreateCompanyResponse company = createCompany("B33445566");

        var result = mvc.perform(post("/api/v1/admin/licenses/{reference}/pairing-codes", company.licenseReference())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        PairingCodeResponse response = mapper.readValue(
                result.getResponse().getContentAsString(),
                PairingCodeResponse.class);
        assertThat(response.pairingCode()).startsWith("TPV-");
        assertThat(response.pairingCode()).isNotEqualTo(company.pairingCode());

        mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(linkRequest(company.pairingCode(), company.storeId()))))
                .andExpect(status().isConflict());

        var linkResult = mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(linkRequest(response.pairingCode(), company.storeId()))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasLinkResponse link = mapper.readValue(
                linkResult.getResponse().getContentAsString(),
                LicenseSaasLinkResponse.class);
        assertThat(link.licenseReference()).isEqualTo(company.licenseReference());
    }

    @Test
    void calculaPulsoDeClienteConRiesgoPorFacturacionYSoporte() throws Exception {
        CreateCompanyResponse company = createCompany("B33557799");
        mvc.perform(put("/api/v1/admin/companies/{companyId}/operations", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateCompanyOperationsRequest(
                                "STANDARD",
                                "IMPAGADO",
                                null,
                                "49.90",
                                "NORMAL",
                                "Cliente Demo",
                                "cliente@example.com",
                                "Pendiente de revisar"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/companies/{companyId}/tickets", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateSupportTicketRequest(
                                "No sincroniza",
                                "Caja sin eventos",
                                "URGENTE"))))
                .andExpect(status().isOk());

        var result = mvc.perform(get("/api/v1/admin/health")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        CustomerHealthResponse[] health = mapper.readValue(
                result.getResponse().getContentAsString(),
                CustomerHealthResponse[].class);
        assertThat(health)
                .filteredOn(value -> value.companyId().equals(company.companyId()))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.riskLevel()).isEqualTo("DANGER");
                    assertThat(value.openTickets()).isEqualTo(1);
                    assertThat(value.billingStatus()).isEqualTo("IMPAGADO");
                    assertThat(value.signals()).contains("Facturacion pendiente");
                });
    }

    @Test
    void calculaResumenDeFacturacionSaas() throws Exception {
        CreateCompanyResponse company = createCompany("B33779911");
        mvc.perform(put("/api/v1/admin/companies/{companyId}/operations", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateCompanyOperationsRequest(
                                "PREMIUM",
                                "IMPAGADO",
                                Instant.now().plus(Duration.ofDays(10)),
                                "79.90",
                                "NORMAL",
                                "Facturacion Demo",
                                "billing@example.com",
                                "Renovar manualmente"))))
                .andExpect(status().isOk());

        var result = mvc.perform(get("/api/v1/admin/billing-summary")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        BillingSummaryResponse summary = mapper.readValue(
                result.getResponse().getContentAsString(),
                BillingSummaryResponse.class);
        assertThat(summary.overdueCompanies()).isGreaterThanOrEqualTo(1);
        assertThat(summary.pendingCompanies()).isGreaterThanOrEqualTo(1);
        assertThat(summary.renewalsNext30Days()).isGreaterThanOrEqualTo(1);
        assertThat(new BigDecimal(summary.monthlyRecurringRevenue())).isGreaterThanOrEqualTo(new BigDecimal("79.90"));
        assertThat(summary.companies())
                .filteredOn(value -> value.companyId().equals(company.companyId()))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.planName()).isEqualTo("PREMIUM");
                    assertThat(value.billingStatus()).isEqualTo("IMPAGADO");
                    assertThat(value.monthlyPrice()).isEqualTo("79.90");
                    assertThat(value.renewalDueSoon()).isTrue();
                });
    }

    @Test
    void portalClienteConsultaSusDatosYCreaTicket() throws Exception {
        CreateCompanyResponse company = createCompany("B44112233");
        mvc.perform(put("/api/v1/admin/companies/{companyId}/operations", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateCompanyOperationsRequest(
                                "STANDARD",
                                "PENDIENTE",
                                Instant.now().plus(Duration.ofDays(20)),
                                "49.90",
                                "NORMAL",
                                "Cliente Portal",
                                "cliente@example.com",
                                "Portal activo"))))
                .andExpect(status().isOk());

        var sessionResult = mvc.perform(get("/api/v1/tenant/me")
                        .header("Authorization", basic(company.tenantUsername(), company.tenantInitialPassword())))
                .andExpect(status().isOk())
                .andReturn();
        var session = mapper.readTree(sessionResult.getResponse().getContentAsString());
        assertThat(session.get("companyId").asText()).isEqualTo(company.companyId().toString());
        assertThat(session.get("companyName").asText()).isEqualTo("Empresa");
        assertThat(session.get("roleName").asText()).isEqualTo("OWNER");

        var dashboardResult = mvc.perform(get("/api/v1/tenant/dashboard")
                        .header("Authorization", basic(company.tenantUsername(), company.tenantInitialPassword())))
                .andExpect(status().isOk())
                .andReturn();
        var dashboard = mapper.readTree(dashboardResult.getResponse().getContentAsString());
        assertThat(dashboard.get("licenses").asInt()).isEqualTo(1);
        assertThat(dashboard.get("stores").asInt()).isEqualTo(1);
        assertThat(dashboard.get("billingStatus").asText()).isEqualTo("PENDIENTE");
        assertThat(dashboard.get("monthlyPrice").asText()).isEqualTo("49.90");

        mvc.perform(post("/api/v1/tenant/tickets")
                        .header("Authorization", basic(company.tenantUsername(), company.tenantInitialPassword()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateSupportTicketRequest(
                                "Consulta cliente",
                                "Necesito revisar mi licencia",
                                "NORMAL"))))
                .andExpect(status().isOk());

        var ticketResult = mvc.perform(get("/api/v1/admin/companies/{companyId}/tickets", company.companyId())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        SupportTicketResponse[] tickets = mapper.readValue(
                ticketResult.getResponse().getContentAsString(),
                SupportTicketResponse[].class);
        assertThat(tickets)
                .filteredOn(value -> value.title().equals("Consulta cliente"))
                .singleElement()
                .satisfies(value -> assertThat(value.createdBy()).isEqualTo("tenant:" + company.tenantUsername()));
    }

    @Test
    void fase8GestionaFacturasPagosYPortalClienteLasConsulta() throws Exception {
        CreateCompanyResponse company = createCompany("B77889911");

        var invoiceResult = mvc.perform(post("/api/v1/admin/companies/{companyId}/invoices", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "number": "SaaS-2026-0001",
                                  "concept": "Suscripcion julio",
                                  "amount": "79.90",
                                  "currency": "EUR",
                                  "issuedAt": "2026-07-01T00:00:00Z",
                                  "dueAt": "2026-07-31T00:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var invoice = mapper.readTree(invoiceResult.getResponse().getContentAsString());
        assertThat(invoice.get("status").asText()).isEqualTo("PENDIENTE");
        assertThat(invoice.get("paidAmount").asText()).isEqualTo("0.00");

        mvc.perform(post("/api/v1/admin/invoices/{invoiceId}/payments", invoice.get("id").asText())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": "79.90",
                                  "method": "TRANSFERENCIA",
                                  "paidAt": "2026-07-05T10:00:00Z",
                                  "reference": "TR-001"
                                }
                                """))
                .andExpect(status().isOk());

        var adminInvoicesResult = mvc.perform(get("/api/v1/admin/companies/{companyId}/invoices", company.companyId())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        var adminInvoices = mapper.readTree(adminInvoicesResult.getResponse().getContentAsString());
        assertThat(adminInvoices.get(0).get("status").asText()).isEqualTo("PAGADA");
        assertThat(adminInvoices.get(0).get("paidAmount").asText()).isEqualTo("79.90");

        var tenantInvoicesResult = mvc.perform(get("/api/v1/tenant/invoices")
                        .header("Authorization", basic(company.tenantUsername(), company.tenantInitialPassword())))
                .andExpect(status().isOk())
                .andReturn();
        var tenantInvoices = mapper.readTree(tenantInvoicesResult.getResponse().getContentAsString());
        assertThat(tenantInvoices).hasSize(1);
        assertThat(tenantInvoices.get(0).get("number").asText()).isEqualTo("SaaS-2026-0001");
        assertThat(tenantInvoices.get(0).get("status").asText()).isEqualTo("PAGADA");
    }

    @Test
    void fase8GestionaUsuariosClienteCompletos() throws Exception {
        CreateCompanyResponse company = createCompany("B77990011");

        mvc.perform(post("/api/v1/admin/companies/{companyId}/tenant-users", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "cliente-billing",
                                  "password": "billing-pass",
                                  "roleName": "BILLING"
                                }
                                """))
                .andExpect(status().isOk());

        var usersResult = mvc.perform(get("/api/v1/admin/companies/{companyId}/tenant-users", company.companyId())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        var users = mapper.readTree(usersResult.getResponse().getContentAsString());
        assertThat(users)
                .anySatisfy(user -> {
                    assertThat(user.get("username").asText()).isEqualTo("cliente-billing");
                    assertThat(user.get("roleName").asText()).isEqualTo("BILLING");
                    assertThat(user.get("active").asBoolean()).isTrue();
                });

        mvc.perform(get("/api/v1/tenant/me")
                        .header("Authorization", basic("cliente-billing", "billing-pass")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/admin/tenant-users/{username}/password", "cliente-billing")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ChangeAdminPasswordRequest("billing-new-pass"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenant/me")
                        .header("Authorization", basic("cliente-billing", "billing-pass")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tenant/me")
                        .header("Authorization", basic("cliente-billing", "billing-new-pass")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/admin/tenant-users/{username}", "cliente-billing")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenant/me")
                        .header("Authorization", basic("cliente-billing", "billing-new-pass")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fase9GestionaMaestrosErpAisladosPorEmpresa() throws Exception {
        CreateCompanyResponse companyA = createCompany("B90110011");
        CreateCompanyResponse companyB = createCompany("B90110022");

        mvc.perform(post("/api/v1/admin/companies/{companyId}/erp/customers", companyA.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "CLI-A",
                                  "name": "Cliente A",
                                  "taxId": "11111111A",
                                  "email": "cliente-a@example.com",
                                  "phone": "600000001"
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/companies/{companyId}/erp/customers", companyB.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "CLI-B",
                                  "name": "Cliente B",
                                  "taxId": "22222222B",
                                  "email": "cliente-b@example.com",
                                  "phone": "600000002"
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/companies/{companyId}/erp/products", companyA.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "PROD-A",
                                  "name": "Producto A",
                                  "category": "General",
                                  "price": "12.50",
                                  "taxRate": "21.00",
                                  "minStock": "3.00"
                                }
                                """))
                .andExpect(status().isOk());

        var customersAResult = mvc.perform(get("/api/v1/admin/companies/{companyId}/erp/customers", companyA.companyId())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        var customersA = mapper.readTree(customersAResult.getResponse().getContentAsString());
        assertThat(customersA).hasSize(1);
        assertThat(customersA.get(0).get("code").asText()).isEqualTo("CLI-A");
        assertThat(customersA.get(0).get("companyId").asText()).isEqualTo(companyA.companyId().toString());

        var customersBResult = mvc.perform(get("/api/v1/admin/companies/{companyId}/erp/customers", companyB.companyId())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        var customersB = mapper.readTree(customersBResult.getResponse().getContentAsString());
        assertThat(customersB).hasSize(1);
        assertThat(customersB.get(0).get("code").asText()).isEqualTo("CLI-B");

        var productsAResult = mvc.perform(get("/api/v1/admin/companies/{companyId}/erp/products", companyA.companyId())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        var productsA = mapper.readTree(productsAResult.getResponse().getContentAsString());
        assertThat(productsA).hasSize(1);
        assertThat(productsA.get(0).get("sku").asText()).isEqualTo("PROD-A");
    }

    @Test
    void fase9PortalClienteGestionaSusMaestrosErp() throws Exception {
        CreateCompanyResponse company = createCompany("B90110033");

        mvc.perform(post("/api/v1/tenant/erp/suppliers")
                        .header("Authorization", basic(company.tenantUsername(), company.tenantInitialPassword()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "PROV-1",
                                  "name": "Proveedor Portal",
                                  "taxId": "33333333C",
                                  "email": "proveedor@example.com",
                                  "phone": "600000003"
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/tenant/erp/warehouses")
                        .header("Authorization", basic(company.tenantUsername(), company.tenantInitialPassword()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "ALM-1",
                                  "name": "Almacen Portal",
                                  "address": "Calle Portal 1"
                                }
                                """))
                .andExpect(status().isOk());

        var suppliersResult = mvc.perform(get("/api/v1/tenant/erp/suppliers")
                        .header("Authorization", basic(company.tenantUsername(), company.tenantInitialPassword())))
                .andExpect(status().isOk())
                .andReturn();
        var suppliers = mapper.readTree(suppliersResult.getResponse().getContentAsString());
        assertThat(suppliers).hasSize(1);
        assertThat(suppliers.get(0).get("code").asText()).isEqualTo("PROV-1");

        var warehousesResult = mvc.perform(get("/api/v1/tenant/erp/warehouses")
                        .header("Authorization", basic(company.tenantUsername(), company.tenantInitialPassword())))
                .andExpect(status().isOk())
                .andReturn();
        var warehouses = mapper.readTree(warehousesResult.getResponse().getContentAsString());
        assertThat(warehouses).hasSize(1);
        assertThat(warehouses.get(0).get("code").asText()).isEqualTo("ALM-1");
    }

    private CreateCompanyRequest request(String taxId) {
        return new CreateCompanyRequest(
                "Empresa",
                taxId,
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                "TIENDA-1",
                "Tienda 1",
                Instant.parse("2027-07-01T00:00:00Z"),
                2,
                1);
    }

    private CreateCompanyResponse createCompany(String taxId) throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(taxId))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), CreateCompanyResponse.class);
    }

    private LicenseSaasLinkRequest linkRequest(String pairingCode, UUID storeId) {
        return new LicenseSaasLinkRequest(
                pairingCode,
                UUID.randomUUID(),
                "INST-1",
                "public-key",
                storeId,
                "TIENDA-1",
                "B00000000",
                "Empresa");
    }

    private LicenseSaasLinkResponse link(CreateCompanyResponse company, UUID installationId) throws Exception {
        var result = mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                company.pairingCode(),
                                installationId,
                                "INST-1",
                                "public-key",
                                company.storeId(),
                                "TIENDA-1",
                                "B00000000",
                                "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);
    }

    private String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
