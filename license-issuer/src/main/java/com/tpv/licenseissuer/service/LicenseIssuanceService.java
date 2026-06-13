package com.tpv.licenseissuer.service;

import com.tpv.licenseissuer.crypto.LicenseCryptography;
import com.tpv.licenseissuer.crypto.Pkcs12IssuerStore;
import com.tpv.licenseissuer.model.LicenseDetails;
import com.tpv.licenseissuer.request.InstallationRequestReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;

public final class LicenseIssuanceService {
    private final InstallationRequestReader requestReader;
    private final Pkcs12IssuerStore issuerStore;
    private final LicenseCryptography cryptography;

    public LicenseIssuanceService() {
        this(Clock.systemUTC());
    }

    public LicenseIssuanceService(Clock clock) {
        this.requestReader = new InstallationRequestReader();
        this.issuerStore = new Pkcs12IssuerStore();
        this.cryptography = new LicenseCryptography(clock);
    }

    public void issue(
            Path requestPath,
            LicenseDetails details,
            Path keyStorePath,
            char[] password,
            Path outputPath) {
        if (requestPath == null || keyStorePath == null || outputPath == null) {
            throw new IllegalArgumentException("request, key store, and output paths are required");
        }
        try {
            var request = requestReader.read(Files.readString(requestPath, StandardCharsets.UTF_8));
            var issuer = issuerStore.loadOrCreate(keyStorePath, password);
            exportPublicKey(keyStorePath, issuer.publicKey().getEncoded());
            String envelope = cryptography.issue(request, details, issuer);
            Path parent = outputPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputPath, envelope + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read request or write license file", exception);
        }
    }

    private void exportPublicKey(Path keyStorePath, byte[] encodedPublicKey) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encodedPublicKey);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + body
                + "\n-----END PUBLIC KEY-----\n";
        Path absolute = keyStorePath.toAbsolutePath();
        Files.writeString(
                absolute.resolveSibling("license-issuer-public.pem"),
                pem,
                StandardCharsets.US_ASCII);
    }
}
