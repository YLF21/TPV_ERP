package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlAlertTest {

    @Test
    void supportsReviewAndTerminalCloseWithoutReopening() {
        var alert = alert();

        assertThat(alert.transition(ControlAlertStatus.REVIEWED, Instant.EPOCH.plusSeconds(1)))
                .isEqualTo(ControlAlertStatus.NEW);
        assertThat(alert.transition(ControlAlertStatus.CLOSED, Instant.EPOCH.plusSeconds(2)))
                .isEqualTo(ControlAlertStatus.REVIEWED);
        assertThatThrownBy(() -> alert.transition(
                ControlAlertStatus.REVIEWED, Instant.EPOCH.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void canDismissANewAlertButCannotReturnToNew() {
        var alert = alert();

        assertThat(alert.transition(ControlAlertStatus.DISMISSED, Instant.EPOCH.plusSeconds(1)))
                .isEqualTo(ControlAlertStatus.NEW);
        assertThatThrownBy(() -> alert.transition(ControlAlertStatus.NEW, Instant.EPOCH.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ControlAlert alert() {
        var storeId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var rule = new ControlRule(storeId, ControlAlertType.TICKET_CANCELLED,
                true, Map.of(), userId, Instant.EPOCH);
        var event = new ControlEvent(storeId, rule, "DOCUMENT", UUID.randomUUID(), UUID.randomUUID(),
                "T-1", null, userId, "ADMIN", Instant.EPOCH, Map.of());
        return new ControlAlert(event);
    }
}
