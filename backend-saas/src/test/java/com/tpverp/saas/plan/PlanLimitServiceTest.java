package com.tpverp.saas.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PlanLimitServiceTest {

    @Test
    void blocksAtLimitAndAllowsBelowIt() {
        UUID companyId = UUID.randomUUID();
        PlanLimitService service = spy(new PlanLimitService(mock(JdbcTemplate.class), Clock.systemUTC()));

        doReturn(new PlanUsageResponse(companyId, "BASIC",
                Map.of(PlanResource.TENANT_USERS, 3L),
                Map.of(PlanResource.TENANT_USERS, 3L)))
                .when(service).usage(companyId);
        assertThatThrownBy(() -> service.requireCapacity(companyId, PlanResource.TENANT_USERS))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        doReturn(new PlanUsageResponse(companyId, "BASIC",
                Map.of(PlanResource.TENANT_USERS, 2L),
                Map.of(PlanResource.TENANT_USERS, 3L)))
                .when(service).usage(companyId);
        assertThatCode(() -> service.requireCapacity(companyId, PlanResource.TENANT_USERS))
                .doesNotThrowAnyException();
    }
    @Test
    void validatesCatalogAndKeepsLegacyProAlias() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlanLimitService service = new PlanLimitService(jdbc, Clock.systemUTC());
        when(jdbc.queryForObject(
                "select exists(select 1 from saas_plan_policy where plan_name = ?)", Boolean.class, "PRO"))
                .thenReturn(true);
        when(jdbc.queryForObject(
                "select exists(select 1 from saas_plan_policy where plan_name = ?)", Boolean.class, "UNKNOWN"))
                .thenReturn(false);

        assertThat(service.requireKnownPlan(" pro ")).isEqualTo("PRO");
        assertThatThrownBy(() -> service.requireKnownPlan("unknown"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
