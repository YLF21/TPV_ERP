package com.tpverp.backend.excel;

import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class StockExcelExportJobLauncher {

    private final StockExcelExportService exports;
    private com.tpverp.backend.servertransfer.ServerTransferFence transferFence;
    @org.springframework.beans.factory.annotation.Autowired
    public void setTransferFence(com.tpverp.backend.servertransfer.ServerTransferFence fence) { transferFence=fence; }

    public StockExcelExportJobLauncher(StockExcelExportService exports) {
        this.exports = exports;
    }

    @Async("stockExcelExportExecutor")
    public void launch(UUID jobId) {
        if(transferFence!=null && !transferFence.enter()) return;
        try {exports.run(jobId);} finally {if(transferFence!=null)transferFence.leave();}
    }
}
