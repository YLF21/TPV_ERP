package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CashActivityRepositoryPostgreSqlTest {
    @Test
    void storeActivityBalancesAllTerminalsBeforeFilteringAndPaginatesEverySortWithoutLosingEvents() throws Exception {
        var url = System.getenv("TPV_TEST_DB_URL");
        var username = System.getenv("TPV_TEST_DB_USERNAME");
        var password = System.getenv("TPV_TEST_DB_PASSWORD");
        assumeTrue(url != null && username != null && password != null, "Configure TPV_TEST_DB_*");
        var schema = "cash_activity_" + UUID.randomUUID().toString().replace("-", "");
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .createSchemas(true).load().migrate();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                execute(connection, "set search_path to " + schema);
                var store = UUID.randomUUID();
                var terminal = UUID.randomUUID();
                var user = UUID.randomUUID();
                seedStore(connection, store, terminal, user);
                var secondTerminal = UUID.randomUUID();
                var secondUser = UUID.randomUUID();
                execute(connection, """
                        insert into terminal(id,tienda_id,nombre,tipo,credential_hash,activa,aprobada)
                        values ('%s','%s','TPV 2','TERMINAL_VENTA','h',true,true)
                        """.formatted(secondTerminal, store));
                execute(connection, """
                        insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                        select '%s',tienda_id,'OTRO USUARIO','other','h',rol_id from usuario where id='%s'
                        """.formatted(secondUser, user));
                var foreignStore = UUID.randomUUID();
                var foreignTerminal = UUID.randomUUID();
                var foreignUser = UUID.randomUUID();
                seedStore(connection, foreignStore, foreignTerminal, foreignUser);
                movement(connection, foreignStore, foreignTerminal, foreignUser, null,
                        "ENTRADA_ENTRE_SESIONES", "999", "2026-08-01T08:00:00Z");
                var session = opening(connection, store, terminal, user, "2026-07-31T08:00:00Z", "100");
                var otherSession = opening(connection, store, secondTerminal, user, "2026-08-01T08:00:00Z", "40");
                movement(connection, store, terminal, user, session, "COBRO_EFECTIVO", "25", "2026-08-01T09:00:00Z");
                movement(connection, store, terminal, secondUser, session, "ENTRADA", "10", "2026-08-01T09:30:00.123456Z");
                movement(connection, store, secondTerminal, user, otherSession, "ENTRADA", "3", "2026-08-01T09:30:00.123456Z");
                movement(connection, store, terminal, user, session, "RETIRADA_CIERRE", "5", "2026-08-01T18:00:00Z");
                execute(connection, """
                        update sesion_caja set estado='CERRADA',usuario_cierre_id='%s',cerrada_en='2026-08-01T18:00:00Z',
                            efectivo_teorico=130,fondo_dejado=130,descuadre=0 where id='%s'
                        """.formatted(user, session));
                movement(connection, store, terminal, user, null, "ENTRADA_ENTRE_SESIONES", "5", "2026-08-01T19:00:00Z");
                var repository = new CashActivityRepository(new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true)));
                var from = Instant.parse("2026-07-31T23:00:00Z");
                var to = Instant.parse("2026-08-01T23:00:00Z");
                var all = repository.find(store, from, to, null, null, null, null, "date", "asc", null, 100);
                assertThat(all).hasSize(6).allSatisfy(row -> {
                    assertThat(row.item().terminalId()).isIn(terminal, secondTerminal);
                    assertThat(row.item().action()).isNotEqualTo("COBRO_EFECTIVO");
                    assertThat(row.item().reference()).matches("260801[0-9]{3}");
                });
                assertThat(all.stream().map(row -> row.item().reference()).distinct()).hasSize(6);
                var terminalRows = repository.find(store, from, to, terminal, null, null, null, "date", "asc", null, 100);
                assertThat(terminalRows).extracting(row -> row.item().action())
                        .containsExactly("ENTRADA", "RETIRADA_CIERRE", "CLOSING", "ENTRADA_ENTRE_SESIONES");
                assertThat(terminalRows).extracting(row -> row.item().balance().intValueExact()).containsExactly(135, 130, 130, 135);
                assertThat(terminalRows).extracting(row -> row.item().cashState()).containsExactly(
                        CashSessionStatus.ABIERTA, CashSessionStatus.ABIERTA, CashSessionStatus.CERRADA, CashSessionStatus.CERRADA);
                assertThat(repository.find(store, from, to, secondTerminal, null, null, null, "date", "asc", null, 100))
                        .extracting(row -> row.item().balance().intValueExact()).containsExactly(40, 43);
                var filtered = repository.find(store, from, to, terminal, user, "RETIRADA_CIERRE", "ABIERTA", "date", "asc", null, 100);
                assertThat(filtered).singleElement().satisfies(row -> {
                    assertThat(row.item().balance()).isEqualByComparingTo("130");
                    assertThat(row.item().amount()).isEqualByComparingTo("-5");
                });
                assertThat(repository.find(store, Instant.parse("2026-08-01T18:00:01Z"), to,
                        terminal, null, null, null, "date", "asc", null, 100))
                        .containsExactly(terminalRows.getLast());
                assertThat(repository.find(store, from, to, foreignTerminal, null, null, null, "date", "asc", null, 100)).isEmpty();
                assertThat(repository.find(store, from, to, null, foreignUser, null, null, "date", "asc", null, 100)).isEmpty();
                // Administrators can be global; changing store assignment must not erase their historical identity.
                execute(connection, "update usuario set tienda_id=null where id='%s'".formatted(secondUser));
                assertThat(repository.find(store, from, to, terminal, secondUser, null, null, "date", "asc", null, 100))
                        .singleElement().satisfies(row -> assertThat(row.item().userName()).isEqualTo("OTRO USUARIO"));
                assertThat(repository.userHasStoreActivity(store, secondUser)).isTrue();
                assertThat(repository.userHasStoreActivity(store, foreignUser)).isFalse();
                assertThat(repository.terminalOptions(store)).extracting(CashClosureFilterOptionView::id)
                        .containsExactlyInAnyOrder(terminal, secondTerminal);
                assertThat(repository.userOptions(store)).extracting(CashClosureFilterOptionView::id)
                        .containsExactlyInAnyOrder(user, secondUser);
                assertThat(repository.earliestDate(store, "Atlantic/Canary")).isEqualTo(LocalDate.parse("2026-07-31"));
                for (var sort : List.of("reference", "terminal", "date", "time", "user", "action", "cashState", "concept", "quantity", "balance")) {
                    for (var direction : List.of("asc", "desc")) {
                        var expected = repository.find(store, from, to, null, null, null, null, sort, direction, null, 100);
                        var collected = new ArrayList<CashActivityRepository.Row>();
                        CashActivityRepository.Cursor cursor = null;
                        for (int page = 0; page < 10; page++) {
                            var rows = repository.find(store, from, to, null, null, null, null, sort, direction, cursor, 2);
                            if (rows.isEmpty()) break;
                            collected.addAll(rows);
                            var last = rows.getLast();
                            cursor = new CashActivityRepository.Cursor(last.sortValue(), last.item().occurredAt(), last.priority(), last.item().id());
                        }
                        assertThat(collected).as("%s %s", sort, direction).containsExactlyElementsOf(expected);
                        assertThat(collected).extracting(row -> row.item().id()).doesNotHaveDuplicates();
                    }
                }
                assertThat(repository.find(store, from, to, null, null, null, null, "reference", "desc", null, 100))
                        .extracting(row -> row.item().reference())
                        .containsExactly("260801006", "260801005", "260801004", "260801003", "260801002", "260801001");
                verifiesDaylightSavingRange(connection, repository, store, terminal, user);
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                execute(connection, "drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void verifiesDaylightSavingRange(Connection connection, CashActivityRepository repository,
            UUID store, UUID terminal, UUID user) throws Exception {
        for (var at : List.of("2026-10-24T22:59:59Z", "2026-10-24T23:00:00Z", "2026-10-25T00:30:00Z",
                "2026-10-25T01:30:00Z", "2026-10-25T23:59:59Z", "2026-10-26T00:00:00Z")) {
            movement(connection, store, terminal, user, null, "ENTRADA_ENTRE_SESIONES", "1", at);
        }
        var zone = ZoneId.of("Atlantic/Canary");
        var day = LocalDate.parse("2026-10-25");
        var rows = repository.find(store, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant(),
                terminal, null, null, "CERRADA", "date", "asc", null, 100);
        assertThat(rows).hasSize(4).extracting(row -> row.item().balance().intValueExact()).containsExactly(137, 138, 139, 140);
        assertThat(rows).extracting(row -> row.item().reference())
                .containsExactly("261025001", "261025002", "261025003", "261025004");
    }

    private static UUID opening(Connection connection, UUID store, UUID terminal, UUID user, String at, String amount) throws Exception {
        var id = UUID.randomUUID();
        execute(connection, """
                insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,estado)
                values ('%s','%s','%s','%s','%s',%s,'ABIERTA')
                """.formatted(id, store, terminal, user, at, amount));
        return id;
    }

    private static void seedStore(Connection connection, UUID store, UUID terminal, UUID user) throws Exception {
        var company = UUID.randomUUID();
        var role = UUID.randomUUID();
        execute(connection, """
                insert into empresa(id,tax_id,razon_social,domicilio_fiscal)
                values ('%s','B1','Test','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}')
                """.formatted(company));
        execute(connection, """
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                values ('%s','%s','Test','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}',
                        'h','Atlantic/Canary','EUR','es-ES','001')
                """.formatted(store, company));
        execute(connection, """
                insert into terminal(id,tienda_id,nombre,tipo,credential_hash,activa,aprobada)
                values ('%s','%s','TPV 1','TERMINAL_VENTA','h',true,true)
                """.formatted(terminal, store));
        execute(connection, "insert into rol(id,tienda_id,nombre) values ('%s','%s','SELLER')".formatted(role, store));
        execute(connection, """
                insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                values ('%s','%s','CAJERO','cajero','h','%s')
                """.formatted(user, store, role));
    }

    private static void movement(Connection connection, UUID store, UUID terminal, UUID user, UUID session,
            String type, String amount, String at) throws Exception {
        execute(connection, """
                insert into movimiento_caja(id,tienda_id,terminal_id,sesion_caja_id,tipo,importe,creado_en,usuario_id)
                values ('%s','%s','%s',%s,'%s',%s,'%s','%s')
                """.formatted(UUID.randomUUID(), store, terminal, session == null ? "null" : "'" + session + "'",
                        type, amount, at, user));
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
