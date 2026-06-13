package com.tpv.licenseissuer.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InstallationRequestReaderTest {
    private static String publicKeyPem;

    @BeforeAll
    static void createPublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPublic().getEncoded());
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    @Test
    void readsAValidInstallationRequest() {
        String json = """
                {
                  "id": "inst-001",
                  "reference": "SHOP-LPA-01",
                  "publicKey": "%s"
                }
                """.formatted(publicKeyPem.replace("\n", "\\n"));

        InstallationRequest request = new InstallationRequestReader().read(json);

        assertEquals("inst-001", request.id());
        assertEquals("SHOP-LPA-01", request.reference());
        assertEquals("RSA", request.publicKey().getAlgorithm());
    }

    @Test
    void rejectsMissingInstallationId() {
        String json = """
                {"reference":"SHOP-LPA-01","publicKey":"%s"}
                """.formatted(publicKeyPem.replace("\n", "\\n"));

        assertThrows(IllegalArgumentException.class, () -> new InstallationRequestReader().read(json));
    }

    @Test
    void rejectsNonRsaPublicKeys() {
        String json = """
                {"id":"inst-001","reference":"SHOP-LPA-01","publicKey":"invalid"}
                """;

        assertThrows(IllegalArgumentException.class, () -> new InstallationRequestReader().read(json));
    }
}
