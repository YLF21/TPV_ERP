package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlRuleTest {

    @Test
    void validatesTypedConfigurationAndCreatesBusinessVersions() {
        var userId = UUID.randomUUID();
        var rule = new ControlRule(
                UUID.randomUUID(), ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT,
                false, Map.of("thresholdPercent", "10.00"), userId, Instant.EPOCH);

        assertThat(rule.getRuleVersion()).isEqualTo(1);
        assertThat(ControlRuleConfiguration.threshold(rule.getConfiguration()))
                .isEqualByComparingTo(new BigDecimal("10"));

        rule.update(true, Map.of("thresholdPercent", 15), userId, Instant.EPOCH.plusSeconds(1));

        assertThat(rule.getRuleVersion()).isEqualTo(2);
        assertThat(rule.isActive()).isTrue();
        assertThat(rule.getName()).isEqualTo(ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT.systemName());
    }

    @Test
    void rejectsUnexpectedOrOutOfRangeConfiguration() {
        assertThatThrownBy(() -> new ControlRule(
                UUID.randomUUID(), ControlAlertType.TICKET_CANCELLED, true,
                Map.of("thresholdPercent", 10), UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ControlRule(
                UUID.randomUUID(), ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT, true,
                Map.of("thresholdPercent", 101), UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesIntegerQuantityForConsecutiveDeletionRule() {
        var rule = new ControlRule(
                UUID.randomUUID(), ControlAlertType.CONSECUTIVE_LINE_DELETIONS, true,
                Map.of("minimumCount", 3), UUID.randomUUID(), Instant.EPOCH);

        assertThat(ControlRuleConfiguration.minimumCount(rule.getConfiguration())).isEqualTo(3);
        assertThatThrownBy(() -> rule.update(
                true, Map.of("minimumCount", "2.5"), UUID.randomUUID(), Instant.EPOCH.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
