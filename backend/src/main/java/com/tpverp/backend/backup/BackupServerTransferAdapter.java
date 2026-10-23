package com.tpverp.backend.backup;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.servertransfer.ServerTransferBackupPort;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Only the controlled transfer orchestrator can request a new final snapshot. */
@Component
public class BackupServerTransferAdapter implements ServerTransferBackupPort {
    private final BackupService backups;
    private final InstallationRepository installations;
    public BackupServerTransferAdapter(BackupService backups, InstallationRepository installations) {
        this.backups = backups; this.installations = installations;
    }
    @Override public void ensureCloudCopy(Path file) { backups.ensureCloudCopy(file); }
    @Override
    public Artifact createFinalBackup(UUID transferId, UUID installationId, String destinationKeyFingerprint) {
        var rows = installations.findAll();
        if (rows.size() != 1 || !rows.getFirst().getId().equals(installationId)
                || transferId == null || destinationKeyFingerprint == null
                || !destinationKeyFingerprint.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException("El traslado no corresponde a esta instalación o destino");
        }
        var result = backups.executePrepared(backups.prepareTransfer(Map.of("transferId", transferId.toString(),
                "installationId", installationId.toString(), "destinationKeyFingerprint", destinationKeyFingerprint)));
        if (result.result() != BackupResult.EXITO || !result.available()) {
            throw new IllegalStateException("No se pudo crear y verificar la copia final del traslado");
        }
        Path file = Path.of(result.metadata().get("path").toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return new Artifact(file, HexFormat.of().formatHex(digest.digest()), Files.size(file));
        } catch (Exception ex) { throw new IllegalStateException("No se pudo sellar el archivo final", ex); }
    }
}
