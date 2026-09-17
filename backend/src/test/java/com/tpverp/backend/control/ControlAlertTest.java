package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ControlAlertTest {

    @Test
    void requiresExplicitReopeningBeforeChangingAClosedAlert() {
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
    void cannotReopenThroughTheOrdinaryTransitionMethod() {
        var alert = alert();

        assertThat(alert.transition(ControlAlertStatus.DISMISSED, Instant.EPOCH.plusSeconds(1)))
                .isEqualTo(ControlAlertStatus.NEW);
        assertThatThrownBy(() -> alert.transition(ControlAlertStatus.NEW, Instant.EPOCH.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updatesOperationalAssignmentAndReturnsPreviousValues() {
        var alert = alert();
        var assigneeId = UUID.randomUUID();
        var dueAt = Instant.EPOCH.plusSeconds(3600);

        var previous = alert.updateWork(
                ControlAlertPriority.CRITICAL, assigneeId, dueAt, Instant.EPOCH.plusSeconds(1));

        assertThat(previous.priority()).isEqualTo(ControlAlertPriority.MEDIUM);
        assertThat(alert.getPriority()).isEqualTo(ControlAlertPriority.CRITICAL);
        assertThat(alert.getAssigneeId()).isEqualTo(assigneeId);
        assertThat(alert.getDueAt()).isEqualTo(dueAt);
    }

    @Test
    void rejectsNoOpAndAssignmentOnTerminalAlert() {
        var alert = alert();
        assertThatThrownBy(() -> alert.updateWork(
                ControlAlertPriority.MEDIUM, null, null, Instant.EPOCH.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        alert.transition(ControlAlertStatus.CLOSED, Instant.EPOCH.plusSeconds(2));
        assertThatThrownBy(() -> alert.updateWork(
                ControlAlertPriority.HIGH, null, null, Instant.EPOCH.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ControlAlertStatus.class, names = {"REVIEWED", "CLOSED", "DISMISSED"})
    void explicitlyReopensWithoutChangingEvidenceOrWorkAssignment(ControlAlertStatus before) {
        var alert = alert();
        var event = alert.getEvent();
        var assignee = UUID.randomUUID();
        var dueAt = Instant.EPOCH.plusSeconds(3600);
        alert.updateWork(ControlAlertPriority.HIGH, assignee, dueAt, Instant.EPOCH.plusSeconds(1));
        alert.transition(before, Instant.EPOCH.plusSeconds(2));
        assertThatThrownBy(() -> alert.transition(ControlAlertStatus.NEW, Instant.EPOCH.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(alert.reopen(Instant.EPOCH.plusSeconds(4))).isEqualTo(before);

        assertThat(alert.getStatus()).isEqualTo(ControlAlertStatus.NEW);
        assertThat(alert.getUpdatedAt()).isEqualTo(Instant.EPOCH.plusSeconds(4));
        assertThat(alert.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(alert.getEvent()).isSameAs(event);
        assertThat(alert.getPriority()).isEqualTo(ControlAlertPriority.HIGH);
        assertThat(alert.getAssigneeId()).isEqualTo(assignee);
        assertThat(alert.getDueAt()).isEqualTo(dueAt);
        assertThat(alert.transition(ControlAlertStatus.REVIEWED, Instant.EPOCH.plusSeconds(5)))
                .isEqualTo(ControlAlertStatus.NEW);
    }

    @Test
    void rejectsReopeningAnAlreadyNewAlertWithoutChangingIt() {
        var alert = alert();

        assertThatThrownBy(() -> alert.reopen(Instant.EPOCH.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(alert.getStatus()).isEqualTo(ControlAlertStatus.NEW);
        assertThat(alert.getUpdatedAt()).isEqualTo(Instant.EPOCH);
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
