package com.tpverp.backend.excel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class StockExcelExportAsyncConfiguration {

    @Bean(name = "stockExcelExportExecutor")
    ThreadPoolTaskExecutor stockExcelExportExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("stock-excel-");
        executor.initialize();
        return executor;
    }
}
