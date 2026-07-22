package com.tpverp.backend.dev;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Profile("dev")
public class DevSampleDataConfiguration {

    @Bean
    DevSampleDataSeeder devSampleDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        return new DevSampleDataSeeder(jdbc, passwordEncoder);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tpv.dev.sample-data", name = "enabled", havingValue = "true")
    ApplicationRunner devSampleDataRunner(DevSampleDataSeeder seeder) {
        return args -> seeder.seed();
    }
}
