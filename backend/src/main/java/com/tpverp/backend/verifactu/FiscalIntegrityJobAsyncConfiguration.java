package com.tpverp.backend.verifactu;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class FiscalIntegrityJobAsyncConfiguration {
    @Bean(name = "fiscalIntegrityJobExecutor")
    ThreadPoolTaskExecutor fiscalIntegrityJobExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("fiscal-integrity-");
        executor.initialize();
        return executor;
    }
}
