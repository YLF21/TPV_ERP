package com.tpverp.backend.servertransfer;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Explicit production registration keeps MVC test slices independent of filesystem maintenance state. */
@Configuration(proxyBeanMethods = false)
public class ServerTransferWebConfiguration {
    @Bean
    FilterRegistrationBean<ServerTransferRequestFilter> serverTransferRequestFilter(ServerTransferFence fence) {
        var registration = new FilterRegistrationBean<>(new ServerTransferRequestFilter(fence));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
