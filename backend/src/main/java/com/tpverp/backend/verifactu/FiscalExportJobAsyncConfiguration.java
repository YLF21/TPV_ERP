package com.tpverp.backend.verifactu;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class FiscalExportJobAsyncConfiguration {
    @Bean(name = "fiscalExportJobExecutor")
    ThreadPoolTaskExecutor fiscalExportJobExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("fiscal-export-");
        executor.initialize();
        return executor;
    }
}
