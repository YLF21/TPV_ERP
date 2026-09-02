package com.tpverp.saas.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IntegrationDeliveryChannelConfiguration {

    @Bean
    @ConditionalOnMissingBean(IntegrationDeliveryChannel.class)
    IntegrationDeliveryChannel pendingIntegrationDeliveryChannel() {
        return new IntegrationDeliveryChannel() {
            @Override public boolean available() { return false; }
            @Override public boolean deliver(IntegrationDelivery delivery) { return false; }
        };
    }
}
