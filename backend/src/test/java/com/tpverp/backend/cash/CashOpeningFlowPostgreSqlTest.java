package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CashOpeningFlowPostgreSqlTest {
    @Test
    void migrationAlertsReviewsTimelineAndClosureBreakdownsPreserveRealCash() throws Exception {
        var database=DatabaseEnvironment.resolve();
        assumeTrue(database!=null,"Configure TPV_TEST_DB_* para PostgreSQL");
        var schema="cash_opening_flow_"+UUID.randomUUID().toString().replace("-","");
        var companyId=UUID.randomUUID(); var storeId=UUID.randomUUID(); var roleId=UUID.randomUUID();
        var userOneId=UUID.randomUUID(); var userTwoId=UUID.randomUUID();
        var terminalAId=UUID.randomUUID(); var terminalBId=UUID.randomUUID();
        var previousId=UUID.randomUUID(); var countedId=UUID.randomUUID(); var pendingId=UUID.randomUUID();
        var withdrawalId=UUID.randomUUID();
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(database.url(),database.user(),database.password()).schemas(schema)
                .defaultSchema(schema).createSchemas(true).load().migrate();
            try(var connection=DriverManager.getConnection(database.url(),database.user(),database.password())) {
                try(var statement=connection.createStatement()) {
                    statement.execute("set search_path to " + schema);
                    statement.execute("""
                            insert into empresa(id,tax_id,razon_social,domicilio_fiscal)
                            values ('%s','B1','Test','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}')
                            """.formatted(companyId));
                    statement.execute("""
                            insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                            values ('%s','%s','T','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}',
                                    'h','Atlantic/Canary','EUR','es-ES','001')
                            """.formatted(storeId, companyId));
                    statement.execute("""
                            insert into terminal(id,tienda_id,nombre,tipo,credential_hash)
                            values ('%s','%s','TERMINAL B','TERMINAL_VENTA','h'),
                                   ('%s','%s','TERMINAL A','TERMINAL_VENTA','h')
                            """.formatted(terminalBId, storeId, terminalAId, storeId));
                    statement.execute("insert into rol(id,tienda_id,nombre) values ('%s','%s','SELLER')"
                            .formatted(roleId, storeId));
                    statement.execute("""
                            insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                            values ('%s','%s','USER ONE','user-one','h','%s'),
                                   ('%s','%s','USER TWO','user-two','h','%s')
                            """.formatted(userOneId, storeId, roleId, userTwoId, storeId, roleId));

                    statement.execute("""
                        insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,
                            usuario_cierre_id,cerrada_en,efectivo_teorico,fondo_dejado,descuadre,estado,fondo_dejado_desglose)
                        values ('%s','%s','%s','%s','2026-07-30T08:00Z',100,'%s','2026-07-30T18:00Z',50,50,0,'CERRADA',
                            '[{"denomination":50.00,"quantity":1}]')
                        """.formatted(previousId,storeId,terminalAId,userOneId,userOneId));
                    statement.execute("""
                        insert into movimiento_caja(id,tienda_id,terminal_id,sesion_caja_id,tipo,importe,creado_en,usuario_id)
                        values ('%s','%s','%s','%s','RETIRADA_CIERRE',50,'2026-07-30T17:59Z','%s')
                        """.formatted(withdrawalId,storeId,terminalAId,previousId,userOneId));
                    statement.execute("""
                        insert into movimiento_caja_denominacion(id,movimiento_caja_id,denominacion,cantidad)
                        values ('%s','%s',20,2),('%s','%s',10,1)
                        """.formatted(UUID.randomUUID(),withdrawalId,UUID.randomUUID(),withdrawalId));
                    statement.execute("""
                        insert into movimiento_caja(id,tienda_id,terminal_id,tipo,importe,creado_en,usuario_id)
                        values ('%s','%s','%s','ENTRADA_ENTRE_SESIONES',10,'2026-07-31T08:00Z','%s')
                        """.formatted(UUID.randomUUID(),storeId,terminalAId,userOneId));
                    statement.execute("""
                        insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,
                            apertura_esperado,apertura_contado,apertura_diferencia,usuario_cierre_id,cerrada_en,
                            efectivo_teorico,fondo_dejado,descuadre,estado)
                        values ('%s','%s','%s','%s','2026-07-31T09:00:00.123456Z',58,60,58,-2,
                            '%s','2026-07-31T12:00Z',60,60,0,'CERRADA')
                        """.formatted(countedId,storeId,terminalAId,userOneId,userOneId));
                    statement.execute("""
                        insert into movimiento_caja(id,tienda_id,terminal_id,sesion_caja_id,tipo,importe,creado_en,usuario_id)
                        values ('%s','%s','%s','%s','ENTRADA',5,'2026-07-31T10:00Z','%s'),
                               ('%s','%s','%s','%s','RETIRADA',3,'2026-07-31T11:00Z','%s')
                        """.formatted(UUID.randomUUID(),storeId,terminalAId,countedId,userOneId,
                            UUID.randomUUID(),storeId,terminalAId,countedId,userOneId));
                    statement.execute("""
                        insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,
                            apertura_esperado,apertura_contado,apertura_diferencia,estado)
                        values ('%s','%s','%s','%s','2026-07-31T13:00:00.123456Z',65,60,65,5,'ABIERTA')
                        """.formatted(pendingId,storeId,terminalAId,userTwoId));
                    assertThatThrownBy(()->statement.execute("update sesion_caja set apertura_diferencia=7 where id='"+pendingId+"'"))
                        .isInstanceOf(java.sql.SQLException.class);
                }
                var jdbc=new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection,true));
                var alerts=new CashOpeningAlertRepository(jdbc);
                assertThat(alerts.pendingCount(storeId)).isEqualTo(2);
                var rows=alerts.find(storeId,null,null,null,null,"PENDING",null,1);
                assertThat(rows).extracting(CashOpeningAlertView::id).containsExactly(pendingId);
                var next=alerts.find(storeId,null,null,null,null,"PENDING",
                    new CashOpeningAlertRepository.Cursor(rows.getFirst().openedAt(),rows.getFirst().id()),1);
                assertThat(next).extracting(CashOpeningAlertView::id).containsExactly(countedId);
                assertThat(alerts.find(UUID.randomUUID(),null,null,null,null,null,null,100)).isEmpty();
                assertThat(alerts.review(UUID.randomUUID(),pendingId,userOneId,Instant.now(),"Invalid scope",0)).isFalse();
                assertThat(alerts.review(storeId,pendingId,userOneId,Instant.parse("2026-07-31T14:00:00Z"),"Contado y revisado",0)).isTrue();
                assertThat(alerts.review(storeId,pendingId,userTwoId,Instant.now(),"Overwrite",0)).isFalse();
                assertThat(alerts.review(storeId,pendingId,userTwoId,Instant.now(),"Overwrite",1)).isFalse();
                var reviewed=alerts.findById(storeId,pendingId).orElseThrow();
                assertThat(reviewed.status()).isEqualTo("REVIEWED");
                assertThat(reviewed.reviewerId()).isEqualTo(userOneId);
                assertThat(reviewed.comment()).isEqualTo("Contado y revisado");
                assertThat(reviewed.version()).isEqualTo(1);
                assertThat(alerts.pendingCount(storeId)).isEqualTo(1);
                var timeline=new CashTimelineRepository(jdbc);
                var from=Instant.parse("2026-07-31T00:00:00Z"); var to=Instant.parse("2026-08-01T00:00:00Z");
                var events=timeline.find(storeId,terminalAId,from,to);
                assertThat(events).extracting(CashTimelineView.Item::action)
                    .containsExactly("ENTRADA_ENTRE_SESIONES","OPENING","ENTRADA","RETIRADA","CLOSING","OPENING");
                assertThat(events.stream().map(e->e.balance().intValue()).toList()).containsExactly(60,58,63,60,60,65);
                assertThat(events).extracting(CashTimelineView.Item::cashState).containsExactly(
                    CashSessionStatus.CERRADA, CashSessionStatus.ABIERTA, CashSessionStatus.ABIERTA,
                    CashSessionStatus.ABIERTA, CashSessionStatus.CERRADA, CashSessionStatus.ABIERTA);
                // This fixture inserts already-closed sessions before their movements: references follow
                // persisted allocation, and displaying events chronologically must never renumber them.
                assertThat(events).extracting(CashTimelineView.Item::reference)
                    .containsExactly("260731001","260731002","260731004","260731005","260731003","260731006");
                assertThat(events.get(4).amount()).isEqualByComparingTo("0");
                var organization = org.mockito.Mockito.mock(com.tpverp.backend.organization.CurrentOrganization.class);
                var store = org.mockito.Mockito.mock(com.tpverp.backend.organization.Store.class);
                var terminals = org.mockito.Mockito.mock(com.tpverp.backend.terminal.TerminalRepository.class);
                org.mockito.Mockito.when(organization.currentStore()).thenReturn(store);
                org.mockito.Mockito.when(store.getId()).thenReturn(storeId);
                org.mockito.Mockito.when(store.getTimezone()).thenReturn("Atlantic/Canary");
                org.mockito.Mockito.when(terminals.findByIdAndTiendaId(terminalAId, storeId))
                        .thenReturn(java.util.Optional.of(org.mockito.Mockito.mock(com.tpverp.backend.terminal.Terminal.class)));
                var permissions = new CashPermissionService(null, null, organization);
                var clock = java.time.Clock.fixed(from, java.time.ZoneOffset.UTC);
                var timelineService = new CashTimelineService(timeline, organization, terminals, permissions, clock);
                var reader = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "reader", "token", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("CASH_READ")));
                var seller = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "seller", "token", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("VENTA")));
                for (var auth : java.util.List.of(reader, seller)) {
                    var hidden = timelineService.timeline(terminalAId, java.time.LocalDate.parse("2026-07-31"), auth).items();
                    assertThat(hidden).hasSameSizeAs(events).allSatisfy(item -> {
                        assertThat(item.amount()).isNull();
                        assertThat(item.balance()).isNull();
                    });
                }
                var alertService = new CashOpeningAlertService(alerts, organization, permissions, clock);
                var hiddenAlerts = alertService.list(null, null, null, null, null, 100, null, reader).items();
                assertThat(hiddenAlerts).hasSize(2).allSatisfy(item -> {
                    assertThat(item.expectedFund()).isNull();
                    assertThat(item.countedFund()).isNull();
                    assertThat(item.difference()).isNull();
                });
                assertThat(timeline.find(storeId,terminalBId,from,to)).isEmpty();
                assertThat(timeline.find(UUID.randomUUID(),terminalAId,from,to)).isEmpty();
                var closures=new CashClosureQueryRepository(jdbc);
                var detail=closures.findById(storeId,previousId).orElseThrow();
                assertThat(detail.finalWithdrawalAmount()).isEqualByComparingTo("50");
                assertThat(detail.retainedFundDenominations()).hasSize(1);
                assertThat(detail.finalWithdrawalDenominations()).hasSize(2);
                assertThat(closures.findById(storeId,pendingId)).isEmpty();
                assertThat(closures.findById(UUID.randomUUID(),previousId)).isEmpty();
                verifyUnifiedAlerts(jdbc, storeId, countedId, pendingId, userOneId, userTwoId, terminalAId, terminalBId);
            }
        } finally {
            try(var connection=DriverManager.getConnection(database.url(),database.user(),database.password());
                    var statement=connection.createStatement()) {
                statement.execute("drop schema if exists "+schema+" cascade");
            }
        }
    }

    private static void verifyUnifiedAlerts(NamedParameterJdbcTemplate jdbc, UUID storeId, UUID closedSessionId,
            UUID collidingId, UUID openingUserId, UUID closingUserId, UUID terminalId, UUID otherTerminalId) {
        var firstAttemptId = UUID.randomUUID();
        var secondAttemptId = collidingId;
        var zeroAttemptId = UUID.randomUUID();
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("session", closedSessionId).addValue("first", firstAttemptId)
                .addValue("second", secondAttemptId).addValue("zero", zeroAttemptId)
                .addValue("user", closingUserId).addValue("firstUser", openingUserId);
        jdbc.update("""
                insert into intento_arqueo_caja(id,sesion_caja_id,numero_intento,usuario_id,creado_en,
                    fondo_declarado,efectivo_teorico,descuadre,cerro_sesion)
                values (:first,:session,1,:firstUser,'2026-07-31T12:30:00Z',15,20,-5,false),
                       (:zero,:second,1,:user,'2026-07-31T14:30:00Z',65,65,0,true)
                """, params);
        var openingAlerts = new CashOpeningAlertRepository(jdbc);
        var alerts = new CashAlertRepository(jdbc, openingAlerts);
        var firstOnly = alerts.find(storeId,null,null,null,null,null,"CLOSING",null,100);
        assertThat(firstOnly).hasSize(1);
        assertThat(firstOnly.getFirst().id()).isEqualTo(firstAttemptId);
        assertThat(firstOnly.getFirst().attemptNumber()).isEqualTo(1);
        assertThat(firstOnly.getFirst().sessionClosed()).isFalse();
        assertThat(alerts.findAttempts(storeId,closedSessionId)).hasSize(1);
        assertThat(alerts.pendingCount(storeId)).isEqualTo(2);
        assertThat(alerts.review(storeId,firstAttemptId,"CLOSING",openingUserId,Instant.now(),"Primer intento revisado",0)).isTrue();
        assertThat(alerts.pendingCount(storeId)).isEqualTo(1);
        jdbc.update("""
                insert into intento_arqueo_caja(id,sesion_caja_id,numero_intento,usuario_id,creado_en,
                    fondo_declarado,efectivo_teorico,descuadre,cerro_sesion)
                values (:second,:session,2,:user,'2026-07-31T13:00:00.123456Z',15,20,-5,true)
                """, params);
        var all = alerts.find(storeId,null,null,null,null,null,null,null,100);
        assertThat(all).hasSize(3);
        assertThat(all).extracting(CashAlertView::type).containsExactly("OPENING","CLOSING","OPENING");
        assertThat(alerts.pendingCount(storeId)).isEqualTo(2);
        assertThat(openingAlerts.pendingCount(storeId)).isEqualTo(1);
        assertThat(alerts.find(storeId,null,null,null,null,null,"CLOSING",null,100))
                .extracting(CashAlertView::attemptNumber).containsExactly(2);
        assertThat(all.get(1).sessionClosed()).isTrue();
        assertThat(all.get(1).difference()).isEqualByComparingTo("-5");
        assertThat(all.get(1).userId()).isEqualTo(closingUserId);
        assertThat(all.get(1).status()).isEqualTo("PENDING");
        assertThat(alerts.findAttempts(storeId,closedSessionId)).extracting(CashAlertAttemptView::attemptNumber).containsExactly(1,2);
        assertThat(alerts.findAttempts(storeId,closedSessionId)).extracting(CashAlertAttemptView::sessionClosed).containsExactly(false,true);
        assertThat(alerts.findById(storeId,firstAttemptId,"CLOSING")).isEmpty();
        assertThat(alerts.review(storeId,firstAttemptId,"CLOSING",openingUserId,Instant.now(),"Stale source",0)).isFalse();
        assertThat(jdbc.queryForObject("select comentario from intento_arqueo_caja_revision where intento_id=:first",params,String.class))
                .isEqualTo("Primer intento revisado");
        assertThat(alerts.find(storeId,null,null,null,openingUserId,null,null,null,100))
                .extracting(CashAlertView::id).containsExactly(closedSessionId);
        assertThat(alerts.find(storeId,null,null,otherTerminalId,null,null,null,null,100)).isEmpty();
        assertThat(alerts.find(storeId,Instant.parse("2026-07-31T13:00:00Z"),Instant.parse("2026-07-31T14:00:00Z"),
                terminalId,null,null,"CLOSING",null,100)).extracting(CashAlertView::id).containsExactly(secondAttemptId);
        assertThat(alerts.find(storeId,Instant.parse("2026-07-31T12:00:00Z"),Instant.parse("2026-07-31T13:00:00Z"),
                terminalId,null,null,"CLOSING",null,100)).isEmpty();
        assertThat(alerts.find(storeId,null,null,null,openingUserId,null,"CLOSING",null,100)).isEmpty();
        assertThat(alerts.find(storeId,null,null,null,null,"REVIEWED",null,null,100))
                .extracting(CashAlertView::id).containsExactly(collidingId);

        // Equal timestamps AND equal UUIDs across source tables must not lose either item.
        CashAlertRepository.Cursor cursor = null;
        var paged = new java.util.ArrayList<CashAlertView>();
        for (int i=0;i<3;i++) {
            var page = alerts.find(storeId,null,null,null,null,null,null,cursor,1);
            assertThat(page).hasSize(1);
            var item = page.getFirst();
            paged.add(item);
            cursor = new CashAlertRepository.Cursor(item.occurredAt(),item.id(),item.type());
        }
        assertThat(paged).isEqualTo(all);
        assertThat(alerts.find(storeId,null,null,null,null,null,null,cursor,1)).isEmpty();

        var wrongStore = UUID.randomUUID();
        assertThat(alerts.find(wrongStore,null,null,null,null,null,null,null,100)).isEmpty();
        assertThat(alerts.pendingCount(wrongStore)).isZero();
        assertThat(alerts.findById(wrongStore,collidingId,"CLOSING")).isEmpty();
        assertThat(alerts.findAttempts(wrongStore,closedSessionId)).isEmpty();
        assertThat(alerts.review(wrongStore,collidingId,"CLOSING",openingUserId,Instant.now(),"Wrong store",0)).isFalse();
        assertThat(alerts.review(storeId,firstAttemptId,"OPENING",openingUserId,Instant.now(),"Wrong source",0)).isFalse();
        assertThat(alerts.review(storeId,collidingId,"CLOSING",openingUserId,Instant.now(),"Stale version",1)).isFalse();
        assertThat(alerts.review(storeId,collidingId,"CLOSING",openingUserId,Instant.now(),"Intento revisado",0)).isTrue();
        assertThat(alerts.review(storeId,collidingId,"CLOSING",closingUserId,Instant.now(),"Overwrite",0)).isFalse();
        assertThat(alerts.review(storeId,collidingId,"CLOSING",closingUserId,Instant.now(),"Overwrite",1)).isFalse();
        var reviewed = alerts.findById(storeId,collidingId,"CLOSING").orElseThrow();
        assertThat(reviewed.version()).isEqualTo(1);
        assertThat(reviewed.reviewerId()).isEqualTo(openingUserId);
        assertThat(reviewed.status()).isEqualTo("REVIEWED");
        assertThat(alerts.findById(storeId,collidingId,"OPENING").orElseThrow().comment()).isEqualTo("Contado y revisado");
        assertThat(alerts.pendingCount(storeId)).isEqualTo(1);
        assertThat(alerts.review(storeId,closedSessionId,"OPENING",openingUserId,Instant.now(),"Apertura revisada",0)).isTrue();
        assertThat(openingAlerts.findById(storeId,closedSessionId).orElseThrow().status()).isEqualTo("REVIEWED");
        assertThat(openingAlerts.pendingCount(storeId)).isZero();
        assertThat(alerts.pendingCount(storeId)).isZero();
        assertThat(jdbc.queryForObject("select descuadre from intento_arqueo_caja where id=:first",params,java.math.BigDecimal.class))
                .isEqualByComparingTo("-5");

        // A zero final difference does not erase the first incorrect attempt or its alert history.
        var correctedSessionId = UUID.randomUUID();
        var correctedFirstId = UUID.randomUUID();
        var correctedFinalId = UUID.randomUUID();
        var corrected = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("session",correctedSessionId).addValue("store",storeId).addValue("terminal",terminalId)
                .addValue("user",closingUserId).addValue("first",correctedFirstId).addValue("last",correctedFinalId);
        jdbc.update("""
                insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,
                    usuario_cierre_id,cerrada_en,efectivo_teorico,fondo_dejado,descuadre,estado)
                values (:session,:store,:terminal,:user,'2026-07-30T08:00Z',20,:user,'2026-07-30T18:00Z',20,20,0,'CERRADA')
                """,corrected);
        jdbc.update("""
                insert into intento_arqueo_caja(id,sesion_caja_id,numero_intento,usuario_id,creado_en,
                    fondo_declarado,efectivo_teorico,descuadre,cerro_sesion)
                values (:first,:session,1,:user,'2026-07-30T17:00Z',15,20,-5,false),
                       (:last,:session,2,:user,'2026-07-30T18:00Z',20,20,0,true)
                """,corrected);
        var correctedRows = alerts.find(storeId,null,null,null,null,"PENDING","CLOSING",null,100);
        assertThat(correctedRows).hasSize(1);
        assertThat(correctedRows.getFirst().id()).isEqualTo(correctedFinalId);
        assertThat(correctedRows.getFirst().difference()).isEqualByComparingTo("0");
        assertThat(correctedRows.getFirst().countedFund()).isEqualByComparingTo("20");
        assertThat(correctedRows.getFirst().occurredAt()).isEqualTo(Instant.parse("2026-07-30T18:00:00Z"));
        assertThat(alerts.findAttempts(storeId,correctedSessionId)).extracting(CashAlertAttemptView::difference)
                .containsExactly(new java.math.BigDecimal("-5.00"),new java.math.BigDecimal("0.00"));
        assertThat(alerts.pendingCount(storeId)).isEqualTo(1);
        assertThat(alerts.review(storeId,correctedFinalId,"CLOSING",openingUserId,Instant.now(),"Final corregido revisado",0)).isTrue();
        assertThat(alerts.findById(storeId,correctedFinalId,"CLOSING").orElseThrow().status()).isEqualTo("REVIEWED");
        assertThat(alerts.pendingCount(storeId)).isZero();
    }

    private record DatabaseEnvironment(String url, String user, String password) {
        private static DatabaseEnvironment resolve() {
            var url = first("TPV_TEST_DB_URL", "TPV_ERP_TEST_DB_URL");
            var user = first("TPV_TEST_DB_USERNAME", "TPV_ERP_TEST_DB_USER");
            var password = first("TPV_TEST_DB_PASSWORD", "TPV_ERP_TEST_DB_PASSWORD");
            return url == null || user == null || password == null
                    ? null
                    : new DatabaseEnvironment(url, user, password);
        }

        private static String first(String primary, String legacy) {
            var value = System.getenv(primary);
            return value == null || value.isBlank() ? System.getenv(legacy) : value;
        }
    }
}
