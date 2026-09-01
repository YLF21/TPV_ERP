package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PostgreSQL coverage for V238's atomic issued-code ledgers. */
class MigrationV238PostgreSqlTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void makesClaimsAtomicAppendOnlyAndScopedWhilePreservingCascade() throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL",
                "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "tpv_erp_test");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "catalog_codes_v238_"
                + UUID.randomUUID().toString().replace("-", "");
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID generalId = UUID.randomUUID();
        UUID retiredFamilyId = UUID.randomUUID();
        UUID subfamilyParentId = UUID.randomUUID();
        UUID retiredSubfamilyId = UUID.randomUUID();
        UUID cascadeFamilyId = UUID.randomUUID();
        UUID cascadeSubfamilyId = UUID.randomUUID();

        try {
            migrate(url, user, password, schema, "237");
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                setPublicSearchPath(connection);
                insertCompany(connection, schema, companyId);
                insertStore(connection, schema, storeId, companyId, "381");
                insertStore(connection, schema, otherStoreId, companyId, "382");
                insertFamily(connection, schema, generalId, storeId,
                        "GENERAL", "000", "GENERAL", true);
                insertFamily(connection, schema, retiredFamilyId, storeId,
                        "010", "010", "FAMILIA RETIRADA", false);
                insertFamily(connection, schema, subfamilyParentId, storeId,
                        "020", "020", "FAMILIA SUBFAMILIA", false);
                insertSubfamily(connection, schema, retiredSubfamilyId,
                        subfamilyParentId, "020001", "001", "SUBFAMILIA RETIRADA");
                insertFamily(connection, schema, cascadeFamilyId, storeId,
                        "030", "030", "FAMILIA CASCADA", false);
                insertSubfamily(connection, schema, cascadeSubfamilyId,
                        cascadeFamilyId, "030002", "002", "SUBFAMILIA CASCADA");
            }

            migrate(url, user, password, schema, "238");

            assertDeleteCannotRaceFamilyReuse(url, user, password, schema,
                    storeId, retiredFamilyId);
            assertDeleteCannotRaceSubfamilyReuse(url, user, password, schema,
                    subfamilyParentId, retiredSubfamilyId);
            assertConcurrentExplicitFamilyClaimHasOneWinner(url, user, password,
                    schema, storeId);
            assertRolledBackClaimCanBeReclaimed(url, user, password, schema, storeId);
            assertConcurrentExplicitSubfamilyClaimHasOneWinner(url, user, password,
                    schema, subfamilyParentId);
            assertConcurrentAutomaticClaimsAdvanceToNextCode(url, user, password,
                    schema, storeId);
            assertClaimsAreScopedByStoreAndParent(url, user, password, schema,
                    storeId, otherStoreId, subfamilyParentId);
            assertCascadeKeepsBothClaims(url, user, password, schema,
                    storeId, cascadeFamilyId);
            assertLedgersAreAppendOnlyAndCoverEveryActiveCode(url, user, password,
                    schema, storeId, subfamilyParentId);
            assertTestCleanerRestoresProductionGuards(url, user, password, schema);

            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                assertThat(scalar(connection, ("""
                        select string_agg(version, ',' order by installed_rank)
                        from %s.flyway_schema_history
                        where version in ('237', '238')
                        """).formatted(schema))).isEqualTo("237,238");
            }
        } finally {
            dropSchema(url, user, password, schema);
        }
    }

    private static void assertDeleteCannotRaceFamilyReuse(
            String url, String user, String password, String schema,
            UUID storeId, UUID familyId) throws Exception {
        CountDownLatch insertStarted = new CountDownLatch(1);
        try (Connection deleting = DriverManager.getConnection(url, user, password);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            deleting.setAutoCommit(false);
            setPublicSearchPath(deleting);
            update(deleting, "delete from " + schema + ".familia where id = ?", familyId);

            Future<Throwable> reuse = executor.submit(() -> attemptFamilyInsert(
                    url, user, password, schema, UUID.randomUUID(), storeId,
                    "REUSE-010", "010", "FAMILIA REUSADA", insertStarted, null));
            assertThat(insertStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Throwable failure = reuse.get(2, TimeUnit.SECONDS);
            assertIssuedCodeFailure(failure);
            deleting.commit();
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(scalar(connection, ("""
                    select count(*) from %s.familia
                    where tienda_id = '%s' and family_code = '010'
                    """).formatted(schema, storeId))).isEqualTo("0");
            assertThat(scalar(connection, ("""
                    select count(*) from %s.familia_codigo_reservado
                    where tienda_id = '%s' and family_code = '010'
                    """).formatted(schema, storeId))).isEqualTo("1");
        }
    }

    private static void assertDeleteCannotRaceSubfamilyReuse(
            String url, String user, String password, String schema,
            UUID parentId, UUID subfamilyId) throws Exception {
        CountDownLatch insertStarted = new CountDownLatch(1);
        try (Connection deleting = DriverManager.getConnection(url, user, password);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            deleting.setAutoCommit(false);
            setPublicSearchPath(deleting);
            update(deleting, "delete from " + schema + ".subfamilia where id = ?", subfamilyId);

            Future<Throwable> reuse = executor.submit(() -> attemptSubfamilyInsert(
                    url, user, password, schema, UUID.randomUUID(), parentId,
                    "020001", "001", "SUBFAMILIA REUSADA", insertStarted, null));
            assertThat(insertStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Throwable failure = reuse.get(2, TimeUnit.SECONDS);
            assertIssuedCodeFailure(failure);
            deleting.commit();
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(scalar(connection, ("""
                    select count(*) from %s.subfamilia
                    where familia_id = '%s' and subfamily_suffix = '001'
                    """).formatted(schema, parentId))).isEqualTo("0");
            assertThat(scalar(connection, ("""
                    select count(*) from %s.subfamilia_codigo_reservado
                    where familia_id = '%s' and subfamily_suffix = '001'
                    """).formatted(schema, parentId))).isEqualTo("1");
        }
    }

    private static void assertConcurrentExplicitFamilyClaimHasOneWinner(
            String url, String user, String password, String schema, UUID storeId)
            throws Exception {
        UUID winnerId = UUID.randomUUID();
        CountDownLatch contenderStarted = new CountDownLatch(1);
        AtomicInteger contenderPid = new AtomicInteger();
        try (Connection winner = DriverManager.getConnection(url, user, password);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            winner.setAutoCommit(false);
            setPublicSearchPath(winner);
            insertFamily(winner, schema, winnerId, storeId,
                    "EXPLICIT-040-A", "040", "EXPLICITA A", false);

            Future<Throwable> contender = executor.submit(() -> attemptFamilyInsert(
                    url, user, password, schema, UUID.randomUUID(), storeId,
                    "EXPLICIT-040-B", "040", "EXPLICITA B", contenderStarted,
                    contenderPid));
            assertThat(contenderStarted.await(2, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(url, user, password, contenderPid);
            assertThatThrownBy(() -> contender.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            winner.commit();
            assertIssuedCodeFailure(contender.get(5, TimeUnit.SECONDS));
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(scalar(connection, ("""
                    select count(*) from %s.familia
                    where tienda_id = '%s' and family_code = '040'
                    """).formatted(schema, storeId))).isEqualTo("1");
        }
    }

    private static void assertRolledBackClaimCanBeReclaimed(
            String url, String user, String password, String schema, UUID storeId)
            throws SQLException {
        UUID rolledBackId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        try (Connection claimant = DriverManager.getConnection(url, user, password)) {
            claimant.setAutoCommit(false);
            setPublicSearchPath(claimant);
            insertFamily(claimant, schema, rolledBackId, storeId,
                    "ROLLBACK-041-A", "041", "ROLLBACK A", false);
            assertThat(scalar(claimant, ("""
                    select count(*) from %s.familia_codigo_reservado
                    where tienda_id = '%s' and family_code = '041'
                    """).formatted(schema, storeId))).isEqualTo("1");
            claimant.rollback();
        }
        try (Connection replacement = DriverManager.getConnection(url, user, password)) {
            setPublicSearchPath(replacement);
            insertFamily(replacement, schema, replacementId, storeId,
                    "ROLLBACK-041-B", "041", "ROLLBACK B", false);
            assertThat(scalar(replacement, "select count(*) from " + schema
                    + ".familia where id = '" + rolledBackId + "'"))
                    .isEqualTo("0");
            assertThat(scalar(replacement, "select count(*) from " + schema
                    + ".familia where id = '" + replacementId + "'"))
                    .isEqualTo("1");
        }
    }

    private static void assertConcurrentExplicitSubfamilyClaimHasOneWinner(
            String url, String user, String password, String schema, UUID parentId)
            throws Exception {
        CountDownLatch contenderStarted = new CountDownLatch(1);
        AtomicInteger contenderPid = new AtomicInteger();
        UUID winnerId = UUID.randomUUID();
        try (Connection winner = DriverManager.getConnection(url, user, password);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            winner.setAutoCommit(false);
            setPublicSearchPath(winner);
            insertSubfamily(winner, schema, winnerId, parentId,
                    "020009", "009", "EXPLICITA A");

            Future<Throwable> contender = executor.submit(() -> attemptSubfamilyInsert(
                    url, user, password, schema, UUID.randomUUID(), parentId,
                    "020009", "009", "EXPLICITA B", contenderStarted, contenderPid));
            assertThat(contenderStarted.await(2, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(url, user, password, contenderPid);
            assertThatThrownBy(() -> contender.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            winner.commit();
            assertIssuedCodeFailure(contender.get(5, TimeUnit.SECONDS));
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            update(connection, """
                    update %s.subfamilia
                    set familia_id = familia_id,
                        subfamily_suffix = subfamily_suffix,
                        subfamily_code = subfamily_code
                    where id = ?
                    """.formatted(schema), winnerId);
            assertThat(scalar(connection, ("""
                    select count(*) from %s.subfamilia
                    where familia_id = '%s' and subfamily_suffix = '009'
                    """).formatted(schema, parentId))).isEqualTo("1");
        }
    }

    private static void assertConcurrentAutomaticClaimsAdvanceToNextCode(
            String url, String user, String password, String schema, UUID storeId)
            throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        CountDownLatch contenderStarted = new CountDownLatch(1);
        AtomicInteger contenderPid = new AtomicInteger();
        try (Connection first = DriverManager.getConnection(url, user, password);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            first.setAutoCommit(false);
            setPublicSearchPath(first);
            insertFamily(first, schema, firstId, storeId,
                    "AUTO-A", null, "AUTOMATICA A", false);

            Future<Throwable> contender = executor.submit(() -> attemptFamilyInsert(
                    url, user, password, schema, secondId, storeId,
                    "AUTO-B", null, "AUTOMATICA B", contenderStarted, contenderPid));
            assertThat(contenderStarted.await(2, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(url, user, password, contenderPid);
            assertThatThrownBy(() -> contender.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            first.commit();
            assertThat(contender.get(5, TimeUnit.SECONDS)).isNull();
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(scalar(connection, "select family_code from " + schema
                    + ".familia where id = '" + firstId + "'"))
                    .isEqualTo("001");
            assertThat(scalar(connection, "select family_code from " + schema
                    + ".familia where id = '" + secondId + "'"))
                    .isEqualTo("002");
        }
    }

    private static void assertClaimsAreScopedByStoreAndParent(
            String url, String user, String password, String schema,
            UUID storeId, UUID otherStoreId, UUID parentId) throws SQLException {
        UUID otherParentId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            setPublicSearchPath(connection);
            insertFamily(connection, schema, UUID.randomUUID(), storeId,
                    "LOCAL-050", "050", "LOCAL 050", false);
            insertFamily(connection, schema, UUID.randomUUID(), otherStoreId,
                    "OTHER-050", "050", "OTRA 050", false);
            insertFamily(connection, schema, otherParentId, storeId,
                    "060", "060", "OTRO PADRE", false);
            insertSubfamily(connection, schema, UUID.randomUUID(), parentId,
                    "020007", "007", "SIETE PADRE A");
            insertSubfamily(connection, schema, UUID.randomUUID(), otherParentId,
                    "060007", "007", "SIETE PADRE B");

            assertThat(scalar(connection, ("""
                    select count(*) from %s.familia_codigo_reservado
                    where family_code = '050' and tienda_id in ('%s', '%s')
                    """).formatted(schema, storeId, otherStoreId))).isEqualTo("2");
            assertThat(scalar(connection, ("""
                    select count(*) from %s.subfamilia_codigo_reservado
                    where subfamily_suffix = '007'
                      and familia_id in ('%s', '%s')
                    """).formatted(schema, parentId, otherParentId))).isEqualTo("2");
        }
    }

    private static void assertCascadeKeepsBothClaims(
            String url, String user, String password, String schema,
            UUID storeId, UUID familyId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            setPublicSearchPath(connection);
            update(connection, "delete from " + schema + ".familia where id = ?", familyId);
            assertThat(scalar(connection, ("""
                    select count(*) from %s.familia_codigo_reservado
                    where tienda_id = '%s' and family_code = '030'
                    """).formatted(schema, storeId))).isEqualTo("1");
            assertThat(scalar(connection, ("""
                    select count(*) from %s.subfamilia_codigo_reservado
                    where familia_id = '%s' and subfamily_suffix = '002'
                    """).formatted(schema, familyId))).isEqualTo("1");
            assertThat(scalar(connection, "select count(*) from " + schema
                    + ".subfamilia where familia_id = '" + familyId + "'"))
                    .isEqualTo("0");
        }
    }

    private static void assertLedgersAreAppendOnlyAndCoverEveryActiveCode(
            String url, String user, String password, String schema,
            UUID storeId, UUID parentId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            setPublicSearchPath(connection);
            assertThatThrownBy(() -> update(connection, """
                    update %s.familia_codigo_reservado
                    set reservado_en = reservado_en
                    where tienda_id = ? and family_code = '010'
                    """.formatted(schema), storeId)).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
            assertThatThrownBy(() -> update(connection, """
                    delete from %s.familia_codigo_reservado
                    where tienda_id = ? and family_code = '010'
                    """.formatted(schema), storeId)).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
            assertThatThrownBy(() -> update(connection, """
                    update %s.subfamilia_codigo_reservado
                    set reservado_en = reservado_en
                    where familia_id = ? and subfamily_suffix = '001'
                    """.formatted(schema), parentId)).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
            assertThatThrownBy(() -> update(connection, """
                    delete from %s.subfamilia_codigo_reservado
                    where familia_id = ? and subfamily_suffix = '001'
                    """.formatted(schema), parentId)).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
            assertThatThrownBy(() -> update(connection,
                    "truncate table " + schema + ".familia_codigo_reservado"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
            assertThatThrownBy(() -> update(connection,
                    "truncate table " + schema + ".subfamilia_codigo_reservado"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));

            assertThat(scalar(connection, ("""
                    select count(*)
                    from %s.familia f
                    left join %s.familia_codigo_reservado r
                      on r.tienda_id = f.tienda_id and r.family_code = f.family_code
                    where r.tienda_id is null
                    """).formatted(schema, schema))).isEqualTo("0");
            assertThat(scalar(connection, ("""
                    select count(*)
                    from %s.subfamilia sf
                    left join %s.subfamilia_codigo_reservado r
                      on r.familia_id = sf.familia_id
                     and r.subfamily_suffix = sf.subfamily_suffix
                    where r.familia_id is null
                    """).formatted(schema, schema))).isEqualTo("0");
        }
    }

    private static void assertTestCleanerRestoresProductionGuards(
            String url, String user, String password, String schema) {
        var dataSource = new DriverManagerDataSource(
                schemaUrl(url, schema), user, password);
        var jdbc = new JdbcTemplate(dataSource);

        int companyCount = jdbc.queryForObject("select count(*) from empresa", Integer.class);
        int familyLedgerCount = jdbc.queryForObject(
                "select count(*) from familia_codigo_reservado", Integer.class);
        int subfamilyLedgerCount = jdbc.queryForObject(
                "select count(*) from subfamilia_codigo_reservado", Integer.class);
        assertThat(companyCount).isPositive();
        assertThat(familyLedgerCount).isPositive();
        assertThat(subfamilyLedgerCount).isPositive();

        assertThatThrownBy(() -> PostgreSqlTestDatabaseCleaner
                .truncateInstallationAndCompanyGraphs(jdbc, "public"))
                .isInstanceOf(IllegalArgumentException.class);
        String differentSchema = "catalog_cleaner_wrong_"
                + UUID.randomUUID().toString().replace("-", "");
        assertThatThrownBy(() -> PostgreSqlTestDatabaseCleaner
                .truncateInstallationAndCompanyGraphs(jdbc, differentSchema))
                .satisfies(failure -> {
                    assertThat(sqlState(failure)).isEqualTo("P0001");
                    assertThat(message(failure)).contains("esperaba el esquema");
                });

        assertThatThrownBy(() -> PostgreSqlTestDatabaseCleaner.truncate(
                jdbc, schema, "raise exception 'fallo intencionado del cleaner';"))
                .satisfies(failure -> {
                    assertThat(sqlState(failure)).isEqualTo("P0001");
                    assertThat(message(failure)).contains("fallo intencionado del cleaner");
                });
        assertThat(jdbc.queryForObject("select count(*) from empresa", Integer.class))
                .isEqualTo(companyCount);
        assertThat(jdbc.queryForObject(
                "select count(*) from familia_codigo_reservado", Integer.class))
                .isEqualTo(familyLedgerCount);
        assertThat(jdbc.queryForObject(
                "select count(*) from subfamilia_codigo_reservado", Integer.class))
                .isEqualTo(subfamilyLedgerCount);
        assertLedgerTruncateGuardsActive(jdbc);

        PostgreSqlTestDatabaseCleaner.truncateInstallationAndCompanyGraphs(jdbc, schema);

        assertThat(jdbc.queryForObject("select count(*) from empresa", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from familia_codigo_reservado", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from subfamilia_codigo_reservado", Integer.class))
                .isZero();
        assertLedgerTruncateGuardsActive(jdbc);
    }

    private static void assertLedgerTruncateGuardsActive(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> jdbc.execute(
                "truncate table familia_codigo_reservado"))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
        assertThatThrownBy(() -> jdbc.execute(
                "truncate table subfamilia_codigo_reservado"))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
    }

    private static Throwable attemptFamilyInsert(
            String url, String user, String password, String schema,
            UUID id, UUID storeId, String alias, String code, String name,
            CountDownLatch started, AtomicInteger backendPid) {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            setPublicSearchPath(connection);
            setLockTimeout(connection);
            if (backendPid != null) {
                backendPid.set(readBackendPid(connection));
            }
            started.countDown();
            insertFamily(connection, schema, id, storeId, alias, code, name, false);
            connection.commit();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static Throwable attemptSubfamilyInsert(
            String url, String user, String password, String schema,
            UUID id, UUID parentId, String code, String suffix, String name,
            CountDownLatch started, AtomicInteger backendPid) {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            setPublicSearchPath(connection);
            setLockTimeout(connection);
            if (backendPid != null) {
                backendPid.set(readBackendPid(connection));
            }
            started.countDown();
            insertSubfamily(connection, schema, id, parentId, code, suffix, name);
            connection.commit();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void assertIssuedCodeFailure(Throwable failure) {
        assertThat(failure).isNotNull();
        assertThat(sqlState(failure)).isEqualTo("P0001");
        assertThat(message(failure)).contains("emitido o reservado");
    }

    private static void insertCompany(
            Connection connection, String schema, UUID id) throws SQLException {
        update(connection, """
                insert into %s.empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B23800001', 'Empresa V238', cast(? as jsonb))
                """.formatted(schema), id, address());
    }

    private static void insertStore(
            Connection connection, String schema, UUID id, UUID companyId, String code)
            throws SQLException {
        update(connection, """
                insert into %s.tienda
                  (id, empresa_id, codigo_tienda, nombre, direccion,
                   address_normalized_hash, timezone, moneda, locale)
                values (?, ?, ?, 'Tienda V238', cast(? as jsonb), ?,
                  'Atlantic/Canary', 'EUR', 'es-ES')
                """.formatted(schema), id, companyId, code, address(), "hash-v238-" + code);
    }

    private static void insertFamily(
            Connection connection, String schema, UUID id, UUID storeId,
            String alias, String code, String name, boolean defaultFamily) throws SQLException {
        update(connection, """
                insert into %s.familia
                  (id, tienda_id, family_id, family_code, nombre, predeterminada)
                values (?, ?, ?, ?, ?, ?)
                """.formatted(schema), id, storeId, alias, code, name, defaultFamily);
    }

    private static void insertSubfamily(
            Connection connection, String schema, UUID id, UUID familyId,
            String code, String suffix, String name) throws SQLException {
        update(connection, """
                insert into %s.subfamilia
                  (id, familia_id, subfamily_id, subfamily_suffix,
                   subfamily_code, nombre)
                values (?, ?, ?, ?, ?, ?)
                """.formatted(schema), id, familyId, code, suffix, code, name);
    }

    private static void update(Connection connection, String sql, Object... values)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void setPublicSearchPath(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to public");
        }
    }

    private static void setLockTimeout(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set local lock_timeout = '5s'");
        }
    }

    private static int readBackendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select pg_backend_pid()")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static void awaitBlocked(
            String url, String user, String password, AtomicInteger backendPid)
            throws Exception {
        int pid = backendPid.get();
        assertThat(pid).isPositive();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Connection observer = DriverManager.getConnection(url, user, password);
                var statement = observer.prepareStatement("""
                        select exists (
                          select 1
                          from pg_stat_activity activity
                          where activity.pid = ?
                            and activity.wait_event_type = 'Lock'
                            and exists (
                              select 1 from pg_locks held
                              where held.pid = activity.pid and not held.granted))
                        """)) {
            while (System.nanoTime() < deadline) {
                statement.setInt(1, pid);
                try (var rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("El contendiente no llego a esperar un lock PostgreSQL real");
    }

    private static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private static String message(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return text.toString();
    }

    private static void migrate(
            String url, String user, String password, String schema, String target) {
        var configuration = FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target(MigrationVersion.fromVersion(target));
        configuration.load().migrate();
    }

    private static void dropSchema(String url, String user, String password, String schema) {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        } catch (Exception ignored) {
            // Preserve the original assertion failure.
        }
    }

    private static boolean canConnect(String url, String user, String password) {
        try (var ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String schemaUrl(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public";
    }

    private static String address() {
        return """
                {"linea1":"Test","ciudad":"Las Palmas","codigoPostal":"35001",
                 "provincia":"Las Palmas","pais":"ES"}
                """;
    }
}
