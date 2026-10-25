package com.tpverp.backend.disasterrecovery;

import com.tpverp.backend.shared.crypto.*;
import com.tpverp.backend.servertransfer.TransferEnvelope;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Recovery gets its own journal/protocol; no planned-transfer proof is accepted here. */
public final class DisasterRecoveryEarlyGuard implements EnvironmentPostProcessor,Ordered {
    @Override public int getOrder(){return ConfigDataEnvironmentPostProcessor.ORDER+2;}
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication app) {
        String configured=environment.getProperty("tpv.installation.key-directory");if(configured==null||configured.isBlank())return;
        Path keys=Path.of(configured).toAbsolutePath().normalize();
        boolean frozen=Files.exists(keys.resolve(DisasterRecoveryJournal.FREEZE),LinkOption.NOFOLLOW_LINKS);
        boolean handoff=Files.exists(keys.resolve(RecoverySecretHandoff.FILE),LinkOption.NOFOLLOW_LINKS);
        if(!frozen&&!handoff)return;
        if(!handoff)throw new IllegalStateException("DISASTER_RECOVERY_QUARANTINED: finish offline recovery and support review");
        try {
            var state=RecoverySecretHandoff.read(keys);
            if(!Set.of("ACTIVATED","BOOTSTRAP_VERIFIED").contains(state.path("phase").asText()))throw new IllegalStateException("Recovery requires support activation");
            UUID id=UUID.fromString(state.path("recoveryId").asText());String digest=state.path("backupSha256").asText();
            byte[] encoded=state.path("destinationPrivate").binaryValue();PrivateKey destination;
            try{destination=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));}finally{Arrays.fill(encoded,(byte)0);}
            var result=new RecoveryCentralClient(environment.getRequiredProperty("tpv.license.saas-url")).call(id,"activate",digest,destination);
            var status=result.path("status");
            if(!"ACTIVATED".equals(status.path("state").asText())||!id.toString().equals(status.path("recoveryId").asText())
                    ||!digest.equals(status.path("backupSha256").asText())||!state.path("installationId").asText().equals(status.path("installationId").asText())
                    ||!state.path("destinationKeyFingerprint").asText().equals(status.path("destinationKeyFingerprint").asText()))throw new IllegalStateException("Recovery central proof mismatch");
            var protector=SecretProtectorFactory.portableOrWindowsDpapi(environment.getProperty("tpv.installation.portable-secret-key",""));
            var material=state.path("secrets");
            for(var entry:Map.of("identity.private","installation-private.dpapi","backup.brk","backup-brk.dpapi").entrySet()) {
                byte[] clear=material.path(entry.getKey()).binaryValue();if(clear==null||clear.length==0)throw new IllegalStateException("Recovery key material missing");
                try{RecoverySecretHandoff.writeSecretFile(keys.resolve(entry.getValue()),protector.protect(clear));}finally{Arrays.fill(clear,(byte)0);}
            }
            RecoverySecretHandoff.writeSecretFile(keys.resolve("installation-public.der"),material.path("identity.public").binaryValue());
            RecoverySecretHandoff.writeSecretFile(keys.resolve("tpv-backup-recovery.key"),material.path("backup.recovery").binaryValue());
            // receive already authenticated this pair with the recovery passphrase and the backup.
            byte[] brk=material.path("backup.brk").binaryValue(),wrapped=material.path("backup.recovery").binaryValue();
            try{new com.tpverp.backend.backup.application.BackupKeyStore(keys,protector).recordVerifiedRecoveryMaterial(brk,wrapped);}
            finally{Arrays.fill(brk,(byte)0);Arrays.fill(wrapped,(byte)0);}
            byte[] token=TransferEnvelope.rsa(javax.crypto.Cipher.DECRYPT_MODE,destination).doFinal(Base64.getDecoder().decode(result.path("encryptedCredential").asText()));
            try{RecoverySecretHandoff.writeSecretFile(keys.resolve("saas-installation-token.dpapi"),protector.protect(token));}finally{Arrays.fill(token,(byte)0);}
            Files.deleteIfExists(keys.resolve("saas-link-recovery-token.dpapi"));
            var identity=new InstallationIdentityStore(keys,protector).loadOrCreate();
            if(!MessageDigest.isEqual(identity.publicKey().getEncoded(),material.path("identity.public").binaryValue()))throw new IllegalStateException("Recovered identity verification failed");
            state.put("phase","BOOTSTRAP_VERIFIED");RecoverySecretHandoff.write(keys,state);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Recovery bootstrap interrupted",interrupted);}
        catch(Exception failure){throw new IllegalStateException("Recovery bootstrap requires verified activation and service-account key protection",failure);}
    }
}
