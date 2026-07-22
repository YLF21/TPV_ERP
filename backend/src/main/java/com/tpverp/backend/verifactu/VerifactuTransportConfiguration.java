package com.tpverp.backend.verifactu;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.tpverp.backend.shared.crypto.WindowsMachineDpapiSecretProtector;
import com.sun.jna.Platform;
import java.nio.file.Path;
import java.util.Locale;

@Configuration
public class VerifactuTransportConfiguration {

    @Bean
    public VerifactuTransport verifactuTransport(
            VerifactuSubmissionPropertiesFactory propertiesFactory,
            ManagedCertificateKeyStoreFactory keyStores,
            VerifactuMutualTlsHttpClientFactory clients) {
        return new ConfiguredVerifactuTransport(propertiesFactory, keyStores, clients);
    }
    // Registers the real transport using the configured certificate for mTLS.

    @Bean
    VerifactuCertificateSecretStore verifactuCertificateSecretStore(
            @Value("${tpv.verifactu.secret-directory}") Path directory,
            @Value("${tpv.verifactu.secret-acl-mode:PORTABLE}") String aclMode,
            @Value("${tpv.verifactu.secret-service-account:NT SERVICE\\TPVERPBackend}")
                    String serviceAccount) {
        var mode = parseAclMode(aclMode);
        SecretDirectoryAccessPolicy accessPolicy;
        if (mode == VerifactuSecretAclMode.STRICT) {
            if (!Platform.isWindows()) {
                throw new IllegalStateException(
                        "La ACL STRICT de secretos VERI*FACTU requiere Windows y NTFS");
            }
            accessPolicy = new WindowsNtfsSecretDirectoryAccessPolicy(serviceAccount);
        } else {
            accessPolicy = VerifactuCertificateSecretStore.defaultAccessPolicy();
        }
        return new VerifactuCertificateSecretStore(
                directory, new WindowsMachineDpapiSecretProtector(), accessPolicy);
    }

    private static VerifactuSecretAclMode parseAclMode(String value) {
        try {
            return VerifactuSecretAclMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "tpv.verifactu.secret-acl-mode debe ser PORTABLE o STRICT", exception);
        }
    }
}
