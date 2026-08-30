package com.tpverp.backend.party.loyalty.central;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Guards the PostgreSQL integration test against accidental non-test DB access. */
final class LocalMemberBalanceReservationTestDatabaseGuard {
    private static final String EXPECTED_DATABASE = "tpv_erp_test";
    private static final String JDBC_PREFIX = "jdbc:postgresql:";

    private LocalMemberBalanceReservationTestDatabaseGuard() {
    }

    static void validateBeforeSchemaCreation(String jdbcUrl, String username, String password) {
        validateJdbcUrl(jdbcUrl);
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
                var statement = connection.prepareStatement("select current_database()");
                var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("La conexión de pruebas no devolvió la base de datos actual");
            }
            validateDatabaseName(result.getString(1));
        } catch (SQLException error) {
            throw new IllegalStateException("No se pudo validar la base de datos PostgreSQL de pruebas");
        }
    }

    static void validateJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
            throw new IllegalArgumentException("La URL JDBC de pruebas no es PostgreSQL");
        }
        if (!jdbcUrl.regionMatches(true, 0, JDBC_PREFIX + "//", 0,
                (JDBC_PREFIX + "//").length())) {
            throw new IllegalArgumentException("La URL JDBC debe declarar explícitamente la base de datos de pruebas");
        }
        int authorityStart = (JDBC_PREFIX + "//").length();
        int pathStart = jdbcUrl.indexOf('/', authorityStart);
        if (pathStart < 0 || jdbcUrl.substring(authorityStart, pathStart).isBlank()
                || jdbcUrl.substring(authorityStart, pathStart).contains("@")) {
            throw new IllegalArgumentException("La URL JDBC no puede omitir el catálogo ni incluir credenciales embebidas");
        }
        rejectUnsafeParameters(jdbcUrl);
        String database = databaseFromUrl(jdbcUrl);
        if (database == null || !EXPECTED_DATABASE.equals(database)) {
            throw new IllegalArgumentException("La URL JDBC no apunta a la base de datos de pruebas esperada");
        }
    }

    static void validateDatabaseName(String databaseName) {
        if (!EXPECTED_DATABASE.equals(databaseName)) {
            throw new IllegalStateException("La conexión no apunta a la base de datos de pruebas esperada");
        }
    }

    private static void rejectUnsafeParameters(String jdbcUrl) {
        int queryStart = jdbcUrl.indexOf('?');
        if (queryStart < 0) {
            return;
        }
        String query = jdbcUrl.substring(queryStart + 1);
        for (String parameter : query.split("[&;]")) {
            int separator = parameter.indexOf('=');
            String key = separator < 0 ? parameter : parameter.substring(0, separator);
            key = URLDecoder.decode(key.trim(), StandardCharsets.UTF_8);
            if (key.equalsIgnoreCase("currentSchema")
                    || key.equalsIgnoreCase("options")
                    || key.equalsIgnoreCase("search_path")
                    || key.equalsIgnoreCase("user")
                    || key.equalsIgnoreCase("password")) {
                throw new IllegalArgumentException(
                        "La URL JDBC no puede definir schema, search_path ni credenciales embebidas");
            }
        }
    }

    private static String databaseFromUrl(String jdbcUrl) {
        if (!jdbcUrl.regionMatches(true, 0, JDBC_PREFIX + "//", 0,
                (JDBC_PREFIX + "//").length())) {
            return null;
        }
        int authorityStart = (JDBC_PREFIX + "//").length();
        int pathStart = jdbcUrl.indexOf('/', authorityStart);
        if (pathStart < 0) {
            return null;
        }
        int pathEnd = jdbcUrl.length();
        int queryStart = jdbcUrl.indexOf('?', pathStart);
        if (queryStart >= 0) {
            pathEnd = queryStart;
        }
        int fragmentStart = jdbcUrl.indexOf('#', pathStart);
        if (fragmentStart >= 0 && fragmentStart < pathEnd) {
            pathEnd = fragmentStart;
        }
        String database = jdbcUrl.substring(pathStart + 1, pathEnd).trim();
        return database.isEmpty() ? null : URLDecoder.decode(database, StandardCharsets.UTF_8);
    }
}
