package com.tpverp.saas.servertransfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/server-transfers")
public class ServerTransferController {
    private final ServerTransferService service;
    public ServerTransferController(ServerTransferService service) { this.service = service; }
    @PostMapping("/prepare")
    public ServerTransferService.Status prepare(@RequestHeader("X-TPV-Installation-Token") String token, @Valid @RequestBody Prepare body) {
        return service.prepare(body, token);
    }
    @PostMapping("/{id}/seal")
    public ServerTransferService.Status seal(@PathVariable UUID id, @RequestHeader("X-TPV-Installation-Token") String token, @Valid @RequestBody Seal body) {
        return service.seal(id, body, token);
    }
    @PostMapping("/{id}/status")
    public ServerTransferService.Status status(@PathVariable UUID id, @RequestHeader("X-TPV-Installation-Token") String token, @Valid @RequestBody Owner body) {
        return service.status(id, body.installationId(), token);
    }
    @PostMapping("/{id}/cancel")
    public ServerTransferService.Status cancel(@PathVariable UUID id, @RequestHeader("X-TPV-Installation-Token") String token, @Valid @RequestBody Cancellation body) {
        return service.cancel(id, body.installationId(), token,body.destinationPublicKey());
    }
    @PostMapping("/{id}/activate")
    public ServerTransferService.Activation activate(@PathVariable UUID id, @Valid @RequestBody Activate body) {
        return service.activate(id, body);
    }
    @PostMapping("/{id}/inspect")
    public ServerTransferService.Status inspect(@PathVariable UUID id,@Valid @RequestBody DestinationOwner body) {
        return service.inspect(id,body.installationId(),body.destinationProof());
    }
    public record DestinationOwner(@NotNull UUID installationId,@NotBlank @Size(max=2048) String destinationProof) {}
    public record Prepare(@NotNull UUID transferId, @NotNull UUID installationId,
            @NotBlank @Size(max=2048) String destinationPublicKey, @NotBlank @Size(max=2048) String destinationProof) {}
    public record Owner(@NotNull UUID installationId) {}
    public record Cancellation(@NotNull UUID installationId,@Size(max=2048) String destinationPublicKey) {}
    public record Seal(@NotNull UUID installationId, @Pattern(regexp="[0-9a-f]{64}") @NotNull String backupSha256,
            @Pattern(regexp="[0-9a-f]{64}") @NotNull String capsuleSha256) {}
    public record Activate(@NotNull UUID installationId, @Pattern(regexp="[0-9a-f]{64}") @NotNull String backupSha256,
            @Pattern(regexp="[0-9a-f]{64}") @NotNull String capsuleSha256,
            @NotBlank @Size(max=2048) String destinationProof) {}
}
