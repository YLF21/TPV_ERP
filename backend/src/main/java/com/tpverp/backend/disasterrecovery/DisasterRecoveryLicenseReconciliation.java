package com.tpverp.backend.disasterrecovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpverp.backend.licensing.*;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Service-account reconciliation; the administrator never reads or exports its installation credential. */
@Component
@Order(1)
public class DisasterRecoveryLicenseReconciliation implements ApplicationRunner {
    private final Path keys;
    private final JdbcTemplate jdbc;
    private final LicenseSaasCredentialStore credentials;
    private final LicenseRecoveryReconciler licenses;
    private final String saasUrl;

    public DisasterRecoveryLicenseReconciliation(@Value("${tpv.installation.key-directory}") Path keys,
            JdbcTemplate jdbc,LicenseSaasCredentialStore credentials,LicenseRecoveryReconciler licenses,
            @Value("${tpv.license.saas-url:}") String saasUrl) {
        this.keys=keys;this.jdbc=jdbc;this.credentials=credentials;this.licenses=licenses;this.saasUrl=saasUrl;
    }

    @Override public void run(ApplicationArguments arguments)throws Exception {
        if(!Files.exists(keys.resolve(RecoverySecretHandoff.LICENSE_FILE),LinkOption.NOFOLLOW_LINKS))return;
        var state=RecoverySecretHandoff.read(keys,RecoverySecretHandoff.LICENSE_FILE);
        if(!"LICENSE_RECONCILIATION_PENDING".equals(state.path("phase").asText()))
            throw new IllegalStateException("Recovery license handoff invalid");
        reconcile(state,true);
        Files.delete(keys.resolve(RecoverySecretHandoff.LICENSE_FILE));
    }

    void reconcile(JsonNode state,boolean completedOnly)throws Exception {
        UUID id=UUID.fromString(state.path("recoveryId").asText());
        var row=jdbc.queryForMap("select state,installation_id,backup_id,backup_sha256 from disaster_recovery where id=?",id);
        verifyDatabase(state,row,completedOnly);
        byte[] encoded=state.path("destinationPrivate").binaryValue();
        try {
            var key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            var token=credentials.readToken().orElseThrow(()->new IllegalStateException("Recovery credential missing"));
            var snapshot=new RecoveryCentralClient(saasUrl).license(id,state.path("backupSha256").asText(),key,token);
            verifyAuthority(state,snapshot);
            licenses.reconcile(snapshot);
        } finally {if(encoded!=null)Arrays.fill(encoded,(byte)0);}
    }

    static void verifyDatabase(JsonNode state,Map<String,Object> row,boolean completedOnly) {
        var allowed=completedOnly?Set.of("COMPLETE"):Set.of("ACTIVE","COMPLETE");
        if(!allowed.contains(row.get("state"))
                || !state.path("installationId").asText().equals(String.valueOf(row.get("installation_id")))
                || !state.path("backupId").asText().equals(String.valueOf(row.get("backup_id")))
                || !state.path("backupSha256").asText().matches("[0-9a-f]{64}")
                || !state.path("backupSha256").asText().equals(row.get("backup_sha256")))
            throw new IllegalStateException("Recovery license database proof mismatch");
    }

    static void verifyAuthority(JsonNode state,RecoveredLicenseSnapshot snapshot) {
        if(snapshot==null || !state.path("recoveryId").asText().equals(String.valueOf(snapshot.recoveryId()))
                || !state.path("installationId").asText().equals(String.valueOf(snapshot.installationId()))
                || !state.path("backupId").asText().equals(String.valueOf(snapshot.backupId()))
                || !state.path("backupSha256").asText().equals(snapshot.backupSha256())
                || !state.path("destinationKeyFingerprint").asText().matches("[0-9a-f]{64}")
                || !state.path("destinationKeyFingerprint").asText().equals(snapshot.destinationKeyFingerprint()))
            throw new IllegalStateException("Recovery license authority binding mismatch");
    }
}
