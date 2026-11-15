package com.tpverp.backend.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class ReplenishmentSyncConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(ReplenishmentSyncConfiguration.class);

    @Bean(name = "replenishmentSyncExecutor")
    ThreadPoolTaskExecutor replenishmentSyncExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("replenishment-sync-");
        executor.setRejectedExecutionHandler((task, pool) ->
                LOG.warn("Despertar de reposicion aplazado; el evento permanece en outbox"));
        executor.initialize();
        return executor;
    }
}
