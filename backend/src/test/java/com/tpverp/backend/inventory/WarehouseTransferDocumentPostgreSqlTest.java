package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class WarehouseTransferDocumentPostgreSqlTest {
    private static final String URL = System.getenv().getOrDefault("TPV_ERP_TEST_DB_URL", "");
    private static final String USER = System.getenv().getOrDefault("TPV_ERP_TEST_DB_USER", "");
    private static final String PASSWORD = System.getenv().getOrDefault("TPV_ERP_TEST_DB_PASSWORD", "");
    private static final String SCHEMA = "transfer_document_" + UUID.randomUUID().toString().replace("-", "");
    private static final String ADDRESS = "{\"linea1\":\"Calle Uno\",\"ciudad\":\"Madrid\",\"codigoPostal\":\"28001\",\"provincia\":\"Madrid\",\"pais\":\"ES\"}";
    static {
        if (!URL.isBlank()) {
            if (!URL.matches("jdbc:postgresql://[^/]+/tpv_erp_[a-z_]*test(?:\\?.*)?")) {
                throw new IllegalStateException("Transfer tests require an isolated tpv_erp_*test database");
            }
            execute("create schema " + SCHEMA);
        }
    }
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @AfterAll static void dropSchema() { if (!URL.isBlank()) execute("drop schema if exists " + SCHEMA + " cascade"); }
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired WarehouseTransferDocumentRepository documents;

    @Test
    void listsWithoutOptionalFiltersAndWithOpenDateBounds() {
        var storeId = UUID.randomUUID();
        var page = org.springframework.data.domain.PageRequest.of(0, 50);
        assertThat(documents.pageFiltered(storeId, null, null, null, null, null, null, page).getContent()).isEmpty();
        var boundary = Instant.parse("2026-09-24T00:00:00Z");
        assertThat(documents.pageFiltered(storeId, null, null, null, boundary, null, null, page).getContent()).isEmpty();
        assertThat(documents.pageFiltered(storeId, null, null, null, null, boundary, null, page).getContent()).isEmpty();
    }


    @Test
    void roundTripsValuationDuplicateLinesOrderAndLineOnlyOptimisticRevision() {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        jdbc.update("insert into empresa (id,tax_id,razon_social,domicilio_fiscal) values (?,?,'Transfer test',cast(? as jsonb))",
                companyId, "B" + companyId.toString().replace("-", "").substring(0, 8), ADDRESS);
        jdbc.update("""
                insert into tienda (id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                values (?,?,'001','Transfer test',cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')
                """, storeId, companyId, ADDRESS, "transfer-" + storeId);
        jdbc.update("insert into rol (id,tienda_id,nombre,protegido) values (?,?,'ADMIN',true)", roleId, storeId);
        jdbc.update("insert into usuario (id,tienda_id,nombre,user_name,password_hash,rol_id,protegido) values (?,?,'ADMIN','ADMIN','hash',?,true)", userId, storeId, roleId);
        jdbc.update("insert into impuesto_tienda (id,tienda_id,porcentaje) values (?,?,21)", taxId, storeId);
        jdbc.update("insert into familia (id,tienda_id,nombre) values (?,?,'GENERAL')", familyId, storeId);
        jdbc.update("insert into almacen (id,tienda_id,nombre,predeterminado) values (?,?,'ORIGEN',true)", sourceId, storeId);
        jdbc.update("insert into almacen (id,tienda_id,nombre,predeterminado) values (?,?,'DESTINO',false)", targetId, storeId);
        jdbc.update("insert into producto (id,tienda_id,familia_id,impuesto_id,nombre,precio_compra) values (?,?,?,?,'Producto',1.104)", productId, storeId, familyId, taxId);
        jdbc.update("insert into producto_identificador (id,tienda_id,producto_id,tipo,valor) values (?,?,?,'CODIGO','P1')", UUID.randomUUID(), storeId, productId);

        var document = new WarehouseTransferDocument(storeId, sourceId, targetId, "Notas", userId,
                Instant.parse("2026-09-23T12:00:00Z"));
        document.valuation(LocalDate.of(2026, 8, 15), "EXT-42", WarehouseInputPriceSource.MEMBER,
                new BigDecimal("5.25"), new BigDecimal("3.32"));
        entities.persist(document);
        var product = entities.find(Product.class, productId);
        var first = new WarehouseTransferLine(document.getId(), product, new BigDecimal("2"),
                new BigDecimal("1.104"), new BigDecimal("10"), true, "Primera", 1);
        var second = new WarehouseTransferLine(document.getId(), product, BigDecimal.ONE,
                new BigDecimal("1.33"), BigDecimal.ZERO, false, "Segunda", 2);
        entities.persist(first);
        entities.persist(second);
        entities.flush();
        entities.clear();

        var page = org.springframework.data.domain.PageRequest.of(0, 1);
        // A full page also executes the count query; both queries must accept missing filters.
        var unfiltered = documents.pageFiltered(storeId, null, null, null, null, null, null, page);
        assertThat(unfiltered.getContent()).extracting(WarehouseTransferDocument::getId).containsExactly(document.getId());
        assertThat(unfiltered.getTotalElements()).isEqualTo(1);
        assertThat(documents.pageFiltered(storeId, WarehouseTransferDocument.Status.DRAFT, sourceId, targetId,
                Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"), "%notas%", page).getContent()).hasSize(1);
        assertThat(documents.pageFiltered(storeId, null, null, null, null, document.getCreatedAt(), null, page).getContent()).isEmpty();
        assertThat(documents.pageFiltered(storeId, null, null, null, document.getCreatedAt(), null, null, page).getContent()).hasSize(1);
        assertThat(documents.pageFiltered(UUID.randomUUID(), null, null, null, null, null, null, page).getContent()).isEmpty();
        assertThat(documents.pageFiltered(storeId, null, targetId, sourceId, null, null, null, page).getContent()).isEmpty();
        assertThat(documents.pageFiltered(storeId, null, null, null, null, null, "%missing%", page).getContent()).isEmpty();
        assertThat(documents.pageFiltered(storeId, null, null, null, null, null, null,
                org.springframework.data.domain.PageRequest.of(1, 1)).getContent()).isEmpty();

        var saved = entities.find(WarehouseTransferDocument.class, document.getId());
        assertThat(saved.getDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(saved.getExternalNumber()).isEqualTo("EXT-42");
        assertThat(saved.getPriceSource()).isEqualTo(WarehouseInputPriceSource.MEMBER);
        assertThat(saved.getGlobalDiscount()).isEqualByComparingTo("5.25");
        assertThat(saved.getSubtotal()).isEqualByComparingTo("3.32");
        assertThat(saved.getTotal()).isEqualByComparingTo("3.15");
        var persistedLines = entities.createQuery("select l from WarehouseTransferLine l where l.transferId = :id order by l.position", WarehouseTransferLine.class)
                .setParameter("id", document.getId()).getResultList();
        assertThat(persistedLines).extracting(WarehouseTransferLine::getProductName).containsExactly("Primera", "Segunda");
        assertThat(persistedLines.get(0).getUnitPrice()).isEqualByComparingTo("1.104");
        assertThat(persistedLines.get(0).getDiscount()).isEqualByComparingTo("10");
        assertThat(persistedLines.get(0).isPriceOverridden()).isTrue();
        long version = saved.getVersion();
        saved.update(sourceId, targetId, saved.getNotes());
        entities.flush();
        assertThat(saved.getVersion()).isEqualTo(version + 1);
        assertThat(jdbc.queryForObject("select count(*) from movimiento_stock where traspaso_almacen_id=?", Integer.class, document.getId())).isZero();
        assertThat(jdbc.queryForObject("select precio_compra from producto where id=?", BigDecimal.class, productId)).isEqualByComparingTo("1.104");
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL aislado", exception);
        }
    }
}
