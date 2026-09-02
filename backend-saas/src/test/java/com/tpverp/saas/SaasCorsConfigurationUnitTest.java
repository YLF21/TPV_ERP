package com.tpverp.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class SaasCorsConfigurationUnitTest {

    @Test
    void configuresEveryApiVersionAndExposesRateLimitHeader() {
        var registry = new InspectableCorsRegistry();

        new SaasCorsConfiguration("https://panel.example.com").addCorsMappings(registry);

        CorsConfiguration cors = registry.configurations().get("/api/**");
        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("https://panel.example.com");
        assertThat(cors.getAllowedMethods()).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(cors.getExposedHeaders()).contains("Retry-After");
    }

    private static final class InspectableCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
