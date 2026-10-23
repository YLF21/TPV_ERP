package com.tpverp.backend.backup;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class BackupWorker {
    private final BackupService backups;
    private final com.tpverp.backend.servertransfer.ServerTransferFence fence;
    public BackupWorker(BackupService backups, com.tpverp.backend.servertransfer.ServerTransferFence fence) {
        this.backups = backups; this.fence = fence;
    }

    @Async
    public void execute(BackupService.Prepared prepared) {
        if (!fence.enter()) { backups.reject(prepared); return; }
        try { backups.executePrepared(prepared); } finally { fence.leave(); }
    }
}
