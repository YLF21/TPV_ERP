package com.tpverp.backend.excel;

import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class StockExcelExportJobLauncher {

    private final StockExcelExportService exports;

    public StockExcelExportJobLauncher(StockExcelExportService exports) {
        this.exports = exports;
    }

    @Async("stockExcelExportExecutor")
    public void launch(UUID jobId) {
        exports.run(jobId);
    }
}
