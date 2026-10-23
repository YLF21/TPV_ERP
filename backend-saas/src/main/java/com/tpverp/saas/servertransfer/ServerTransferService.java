package com.tpverp.saas.servertransfer;

import com.tpverp.saas.license.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** All transitions lock the logical installation first, then its transfer. */
@Service
@Transactional
public class ServerTransferService {
    private final JdbcTemplate jdbc;
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator auth;
    private final TokenHasher tokens;
    public ServerTransferService(JdbcTemplate jdbc, SaasInstallationRepository installations,
            InstallationAuthenticator auth, TokenHasher tokens) {
        this.jdbc=jdbc; this.installations=installations; this.auth=auth; this.tokens=tokens;
    }
    public Status prepare(ServerTransferController.Prepare request, String token) {
        var installation = lock(request.installationId());
        auth.requireToken(installation, token);
        String fingerprint;
        try { fingerprint=TransferCryptography.fingerprint(request.destinationPublicKey()); }
        catch (IllegalArgumentException e) { throw failure(HttpStatus.BAD_REQUEST,"INVALID_DESTINATION_KEY"); }
        if (!TransferCryptography.verifyDestination(request.destinationPublicKey(),request.destinationProof()))
            throw failure(HttpStatus.FORBIDDEN,"DESTINATION_PROOF_INVALID");
        var prior = find(request.transferId());
        if (prior != null) {
            if (!prior.installationId().equals(request.installationId()) || !prior.destinationKeyFingerprint().equals(fingerprint))
                throw failure(HttpStatus.CONFLICT,"TRANSFER_ID_CONFLICT");
            return prior;
        }
        if (jdbc.queryForObject("select count(*) from saas_server_transfer where installation_id=? and state in ('PREPARED','SEALED')",Long.class,request.installationId())>0)
            throw failure(HttpStatus.CONFLICT,"TRANSFER_ALREADY_PENDING");
        jdbc.update("insert into saas_server_transfer(id,installation_id,state,destination_public_key,destination_key_fingerprint,source_token_hash) values (?,?,'PREPARED',?,?,?)",
                request.transferId(),request.installationId(),request.destinationPublicKey(),fingerprint,tokens.hash(token));
        return find(request.transferId());
    }
    public Status seal(UUID id, ServerTransferController.Seal request, String token) {
        auth.requireToken(lock(request.installationId()),token); var state=source(id,request.installationId(),token);
        if ("SEALED".equals(state.state())) {
            if (!Objects.equals(state.backupSha256(),request.backupSha256()) || !Objects.equals(state.capsuleSha256(),request.capsuleSha256()))
                throw failure(HttpStatus.CONFLICT,"TRANSFER_SEAL_CONFLICT");
            return state;
        }
        requireState(state,"PREPARED");
        jdbc.update("update saas_server_transfer set state='SEALED',backup_sha256=?,capsule_sha256=?,sealed_at=now() where id=?",request.backupSha256(),request.capsuleSha256(),id);
        return find(id);
    }
    public Status status(UUID id, UUID installationId, String token) {
        lock(installationId); return source(id,installationId,token);
    }
    public Status cancel(UUID id, UUID installationId, String token) {
        return cancel(id,installationId,token,null);
    }
    public Status cancel(UUID id, UUID installationId, String token,String destinationPublicKey) {
        auth.requireToken(lock(installationId),token);
        var existing=find(id);
        if(existing==null) {
            String fingerprint;
            try{fingerprint=TransferCryptography.fingerprint(destinationPublicKey);}
            catch(RuntimeException invalid){throw failure(HttpStatus.BAD_REQUEST,"INVALID_DESTINATION_KEY");}
            // A durable tombstone also wins against a delayed prepare with this same transfer ID.
            jdbc.update("insert into saas_server_transfer(id,installation_id,state,destination_public_key,destination_key_fingerprint,source_token_hash,cancelled_at) values (?,?,'CANCELLED',?,?,?,now())",
                    id,installationId,destinationPublicKey,fingerprint,tokens.hash(token));
            return find(id);
        }
        if(destinationPublicKey!=null) {
            String fingerprint;
            try{fingerprint=TransferCryptography.fingerprint(destinationPublicKey);}
            catch(RuntimeException invalid){throw failure(HttpStatus.BAD_REQUEST,"INVALID_DESTINATION_KEY");}
            if(!existing.destinationKeyFingerprint().equals(fingerprint))throw failure(HttpStatus.CONFLICT,"TRANSFER_DESTINATION_MISMATCH");
        }
        var state=source(id,installationId,token);
        if ("ACTIVATED".equals(state.state())) throw failure(HttpStatus.CONFLICT,"DESTINATION_ALREADY_ACTIVATED");
        jdbc.update("update saas_server_transfer set state='CANCELLED',cancelled_at=coalesce(cancelled_at,now()) where id=?",id);
        return find(id);
    }
    public Activation activate(UUID id, ServerTransferController.Activate request) {
        var installation=lock(request.installationId());
        var state=find(id);
        if (state==null || !state.installationId().equals(request.installationId())) throw failure(HttpStatus.NOT_FOUND,"TRANSFER_NOT_FOUND");
        var key=jdbc.queryForObject("select destination_public_key from saas_server_transfer where id=?",String.class,id);
        var payload="TPV-ACTIVATE-V1\n"+id+"\n"+request.installationId()+"\n"+request.backupSha256()+"\n"+request.capsuleSha256()+"\n"+state.destinationKeyFingerprint();
        if (!TransferCryptography.verify(key,payload,request.destinationProof())) throw failure(HttpStatus.FORBIDDEN,"DESTINATION_PROOF_INVALID");
        if (!Objects.equals(state.backupSha256(),request.backupSha256()) || !Objects.equals(state.capsuleSha256(),request.capsuleSha256()))
            throw failure(HttpStatus.CONFLICT,"TRANSFER_ARTIFACT_MISMATCH");
        if (!installation.isActive()) throw failure(HttpStatus.CONFLICT,"INSTALLATION_REVOKED");
        if ("ACTIVATED".equals(state.state())) {
            var issuedHash=jdbc.queryForObject("select activated_token_hash from saas_server_transfer where id=?",String.class,id);
            if(!installation.hasTokenHash(issuedHash))throw failure(HttpStatus.CONFLICT,"TRANSFER_ACTIVATION_SUPERSEDED");
            return activation(id);
        }
        requireState(state,"SEALED");
        var sourceHash=jdbc.queryForObject("select source_token_hash from saas_server_transfer where id=?",String.class,id);
        if(!installation.hasTokenHash(sourceHash))throw failure(HttpStatus.CONFLICT,"TRANSFER_SOURCE_SUPERSEDED");
        var token=tokens.newToken();
        var encrypted=TransferCryptography.encrypt(key,token);
        installation.rotateTokenHash(tokens.hash(token));
        installation.invalidateLinkRecovery();
        installations.saveAndFlush(installation);
        jdbc.update("update saas_server_transfer set state='ACTIVATED',encrypted_credential=?,activated_token_hash=?,activated_at=now() where id=?",encrypted,tokens.hash(token),id);
        return activation(id);
    }
    public Status inspect(UUID id,UUID installationId,String proof) {
        lock(installationId); var state=find(id);
        if(state==null || !state.installationId().equals(installationId)) throw failure(HttpStatus.NOT_FOUND,"TRANSFER_NOT_FOUND");
        var key=jdbc.queryForObject("select destination_public_key from saas_server_transfer where id=?",String.class,id);
        if(!TransferCryptography.verify(key,"TPV-INSPECT-V1\n"+id+"\n"+installationId,proof))
            throw failure(HttpStatus.FORBIDDEN,"DESTINATION_PROOF_INVALID");
        return state;
    }
    private Activation activation(UUID id) {
        return new Activation(find(id),jdbc.queryForObject("select encrypted_credential from saas_server_transfer where id=?",String.class,id));
    }
    private SaasInstallation lock(UUID installationId) {
        return installations.findByInstallationIdForUpdate(installationId).orElseThrow(()->failure(HttpStatus.NOT_FOUND,"INSTALLATION_NOT_FOUND"));
    }
    private Status source(UUID id, UUID installationId, String token) {
        var state=find(id);
        if (state==null || !state.installationId().equals(installationId)) throw failure(HttpStatus.NOT_FOUND,"TRANSFER_NOT_FOUND");
        var hash=jdbc.queryForObject("select source_token_hash from saas_server_transfer where id=?",String.class,id);
        if (token==null || !MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),tokens.hash(token).getBytes(StandardCharsets.US_ASCII)))
            throw failure(HttpStatus.UNAUTHORIZED,"TRANSFER_SOURCE_UNAUTHORIZED");
        return state;
    }
    private Status find(UUID id) {
        return jdbc.query("select * from saas_server_transfer where id=?",(rs,row)->new Status(rs.getObject("id",UUID.class),rs.getObject("installation_id",UUID.class),rs.getString("state"),rs.getString("destination_key_fingerprint"),rs.getString("backup_sha256"),rs.getString("capsule_sha256")),id).stream().findFirst().orElse(null);
    }
    private static void requireState(Status state,String expected) {
        if (!expected.equals(state.state())) throw failure(HttpStatus.CONFLICT,"TRANSFER_STATE_CONFLICT");
    }
    private static ResponseStatusException failure(HttpStatus status,String code) { return new ResponseStatusException(status,code); }
    public record Status(UUID transferId,UUID installationId,String state,String destinationKeyFingerprint,String backupSha256,String capsuleSha256) {}
    public record Activation(Status status,String encryptedCredential) {}
}
