package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.tpverp.saas.SaasTestData.fiscalAddress;
import static com.tpverp.saas.SaasTestData.validCif;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.LicenseSaasLinkRequest;
import com.tpverp.saas.license.LicenseSaasLinkResponse;
import com.tpverp.saas.license.LicenseSaasStatus;
import com.tpverp.saas.license.LicenseSaasValidationRequest;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.SaasLicenseRepository;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

    private static final String LEGACY_ADMIN_HASH =
            "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SaasAdminUserRepository adminUsers;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasLicenseRepository licenses;

    @BeforeEach
    void restoreViewerFixture() {
        adminUsers.findByUsernameIgnoreCase("viewer").ifPresent(user -> {
            user.changePasswordHash(LEGACY_ADMIN_HASH);
            adminUsers.save(user);
        });
    }

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
        assertThat(response.licenseReference()).isEqualTo("LIC-B12345674-001");
        assertThat(response.pairingCode()).matches("TPV-[A-HJ-NP-Z2-9]{12}");
    }

    @Test
    void rechazaAltaSinPerfilComercialExplicito() throws Exception {
        var payload = mapper.readTree(mapper.writeValueAsString(request("B24681357")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).putNull("commercialProfile");

        mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().isBadRequest());
        assertThat(companies.findAll().stream().noneMatch(
                company -> "B24681357".equals(company.getTaxId()))).isTrue();
    }

    @Test
    void estadoTecnicoPublicaLaMigracionYModulosReales() throws Exception {
        var result = mvc.perform(get("/api/v1/admin/status")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        SaasStatusResponse response = mapper.readValue(
                result.getResponse().getContentAsString(), SaasStatusResponse.class);
        assertThat(response.expectedMigration())
                .isEqualTo("V48__outbox_claims_and_delivery_safety");
        assertThat(response.modules()).contains(
                "licenses", "fiscal-provisioning", "fiscal-status",
                "operational-incidents");
    }

    @Test
    void normalizaIdentidadFiscalNombresYCodigoDeTienda() throws Exception {
        String taxId = validCif("B24681350");
        String decoratedTaxId = "  " + taxId.substring(0, 1).toLowerCase()
                + "-" + taxId.substring(1, 8) + "-" + taxId.substring(8) + "  ";
        var request = new CreateCompanyRequest(
                "  Empresa   Normalizada  ",
                decoratedTaxId,
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                com.tpverp.saas.license.CommercialProfile.MAYORISTA,
                fiscalAddress(),
                " 001 ",
                "  Tienda   Norte  ",
                fiscalAddress(),
                "Europe/Madrid",
                Instant.parse("2099-01-01T00:00:00Z"),
                1,
                0);

        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        CreateCompanyResponse created = mapper.readValue(
                result.getResponse().getContentAsString(), CreateCompanyResponse.class);

        assertThat(created.licenseReference())
                .isEqualTo("LIC-" + taxId + "-001");
        var licensesResult = mvc.perform(get("/api/v1/admin/licenses")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSummaryResponse[] licenses = mapper.readValue(
                licensesResult.getResponse().getContentAsString(), LicenseSummaryResponse[].class);
        assertThat(licenses)
                .filteredOn(value -> value.companyId().equals(created.companyId()))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.companyName()).isEqualTo("Empresa Normalizada");
                    assertThat(value.taxId()).isEqualTo(taxId);
                });
    }

    @Test
    void rechazaAltaConNifDireccionVigenciaOCuposInvalidos() throws Exception {
        var incompleteAddress = new LinkedHashMap<>(fiscalAddress());
        incompleteAddress.remove("provincia");
        var invalidTaxId = new CreateCompanyRequest(
                "Empresa", "B12345678", TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                com.tpverp.saas.license.CommercialProfile.MAYORISTA,
                fiscalAddress(), "TIENDA-A", "Tienda A", fiscalAddress(), "Europe/Madrid",
                Instant.parse("2099-01-01T00:00:00Z"), 1, 0);
        var invalidAddress = new CreateCompanyRequest(
                "Empresa", validCif("B13579130"), TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                com.tpverp.saas.license.CommercialProfile.MAYORISTA,
                incompleteAddress, "002", "Tienda B", fiscalAddress(), "Europe/Madrid",
                Instant.parse("2099-01-01T00:00:00Z"), 1, 0);
        var invalidTerms = new CreateCompanyRequest(
                "Empresa", validCif("B13579140"), TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                com.tpverp.saas.license.CommercialProfile.MAYORISTA,
                fiscalAddress(), "003", "Tienda C", fiscalAddress(), "Europe/Madrid",
                Instant.parse("2020-01-01T00:00:00Z"), 0, -1);
        var invalidTimeZone = new CreateCompanyRequest(
                "Empresa", validCif("B13579150"), TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                com.tpverp.saas.license.CommercialProfile.MAYORISTA,
                fiscalAddress(), "004", "Tienda D", fiscalAddress(), "Europe/NoExiste",
                Instant.parse("2099-01-01T00:00:00Z"), 1, 0);

        mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(invalidTaxId)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(invalidAddress)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(invalidTerms)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(invalidTimeZone)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rechazaEmpresaConNifDuplicadoTrasNormalizar() throws Exception {
        String taxId = validCif("B13579240");
        createCompany(taxId);
        CreateCompanyRequest duplicate = request(
                taxId.substring(0, 1).toLowerCase() + "-"
                        + taxId.substring(1, 8) + "-" + taxId.substring(8));

        mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict());
    }

    @Test
    void listadoExponeCaducadaSinMutarElEstadoPersistido() throws Exception {
        CreateCompanyResponse company = createCompany("B14725830");
        var license = licenses.findByReference(company.licenseReference()).orElseThrow();
        license.renew(Instant.parse("2020-01-01T00:00:00Z"), 2, 1);
        licenses.saveAndFlush(license);

        var result = mvc.perform(get("/api/v1/admin/licenses")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSummaryResponse[] summaries = mapper.readValue(
                result.getResponse().getContentAsString(), LicenseSummaryResponse[].class);
        assertThat(summaries)
                .filteredOn(value -> value.licenseReference().equals(company.licenseReference()))
                .singleElement()
                .extracting(LicenseSummaryResponse::status)
                .isEqualTo(LicenseSaasStatus.CADUCADA);
        assertThat(licenses.findByReference(company.licenseReference()).orElseThrow().getStatus())
                .isEqualTo(LicenseSaasStatus.VALIDA);
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
                        .content(mapper.writeValueAsString(new ChangeAdminPasswordRequest("admin-restored"))))
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
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                CommercialProfile.MAYORISTA))))
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
    void nombresDeUsuarioSonUnicosEntreAdminClienteYBootstrapOwner() throws Exception {
        CreateCompanyResponse company = createCompany("B77990144");

        mvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateAdminUserRequest(
                                company.tenantUsername().toUpperCase(java.util.Locale.ROOT),
                                "cross-realm-admin-pass",
                                "VIEWER"))))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateAdminUserRequest(
                                "cross-realm-admin",
                                "cross-realm-admin-pass",
                                "VIEWER"))))
                .andExpect(status().isOk());
        createTenantUser(
                        company.companyId(),
                        "CROSS-REALM-ADMIN",
                        "cross-realm-tenant-pass",
                        "VIEWER")
                .andExpect(status().isConflict());

        String bootstrapTaxId = validCif("B77990155");
        mvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateAdminUserRequest(
                                bootstrapTaxId.toLowerCase(java.util.Locale.ROOT),
                                "reserved-bootstrap-pass",
                                "VIEWER"))))
                .andExpect(status().isOk());

        CreateCompanyResponse bootstrapped = createCompany("B77990155");
        assertThat(bootstrapped.tenantUsername())
                .isEqualTo(bootstrapTaxId.toLowerCase(java.util.Locale.ROOT) + "-2");
    }

    @Test
    void resumenYEdicionConservanElPerfilComercialReal() throws Exception {
        CreateCompanyResponse company = createCompany("B66554444", CommercialProfile.MINORISTA);

        var listedResult = mvc.perform(get("/api/v1/admin/licenses")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSummaryResponse[] listed = mapper.readValue(
                listedResult.getResponse().getContentAsString(),
                LicenseSummaryResponse[].class);
        assertThat(listed)
                .filteredOn(value -> value.companyId().equals(company.companyId()))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.taxpayerType()).isEqualTo(TaxpayerType.SOCIEDAD);
                    assertThat(value.taxRegime()).isEqualTo(TaxRegime.IGIC);
                    assertThat(value.commercialProfile()).isEqualTo(CommercialProfile.MINORISTA);
                });

        var editedResult = mvc.perform(put("/api/v1/admin/companies/{companyId}", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new EditCompanyDataRequest(
                                "Empresa Minorista Editada",
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                CommercialProfile.MINORISTA))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSummaryResponse edited = mapper.readValue(
                editedResult.getResponse().getContentAsString(), LicenseSummaryResponse.class);

        assertThat(edited.companyName()).isEqualTo("Empresa Minorista Editada");
        assertThat(edited.commercialProfile()).isEqualTo(CommercialProfile.MINORISTA);
        assertThat(companies.findById(company.companyId()).orElseThrow().getCommercialProfile())
                .isEqualTo(CommercialProfile.MINORISTA);
    }

    @Test
    void rechazaEdicionSinPerfilComercial() throws Exception {
        CreateCompanyResponse company = createCompany("B66554455", CommercialProfile.MINORISTA);

        mvc.perform(put("/api/v1/admin/companies/{companyId}", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Empresa sin perfil",
                                  "taxpayerType": "SOCIEDAD",
                                  "impuestos": "IGIC"
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertThat(companies.findById(company.companyId()).orElseThrow().getCommercialProfile())
                .isEqualTo(CommercialProfile.MINORISTA);
    }

    @Test
    void noPermiteReclasificarLaIdentidadFiscalDespuesDeEmitirLicencia() throws Exception {
        CreateCompanyResponse company = createCompany("B67554430");

        mvc.perform(put("/api/v1/admin/companies/{companyId}", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new EditCompanyDataRequest(
                                "Empresa Reclasificada",
                                TaxpayerType.AUTONOMO,
                                TaxRegime.IVA,
                                CommercialProfile.MAYORISTA))))
                .andExpect(status().isConflict());

        SaasCompany persisted = companies.findById(company.companyId()).orElseThrow();
        assertThat(persisted.getTaxpayerType()).isEqualTo(TaxpayerType.SOCIEDAD);
        assertThat(persisted.getTaxRegime()).isEqualTo(TaxRegime.IGIC);
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
                                TaxRegime.IVA,
                                CommercialProfile.MAYORISTA))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aceptaPoliticaDeLicenciaPosteriorAlPlazoDeAdaptacionSif() throws Exception {
        var result = mvc.perform(put("/api/v1/admin/verifactu-activation-policies/SOCIEDAD")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateVerifactuActivationPolicyRequest(
                                java.time.LocalDate.of(2027, 1, 2),
                                "Politica comercial posterior al plazo de adaptacion SIF"))))
                .andExpect(status().isOk())
                .andReturn();

        VerifactuActivationPolicyResponse updated = mapper.readValue(
                result.getResponse().getContentAsString(),
                VerifactuActivationPolicyResponse.class);
        assertThat(updated.activationDate()).isEqualTo(java.time.LocalDate.of(2027, 1, 2));
        assertThat(updated.reason()).isEqualTo(
                "Politica comercial posterior al plazo de adaptacion SIF");
    }

    @Test
    void actualizaAprovisionamientoFiscalCompletoYNoDejaCambiosParciales() throws Exception {
        CreateCompanyResponse company = createCompany("B71234560");
        var initialResult = mvc.perform(get(
                        "/api/v1/admin/companies/{companyId}/fiscal-provisioning",
                        company.companyId())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        FiscalProvisioningResponse initial = mapper.readValue(
                initialResult.getResponse().getContentAsString(),
                FiscalProvisioningResponse.class);
        assertThat(initial.stores()).singleElement();

        var changedCompanyAddress = java.util.Map.of(
                "linea1", "Avenida Fiscal 20",
                "ciudad", "Madrid",
                "codigoPostal", "28001",
                "provincia", "Madrid",
                "pais", "ES");
        var changedStoreAddress = java.util.Map.of(
                "linea1", "Calle Tienda 5",
                "ciudad", "Madrid",
                "codigoPostal", "28002",
                "provincia", "Madrid",
                "pais", "ES");
        var incompleteRequest = new UpdateFiscalProvisioningRequest(
                changedCompanyAddress,
                java.util.List.of(new UpdateFiscalProvisioningRequest.StoreProvisioning(
                        UUID.randomUUID(), changedStoreAddress, "Europe/Madrid")));

        mvc.perform(put("/api/v1/admin/companies/{companyId}/fiscal-provisioning",
                        company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(incompleteRequest)))
                .andExpect(status().isBadRequest());

        var afterFailureResult = mvc.perform(get(
                        "/api/v1/admin/companies/{companyId}/fiscal-provisioning",
                        company.companyId())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        FiscalProvisioningResponse afterFailure = mapper.readValue(
                afterFailureResult.getResponse().getContentAsString(),
                FiscalProvisioningResponse.class);
        assertThat(afterFailure.companyAddress()).isEqualTo(initial.companyAddress());
        assertThat(afterFailure.stores().getFirst().storeAddress())
                .isEqualTo(initial.stores().getFirst().storeAddress());

        var validRequest = new UpdateFiscalProvisioningRequest(
                changedCompanyAddress,
                java.util.List.of(new UpdateFiscalProvisioningRequest.StoreProvisioning(
                        initial.stores().getFirst().storeId(),
                        changedStoreAddress,
                        "Europe/Madrid")));
        var updatedResult = mvc.perform(put(
                        "/api/v1/admin/companies/{companyId}/fiscal-provisioning",
                        company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andReturn();
        FiscalProvisioningResponse updated = mapper.readValue(
                updatedResult.getResponse().getContentAsString(),
                FiscalProvisioningResponse.class);
        assertThat(updated.companyAddress()).isEqualTo(changedCompanyAddress);
        assertThat(updated.stores()).singleElement().satisfies(store -> {
            assertThat(store.storeAddress()).isEqualTo(changedStoreAddress);
            assertThat(store.timeZoneId()).isEqualTo("Europe/Madrid");
        });

        mvc.perform(put("/api/v1/admin/companies/{companyId}/fiscal-provisioning",
                        company.companyId())
                        .header("Authorization", basic("viewer", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    void validaMotivoYPermisoAntesDeRevocarUnaInstalacion() throws Exception {
        CreateCompanyResponse company = createCompany("B71234570");
        UUID installationId = UUID.randomUUID();
        link(company, installationId);

        mvc.perform(post("/api/v1/admin/installations/{installationId}/revoke",
                        installationId)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"mal\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/installations/{installationId}/revoke",
                        installationId)
                        .header("Authorization", basic("viewer", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"equipo sustituido\"}"))
                .andExpect(status().isForbidden());

        var installationsResult = mvc.perform(get("/api/v1/admin/installations")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        InstallationSummaryResponse[] installations = mapper.readValue(
                installationsResult.getResponse().getContentAsString(),
                InstallationSummaryResponse[].class);
        assertThat(installations)
                .filteredOn(value -> value.installationId().equals(installationId))
                .singleElement()
                .satisfies(value -> assertThat(value.active()).isTrue());
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
                                Instant.parse("2100-01-01T00:00:00Z"),
                                5,
                                2))))
                .andExpect(status().isOk())
                .andReturn();

        AdminLicenseResponse response = mapper.readValue(
                result.getResponse().getContentAsString(),
                AdminLicenseResponse.class);
        assertThat(response.status()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(response.validUntil()).isEqualTo(Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(response.maxWindows()).isEqualTo(5);
        assertThat(response.maxPda()).isEqualTo(2);
    }

    @Test
    void rechazaRenovacionCaducadaOCuposInvalidos() throws Exception {
        CreateCompanyResponse company = createCompany("B22335566");

        mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", company.licenseReference())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RenewLicenseRequest(
                                Instant.parse("2020-01-01T00:00:00Z"), 1, 0))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", company.licenseReference())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RenewLicenseRequest(
                                Instant.parse("2100-01-01T00:00:00Z"), 0, -1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rechazaRenovarSinPermiso() throws Exception {
        CreateCompanyResponse company = createCompany("B55667788");

        mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", company.licenseReference())
                        .header("Authorization", basic("viewer", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RenewLicenseRequest(
                                Instant.parse("2100-01-01T00:00:00Z"),
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
        assertThat(response.pairingCode()).matches("TPV-[A-HJ-NP-Z2-9]{12}");
        assertThat(response.pairingCode()).isNotEqualTo(company.pairingCode());

        mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(linkRequest(company.pairingCode(), company.storeId()))))
                .andExpect(status().isConflict());

        var linkResult = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token",
                                "recovery-token-0123456789abcdef0123456789abcdef")
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
        assertThat(new BigDecimal(summary.monthlyRecurringRevenue()))
                .isGreaterThanOrEqualTo(new BigDecimal("79.90"));
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

        markInvoiceNotApplicable(invoice.get("id").asText());

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
    void rolesClienteRestringenEscrituraErpSinBloquearTickets() throws Exception {
        CreateCompanyResponse company = createCompany("B77990022");

        createTenantUser(company.companyId(), "tenant-viewer-role", "viewer-role-pass", "VIEWER")
                .andExpect(status().isOk());
        createTenantUser(company.companyId(), "tenant-billing-role", "billing-role-pass", "BILLING")
                .andExpect(status().isOk());
        createTenantUser(company.companyId(), "tenant-manager-role", "manager-role-pass", "MANAGER")
                .andExpect(status().isOk());
        createTenantUser(company.companyId(), "tenant-owner-role", "reserved-owner-pass", "OWNER")
                .andExpect(status().isBadRequest());
        createTenantUser(company.companyId(), "tenant-invalid-role", "invalid-role-pass", "SUPERUSER")
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/tenant/erp/customers")
                        .header("Authorization", basic("tenant-viewer-role", "viewer-role-pass")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenant/erp/customers")
                        .header("Authorization", basic("tenant-viewer-role", "viewer-role-pass"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(erpCustomerJson("CLI-VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tenant/erp/customers")
                        .header("Authorization", basic("tenant-billing-role", "billing-role-pass"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(erpCustomerJson("CLI-BILLING")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tenant/erp/customers")
                        .header("Authorization", basic("tenant-manager-role", "manager-role-pass"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(erpCustomerJson("CLI-MANAGER")))
                .andExpect(status().isOk());

        var ticketResult = mvc.perform(post("/api/v1/tenant/tickets")
                        .header("Authorization", basic("tenant-viewer-role", "viewer-role-pass"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateSupportTicketRequest(
                                "Consulta de solo lectura",
                                "El rol VIEWER debe conservar soporte",
                                "NORMAL"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID ticketId = UUID.fromString(mapper.readTree(ticketResult.getResponse().getContentAsString())
                .get("id").asText());
        mvc.perform(post("/api/v1/tenant/tickets/{ticketId}/comments", ticketId)
                        .header("Authorization", basic("tenant-billing-role", "billing-role-pass"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Comentario de facturacion"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void cambiarPasswordYDesactivarRevocaTodasLasSesionesDelUsuario() throws Exception {
        mvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "session-admin-revoke",
                                  "password": "initial-admin-pass",
                                  "roleName": "VIEWER"
                                }
                                """))
                .andExpect(status().isOk());
        String firstAdminToken = loginToken("session-admin-revoke", "initial-admin-pass");
        mvc.perform(get("/api/v1/admin/status")
                        .header("Authorization", "Bearer " + firstAdminToken))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/admin/users/{username}/password", "session-admin-revoke")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ChangeAdminPasswordRequest("changed-admin-pass"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/status")
                        .header("Authorization", "Bearer " + firstAdminToken))
                .andExpect(status().isUnauthorized());

        String secondAdminToken = loginToken("session-admin-revoke", "changed-admin-pass");
        mvc.perform(delete("/api/v1/admin/users/{username}", "session-admin-revoke")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/status")
                        .header("Authorization", "Bearer " + secondAdminToken))
                .andExpect(status().isUnauthorized());

        CreateCompanyResponse company = createCompany("B77990033");
        String firstTenantToken = loginToken(company.tenantUsername(), company.tenantInitialPassword());
        mvc.perform(get("/api/v1/tenant/me")
                        .header("Authorization", "Bearer " + firstTenantToken))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/admin/tenant-users/{username}/password", company.tenantUsername())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ChangeAdminPasswordRequest("changed-tenant-pass"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenant/me")
                        .header("Authorization", "Bearer " + firstTenantToken))
                .andExpect(status().isUnauthorized());

        String secondTenantToken = loginToken(company.tenantUsername(), "changed-tenant-pass");
        mvc.perform(delete("/api/v1/admin/tenant-users/{username}", company.tenantUsername())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenant/me")
                        .header("Authorization", "Bearer " + secondTenantToken))
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

    @Test
    void rechazaDocumentoDeVentaConTiendaDeOtraEmpresa() throws Exception {
        CreateCompanyResponse companyA = createCompany("B91000010");
        CreateCompanyResponse companyB = createCompany("B91000020");

        mvc.perform(post("/api/v1/admin/companies/{companyId}/sales-documents", companyA.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeId": "%s",
                                  "documentNumber": "VENTA-AJENA-1",
                                  "customerCode": "CLI-1",
                                  "total": "10.00",
                                  "currency": "EUR",
                                  "status": "CONFIRMADA",
                                  "issuedAt": "2026-09-01T10:00:00Z"
                                }
                                """.formatted(companyB.storeId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pagoEsIdempotenteYNoPermiteSuperarLaFactura() throws Exception {
        CreateCompanyResponse company = createCompany("B92000010");
        var invoiceResult = mvc.perform(post("/api/v1/admin/companies/{companyId}/invoices", company.companyId())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "number": "INV-INTEGRITY-1",
                                  "concept": "Integridad",
                                  "amount": "100.00",
                                  "currency": "EUR",
                                  "issuedAt": "2026-09-01T10:00:00Z",
                                  "dueAt": "2026-10-01T10:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID invoiceId = UUID.fromString(mapper.readTree(
                invoiceResult.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/api/v1/admin/invoices/{invoiceId}/payments", invoiceId)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": "1.00",
                                  "method": "TRANSFERENCIA",
                                  "paidAt": "2026-09-01T10:30:00Z",
                                  "reference": "BLOCK-PENDING"
                                }
                                """))
                .andExpect(status().isConflict());
        markInvoiceNotApplicable(invoiceId.toString());

        String payment = """
                {
                  "amount": "60.00",
                  "method": "TRANSFERENCIA",
                  "paidAt": "2026-09-01T11:00:00Z",
                  "reference": "IDEMPOTENT-1"
                }
                """;
        var first = mvc.perform(post("/api/v1/admin/invoices/{invoiceId}/payments", invoiceId)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment))
                .andExpect(status().isOk())
                .andReturn();
        var replay = mvc.perform(post("/api/v1/admin/invoices/{invoiceId}/payments", invoiceId)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(first.getResponse().getContentAsString()).get("id"))
                .isEqualTo(mapper.readTree(replay.getResponse().getContentAsString()).get("id"));

        mvc.perform(post("/api/v1/admin/invoices/{invoiceId}/payments", invoiceId)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment.replace("60.00", "50.00").replace("IDEMPOTENT-1", "OVERPAY-1")))
                .andExpect(status().isConflict());
    }

    @Test
    void integracionLocalConservaHistorialEIdempotencia() throws Exception {
        CreateCompanyResponse company = createCompany("B93000010");
        var created = mvc.perform(post("/api/v1/admin/integrations")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId": "%s",
                                  "name": "Export local",
                                  "integrationType": "ACCOUNTING_EXPORT",
                                  "status": "ACTIVA",
                                  "targetUrl": null,
                                  "apiKey": null
                                }
                                """.formatted(company.companyId())))
                .andExpect(status().isOk())
                .andReturn();
        UUID integrationId = UUID.fromString(mapper.readTree(
                created.getResponse().getContentAsString()).get("id").asText());

        for (int replay = 0; replay < 2; replay++) {
            mvc.perform(post("/api/v1/admin/integrations/{integrationId}/sync", integrationId)
                            .header("Authorization", basic("admin", "admin"))
                            .header("Idempotency-Key", "export-september"))
                    .andExpect(status().isOk());
        }
        var historyResult = mvc.perform(get("/api/v1/admin/integrations/{integrationId}/runs", integrationId)
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        var history = mapper.readTree(historyResult.getResponse().getContentAsString());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(history.get(0).get("deliveryMode").asText()).isEqualTo("LOCAL_OUTBOX");
        assertThat(history.get(0).get("payload").asText()).contains("\"schemaVersion\":1");
    }

    @Test
    void notificacionLeidaPermaneceMarcadaYNoAceptaIdsInventados() throws Exception {
        CreateCompanyResponse company = createCompany("B94000010");
        mvc.perform(post("/api/v1/admin/licenses/{reference}/block", company.licenseReference())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());
        String notificationId = "license-blocked-" + company.licenseReference();

        mvc.perform(put("/api/v1/admin/notifications/{notificationId}/read", notificationId)
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());
        var notificationsResult = mvc.perform(get("/api/v1/admin/notifications")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        var notifications = mapper.readTree(notificationsResult.getResponse().getContentAsString());
        assertThat(notifications).anySatisfy(notification -> {
            assertThat(notification.get("id").asText()).isEqualTo(notificationId);
            assertThat(notification.get("read").asBoolean()).isTrue();
        });

        mvc.perform(put("/api/v1/admin/notifications/{notificationId}/read", "inventada")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isNotFound());
    }
    private void markInvoiceNotApplicable(String invoiceId) throws Exception {
        mvc.perform(put("/api/v1/admin/invoices/{invoiceId}/fiscal", invoiceId)
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiscalStatus\":\"NOT_APPLICABLE\"}"))
                .andExpect(status().isOk());
    }

    private CreateCompanyRequest request(String taxId) {
        return request(taxId, CommercialProfile.MAYORISTA);
    }

    private CreateCompanyRequest request(String taxId, CommercialProfile commercialProfile) {
        return new CreateCompanyRequest(
                "Empresa",
                validCif(taxId),
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                commercialProfile,
                fiscalAddress(),
                "001",
                "Tienda 1",
                fiscalAddress(),
                "Atlantic/Canary",
                Instant.parse("2099-07-01T00:00:00Z"),
                2,
                1);
    }

    private CreateCompanyResponse createCompany(String taxId) throws Exception {
        return createCompany(taxId, CommercialProfile.MAYORISTA);
    }

    private CreateCompanyResponse createCompany(
            String taxId, CommercialProfile commercialProfile) throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(taxId, commercialProfile))))
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
                "001",
                "DEMO-00000000",
                "Empresa",
                null,
                null,
                "Atlantic/Canary");
    }

    private LicenseSaasLinkResponse link(CreateCompanyResponse company, UUID installationId) throws Exception {
        var result = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token",
                                "recovery-token-0123456789abcdef0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                company.pairingCode(),
                                installationId,
                                "INST-1",
                                "public-key",
                                company.storeId(),
                                "001",
                                "DEMO-00000000",
                                "Empresa",
                                null,
                                null,
                                "Atlantic/Canary"))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);
    }

    private org.springframework.test.web.servlet.ResultActions createTenantUser(
            UUID companyId, String username, String password, String roleName) throws Exception {
        return mvc.perform(post("/api/v1/admin/companies/{companyId}/tenant-users", companyId)
                .header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new CreateTenantUserRequest(username, password, roleName))));
    }

    private String loginToken(String username, String password) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SaasLoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), SaasLoginResponse.class).accessToken();
    }

    private static String erpCustomerJson(String code) {
        return """
                {
                  "code": "%s",
                  "name": "Cliente por rol",
                  "taxId": "11111111A",
                  "email": "rol@example.com",
                  "phone": "600000099"
                }
                """.formatted(code);
    }

    private String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
