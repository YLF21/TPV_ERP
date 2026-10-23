package com.tpverp.backend.servertransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.shared.crypto.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Runs under the actual Windows service identity, before any installation key is loaded. */
public final class ServerTransferEarlyGuard implements EnvironmentPostProcessor,Ordered {
    @Override public int getOrder(){return ConfigDataEnvironmentPostProcessor.ORDER+2;}
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication application) {
        String configured=environment.getProperty("tpv.installation.key-directory");
        if(configured==null || configured.isBlank())return;
        Path keys=Path.of(configured).toAbsolutePath().normalize();
        boolean handoff=Files.exists(keys.resolve(TransferSecretHandoff.FILE),LinkOption.NOFOLLOW_LINKS);
        if(!handoff) {
            if(new ServerTransferJournal(keys).frozen())throw new IllegalStateException("SERVER_TRANSFER_FROZEN: use offline maintenance to cancel or finish activation");
            return;
        }
        try {
            var state=TransferSecretHandoff.read(keys);
            if(!Set.of("ACTIVATED","BOOTSTRAP_VERIFIED").contains(state.path("phase").asText()))throw new IllegalStateException("Destination transfer has not been activated");
            UUID id=UUID.fromString(state.path("transferId").asText());
            String installation=state.path("installationId").asText(),digest=state.path("backupSha256").asText(),capsule=state.path("capsuleSha256").asText(),fingerprint=state.path("destinationKeyFingerprint").asText();
            byte[] privateBytes=state.path("destinationPrivate").binaryValue();
            var privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateBytes));Arrays.fill(privateBytes,(byte)0);
            String claim="TPV-ACTIVATE-V1\n"+id+"\n"+installation+"\n"+digest+"\n"+capsule+"\n"+fingerprint;
            URI base=URI.create(environment.getRequiredProperty("tpv.license.saas-url").replaceAll("/+$",""));
            if(!"https".equalsIgnoreCase(base.getScheme()) || base.getUserInfo()!=null)throw new IllegalStateException("Transfer requires HTTPS SaaS");
            var mapper=new ObjectMapper();
            var body=Map.of("installationId",installation,"backupSha256",digest,"capsuleSha256",capsule,"destinationProof",TransferEnvelope.sign(privateKey,claim));
            var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/server-transfers/"+id+"/activate")).timeout(Duration.ofSeconds(30))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("Destination activation proof unavailable");
            var result=mapper.readTree(response.body());var status=result.path("status");
            if(!"ACTIVATED".equals(status.path("state").asText()) || !id.toString().equals(status.path("transferId").asText())
                    || !installation.equals(status.path("installationId").asText()) || !digest.equals(status.path("backupSha256").asText())
                    || !capsule.equals(status.path("capsuleSha256").asText()) || !fingerprint.equals(status.path("destinationKeyFingerprint").asText()))throw new IllegalStateException("Destination activation proof mismatch");
            var serviceProtector=SecretProtectorFactory.portableOrWindowsDpapi(environment.getProperty("tpv.installation.portable-secret-key",""));
            var material=state.path("secrets");
            for(var entry:Map.of("identity.private","installation-private.dpapi","backup.brk","backup-brk.dpapi").entrySet()) {
                byte[] clear=material.path(entry.getKey()).binaryValue();
                if(clear==null || clear.length==0)throw new IllegalStateException("Transfer key material missing");
                try{TransferSecretHandoff.writeFile(keys.resolve(entry.getValue()),serviceProtector.protect(clear));}
                finally{Arrays.fill(clear,(byte)0);}
            }
            TransferSecretHandoff.writeFile(keys.resolve("installation-public.der"),material.path("identity.public").binaryValue());
            TransferSecretHandoff.writeFile(keys.resolve("tpv-backup-recovery.key"),material.path("backup.recovery").binaryValue());
            byte[] verifiedBrk=material.path("backup.brk").binaryValue(),verifiedRecovery=material.path("backup.recovery").binaryValue();
            try{new com.tpverp.backend.backup.application.BackupKeyStore(keys,serviceProtector).recordVerifiedRecoveryMaterial(verifiedBrk,verifiedRecovery);}
            finally{Arrays.fill(verifiedBrk,(byte)0);Arrays.fill(verifiedRecovery,(byte)0);}
            byte[] token=TransferEnvelope.rsa(javax.crypto.Cipher.DECRYPT_MODE,privateKey).doFinal(Base64.getDecoder().decode(result.path("encryptedCredential").asText()));
            try{TransferSecretHandoff.writeFile(keys.resolve("saas-installation-token.dpapi"),serviceProtector.protect(token));}finally{Arrays.fill(token,(byte)0);}
            Files.deleteIfExists(keys.resolve("saas-link-recovery-token.dpapi"));
            var identity=new InstallationIdentityStore(keys,serviceProtector).loadOrCreate();
            if(!MessageDigest.isEqual(identity.publicKey().getEncoded(),material.path("identity.public").binaryValue()))throw new IllegalStateException("Transferred identity verification failed");
            // HTTP, scheduled work and the external fence stay closed until the fiscal finalizer commits.
            state.put("phase","BOOTSTRAP_VERIFIED");
            TransferSecretHandoff.write(keys,state);
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Transfer bootstrap interrupted",e);}
        catch(Exception e){throw new IllegalStateException("Transfer bootstrap requires successful service-user rewrap and central activation proof",e);}
    }
}
