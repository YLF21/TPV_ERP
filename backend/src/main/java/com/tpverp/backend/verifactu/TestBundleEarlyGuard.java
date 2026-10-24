package com.tpverp.backend.verifactu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Runs after configuration, before recovery handoffs, Flyway and Spring beans. */
public final class TestBundleEarlyGuard implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
        var policy = TestBundlePolicy.load();
        if (!policy.testOnly()) return;
        policy.requireRuntime(environment);
        policy.requireStorageConfiguration(environment);
        try {
            policy.requireDatabase(environment.getRequiredProperty("spring.datasource.url"),
                    environment.getRequiredProperty("spring.datasource.username"),
                    environment.getRequiredProperty("spring.datasource.password"), true);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("TEST_ONLY database validation failed before startup", failure);
        }
    }
}
