package com.tpverp.backend.backup;

import java.util.UUID;

public class BackupJobLauncher {

    private final BackupService backupService;
    private final BackupWorker worker;

    public BackupJobLauncher(BackupService backupService, BackupWorker worker) {
        this.backupService = backupService;
        this.worker = worker;
    }

    public UUID launch() {
        return launch("MANUAL");
    }

    public UUID launchAutomatic() { return launch("AUTOMATIC"); }

    private UUID launch(String origin) {
        var prepared = backupService.prepare(origin);
        try { worker.execute(prepared); }
        catch (RuntimeException ex) { backupService.reject(prepared); throw ex; }
        return prepared.execution().getId();
    }
}
