package com.tpv.licenseissuer.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tpv.licenseissuer.model.LicenseDetails;
import com.tpv.licenseissuer.model.TaxRegime;
import com.tpv.licenseissuer.model.TaxpayerType;
import com.tpv.licenseissuer.request.InstallationRequest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LicenseCryptographyTest {
    private KeyPair issuer;
    private KeyPair installation;
    private LicenseCryptography cryptography;

    @BeforeEach
    void setUp() throws Exception {
        issuer = rsaKeyPair();
        installation = rsaKeyPair();
        cryptography = new LicenseCryptography(
                Clock.fixed(Instant.parse("2026-06-08T10:15:30Z"), ZoneOffset.UTC));
    }

    @Test
    void encryptsWrapsAndSignsAVersionedLicenseEnvelope() {
        String json = cryptography.issue(
                new InstallationRequest("inst-001", "SHOP-LPA-01", installation.getPublic()),
                new LicenseDetails("B12345678", TaxpayerType.SOCIEDAD, "Example SL", "Main Store",
                        LocalDate.parse("2026-06-08"), LocalDate.parse("2027-06-08"),
                        3, 10, TaxRegime.IGIC),
                new IssuerIdentity(issuer.getPrivate(), issuer.getPublic()));

        JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(3, envelope.get("version").getAsInt());
        assertEquals("AES-256-GCM", envelope.get("payloadEncryption").getAsString());
        assertEquals("RSA-OAEP-256", envelope.get("keyEncryption").getAsString());
        assertEquals("RSA-PSS-256", envelope.get("signatureAlgorithm").getAsString());
        assertTrue(cryptography.verify(json, issuer.getPublic()));

        LicensePayload payload = cryptography.decryptAndVerify(
                json, installation.getPrivate(), issuer.getPublic());
        assertEquals("inst-001", payload.installationId());
        assertEquals("B12345678", payload.taxId());
        assertEquals(TaxpayerType.SOCIEDAD, payload.taxpayerType());
        assertEquals("Example SL", payload.company());
        assertEquals(3, payload.maxWindows());
        assertEquals(10, payload.maxPda());
        assertEquals(TaxRegime.IGIC, payload.impuestos());
    }

    @Test
    void detectsEnvelopeTampering() {
        String json = cryptography.issue(
                new InstallationRequest("inst-001", "SHOP-LPA-01", installation.getPublic()),
                new LicenseDetails("B12345678", TaxpayerType.SOCIEDAD, "Example SL", "Main Store",
                        LocalDate.parse("2026-06-08"), LocalDate.parse("2027-06-08"),
                        3, 10, TaxRegime.IVA),
                new IssuerIdentity(issuer.getPrivate(), issuer.getPublic()));
        JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
        envelope.addProperty("installationReference", "ALTERED");

        assertThrows(IllegalArgumentException.class,
                () -> cryptography.decryptAndVerify(
                        envelope.toString(), installation.getPrivate(), issuer.getPublic()));
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
