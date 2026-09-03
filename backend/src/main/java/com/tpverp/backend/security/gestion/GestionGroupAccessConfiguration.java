package com.tpverp.backend.security.gestion;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnBean(GestionGroupAccessService.class)
class GestionGroupAccessConfiguration implements WebMvcConfigurer {

    private final GestionGroupAccessService access;

    GestionGroupAccessConfiguration(GestionGroupAccessService access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new GestionGroupAccessInterceptor(access))
                .addPathPatterns("/api/v1/**");
    }
}
