package com.tpverp.backend.servertransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ServerTransferSaasClient {
    private final URI base;
    private final LicenseSaasCredentialStore credentials;
    private final ObjectMapper mapper;
    private final java.nio.file.Path keys;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ServerTransferSaasClient(@Value("${tpv.license.saas-url:${tpv.sync.central-url:}}") String url,LicenseSaasCredentialStore credentials,ObjectMapper mapper,
            @Value("${tpv.installation.key-directory}") java.nio.file.Path keys) {
        this.base=url==null || url.isBlank()?null:URI.create(url.replaceAll("/+$","")); this.credentials=credentials; this.mapper=mapper;
        this.keys=keys;
    }
    public void preserveSourceCancellationCredential(UUID transferId,UUID installationId) {
        byte[] token;
        try{token=mapper.writeValueAsBytes(Map.of("transferId",transferId,"installationId",installationId,"token",credentials.readToken().orElseThrow()));}
        catch(Exception e){throw new IllegalStateException("TRANSFER_CANCELLATION_CREDENTIAL_FAILED",e);}
        try{TransferSecretHandoff.requireProtectedDirectory(keys);TransferSecretHandoff.writeFile(keys.resolve("server-transfer-source-token.dpapi"),new com.tpverp.backend.shared.crypto.WindowsMachineDpapiSecretProtector().protect(token));}
        catch(Exception e){throw new IllegalStateException("TRANSFER_CANCELLATION_CREDENTIAL_FAILED",e);}
        finally{java.util.Arrays.fill(token,(byte)0);}
    }
    public void clearSourceCancellationCredential() {
        try{java.nio.file.Files.deleteIfExists(keys.resolve("server-transfer-source-token.dpapi"));}
        catch(java.io.IOException e){throw new IllegalStateException("TRANSFER_CANCELLATION_ESCROW_CLEANUP_FAILED",e);}
    }
    public Status prepare(UUID id,UUID installation,String key,String proof) {
        return send("/prepare",Map.of("transferId",id,"installationId",installation,"destinationPublicKey",key,"destinationProof",proof));
    }
    public Status seal(UUID id,UUID installation,String backup,String capsule) {
        return send("/"+id+"/seal",Map.of("installationId",installation,"backupSha256",backup,"capsuleSha256",capsule));
    }
    public Status cancel(UUID id,UUID installation) { return send("/"+id+"/cancel",Map.of("installationId",installation)); }
    public Status cancel(UUID id,UUID installation,String destinationPublicKey) {
        return send("/"+id+"/cancel",Map.of("installationId",installation,"destinationPublicKey",destinationPublicKey));
    }
    public Status status(UUID id,UUID installation) { return send("/"+id+"/status",Map.of("installationId",installation)); }
    private Status send(String route,Object body) {
        if (base==null || !"https".equalsIgnoreCase(base.getScheme()) || base.getUserInfo()!=null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TRANSFER_SAAS_HTTPS_REQUIRED");
        try {
            var token=credentials.readToken().orElseThrow();
            var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/server-transfers"+route)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type","application/json").header("X-TPV-Installation-Token",token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw new IllegalStateException("SaaS transfer response "+response.statusCode());
            return mapper.readValue(response.body(),Status.class);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TRANSFER_SAAS_UNAVAILABLE"); }
        catch(Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TRANSFER_SAAS_UNAVAILABLE"); }
    }
    public record Status(UUID transferId,UUID installationId,String state,String destinationKeyFingerprint,String backupSha256,String capsuleSha256) {}
}
