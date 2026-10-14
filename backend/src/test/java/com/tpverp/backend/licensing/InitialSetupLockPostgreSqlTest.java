package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.*;
import com.tpverp.backend.licensing.application.InitialSetupException;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.terminal.TerminalRepository;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses only its own disposable schema; does not load or alter installation data. */
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
class InitialSetupLockPostgreSqlTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothBootstrapRoutesWaitForProvisioningCommitBeforeCheckingEmptiness(boolean initialRoute) throws Exception {
        var schema = "initial_setup_lock_" + UUID.randomUUID().toString().replace("-", "");
        var url = System.getenv("TPV_ERP_TEST_DB_URL");
        var user = System.getenv("TPV_ERP_TEST_DB_USER");
        var password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        try (var admin = DriverManager.getConnection(url, user, password)) {
            try (var statement = admin.createStatement()) {
                statement.execute("create schema " + schema);
            }
            try {
                var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
                var jdbc = spy(new JdbcTemplate(source));
                jdbc.execute("create table instalacion (id uuid primary key)");
                jdbc.execute("create table empresa (id uuid primary key)");
                jdbc.update("insert into instalacion values (?)", UUID.randomUUID());
                var companies = mock(CompanyRepository.class);
                when(companies.count()).thenAnswer(invocation -> jdbc.queryForObject("select count(*) from empresa", Long.class));
                var client = mock(LicenseSaasLinkClient.class);
                var initialAdmin = mock(InitialAdminPasswordState.class);
                when(initialAdmin.isRequired()).thenReturn(true);
                var service = new LicenseSaasLinkService(
                        mock(InstallationRepository.class), companies, mock(StoreRepository.class),
                        mock(CurrentOrganization.class), mock(LicenseRepository.class), client,
                        mock(LicenseSaasCredentialStore.class), mock(LicenseSaasCacheAuthenticator.class),
                        mock(TerminalRepository.class), mock(PasswordEncoder.class), Clock.systemUTC(),
                        mock(AuditService.class), jdbc, mock(CommercialBootstrapService.class), initialAdmin);
                var attemptedLock = new CountDownLatch(1);
                doAnswer(invocation -> {
                    attemptedLock.countDown();
                    return invocation.callRealMethod();
                }).when(jdbc).queryForList("select id from instalacion order by id for update");
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
                try (var first = source.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
                    first.setAutoCommit(false);
                    try (var statement = first.createStatement()) {
                        statement.executeQuery("select id from instalacion order by id for update").close();
                        statement.executeUpdate("insert into empresa values ('" + UUID.randomUUID() + "')");
                    }
                    var second = executor.submit(() -> transaction.execute(status -> initialRoute
                            ? service.bootstrapInitialDatabase("PAIRING") : service.bootstrapEmptyDatabase("PAIRING")));
                    try {
                        assertThat(attemptedLock.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    } finally {
                        first.commit();
                    }
                    assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class)
                            .hasCauseInstanceOf(initialRoute ? InitialSetupException.class : LicenseValidationException.class);
                    verifyNoInteractions(client);
                }
            } finally {
                try (var statement = admin.createStatement()) {
                    statement.execute("drop schema " + schema + " cascade");
                }
            }
        }
    }
}
