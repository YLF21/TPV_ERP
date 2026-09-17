package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;

class ControlAlertViewPreferenceServiceTest {

    @Test
    void rejectsAnAbsentSettingsBodyBeforeResolvingAUserOrWriting() {
        var jdbc = mock(JdbcTemplate.class);
        var organization = mock(CurrentOrganization.class);
        var service = new ControlAlertViewPreferenceService(
                jdbc, new ObjectMapper(), organization, Clock.systemUTC());

        assertThatThrownBy(() -> service.save(null, mock(Authentication.class)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, organization);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void requiresEveryPresentationBoolean(int absentIndex) {
        var flags = new Boolean[]{true, true, true, false};
        flags[absentIndex] = null;

        assertThatThrownBy(() -> new ControlAlertViewPreferenceService.Settings(
                flags[0], flags[1], flags[2], flags[3], "TODAY", 30, "occurredAt", "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidOptions")
    void rejectsUnknownOrMissingOptions(String period, Integer refresh, String sortBy, String direction) {
        assertThatThrownBy(() -> new ControlAlertViewPreferenceService.Settings(
                true, true, true, false, period, refresh, sortBy, direction))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidOptions() {
        return Stream.of(
                Arguments.of(null, 30, "occurredAt", "desc"),
                Arguments.of("ALL_TIME", 30, "occurredAt", "desc"),
                Arguments.of("TODAY", null, "occurredAt", "desc"),
                Arguments.of("TODAY", 1, "occurredAt", "desc"),
                Arguments.of("TODAY", 30, null, "desc"),
                Arguments.of("TODAY", 30, "storeId", "desc"),
                Arguments.of("TODAY", 30, "occurredAt", null),
                Arguments.of("TODAY", 30, "occurredAt", "sideways"));
    }
}
