package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.*;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.persistence.PostgreSqlTestDatabaseCleaner;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalType;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, AuthenticationService.class,
        InitialPasswordConcurrencyPostgreSqlTest.TestBeans.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InitialPasswordConcurrencyPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "initial_password_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired AuthenticationService authentication;
    @Autowired UserSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PasswordEncoder encoder;
    private UUID terminalId;

    @TestConfiguration
    static class TestBeans {
        @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(4); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> USER);
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void fixture() {
        jdbc.update("delete from sesion");
        jdbc.update("delete from usuario");
        jdbc.update("delete from rol where tienda_id is null");
        PostgreSqlTestDatabaseCleaner.truncateInstallationAndCompanyGraphs(jdbc, SCHEMA);
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            var address = Map.of("linea1", "Test", "ciudad", "Test", "codigoPostal", "35001", "provincia", "Test", "pais", "ES");
            var company = new Company("B12345678", "Initial password test", address);
            em.persist(company);
            var store = new Store(company, "Test", address, "test", "Atlantic/Canary", "EUR", "es");
            em.persist(store);
            var terminal = new Terminal(store, "SERVIDOR", TerminalType.SERVIDOR, encoder.encode("terminal-secret"));
            em.persist(terminal);
            terminalId = terminal.getId();
            var role = new Role(null, "ADMIN");
            em.persist(role);
            var admin = new UserAccount(null, "ADMIN", encoder.encode("0000"), role);
            admin.requirePasswordChange();
            em.persist(admin);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordChangeWaitsForOldPasswordLoginAndRevokesItsSession(boolean terminalLogin) throws Exception {
        var initial = authentication.installationLogin("ADMIN", "0000");
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var changeStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var login = executor.submit(() -> new TransactionTemplate(transactions).execute(ignored -> {
                var result = terminalLogin
                        ? authentication.login(terminalId, "terminal-secret", "ADMIN", "0000")
                        : authentication.installationLogin("ADMIN", "0000");
                created.countDown();
                await(release);
                return result;
            }));
            try {
                assertThat(created.await(10, TimeUnit.SECONDS)).isTrue();
                var changed = executor.submit(() -> {
                    changeStarted.countDown();
                    return authentication.changeInstallationPassword(initial.accessToken(), "0000", "1234");
                });
                assertThat(changeStarted.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    assertThatThrownBy(() -> changed.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally {
                    release.countDown();
                }
                var oldLogin = login.get(10, TimeUnit.SECONDS);
                var replacement = changed.get(10, TimeUnit.SECONDS);
                assertThat(replacement.mustChangePassword()).isFalse();
                assertThat(sessions.findByTokenHashAndRevocadaEnIsNull(authentication.hash(oldLogin.accessToken()))).isEmpty();
                assertThat(sessions.findByTokenHashAndRevocadaEnIsNull(authentication.hash(initial.accessToken()))).isEmpty();
                assertThat(sessions.findByTokenHashAndRevocadaEnIsNull(authentication.hash(replacement.accessToken()))).isPresent();
                assertThatThrownBy(() -> authentication.renew(oldLogin.accessToken())).isInstanceOf(AuthenticationFailedException.class);
                assertThatThrownBy(() -> authentication.installationLogin("ADMIN", "0000")).isInstanceOf(AuthenticationFailedException.class);
                assertThat(authentication.installationLogin("ADMIN", "1234").mustChangePassword()).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldPasswordLoginWaitsForPasswordCommitAndThenFails(boolean terminalLogin) throws Exception {
        var initial = authentication.installationLogin("ADMIN", "0000");
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loginStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var change = executor.submit(() -> new TransactionTemplate(transactions).execute(ignored -> {
                var result = authentication.changeInstallationPassword(initial.accessToken(), "0000", "1234");
                changed.countDown();
                await(release);
                return result;
            }));
            try {
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                var login = executor.submit(() -> {
                    loginStarted.countDown();
                    return terminalLogin
                            ? authentication.login(terminalId, "terminal-secret", "ADMIN", "0000")
                            : authentication.installationLogin("ADMIN", "0000");
                });
                assertThat(loginStarted.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    assertThatThrownBy(() -> login.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally {
                    release.countDown();
                }
                var replacement = change.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> login.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AuthenticationFailedException.class);
                assertThat(sessions.findByTokenHashAndRevocadaEnIsNull(authentication.hash(replacement.accessToken()))).isPresent();
                assertThat(jdbc.queryForObject("select count(*) from sesion where revocada_en is null", Long.class)).isEqualTo(1L);
            } finally {
                release.countDown();
            }
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }
}
