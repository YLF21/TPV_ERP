package com.tpverp.saas.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SecurityNotificationChannelConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityNotificationChannel.class)
    SecurityNotificationChannel pendingSecurityNotificationChannel() {
        return new SecurityNotificationChannel() {
            @Override public boolean available() { return false; }
            @Override public boolean deliver(SecurityNotification notification) { return false; }
        };
    }
}
