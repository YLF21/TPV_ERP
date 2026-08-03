package com.tpverp.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tpverp.backend.document.CommercialDocumentType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class DevSampleDataSeederTest {

    @Test
    void coversEveryCommercialDocumentType() {
        assertThat(EnumSet.copyOf(DevSampleDataSeeder.documentTypes()))
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(CommercialDocumentType.class));
    }

    @Test
    void anchorsDemoPeriodsToTheClockWhenNoDateIsConfigured() {
        var seeder = seeder(Clock.fixed(
                Instant.parse("2031-04-12T23:59:59Z"), ZoneOffset.UTC), "");

        assertThat(seeder.seedDate()).isEqualTo(LocalDate.of(2031, 4, 12));
    }

    @Test
    void configuredBaseDateMakesDemoPeriodsReproducible() {
        var seeder = seeder(Clock.fixed(
                Instant.parse("2031-04-12T23:59:59Z"), ZoneOffset.UTC), "2029-01-15");

        assertThat(seeder.seedDate()).isEqualTo(LocalDate.of(2029, 1, 15));
    }

    private DevSampleDataSeeder seeder(Clock clock, String baseDate) {
        return new DevSampleDataSeeder(
                mock(JdbcTemplate.class), mock(PasswordEncoder.class), clock, baseDate);
    }
}
