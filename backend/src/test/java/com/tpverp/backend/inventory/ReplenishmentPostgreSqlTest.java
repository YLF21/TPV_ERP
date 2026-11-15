package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, ReplenishmentService.class, ReplenishmentExpirationService.class,
        ReplenishmentSyncPublisher.class, SyncOutboxService.class, ReplenishmentPostgreSqlTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_PASSWORD", matches = ".+")
class ReplenishmentPostgreSqlTest {
    @TestConfiguration
    static class ClockConfig {
        @Bean Clock replenishmentTestClock() { return Clock.systemUTC(); }
    }

    private static final String URL = System.getenv("TPV_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_TEST_DB_USERNAME");
    private static final String PASSWORD = System.getenv("TPV_TEST_DB_PASSWORD");
    private static final String SCHEMA = "replenishment_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired ReplenishmentService service;
    @Autowired ReplenishmentExpirationService expiration;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReplenishmentSyncPublisher publisher;
    @Autowired SyncOutboxService outbox;
    @MockitoBean CurrentOrganization organization;
    private final ObjectMapper mapper = new ObjectMapper();
    private UUID storeId, productId, warehouseId, familyId, subfamilyId, supplierId;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void setup() {
        UUID company = UUID.randomUUID(), tax = UUID.randomUUID();
        storeId = UUID.randomUUID(); productId = UUID.randomUUID(); warehouseId = UUID.randomUUID();
        familyId = UUID.randomUUID(); subfamilyId = UUID.randomUUID(); supplierId = UUID.randomUUID();
        String address = "{\"linea1\":\"Test\",\"ciudad\":\"Test\",\"codigoPostal\":\"1\",\"provincia\":\"Test\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,'Reposicion',cast(? as jsonb))", company, company.toString(), address);
        jdbc.update("""
            insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
            values (?,?,'001','Reposicion',cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')
            """, storeId, company, address, storeId.toString());
        jdbc.update("insert into almacen(id,tienda_id,nombre) values (?,?,'GENERAL')", warehouseId, storeId);
        jdbc.update("insert into familia(id,tienda_id,nombre,family_id,family_code) values (?,?,'Familia','FAMILIA','001')", familyId, storeId);
        jdbc.update("insert into subfamilia(id,familia_id,nombre) values (?,?,'Subfamilia')", subfamilyId, familyId);
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values (?,?,0)", tax, storeId);
        jdbc.update("""
            insert into producto(id,tienda_id,familia_id,subfamilia_id,impuesto_id,nombre,precio_compra,package_quantity)
            values (?,?,?,?,?,'Producto test',2.50,12)
            """, productId, storeId, familyId, subfamilyId, tax);
        jdbc.update("insert into producto_identificador(id,tienda_id,producto_id,tipo,valor) values (?,?,?,'CODIGO','TEST')", UUID.randomUUID(), storeId, productId);
        jdbc.update("insert into producto_identificador(id,tienda_id,producto_id,tipo,valor) values (?,?,?,'CODIGO_BARRAS','0012345678901')", UUID.randomUUID(), storeId, productId);
        jdbc.update("""
            insert into proveedor(id,empresa_id,supplier_id,razon_social,tipo_documento,numero_documento)
            values (?,?,'S-000001','Proveedor test','OTRO','TEST')
            """, supplierId, company);
        jdbc.update("insert into producto_proveedor(id,producto_id,proveedor_id,principal) values (?,?,?,true)", UUID.randomUUID(), productId, supplierId);
        jdbc.update("insert into existencia(id,producto_id,almacen_id,cantidad,version) values (?,?,?,37,9)", UUID.randomUUID(), productId, warehouseId);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId); when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("ADMIN", "test",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach void clearAuth() { SecurityContextHolder.clearContext(); }

    @AfterAll static void cleanup() throws Exception {
        if (!SCHEMA.matches("replenishment_[0-9a-f]{32}")) throw new IllegalStateException(SCHEMA);
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void completeLifecycleLeavesStockAndMovementsUnchanged() {
        var stockBefore = jdbc.queryForList("select * from existencia order by id");
        var movementsBefore = jdbc.queryForList("select * from movimiento_stock order by id");
        var productsBefore = jdbc.queryForList("select * from producto order by id");
        service.create(productId, warehouseId, new BigDecimal("12"), "Reposición");
        service.create(productId, warehouseId, new BigDecimal("12"), "Falta stock");
        var rows = list(); assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ReplenishmentView::id).doesNotHaveDuplicates();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.quantity()).isEqualByComparingTo("12"); assertThat(r.stock()).isEqualByComparingTo("37");
            assertThat(r.orderDate()).isNull(); assertThat(r.status()).isEqualTo("PENDING");
            assertThat(r.supplierName()).isEqualTo("Proveedor test"); assertThat(r.purchasePrice()).isEqualByComparingTo("2.50");
        });
        assertThat(service.list(warehouseId,supplierId,familyId,subfamilyId,"0012345678901",null)).hasSize(2);
        assertThat(service.list(null,null,null,null,"%",null)).isEmpty();
        assertThat(service.list(UUID.randomUUID(),null,null,null,null,null)).isEmpty();
        assertThat(service.list(null,null,null,null,null,List.of(rows.getFirst().id()))).hasSize(1);
        service.edit(rows.getFirst().id(),0,new BigDecimal("24"),"Urgente");
        rows = list();
        service.changeStatus(items(rows),true);
        rows = list(); assertThat(rows).allSatisfy(r -> assertThat(r.orderDate()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Atlantic/Canary"))));
        var ordered = rows.getFirst();
        jdbc.update("update replenishment_request set order_date = date '2026-01-31' where id = ?",ordered.id());
        service.changeStatus(List.of(new ReplenishmentService.Item(ordered.id(), ordered.version())),true);
        assertThat(list().stream().filter(r -> r.id().equals(ordered.id())).findFirst().orElseThrow().orderDate()).isEqualTo(LocalDate.of(2026,1,31));
        assertThat(expiration.expire(storeId,LocalDate.of(2026,2,27))).isZero();
        assertThat(expiration.expire(storeId,LocalDate.of(2026,2,28))).isEqualTo(1);
        rows = list(); service.changeStatus(items(rows),false);
        assertThat(list()).allSatisfy(r -> { assertThat(r.orderDate()).isNull(); assertThat(r.status()).isEqualTo("PENDING"); });
        assertThat(expiration.expire(storeId,LocalDate.of(2099,1,1))).isZero();
        service.delete(items(list())); assertThat(list()).isEmpty();
        assertThat(jdbc.queryForList("select * from existencia order by id")).isEqualTo(stockBefore);
        assertThat(jdbc.queryForList("select * from movimiento_stock order by id")).isEqualTo(movementsBefore);
        assertThat(jdbc.queryForList("select * from producto order by id")).isEqualTo(productsBefore);
    }

    @Test
    void staleOrForeignBatchRollsBackEveryLineAndCannotEditOrderedRequests() {
        service.create(productId,warehouseId,BigDecimal.ONE,"Reposición");
        service.create(productId,warehouseId,BigDecimal.TEN,"Reposición");
        var rows = list();
        var batch = new ArrayList<>(items(rows));
        batch.add(new ReplenishmentService.Item(UUID.randomUUID(),0));
        long outboxBefore = outboxCount();
        assertThatThrownBy(() -> service.changeStatus(batch,true)).isInstanceOf(ReplenishmentConflictException.class);
        assertThat(list()).allSatisfy(r -> { assertThat(r.status()).isEqualTo("PENDING"); assertThat(r.version()).isZero(); });
        assertThat(outboxCount()).isEqualTo(outboxBefore);
        assertThatThrownBy(() -> service.delete(batch)).isInstanceOf(ReplenishmentConflictException.class);
        assertThat(list()).hasSize(2);
        assertThat(outboxCount()).isEqualTo(outboxBefore);
        service.changeStatus(items(rows),true);
        assertThatThrownBy(() -> service.edit(rows.getFirst().id(),1,BigDecimal.ONE,"Cambio"))
                .isInstanceOf(ReplenishmentConflictException.class);
        assertThatThrownBy(() -> service.changeStatus(items(rows),false)).isInstanceOf(ReplenishmentConflictException.class);
        var foreign = mock(Store.class); when(foreign.getId()).thenReturn(UUID.randomUUID()); when(foreign.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(foreign);
        assertThat(list()).isEmpty();
        assertThatThrownBy(() -> service.create(productId,warehouseId,BigDecimal.ONE,"Reposición")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(items(rows))).isInstanceOf(ReplenishmentConflictException.class);
    }

    @Test
    void publishesDistinctSnapshotsAndHigherRevisionForPermanentDelete() throws Exception {
        service.create(productId, warehouseId, new BigDecimal("12.500"), "Reposición");
        service.create(productId, warehouseId, new BigDecimal("9999999999999.123"), "Otra solicitud");
        var rows = list();
        assertThat(rows).hasSize(2);
        assertThat(outboxCount()).isEqualTo(2);
        for (var row : rows) {
            var event = latestEvent(row.id());
            assertThat(event.get("entityId").asText()).isEqualTo(row.id().toString());
            assertThat(event.get("operation").asText()).isEqualTo("CREAR");
            var payload = event.get("payload");
            assertThat(payload.get("schemaVersion").asInt()).isOne();
            assertThat(payload.get("requestId").asText()).isEqualTo(row.id().toString());
            assertThat(payload.get("revision").asLong()).isZero();
            assertThat(payload.get("productId").asText()).isEqualTo(productId.toString());
            assertThat(payload.get("warehouseId").asText()).isEqualTo(warehouseId.toString());
            assertThat(payload.get("quantity").isTextual()).isTrue();
            assertThat(payload.get("quantity").asText()).isEqualTo(row.quantity().toPlainString());
            assertThat(new BigDecimal(payload.get("metadata").get("stock").asText())).isEqualByComparingTo("37");
            assertThat(new BigDecimal(payload.get("metadata").get("purchasePrice").asText())).isEqualByComparingTo("2.50");
            assertThat(payload.get("reason").asText()).isEqualTo(row.reason());
            assertThat(payload.get("status").asText()).isEqualTo("PENDING");
            assertThat(payload.get("orderDate").isNull()).isTrue();
            assertThat(payload.get("createdAt").isNull()).isFalse();
            assertThat(payload.get("expiresAt").isNull()).isTrue();
            assertThat(payload.get("metadata").isObject()).isTrue();
        }

        var selected = rows.getFirst();
        service.edit(selected.id(), selected.version(), BigDecimal.TEN, "Cambio");
        assertThat(latestEvent(selected.id()).get("payload").get("revision").asLong()).isEqualTo(1);
        assertThat(latestEvent(selected.id()).get("operation").asText()).isEqualTo("ACTUALIZAR");
        service.delete(items(list().stream().filter(r -> r.id().equals(selected.id())).toList()));
        var deletion = latestEvent(selected.id());
        assertThat(deletion.get("operation").asText()).isEqualTo("BORRAR");
        assertThat(deletion.get("payload").get("revision").asLong()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    void expiryQueuesDeletionAtHigherRevisionWithoutStockMovement() throws Exception {
        var stockBefore = jdbc.queryForList("select * from existencia order by id");
        var movementsBefore = jdbc.queryForList("select * from movimiento_stock order by id");
        service.create(productId, warehouseId, BigDecimal.ONE, "Caducará");
        var row = list().getFirst();
        service.changeStatus(List.of(new ReplenishmentService.Item(row.id(), row.version())), true);
        jdbc.update("update replenishment_request set order_date = date '2026-01-31' where id = ?", row.id());

        assertThat(expiration.expire(storeId, LocalDate.of(2026, 2, 27))).isZero();
        assertThat(expiration.expire(storeId, LocalDate.of(2026, 2, 28))).isEqualTo(1);
        assertThat(list()).isEmpty();
        var event = latestEvent(row.id());
        assertThat(event.get("operation").asText()).isEqualTo("BORRAR");
        assertThat(event.get("payload").get("revision").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForList("select * from existencia order by id")).isEqualTo(stockBefore);
        assertThat(jdbc.queryForList("select * from movimiento_stock order by id")).isEqualTo(movementsBefore);
    }

    @Test
    void backfillQueuesLegacyRowsOnceWithoutChangingBusinessRevision() throws Exception {
        UUID legacyId = UUID.randomUUID();
        jdbc.update("""
            insert into replenishment_request(id, store_id, product_id, warehouse_id, quantity, reason)
            values (?,?,?,?,?,?)
            """, legacyId, storeId, productId, warehouseId, BigDecimal.TEN, "Anterior");
        service.create(productId, warehouseId, BigDecimal.ONE, "Actual");
        long outboxBefore = outboxCount();

        assertThat(publisher.backfill(10)).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(outboxBefore + 1);
        assertThat(publisher.backfill(10)).isZero();
        assertThat(outboxCount()).isEqualTo(outboxBefore + 1);
        assertThat(jdbc.queryForObject("select version from replenishment_request where id = ?", Long.class, legacyId)).isZero();
        assertThat(jdbc.queryForObject("select sync_enqueued_revision from replenishment_request where id = ?", Long.class, legacyId)).isZero();
        assertThat(latestEvent(legacyId).get("payload").get("revision").asLong()).isZero();
    }

    @Test
    void finalDeleteRemovesClaimedAndDeadLetterSnapshotsOnlyForThatRequest() throws Exception {
        service.create(productId, warehouseId, BigDecimal.ONE, "Borrar");
        service.create(productId, warehouseId, BigDecimal.TEN, "Conservar");
        var selected = list().stream().filter(row -> row.reason().equals("Borrar")).findFirst().orElseThrow();
        var other = list().stream().filter(row -> row.reason().equals("Conservar")).findFirst().orElseThrow();
        service.edit(selected.id(), 0, BigDecimal.TWO, "Borrar editada");
        jdbc.update("""
                update sync_outbox set estado = 'DEAD_LETTER'
                where tipo_entidad = 'REPLENISHMENT_REQUEST' and entidad_id = ?
                  and (payload->>'revision')::bigint = 0
                """, selected.id());
        jdbc.update("""
                update sync_outbox set estado = 'ENVIANDO'
                where tipo_entidad = 'REPLENISHMENT_REQUEST' and entidad_id = ?
                  and (payload->>'revision')::bigint = 1
                """, selected.id());
        UUID companyId = jdbc.queryForObject("select empresa_id from tienda where id = ?", UUID.class, storeId);
        var unrelated = outbox.enqueue(new SyncOutboundEventCommand(companyId, storeId, null,
                "DOCUMENTO", UUID.randomUUID(), SyncOperation.CONFIRMAR, Map.of("numero", "T-1")));

        service.delete(List.of(new ReplenishmentService.Item(selected.id(), 1)));

        assertThat(jdbc.queryForObject("""
                select count(*) from sync_outbox
                where tipo_entidad = 'REPLENISHMENT_REQUEST' and entidad_id = ?
                """, Long.class, selected.id())).isEqualTo(1);
        assertThat(latestEvent(selected.id()).get("operation").asText()).isEqualTo("BORRAR");
        assertThat(latestEvent(selected.id()).get("payload").size()).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from sync_outbox
                where tipo_entidad = 'REPLENISHMENT_REQUEST' and entidad_id = ?
                """, Long.class, other.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sync_outbox where event_id = ?", Long.class,
                unrelated.getEventId())).isEqualTo(1);
    }

    @Test
    void failedDeleteBatchRestoresBusinessRowAndPurgedSnapshot() {
        service.create(productId, warehouseId, BigDecimal.ONE, "Rollback");
        var selected = list().getFirst();
        jdbc.update("""
                update sync_outbox set estado = 'DEAD_LETTER'
                where tipo_entidad = 'REPLENISHMENT_REQUEST' and entidad_id = ?
                """, selected.id());
        UUID missingAfterSelected = new UUID(Long.MAX_VALUE, Long.MAX_VALUE);
        var batch = List.of(new ReplenishmentService.Item(selected.id(), 0),
                new ReplenishmentService.Item(missingAfterSelected, 0));

        assertThatThrownBy(() -> service.delete(batch)).isInstanceOf(ReplenishmentConflictException.class);

        assertThat(list()).extracting(ReplenishmentView::id).contains(selected.id());
        assertThat(jdbc.queryForObject("""
                select count(*) from sync_outbox
                where tipo_entidad = 'REPLENISHMENT_REQUEST' and entidad_id = ? and estado = 'DEAD_LETTER'
                """, Long.class, selected.id())).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void pricePermissionsAndValidationAreEnforced() {
        assertThatThrownBy(() -> service.create(productId,warehouseId,BigDecimal.ZERO,"Reposición")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(productId,warehouseId,new BigDecimal("1.0001"),"Reposición")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(productId,warehouseId,BigDecimal.ONE," ")).isInstanceOf(IllegalArgumentException.class);
        service.create(productId,warehouseId,BigDecimal.ONE,"Reposición");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("STOCK", "test",
                List.of(new SimpleGrantedAuthority("STOCK_READ"))));
        assertThat(list().getFirst().purchasePrice()).isNull();
        var selection = items(list());
        assertThatThrownBy(() -> service.delete(List.of(selection.getFirst(),selection.getFirst()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(list()).hasSize(1);
    }

    private List<ReplenishmentView> list() { return service.list(null,null,null,null,null,null); }
    private long outboxCount() {
        return jdbc.queryForObject("select count(*) from sync_outbox where tipo_entidad = 'REPLENISHMENT_REQUEST' and tienda_id = ?",
                Long.class, storeId);
    }
    private JsonNode latestEvent(UUID requestId) throws Exception {
        var payload = jdbc.queryForObject("""
                select jsonb_build_object('entityId', entidad_id, 'operation', operacion,
                        'payload', payload)::text
                from sync_outbox where tipo_entidad = 'REPLENISHMENT_REQUEST' and entidad_id = ?
                order by (payload->>'revision')::bigint desc, creado_en desc limit 1
                """, String.class, requestId);
        return mapper.readTree(payload);
    }
    private List<ReplenishmentService.Item> items(List<ReplenishmentView> rows) {
        return rows.stream().map(r -> new ReplenishmentService.Item(r.id(),r.version())).toList();
    }
}
