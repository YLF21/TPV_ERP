package com.tpverp.backend.observability;

import com.tpverp.backend.audit.AuditService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class BusinessObservabilityConfiguration implements WebMvcConfigurer {

    private final MeterRegistry meters;
    private final AuditService audit;

    BusinessObservabilityConfiguration(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<AuditService> auditServiceProvider
    ) {
        this.meters = meterRegistryProvider.getIfAvailable();
        this.audit = auditServiceProvider.getIfAvailable();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (meters == null || audit == null) {
            return;
        }
        registry.addInterceptor(new BusinessObservabilityInterceptor(meters, audit))
                .addPathPatterns("/api/v1/**");
    }
}
