package com.tpv.licenseissuer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tpv.licenseissuer.model.LicenseDetails;
import com.tpv.licenseissuer.model.TaxRegime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseIssuanceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void exportsALicenseFileFromAnInstallationRequest() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var installation = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(installation.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        Path request = tempDir.resolve("installation.json");
        Files.writeString(request, """
                {"id":"inst-001","reference":"SHOP-LPA-01","publicKey":"%s"}
                """.formatted(pem.replace("\n", "\\n")));
        Path output = tempDir.resolve("license.json");
        Path keyStore = tempDir.resolve("issuer.p12");

        new LicenseIssuanceService(
                Clock.fixed(Instant.parse("2026-06-08T10:15:30Z"), ZoneOffset.UTC))
                .issue(request, new LicenseDetails(
                        "Example SL", "Main Store",
                        LocalDate.parse("2026-06-08"), LocalDate.parse("2027-06-08"),
                        3, 10, TaxRegime.IGIC),
                        keyStore, "strong local password".toCharArray(), output);

        assertTrue(Files.isRegularFile(keyStore));
        assertTrue(Files.isRegularFile(tempDir.resolve("license-issuer-public.pem")));
        assertTrue(Files.isRegularFile(output));
        JsonObject envelope = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertEquals(2, envelope.get("version").getAsInt());
        assertEquals("inst-001", envelope.get("installationId").getAsString());
    }
}
