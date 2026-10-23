package com.tpverp.saas.disasterrecovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpverp.saas.backup.CloudBackupDescriptor;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class DisasterRecoveryModels {
    private DisasterRecoveryModels() { }
    public record Create(@NotNull UUID requestId,@NotNull UUID installationId,@NotNull UUID backupId,
            @NotBlank @Size(max=2048) String destinationPublicKey,@NotBlank @Size(max=4000) String note) { }
    public record Review(@Min(0) long expectedVersion,@NotBlank @Size(max=4000) String note,boolean sourceRetiredConfirmed) { }
    public record Proof(@NotBlank @Size(max=2048) String destinationProof) { }
    public record Received(@NotBlank @Size(max=2048) String destinationProof,@NotNull JsonNode report) { }
    /** MVC uses Jackson 3; its wire types must not expose Jackson 2 JsonNode. */
    public record ReceivedRequest(@NotBlank @Size(max=2048) String destinationProof,
            @NotNull Map<String,Object> report) { }
    public record State(UUID recoveryId,UUID ticketId,UUID installationId,UUID backupId,String state,long version,
            String destinationKeyFingerprint,String backupSha256,Instant expiresAt,List<String> blockingReasons,
            long sequenceFloor,CloudBackupDescriptor backupDescriptor,JsonNode evidence,List<String> warnings) { }
    public record Activation(State status,String encryptedCredential) { }
    public record StateResponse(UUID recoveryId,UUID ticketId,UUID installationId,UUID backupId,String state,long version,
            String destinationKeyFingerprint,String backupSha256,Instant expiresAt,List<String> blockingReasons,
            long sequenceFloor,CloudBackupDescriptor backupDescriptor,Map<String,Object> evidence,List<String> warnings) { }
    public record ActivationResponse(StateResponse status,String encryptedCredential) { }
}
