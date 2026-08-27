package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MemberCategoryBootstrapServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void conservaElBootstrapCompletadoComoBaselineDescubrible() {
        UUID companyId = UUID.randomUUID();
        UUID completedBootstrapId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                        anyString(),
                        any(RowMapper.class),
                        eq(companyId)))
                .thenReturn(List.of(completedBootstrapId));
        var service = new MemberCategoryBootstrapService(
                mock(SaasInstallationRepository.class),
                mock(InstallationAuthenticator.class),
                jdbc,
                Clock.systemUTC());

        UUID result = service.discoverableBootstrap(companyId);

        assertThat(result).isEqualTo(completedBootstrapId);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(companyId));
        assertThat(sql.getValue())
                .contains("status in ('COMPLETED','COLLECTING','CONFLICT')")
                .contains("case when status='COMPLETED' then 0 else 1 end");
    }
}
