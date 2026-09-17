package com.tpverp.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;

import com.tpverp.backend.document.CommercialDocumentType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

    @ParameterizedTest
    @ValueSource(strings = {"", "2029-01-15", "2035-01-15"})
    void demoLicenseIsValidAtStartupRegardlessOfDocumentBaseDate(String baseDate) {
        var now = Instant.parse("2031-04-12T00:01:00Z");
        var jdbc = mock(JdbcTemplate.class);
        var seeder = new DevSampleDataSeeder(jdbc, mock(PasswordEncoder.class),
                Clock.fixed(now, ZoneOffset.UTC), baseDate);

        seeder.seedLicense(UUID.randomUUID());

        var parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), parameters.capture());
        var values = parameters.getValue();
        assertThat(((Timestamp) values[3]).toInstant()).isBefore(now);
        assertThat(((Timestamp) values[4]).toInstant()).isEqualTo(now.plusSeconds(365L * 24 * 60 * 60));
        assertThat(((Timestamp) values[5]).toInstant()).isEqualTo(now);
        assertThat(((Timestamp) values[6]).toInstant()).isEqualTo(now);
    }

    private DevSampleDataSeeder seeder(Clock clock, String baseDate) {
        return new DevSampleDataSeeder(
                mock(JdbcTemplate.class), mock(PasswordEncoder.class), clock, baseDate);
    }
}
