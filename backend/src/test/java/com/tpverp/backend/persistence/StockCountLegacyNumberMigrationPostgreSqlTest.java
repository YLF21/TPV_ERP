package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.document.DocumentCounter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class StockCountLegacyNumberMigrationPostgreSqlTest {
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final UUID STORE_ONE = id("store-one");
    private static final UUID STORE_TWO = id("store-two");
    private static final UUID STORE_THREE = id("store-three");
    private static final UUID USER = id("user");
    private static final UUID PRODUCT = id("product");

    @Test
    void reservesNumbersAcrossStoresAndYearsWithoutChangingInventoryData() throws Exception {
        inSchema((connection, statement) -> {
            insertCount(connection, "valid", STORE_ONE, "INV-2026-000040", "2025-12-31", "CONFIRMED");
            insertCount(connection, "old-valid", STORE_TWO, "INV-2025-000009", "2025-01-01", "CANCELLED");
            insertCount(connection, "legacy-draft", STORE_ONE, null, "2026-01-01", "DRAFT");
            insertCount(connection, "legacy-confirmed", STORE_TWO, null, "2026-02-01", "CONFIRMED");
            insertCount(connection, "legacy-cancelled", STORE_ONE, null, "2026-03-01", "CANCELLED");
            insertCount(connection, "legacy-older", STORE_TWO, null, "2025-03-01", "CONFIRMED");
            // Similar text that was not generated from this inventory's UUID is not legacy.
            var customNumber = "INV-L-" + id("other-record").toString().replace("-", "");
            insertCount(connection, "custom", STORE_ONE, customNumber, "2026-04-01", "CANCELLED");
            insertCounter(statement, STORE_ONE, "INV", "2026", 42);
            insertCounter(statement, STORE_THREE, "INV", "2026", 50);
            insertCounter(statement, STORE_ONE, "INV", "2027", 99);
            insertCounter(statement, STORE_ONE, "SAL", "2026", 900);
            insertRelatedData(statement, id("legacy-confirmed"));
            connection.commit();

            var inventoryBefore = inventorySnapshot(statement);
            var relatedBefore = relatedSnapshot(statement);
            var untouchedCounters = scalar(statement,
                    "select jsonb_agg(to_jsonb(c) order by id)::text from contador_documento c "
                            + "where tipo = 'SAL' or periodo = '2027'");

            migrate(statement);
            connection.commit();

            assertThat(number(statement, "legacy-draft")).isEqualTo("INV-2026-000051");
            assertThat(number(statement, "legacy-confirmed")).isEqualTo("INV-2026-000052");
            assertThat(number(statement, "legacy-cancelled")).isEqualTo("INV-2026-000053");
            assertThat(number(statement, "legacy-older")).isEqualTo("INV-2025-000010");
            assertThat(number(statement, "valid")).isEqualTo("INV-2026-000040");
            assertThat(number(statement, "old-valid")).isEqualTo("INV-2025-000009");
            assertThat(number(statement, "custom")).isEqualTo(customNumber);
            assertThat(inventorySnapshot(statement)).isEqualTo(inventoryBefore);
            assertThat(relatedSnapshot(statement)).isEqualTo(relatedBefore);
            assertThat(scalar(statement,
                    "select jsonb_agg(to_jsonb(c) order by id)::text from contador_documento c "
                            + "where tipo = 'SAL' or periodo = '2027'")).isEqualTo(untouchedCounters);
            assertThat(scalar(statement,
                    "select string_agg(ultimo_numero::text, ',' order by tienda_id) "
                            + "from contador_documento where tipo='INV' and periodo='2026'"))
                    .isEqualTo("53,53,53");
            assertThat(counter(statement, STORE_TWO, "2025")).isEqualTo(10);

            var allBeforeRerun = fullSnapshot(statement);
            migrate(statement);
            connection.commit();
            assertThat(fullSnapshot(statement)).isEqualTo(allBeforeRerun);

            // Feed the persisted counter into the production formatter and insert the next
            // local document under the actual UNIQUE(numero) constraint.
            var counter = DocumentCounter.inventario(STORE_ONE, LocalDate.of(2026, 5, 1));
            ReflectionTestUtils.setField(counter, "ultimoNumero", counter(statement, STORE_ONE, "2026"));
            var nextNumber = counter.siguienteInventario(LocalDate.of(2026, 5, 1));
            assertThat(nextNumber).isEqualTo("INV-2026-000054");
            insertCount(connection, "next-local", STORE_ONE, nextNumber, "2026-05-01", "CONFIRMED");
            statement.executeUpdate("update contador_documento set ultimo_numero=54, version=version+1 "
                    + "where tienda_id='" + STORE_ONE + "' and tipo='INV' and periodo='2026'");
            connection.commit();
        });
    }

    @Test
    void preservesAllDigitsWhenSequenceExceedsSixDigits() throws Exception {
        inSchema((connection, statement) -> {
            insertCount(connection, "six-digit-valid", STORE_ONE, "INV-2026-999999", "2026-01-01", "CONFIRMED");
            insertCount(connection, "seven-digit-legacy", STORE_ONE, null, "2026-02-01", "CONFIRMED");
            connection.commit();
            migrate(statement);
            connection.commit();
            assertThat(number(statement, "six-digit-valid")).isEqualTo("INV-2026-999999");
            assertThat(number(statement, "seven-digit-legacy")).isEqualTo("INV-2026-1000000");
            assertThat(counter(statement, STORE_ONE, "2026")).isEqualTo(1_000_000);
        });
    }

    @Test
    void integerOverflowRollsBackTheWholeMigration() throws Exception {
        inSchema((connection, statement) -> {
            insertCount(connection, "overflow-legacy", STORE_ONE, null, "2026-01-01", "CONFIRMED");
            insertCount(connection, "otherwise-valid", STORE_TWO, null, "2025-01-01", "CANCELLED");
            insertCounter(statement, STORE_ONE, "INV", "2026", Integer.MAX_VALUE);
            connection.commit();
            var before = fullSnapshot(statement);
            assertThatThrownBy(() -> migrate(statement))
                    .isInstanceOf(SQLException.class).hasMessageContaining("limite del contador de inventarios");
            connection.rollback();
            assertThat(fullSnapshot(statement)).isEqualTo(before);
        });
    }

    private static void inSchema(DatabaseTest test) throws Exception {
        var schema = "stock_legacy_numbers_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("TPV_ERP_TEST_DB_URL"),
                System.getenv("TPV_ERP_TEST_DB_USER"), System.getenv("TPV_ERP_TEST_DB_PASSWORD"));
                var statement = connection.createStatement()) {
            boolean schemaCreated = false;
            try {
                statement.execute("create schema " + schema);
                schemaCreated = true;
                statement.execute("set search_path to " + schema);
                connection.setAutoCommit(false);
                createTables(statement);
                test.run(connection, statement);
            } finally {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(true);
                if (schemaCreated) {
                    statement.execute("set search_path to public");
                    statement.execute("drop schema " + schema + " cascade");
                }
            }
        }
    }

    private static void createTables(Statement statement) throws Exception {
        statement.execute("create table tienda(id uuid primary key)");
        statement.execute("create table almacen(id uuid primary key)");
        statement.execute("create table usuario(id uuid primary key)");
        statement.execute("create table producto(id uuid primary key)");
        statement.execute("create table movimiento_stock(id uuid primary key, producto_id uuid not null "
                + "references producto(id), cantidad numeric(19,3))");
        statement.execute("create table existencia(id uuid primary key, producto_id uuid not null "
                + "references producto(id), cantidad numeric(19,3))");
        statement.execute("create table contador_documento(id uuid primary key, tienda_id uuid not null "
                + "references tienda(id), tipo varchar(24) not null, periodo varchar(8) not null, "
                + "ultimo_numero integer not null default 0 check(ultimo_numero>=0), "
                + "version bigint not null default 0, unique(tienda_id,tipo,periodo))");
        statement.execute(Files.readString(MIGRATIONS.resolve("V129__recuentos_fisicos_stock.sql")));
        statement.execute(Files.readString(MIGRATIONS.resolve("V257__stock_count_document_numbers.sql")));
        statement.execute(Files.readString(MIGRATIONS.resolve("V261__stock_count_draft_documents.sql")));
        statement.execute("insert into tienda values ('" + STORE_ONE + "'),('" + STORE_TWO + "'),('" + STORE_THREE + "')");
        statement.execute("insert into usuario values ('" + USER + "')");
        statement.execute("insert into producto values ('" + PRODUCT + "')");
    }

    private static void insertCount(Connection connection, String key, UUID store, String number,
                                    String date, String status) throws SQLException {
        var countId = id(key);
        var warehouse = id("warehouse-" + key);
        try (var statement = connection.createStatement()) {
            statement.execute("insert into almacen values ('" + warehouse + "')");
        }
        try (var statement = connection.prepareStatement("insert into recuento_stock "
                + "(id,tienda_id,almacen_id,estado,notas,creado_por,creado_en,confirmado_por,confirmado_en,"
                + "cancelado_por,cancelado_en,version,numero,fecha,revision_edicion) "
                + "values (?,?,?,?,?,?,?::timestamptz,?,?::timestamptz,?,?::timestamptz,7,?,?::date,9)")) {
            statement.setObject(1, countId);
            statement.setObject(2, store);
            statement.setObject(3, warehouse);
            statement.setString(4, status);
            statement.setString(5, "Notes " + key);
            statement.setObject(6, USER);
            statement.setString(7, date + "T12:30:00Z");
            statement.setObject(8, status.equals("CONFIRMED") ? USER : null);
            statement.setString(9, status.equals("CONFIRMED") ? date + "T13:00:00Z" : null);
            statement.setObject(10, status.equals("CANCELLED") ? USER : null);
            statement.setString(11, status.equals("CANCELLED") ? date + "T13:00:00Z" : null);
            statement.setString(12, number == null ? "INV-L-" + countId.toString().replace("-", "") : number);
            statement.setString(13, date);
            statement.executeUpdate();
        }
    }

    private static void insertCounter(Statement statement, UUID store, String type, String year, int value)
            throws SQLException {
        statement.execute("insert into contador_documento values ('" + UUID.randomUUID() + "','" + store
                + "','" + type + "','" + year + "'," + value + ",4)");
    }

    private static void insertRelatedData(Statement statement, UUID count) throws SQLException {
        statement.execute("insert into recuento_stock_linea values ('" + id("line") + "','" + count
                + "','" + PRODUCT + "',10.125,12.250,2.125,3)");
        statement.execute("insert into movimiento_stock values ('" + id("movement") + "','" + PRODUCT
                + "',2.125,'" + count + "')");
        statement.execute("insert into existencia values ('" + id("stock") + "','" + PRODUCT + "',12.250)");
    }

    private static void migrate(Statement statement) throws Exception {
        statement.execute(Files.readString(MIGRATIONS.resolve("V274__stock_count_legacy_numeric_numbers.sql")));
    }

    private static String inventorySnapshot(Statement statement) throws SQLException {
        return scalar(statement, "select jsonb_agg(to_jsonb(r)-'numero' order by id)::text from recuento_stock r");
    }

    private static String relatedSnapshot(Statement statement) throws SQLException {
        return scalar(statement, "select jsonb_build_array("
                + "(select jsonb_agg(to_jsonb(l) order by id) from recuento_stock_linea l),"
                + "(select jsonb_agg(to_jsonb(m) order by id) from movimiento_stock m),"
                + "(select jsonb_agg(to_jsonb(e) order by id) from existencia e))::text");
    }

    private static String fullSnapshot(Statement statement) throws SQLException {
        return scalar(statement, "select jsonb_build_array("
                + "(select jsonb_agg(to_jsonb(r) order by id) from recuento_stock r),"
                + "(select jsonb_agg(to_jsonb(c) order by id) from contador_documento c))::text")
                + relatedSnapshot(statement);
    }

    private static String number(Statement statement, String key) throws SQLException {
        return scalar(statement, "select numero from recuento_stock where id='" + id(key) + "'");
    }

    private static int counter(Statement statement, UUID store, String year) throws SQLException {
        return Integer.parseInt(scalar(statement, "select ultimo_numero from contador_documento where tienda_id='"
                + store + "' and tipo='INV' and periodo='" + year + "'"));
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface DatabaseTest {
        void run(Connection connection, Statement statement) throws Exception;
    }
}
