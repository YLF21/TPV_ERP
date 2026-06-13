package com.tpverp.backend.backup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backups")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('BACKUPS_MANAGE')")
public class BackupController {

    private final BackupService backupService;
    private final BackupJobLauncher launcher;

    public BackupController(BackupService backupService, BackupJobLauncher launcher) {
        this.backupService = backupService;
        this.launcher = launcher;
    }

    @GetMapping("/configuration")
    public BackupService.BackupConfigurationItem configuration() {
        return backupService.configuration();
    }

    @PutMapping("/configuration")
    public BackupService.BackupConfigurationItem configure(
            @Valid @RequestBody ConfigureBackupRequest request) {
        return backupService.configure(
                request.time(),
                request.dailyRetention(),
                request.monthlyRetention(),
                Path.of(request.directory()),
                request.active(),
                request.adminPassword());
    }

    @PostMapping("/run")
    public ResponseEntity<Void> run() {
        launcher.launch();
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> restore(@Valid @RequestBody RestoreBackupRequest request) {
        backupService.restore(
                Path.of(request.backupFile()),
                Path.of(request.recoveryFile()),
                request.adminPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<BackupService.ExecutionItem> history() {
        return backupService.history();
    }

    public record ConfigureBackupRequest(
            @NotNull LocalTime time,
            @Min(30) int dailyRetention,
            @Min(72) int monthlyRetention,
            @NotBlank String directory,
            boolean active,
            @NotBlank String adminPassword) {
    }

    public record RestoreBackupRequest(
            @NotBlank String backupFile,
            @NotBlank String recoveryFile,
            @NotBlank String adminPassword) {
    }
}
