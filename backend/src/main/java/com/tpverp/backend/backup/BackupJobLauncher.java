package com.tpverp.backend.backup;

import org.springframework.scheduling.annotation.Async;

public class BackupJobLauncher {

    private final BackupService backupService;

    public BackupJobLauncher(BackupService backupService) {
        this.backupService = backupService;
    }

    @Async
    public void launch() {
        backupService.executeNow();
    }
}
