package com.tpverp.backend.party.loyalty.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MemberWalletSyncWakeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemberWalletSyncWakeConfiguration.class);

    @Bean(name = "memberWalletSyncWakeExecutor")
    ThreadPoolTaskExecutor memberWalletSyncWakeExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("member-wallet-sync-");
        executor.setRejectedExecutionHandler((task, ignored) ->
                log.warn("Wake de sincronizacion de saldo descartado por saturacion; queda durable en outbox"));
        executor.initialize();
        return executor;
    }
}
