package com.tpverp.saas.backup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/backup-vault")
public class CloudBackupController {
    private final CloudBackupVaultService vault;
    public CloudBackupController(CloudBackupVaultService vault) { this.vault = vault; }

    @PostMapping("/{installationId}/{backupId}/begin")
    public CloudBackupVaultService.Status begin(@PathVariable UUID installationId, @PathVariable UUID backupId,
            @RequestHeader("X-TPV-Installation-Token") String token, @Valid @RequestBody Begin body) {
        if (!installationId.equals(body.descriptor().installationId())
                || !backupId.equals(body.descriptor().backupId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BACKUP_ID_MISMATCH");
        if (body.wrappedRecoveryKey().length() > 1398104)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RECOVERY_KEY_TOO_LARGE");
        byte[] wrapped;
        try { wrapped = Base64.getDecoder().decode(body.wrappedRecoveryKey()); }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RECOVERY_KEY_INVALID");
        }
        return vault.begin(body.descriptor(), wrapped, token);
    }

    @PutMapping("/{installationId}/{backupId}/content")
    public CloudBackupVaultService.Status content(@PathVariable UUID installationId, @PathVariable UUID backupId,
            @RequestHeader("X-TPV-Installation-Token") String token, HttpServletRequest request) throws IOException {
        return vault.upload(installationId, backupId, token, request.getInputStream());
    }

    @GetMapping("/{installationId}/{backupId}/status")
    public CloudBackupVaultService.Status status(@PathVariable UUID installationId, @PathVariable UUID backupId,
            @RequestHeader("X-TPV-Installation-Token") String token) throws IOException {
        return vault.status(installationId, backupId, token);
    }

    @GetMapping("/{installationId}/available")
    public List<UUID> available(@PathVariable UUID installationId,
            @RequestHeader("X-TPV-Installation-Token") String token) {
        return vault.available(installationId, token);
    }

    public record Begin(@NotNull CloudBackupDescriptor descriptor,
            @NotBlank @Size(max = 1398104) String wrappedRecoveryKey) { }
}
