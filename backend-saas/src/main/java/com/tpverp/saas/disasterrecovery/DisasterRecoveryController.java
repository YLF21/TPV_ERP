package com.tpverp.saas.disasterrecovery;

import static com.tpverp.saas.disasterrecovery.DisasterRecoveryModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class DisasterRecoveryController {
    private final DisasterRecoveryService service;
    private final ObjectMapper mapper;
    public DisasterRecoveryController(DisasterRecoveryService service,ObjectMapper mapper) {this.service=service;this.mapper=mapper;}
    @PostMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries")
    public StateResponse create(@PathVariable UUID ticket,@Valid @RequestBody Create request) {return response(service.create(ticket,request));}
    @GetMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/backups")
    public java.util.List<com.tpverp.saas.backup.CloudBackupDescriptor> backups(@PathVariable UUID ticket,@RequestParam UUID installationId) {return service.backups(ticket,installationId);}
    @GetMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/backups/{backupId}/artifact")
    public ResponseEntity<InputStreamResource> backupArtifact(@PathVariable UUID ticket,@PathVariable UUID backupId,@RequestParam UUID installationId)throws Exception {return binary(service.downloadBackup(ticket,installationId,backupId),"backup.tpvb");}
    @GetMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/backups/{backupId}/wrapped-key")
    public ResponseEntity<byte[]> backupKey(@PathVariable UUID ticket,@PathVariable UUID backupId,@RequestParam UUID installationId) {return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=tpv-backup-recovery.key").body(service.downloadBackupKey(ticket,installationId,backupId));}
    @GetMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/installations")
    public java.util.List<java.util.Map<String,Object>> installations(@PathVariable UUID ticket) {return service.ticketInstallations(ticket);}
    @GetMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/{id}")
    public StateResponse state(@PathVariable UUID ticket,@PathVariable UUID id) {return response(service.adminState(ticket,id));}
    @PostMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/{id}/approve")
    public StateResponse approve(@PathVariable UUID ticket,@PathVariable UUID id,@Valid @RequestBody Review request) {return response(service.approve(ticket,id,request));}
    @PostMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/{id}/cancel")
    public StateResponse cancel(@PathVariable UUID ticket,@PathVariable UUID id,@Valid @RequestBody Review request) {return response(service.cancel(ticket,id,request));}
    @PostMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/{id}/renew")
    public StateResponse renew(@PathVariable UUID ticket,@PathVariable UUID id,@Valid @RequestBody Review request) {return response(service.renew(ticket,id,request));}
    @GetMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/{id}/artifact")
    public ResponseEntity<InputStreamResource> artifact(@PathVariable UUID ticket,@PathVariable UUID id) throws Exception {return binary(service.adminArtifact(ticket,id),"backup.tpvb");}
    @GetMapping("/api/v1/admin/tickets/{ticket}/backup-recoveries/{id}/wrapped-key")
    public ResponseEntity<byte[]> key(@PathVariable UUID ticket,@PathVariable UUID id) {return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=tpv-backup-recovery.key").body(service.adminKey(ticket,id));}
    @PostMapping("/api/v1/backup-recoveries/{id}/inspect")
    public StateResponse inspect(@PathVariable UUID id,@Valid @RequestBody Proof proof) {return response(service.inspect(id,proof));}
    @PostMapping("/api/v1/backup-recoveries/{id}/evidence")
    public Map<String,Object> evidence(@PathVariable UUID id,@RequestParam(required=false) UUID after,@Valid @RequestBody Proof proof) {return evidenceResponse(service.evidence(id,after,proof));}
    @PostMapping("/api/v1/backup-recoveries/{id}/received")
    public StateResponse received(@PathVariable UUID id,@Valid @RequestBody ReceivedRequest request) {
        return response(service.received(id,new Received(request.destinationProof(),mapper.valueToTree(request.report()))));
    }
    @PostMapping("/api/v1/backup-recoveries/{id}/activate")
    public ActivationResponse activate(@PathVariable UUID id,@Valid @RequestBody Proof proof) {
        var result=service.activate(id,proof);return new ActivationResponse(response(result.status()),result.encryptedCredential());
    }
    @PostMapping("/api/v1/backup-recoveries/{id}/acknowledge")
    public StateResponse acknowledge(@PathVariable UUID id,@Valid @RequestBody Proof proof) {return response(service.acknowledge(id,proof));}
    @PostMapping("/api/v1/backup-recoveries/{id}/artifact")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id,@Valid @RequestBody Proof proof) throws Exception {return binary(service.destinationArtifact(id,proof),"backup.tpvb");}
    @PostMapping("/api/v1/backup-recoveries/{id}/wrapped-key")
    public ResponseEntity<byte[]> downloadKey(@PathVariable UUID id,@Valid @RequestBody Proof proof) {return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(service.destinationKey(id,proof));}
    private StateResponse response(State state) {
        return new StateResponse(state.recoveryId(),state.ticketId(),state.installationId(),state.backupId(),
                state.state(),state.version(),state.destinationKeyFingerprint(),state.backupSha256(),state.expiresAt(),
                state.blockingReasons(),state.sequenceFloor(),state.backupDescriptor(),evidenceResponse(state.evidence()),state.warnings());
    }
    private Map<String,Object> evidenceResponse(JsonNode value) {
        return mapper.convertValue(value,new TypeReference<LinkedHashMap<String,Object>>() { });
    }
    private static ResponseEntity<InputStreamResource> binary(java.io.InputStream input,String name) {return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+name).body(new InputStreamResource(input));}
}
