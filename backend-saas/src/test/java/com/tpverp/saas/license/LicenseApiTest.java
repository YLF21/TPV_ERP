package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.tpverp.saas.SaasTestData.fiscalAddress;
import static com.tpverp.saas.SaasTestData.validCif;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.CreateCompanyRequest;
import com.tpverp.saas.admin.CreateCompanyResponse;
import com.tpverp.saas.admin.PairingCodeResponse;
import com.tpverp.saas.admin.RenewLicenseRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class LicenseApiTest {

    private static final String WRONG_RECOVERY_TOKEN =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasPairingCodeRepository pairingCodes;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokenHasher;
    @Autowired JdbcTemplate jdbc;

    @Test
    void vinculaInstalacionYValidaLicencia() throws Exception {
        CreateCompanyResponse company = createCompany("B11111111");
        UUID installationId = UUID.randomUUID();

        var linkResult = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-1",
                                UUID.randomUUID(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSaasLinkResponse link = mapper.readValue(
                linkResult.getResponse().getContentAsString(),
                LicenseSaasLinkResponse.class);
        assertThat(link.licenseReference()).isEqualTo(company.licenseReference());
        assertThat(link.installationToken()).isNotBlank();

        var validationResult = mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                installationId,
                                "INST-1",
                                company.storeId(),
                                company.licenseReference(),
                                "hash-local"))))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSaasValidationResponse validation = mapper.readValue(
                validationResult.getResponse().getContentAsString(),
                LicenseSaasValidationResponse.class);
        assertThat(validation.status()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(validation.validUntil()).isEqualTo(Instant.parse("2099-07-01T00:00:00Z"));
        assertThat(validation.maxWindows()).isEqualTo(2);
        assertThat(validation.maxPda()).isEqualTo(1);
        assertThat(validation.licenseVersion()).isEqualTo(1);
    }

    @Test
    void enlaceSinTiendaLocalRecibeDomiciliosFiscalesDelSaas() throws Exception {
        Map<String, String> companyAddress = Map.of(
                "linea1", "Calle Fiscal 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        Map<String, String> storeAddress = Map.of(
                "linea1", "Avenida Tienda 2",
                "ciudad", "Telde",
                "codigoPostal", "35200",
                "provincia", "Las Palmas",
                "pais", "ES");
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateCompanyRequest(
                                "Empresa sin tienda local",
                                validCif("B88888888"),
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                CommercialProfile.MAYORISTA,
                                companyAddress,
                                "001",
                                "Tienda 1",
                                storeAddress,
                                "Atlantic/Canary",
                                Instant.parse("2099-07-01T00:00:00Z"),
                                2,
                                1))))
                .andExpect(status().isOk())
                .andReturn();
        CreateCompanyResponse company = mapper.readValue(
                result.getResponse().getContentAsString(), CreateCompanyResponse.class);

        UUID installationId = UUID.randomUUID();
        var linkResult = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                company.pairingCode(), installationId, "INST-NO-LOCAL", "public-key",
                                null, null, null, null))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasLinkResponse link = mapper.readValue(
                linkResult.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);

        assertThat(link.companyAddress()).containsAllEntriesOf(companyAddress);
        assertThat(link.storeAddress()).containsAllEntriesOf(storeAddress);
        assertThat(link.timeZoneId()).isEqualTo("Atlantic/Canary");
    }

    @Test
    void validacionRechazaTokenIncorrecto() throws Exception {
        CreateCompanyResponse company = createCompany("B22222222");
        UUID installationId = UUID.randomUUID();
        link(company, installationId);

        mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                installationId,
                                "INST-1",
                                company.storeId(),
                                company.licenseReference(),
                                "hash-local"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void primerEnlaceExigeSecretoDeRecuperacionSinConsumirElCodigo() throws Exception {
        CreateCompanyResponse company = createCompany("B34343434");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkRequest request = localLinkRequest(
                company.pairingCode(), installationId, "INST-RECOVERY",
                company.storeId(), "DEMO-00000000", "Empresa");

        mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void congelaHashesDeRecuperacionEnInstalacionYEnIntentoDePairing() throws Exception {
        CreateCompanyResponse company = createCompany("B51515151");
        UUID installationId = UUID.randomUUID();
        link(company, installationId);
        String differentHash = "0".repeat(64);

        assertThatThrownBy(() -> jdbc.update(
                        "update saas_installation set link_recovery_token_hash = ? "
                                + "where installation_id = ?",
                        differentHash,
                        installationId))
                .rootCause()
                .hasMessageContaining("link_recovery_token_hash es inmutable");
        assertThatThrownBy(() -> jdbc.update(
                        "update saas_pairing_code set link_recovery_token_hash = ? where code = ?",
                        differentHash,
                        company.pairingCode()))
                .rootCause()
                .hasMessageContaining("saas_pairing_code.link_recovery_token_hash es inmutable");
    }

    @Test
    void instalacionLegacySinHashDeRecuperacionPuedeReintentarConSuTokenPrevio() throws Exception {
        CreateCompanyResponse created = createCompany("B35353535");
        var pairing = pairingCodes.findFirstByCode(created.pairingCode()).orElseThrow();
        String previousToken = "legacy-installation-token";
        UUID installationId = UUID.randomUUID();
        var legacyInstallation = installations.save(new SaasInstallation(
                UUID.randomUUID(),
                pairing.getCompany(),
                pairing.getStore(),
                pairing.getLicense(),
                installationId,
                "INST-LEGACY-TOKEN",
                "public-key",
                tokenHasher.hash(previousToken),
                Instant.now()));
        pairing.consume(Instant.now(), legacyInstallation);
        pairingCodes.saveAndFlush(pairing);

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Installation-Token", previousToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                created.pairingCode(), installationId, "INST-LEGACY-TOKEN",
                                created.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk());
    }

    @Test
    void pairingNuevoDeInstalacionLegacyRecuperaRespuestaPerdidaConContextoDelIntento()
            throws Exception {
        CreateCompanyResponse created = createCompany("B36363636");
        var originalPairing = pairingCodes.findFirstByCode(created.pairingCode()).orElseThrow();
        String previousToken = "legacy-token-before-new-pairing";
        UUID installationId = UUID.randomUUID();
        installations.saveAndFlush(new SaasInstallation(
                UUID.randomUUID(),
                originalPairing.getCompany(),
                originalPairing.getStore(),
                originalPairing.getLicense(),
                installationId,
                "INST-LEGACY-NEW-PAIRING",
                "public-key",
                tokenHasher.hash(previousToken),
                Instant.now()));
        PairingCodeResponse replacementPairing = mapper.readValue(
                mvc.perform(post(
                                "/api/v1/admin/licenses/{reference}/pairing-codes",
                                created.licenseReference())
                                .header("Authorization", basic("admin", "admin")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                PairingCodeResponse.class);
        String recoveryToken = linkRecoveryToken(installationId);
        LicenseSaasLinkRequest request = localLinkRequest(
                replacementPairing.pairingCode(), installationId,
                "INST-LEGACY-NEW-PAIRING", created.storeId(),
                "DEMO-00000000", "Empresa");

        // La primera respuesta se pierde despues de rotar el token legacy.
        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Installation-Token", previousToken)
                        .header("X-TPV-Link-Recovery-Token", recoveryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        LicenseSaasLinkResponse firstRecovery = mapper.readValue(
                mvc.perform(post("/api/v1/license/link")
                                .header("X-TPV-Installation-Token", previousToken)
                                .header("X-TPV-Link-Recovery-Token", recoveryToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                LicenseSaasLinkResponse.class);
        LicenseSaasLinkResponse repeatedRecovery = mapper.readValue(
                mvc.perform(post("/api/v1/license/link")
                                .header("X-TPV-Link-Recovery-Token", recoveryToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                LicenseSaasLinkResponse.class);

        assertThat(repeatedRecovery.installationToken())
                .isEqualTo(firstRecovery.installationToken());
    }

    @Test
    void reintentoRecuperaRespuestaPerdidaSinConocerElPrimerInstallationToken() throws Exception {
        CreateCompanyResponse company = createCompany("B44444444");
        UUID installationId = UUID.randomUUID();
        String recoveryToken = linkRecoveryToken(installationId);

        // SaaS consume el codigo y emite una credencial, pero simulamos que la
        // primera respuesta se pierde sin leer installationToken.
        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", recoveryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-1",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", WRONG_RECOVERY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-1",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isUnauthorized());

        var retry = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", recoveryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-1",
                                UUID.randomUUID(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSaasLinkResponse recovered = mapper.readValue(
                retry.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);
        assertThat(recovered.installationToken()).isNotBlank();

        var repeatedRetry = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", recoveryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-1",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasLinkResponse repeated = mapper.readValue(
                repeatedRetry.getResponse().getContentAsString(),
                LicenseSaasLinkResponse.class);
        assertThat(repeated.installationToken()).isEqualTo(recovered.installationToken());
    }

    @Test
    void tokenEmitidoNoPuedeCambiarElContextoCongeladoDelReintento() throws Exception {
        CreateCompanyResponse company = createCompany("B57575757");
        UUID installationId = UUID.randomUUID();
        String recoveryToken = linkRecoveryToken(installationId);
        LicenseSaasLinkResponse first = link(company, installationId);
        LicenseSaasLinkRequest request = localLinkRequest(
                company.pairingCode(), installationId, "INST-1",
                company.storeId(), "DEMO-00000000", "Empresa");

        mvc.perform(post("/api/v1/license/link")
                        .with(value -> {
                            value.setRemoteAddr("203.0.113.57");
                            return value;
                        })
                        .header("X-TPV-Installation-Token", first.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        var recoveredResult = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", recoveryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasLinkResponse recovered = mapper.readValue(
                recoveredResult.getResponse().getContentAsString(),
                LicenseSaasLinkResponse.class);
        assertThat(recovered.installationToken()).isEqualTo(first.installationToken());
    }

    @Test
    void recoveryConsumidoDevuelveEstadoBloqueadoSinRepetirElPreflightDeAlta()
            throws Exception {
        CreateCompanyResponse company = createCompany("B58585858");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse first = link(company, installationId);
        SaasLicense license = licenses.findByReference(company.licenseReference()).orElseThrow();
        license.block();
        licenses.saveAndFlush(license);

        var result = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-1",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasLinkResponse recovered = mapper.readValue(
                result.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);

        assertThat(recovered.status()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        assertThat(recovered.installationToken()).isEqualTo(first.installationToken());
    }

    @Test
    void recoveryConsumidoEntregaElTokenOriginalAunqueLaInstalacionFueRevocada()
            throws Exception {
        CreateCompanyResponse company = createCompany("B59595959");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse first = link(company, installationId);
        SaasInstallation installation = installations.findByInstallationId(installationId)
                .orElseThrow();
        installation.revoke(Instant.now(), "admin", "prueba de recuperacion");
        installations.saveAndFlush(installation);

        var result = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-1",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasLinkResponse recovered = mapper.readValue(
                result.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);

        assertThat(recovered.status()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        assertThat(recovered.installationToken()).isEqualTo(first.installationToken());
    }

    @Test
    void reintentosConcurrentesDevuelvenLaMismaCredencialRecuperada() throws Exception {
        CreateCompanyResponse company = createCompany("B54545454");
        UUID installationId = UUID.randomUUID();
        String recoveryToken = linkRecoveryToken(installationId);
        LicenseSaasLinkRequest request = localLinkRequest(
                company.pairingCode(), installationId, "INST-CONCURRENT-RECOVERY",
                company.storeId(), "DEMO-00000000", "Empresa");

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", recoveryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> recoveredToken(start, request, recoveryToken));
            var second = pool.submit(() -> recoveredToken(start, request, recoveryToken));
            start.countDown();

            assertThat(first.get(15, TimeUnit.SECONDS))
                    .isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void pairingNuevoNoPuedeTomarUnaInstalacionExistenteSinTokenActual() throws Exception {
        CreateCompanyResponse company = createCompany("B47474747");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse first = link(company, installationId);
        var pairingResult = mvc.perform(post(
                        "/api/v1/admin/licenses/{reference}/pairing-codes", company.licenseReference())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        PairingCodeResponse replacementPairing = mapper.readValue(
                pairingResult.getResponse().getContentAsString(), PairingCodeResponse.class);
        var request = localLinkRequest(
                replacementPairing.pairingCode(), installationId, "INST-1",
                company.storeId(), "DEMO-00000000", "Empresa");

        mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Installation-Token", first.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void rechazoDeIdentidadNoConsumeElCodigoDeEnlace() throws Exception {
        CreateCompanyResponse company = createCompany("B45454545");
        UUID installationId = UUID.randomUUID();

        mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-IDENTIDAD",
                                company.storeId(), validCif("B56565656"), "Empresa"))))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-IDENTIDAD",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk());
    }

    @Test
    void zonaHorariaIncorrectaNoConsumeElCodigoDeEnlace() throws Exception {
        CreateCompanyResponse company = createCompany("B46464646");
        UUID installationId = UUID.randomUUID();

        mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                company.pairingCode(), installationId, "INST-ZONE", "public-key",
                                company.storeId(), "001", "DEMO-00000000", "Empresa",
                                null, null, "Europe/Madrid"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("La zona horaria local no coincide con la tienda de la licencia"));

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-ZONE",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk());
    }

    @Test
    void configuracionLegacyIncompletaNoConsumeCodigoYAdmiteFallbackCompleto() throws Exception {
        Instant now = Instant.now();
        String taxId = validCif("B67676767");
        var company = companies.save(new SaasCompany(
                UUID.randomUUID(), "Empresa Legacy", taxId,
                TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                CommercialProfile.MAYORISTA, null, now));
        var store = stores.save(new SaasStore(
                UUID.randomUUID(), company, "001", "Tienda Legacy", null,
                "Atlantic/Canary", now));
        var license = licenses.save(new SaasLicense(
                UUID.randomUUID(), company, "LIC-" + taxId + "-LEGACY-1",
                now.plusSeconds(86_400), 1, 0, now));
        String pairingCode = "TPV-LEGACY1";
        pairingCodes.save(new SaasPairingCode(
                UUID.randomUUID(), company, store, license, pairingCode,
                now.plusSeconds(3_600), now));
        UUID installationId = UUID.randomUUID();

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                pairingCode, installationId, "INST-LEGACY", "public-key",
                                store.getId(), "001", taxId, "Empresa Legacy",
                                null, null, "Atlantic/Canary"))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                pairingCode, installationId, "INST-LEGACY", "public-key",
                                store.getId(), "001", taxId, "Empresa Legacy",
                                fiscalAddress(), fiscalAddress(), "Atlantic/Canary"))))
                .andExpect(status().isOk());
    }

    @Test
    void mantieneUnBackendActivoPorTiendaSinConfundirloConElCupoWindows() throws Exception {
        CreateCompanyResponse company = createCompany("B78787878", 1);
        UUID firstInstallationId = UUID.randomUUID();
        LicenseSaasLinkResponse first = link(company, firstInstallationId);

        var firstRetry = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token",
                                linkRecoveryToken(firstInstallationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), firstInstallationId, "INST-1",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasLinkResponse retried = mapper.readValue(
                firstRetry.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);
        assertThat(retried.installationToken()).isEqualTo(first.installationToken());

        var pairingResult = mvc.perform(post(
                        "/api/v1/admin/licenses/{reference}/pairing-codes", company.licenseReference())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        PairingCodeResponse secondPairing = mapper.readValue(
                pairingResult.getResponse().getContentAsString(), PairingCodeResponse.class);
        UUID secondInstallationId = UUID.randomUUID();
        var secondRequest = localLinkRequest(
                secondPairing.pairingCode(), secondInstallationId, "INST-2",
                company.storeId(), "DEMO-00000000", "Empresa");

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(secondInstallationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(secondRequest)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", company.licenseReference())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RenewLicenseRequest(
                                Instant.parse("2100-01-01T00:00:00Z"), 2, 0))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(secondInstallationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(secondRequest)))
                .andExpect(status().isConflict());

        var validationResult = mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", retried.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                firstInstallationId, "INST-1", company.storeId(),
                                company.licenseReference(), "hash-local"))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasValidationResponse validation = mapper.readValue(
                validationResult.getResponse().getContentAsString(),
                LicenseSaasValidationResponse.class);
        assertThat(validation.maxWindows()).isEqualTo(2);
        assertThat(validation.maxPda()).isZero();
        assertThat(validation.licenseVersion()).isEqualTo(2);

        // maxWindows es el cupo de terminales Windows dentro de la tienda y no
        // el numero de backends SaaS. Reducirlo no depende de esta instalacion.
        mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", company.licenseReference())
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RenewLicenseRequest(
                                Instant.parse("2100-02-01T00:00:00Z"), 1, 0))))
                .andExpect(status().isOk());

        SaasInstallation oldInstallation = installations
                .findByInstallationId(firstInstallationId)
                .orElseThrow();
        oldInstallation.revoke(Instant.now(), "test", "sustitucion controlada");
        installations.saveAndFlush(oldInstallation);

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(firstInstallationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), firstInstallationId, "INST-1",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(secondInstallationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(secondRequest)))
                .andExpect(status().isOk());
        assertThat(installations.findByCompany_IdAndActiveTrue(company.companyId())).hasSize(1);
    }

    @Test
    void concurrenciaPostgresqlNoCreaDosBackendsActivosParaLaTienda() throws Exception {
        CreateCompanyResponse created = createCompany("B89898989", 1);
        var company = companies.findById(created.companyId()).orElseThrow();
        var store = stores.findById(created.storeId()).orElseThrow();
        var license = licenses.findByReference(created.licenseReference()).orElseThrow();
        String secondCode = "TPV-CONC-" + UUID.randomUUID().toString().substring(0, 8);
        pairingCodes.saveAndFlush(new SaasPairingCode(
                UUID.randomUUID(), company, store, license, secondCode,
                Instant.now().plusSeconds(3600), Instant.now()));

        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return linkStatus(created.pairingCode(), created.storeId(), UUID.randomUUID());
            });
            var second = pool.submit(() -> {
                start.await();
                return linkStatus(secondCode, created.storeId(), UUID.randomUUID());
            });
            start.countDown();

            LinkAttempt firstAttempt = first.get(20, TimeUnit.SECONDS);
            LinkAttempt secondAttempt = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(firstAttempt.status(), secondAttempt.status()))
                    .as("first=%s second=%s", firstAttempt, secondAttempt)
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(installations.findByCompany_IdAndActiveTrue(created.companyId())).hasSize(1);
    }

    @Test
    void devuelveBloqueadaManualCuandoAdminBloquea() throws Exception {
        CreateCompanyResponse company = createCompany("B33333333");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse link = link(company, installationId);

        mvc.perform(post("/api/v1/admin/licenses/{reference}/block", company.licenseReference())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());

        var validationResult = mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                installationId,
                                "INST-1",
                                company.storeId(),
                                company.licenseReference(),
                                "hash-local"))))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSaasValidationResponse validation = mapper.readValue(
                validationResult.getResponse().getContentAsString(),
                LicenseSaasValidationResponse.class);
        assertThat(validation.status()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
    }

    @Test
    void limitaIntentosDeEnlaceInvalidosPorCodigoAunqueCambieLaDireccionRemota() throws Exception {
        UUID installationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String[] equivalentCodes = {
                "tpv aaaaaaaaaaaa",
                "TPV  AAAAAAAAAAAA",
                "TPV\tAAAAAAAAAAAA",
                "TPV-AAAAAAAAAAAA",
                "tpv    aaaaaaaaaaaa"
        };

        for (int attempt = 0; attempt < 5; attempt++) {
            String remoteAddress = "203.0.113." + (25 + attempt);
            var request = localLinkRequest(
                    equivalentCodes[attempt], installationId, "INST-BRUTE-FORCE",
                    storeId, "DEMO-00000000", "Empresa");
            mvc.perform(post("/api/v1/license/link")
                            .with(mockRequest -> {
                                mockRequest.setRemoteAddr(remoteAddress);
                                return mockRequest;
                            })
                            .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        var canonicalRequest = localLinkRequest(
                "TPV-AAAAAAAAAAAA", installationId, "INST-BRUTE-FORCE",
                storeId, "DEMO-00000000", "Empresa");
        mvc.perform(post("/api/v1/license/link")
                        .with(mockRequest -> {
                            mockRequest.setRemoteAddr("203.0.113.250");
                            return mockRequest;
                        })
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(canonicalRequest)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void codigosInvalidosDistintosNoBloqueanUnEmparejamientoValidoDelMismoProxy() throws Exception {
        String remoteAddress = "192.0.2.44";
        UUID rejectedInstallationId = UUID.randomUUID();
        for (int attempt = 0; attempt < 5; attempt++) {
            var invalidRequest = localLinkRequest(
                    "TPV-CODIGO-INEXISTENTE-" + attempt,
                    rejectedInstallationId,
                    "INST-PROXY-INVALIDA",
                    UUID.randomUUID(),
                    "DEMO-00000000",
                    "Empresa");
            mvc.perform(post("/api/v1/license/link")
                            .with(mockRequest -> {
                                mockRequest.setRemoteAddr(remoteAddress);
                                return mockRequest;
                            })
                            .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(rejectedInstallationId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isNotFound());
        }

        CreateCompanyResponse company = createCompany("B69696969");
        UUID installationId = UUID.randomUUID();
        mvc.perform(post("/api/v1/license/link")
                        .with(mockRequest -> {
                            mockRequest.setRemoteAddr(remoteAddress);
                            return mockRequest;
                        })
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-PROXY-VALIDA",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk());
    }

    private LicenseSaasLinkResponse link(CreateCompanyResponse company, UUID installationId) throws Exception {
        var result = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                company.pairingCode(), installationId, "INST-1",
                                company.storeId(), "DEMO-00000000", "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);
    }

    private CreateCompanyResponse createCompany(String taxId) throws Exception {
        return createCompany(taxId, 2);
    }

    private CreateCompanyResponse createCompany(String taxId, int maxWindows) throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateCompanyRequest(
                                "Empresa",
                                validCif(taxId),
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                CommercialProfile.MAYORISTA,
                                fiscalAddress(),
                                "001",
                                "Tienda 1",
                                fiscalAddress(),
                                "Atlantic/Canary",
                                Instant.parse("2099-07-01T00:00:00Z"),
                                maxWindows,
                                1))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), CreateCompanyResponse.class);
    }

    private LinkAttempt linkStatus(String pairingCode, UUID storeId, UUID installationId) throws Exception {
        var result = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", linkRecoveryToken(installationId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(localLinkRequest(
                                pairingCode, installationId, "INST-CONC",
                                storeId, "DEMO-00000000", "Empresa"))))
                .andReturn();
        return new LinkAttempt(
                pairingCode,
                result.getResponse().getStatus(),
                result.getResponse().getErrorMessage(),
                result.getResolvedException() == null
                        ? null
                        : result.getResolvedException().getMessage());
    }

    private record LinkAttempt(String pairingCode, int status, String error, String exception) {
    }

    private LicenseSaasLinkRequest localLinkRequest(
            String pairingCode,
            UUID installationId,
            String installationReference,
            UUID storeId,
            String taxId,
            String companyName) {
        return new LicenseSaasLinkRequest(
                pairingCode,
                installationId,
                installationReference,
                "public-key",
                storeId,
                "001",
                taxId,
                companyName,
                null,
                null,
                "Atlantic/Canary");
    }

    private String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private String linkRecoveryToken(UUID installationId) {
        return "recovery-token-" + installationId.toString().replace("-", "");
    }

    private String recoveredToken(
            CountDownLatch start,
            LicenseSaasLinkRequest request,
            String recoveryToken) throws Exception {
        start.await();
        var result = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token", recoveryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(
                result.getResponse().getContentAsString(),
                LicenseSaasLinkResponse.class).installationToken();
    }
}
