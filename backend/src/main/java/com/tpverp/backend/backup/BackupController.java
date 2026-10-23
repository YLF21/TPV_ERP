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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
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
                request.adminPassword(),
                request.recoverySecret());
    }

    @PostMapping("/run")
    public ResponseEntity<RunResponse> run() {
        return ResponseEntity.accepted().body(new RunResponse(launcher.launch()));
    }

    @GetMapping
    public BackupService.HistoryPage history(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return backupService.history(page, size);
    }

    @GetMapping("/{id}")
    public BackupService.ExecutionItem detail(@PathVariable java.util.UUID id) { return backupService.detail(id); }

    @PostMapping("/{id}/cloud/retry")
    public com.tpverp.backend.backup.cloud.CloudBackupStatus retryCloud(@PathVariable java.util.UUID id) {
        return backupService.retryCloud(id);
    }

    public record RunResponse(java.util.UUID id) { }

    public record ConfigureBackupRequest(
            @NotNull LocalTime time,
            @Min(30) int dailyRetention,
            @Min(72) int monthlyRetention,
            @NotBlank String directory,
            boolean active,
            @NotBlank String adminPassword,
            @NotBlank String recoverySecret) {
    }

}
