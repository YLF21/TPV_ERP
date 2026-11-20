package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tpverp.backend.party.MemberCardDeliveryRepository;
import com.tpverp.backend.party.MemberCardDeliveryWorker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Full upgrade and actual detached JPA worker lifecycle; never connects to SMTP. */
class MailMigrationPostgreSqlTest {
    @Test
    @Timeout(180)
    void upgradesExistingWelcomeQueueAndClaimsOnceWithDetachedRelations() throws Exception {
        String url = System.getenv("TPV_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "Explicit TPV_TEST_DB_URL required");
        assertThat(url).matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]*_test(?:\\?.*)?");
        String user = System.getenv().getOrDefault("TPV_TEST_DB_USERNAME", "tpv_erp");
        String password = System.getenv().getOrDefault("TPV_TEST_DB_PASSWORD", "");
        String schema = "mail_migration_test_" + UUID.randomUUID().toString().replace("-", "");
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        LocalContainerEntityManagerFactoryBean factory = null;
        try {
            // Keep the shared extension outside our disposable schema, including on a fresh test database.
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("create extension if not exists pg_trgm with schema public");
            }
            migrate(url, user, password, schema, "290");
            var source = new DriverManagerDataSource(schemaUrl, user, password);
            var jdbc = new JdbcTemplate(source);
            UUID company = UUID.randomUUID(), store = UUID.randomUUID(), customer = UUID.randomUUID();
            UUID member = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
            seed(jdbc, company, store, customer, member);
            insertLegacyDelivery(jdbc, first, member);
            migrate(url, user, password, schema, "303");
            assertThat(jdbc.queryForObject("select max(version::integer) from flyway_schema_history where success and version is not null", Integer.class))
                    .isEqualTo(303);
            assertThat(jdbc.queryForMap("select status,attempt_count,html_body from member_card_delivery where id=?", first))
                    .containsEntry("status", "PENDIENTE").containsEntry("attempt_count", 0).containsEntry("html_body", null);
            assertThat(jdbc.queryForObject("select count(*) from communication_template", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from mail_oauth_connection", Integer.class)).isZero();
            var cipher = new com.tpverp.backend.party.MailSecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]));
            String longCredential = "密".repeat(512);
            String encryptedCredential = cipher.encrypt(longCredential, company);
            jdbc.update("""
                    insert into member_smtp_settings(empresa_id,enabled,host,port,username,password,from_email,start_tls,ssl_enabled)
                    values(?,true,'smtp.example.org',587,'user',?,'sender@example.org',true,false)
                    """, company, encryptedCredential);
            assertThat(cipher.decrypt(jdbc.queryForObject("select password from member_smtp_settings where empresa_id=?",
                    String.class, company), company)).isEqualTo(longCredential);
            insertLegacyDelivery(jdbc, second, member);
            var claimSql = MemberCardDeliveryRepository.class.getMethod("claimNext", Instant.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value().replace(":now", "current_timestamp");
            try (var a = DriverManager.getConnection(schemaUrl,user,password);
                    var b = DriverManager.getConnection(schemaUrl,user,password)) {
                a.setAutoCommit(false); b.setAutoCommit(false);
                try {
                    UUID claimedA = claim(a, claimSql), claimedB = claim(b, claimSql);
                    assertThat(claimedA).isNotNull();
                    assertThat(claimedB).isNotNull().isNotEqualTo(claimedA);
                } finally { a.rollback(); b.rollback(); }
            }
            jdbc.update("delete from member_card_delivery where id=?", second);
            factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.tpverp.backend");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            factory.afterPropertiesSet();
            var emf = java.util.Objects.requireNonNull(factory.getObject());
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
            var repository = new JpaRepositoryFactory(entityManager).getRepository(MemberCardDeliveryRepository.class);
            var transactions = new JpaTransactionManager(emf);
            verifyFirstMemberSettingsSave(entityManager, transactions, company);
            var sends = new AtomicInteger();
            var worker = new MemberCardDeliveryWorker(repository, delivery -> {
                // This runs after the claim transaction closes; no OpenEntityManagerInView masks lazy-load defects.
                assertThat(Hibernate.isInitialized(delivery.getMember())).isTrue();
                assertThat(Hibernate.isInitialized(delivery.getMember().getCompany())).isTrue();
                assertThat(delivery.getMember().getCompany().getRazonSocial()).isEqualTo("Email test company");
                assertThat(jdbc.queryForObject("select status from member_card_delivery where id=?", String.class, first))
                        .isEqualTo("ENVIANDO");
                sends.incrementAndGet();
            }, Clock.systemUTC(), transactions);
            assertThat(worker.runOnce()).isEqualTo(1);
            assertThat(worker.runOnce()).isZero();
            assertThat(sends.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from member_card_delivery where id=?", String.class, first)).isEqualTo("ENVIADO");
            assertThat(jdbc.queryForObject("select attempt_count from member_card_delivery where id=?", Integer.class, first)).isEqualTo(1);
            jdbc.update("update member_card_delivery set status='ENVIANDO',claimed_at=current_timestamp-interval '11 minutes' where id=?", first);
            assertThat(worker.runOnce()).isZero();
            assertThat(jdbc.queryForObject("select status from member_card_delivery where id=?", String.class, first)).isEqualTo("INCIERTO");
            assertThat(sends.get()).isEqualTo(1);
        } finally {
            if (factory != null && factory.getObject() != null) factory.destroy();
            // Schema is an internal UUID-derived identifier, never accepted from the environment.
            try (var connection = DriverManager.getConnection(url,user,password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void verifyFirstMemberSettingsSave(jakarta.persistence.EntityManager entityManager,
            JpaTransactionManager transactions, UUID companyId) {
        var settings = new JpaRepositoryFactory(entityManager)
                .getRepository(com.tpverp.backend.party.MemberSettingsRepository.class);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            assertThat(settings.findById(companyId)).isEmpty();
            var company = entityManager.getReference(com.tpverp.backend.organization.Company.class, companyId);
            // Same assigned company ID and repository save used by GET /member-settings.
            var created = settings.saveAndFlush(new com.tpverp.backend.party.MemberSettings(company));
            assertThat(created.getCompanyId()).isEqualTo(companyId);
            assertThat(created.isMemberWelcomeEnabled()).isFalse();
        });
        tx.executeWithoutResult(status -> {
            var saved = settings.findById(companyId).orElseThrow();
            saved.update(saved.isBalanceAccrualEnabled(), saved.getBalanceAccrualBaseAmount(),
                    saved.getBalanceAccrualPercent(), saved.getBalanceExpirationPolicy(),
                    saved.isPointsAccrualEnabled(), saved.getPointsAccrualBaseAmount(),
                    saved.getPointsPerEuro(), saved.isCategoryAutoEnabled(), false,
                    com.tpverp.backend.party.MemberCardCodeFormat.BARCODE, "Welcome {{name}}", "Saved body");
            settings.flush();
        });
        tx.executeWithoutResult(status -> {
            var reloaded = settings.findById(companyId).orElseThrow();
            assertThat(reloaded.getWelcomeSubjectTemplate()).isEqualTo("Welcome {{name}}");
            assertThat(reloaded.getWelcomeBodyTemplate()).isEqualTo("Saved body");
            assertThat(reloaded.getMemberCardCodeFormat())
                    .isEqualTo(com.tpverp.backend.party.MemberCardCodeFormat.BARCODE);
            assertThat(settings.count()).isEqualTo(1);
        });
    }

    private static void migrate(String url,String user,String password,String schema,String target) {
        FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(url,user,password).schemas(schema).defaultSchema(schema).createSchemas(true)
                .target(target).load().migrate();
    }
    private static UUID claim(Connection connection,String query) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(query)) { return rows.next() ? rows.getObject("id",UUID.class) : null; }
        }
    }
    private static void seed(JdbcTemplate jdbc,UUID company,UUID store,UUID customer,UUID member) {
        String address = "{\"linea1\":\"Test street\",\"ciudad\":\"Madrid\",\"codigoPostal\":\"28001\",\"provincia\":\"Madrid\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values(?,'B00000001','Email test company',?::jsonb)",company,address);
        jdbc.update("""
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                values(?,?,'Email test store',?::jsonb,'mail-test-address','Europe/Madrid','EUR','es-ES','001')
                """,store,company,address);
        jdbc.update("""
                insert into cliente(id,empresa_id,nombre_fiscal,tipo_documento,numero_documento,client_id,client_code_store_id,email)
                values(?,?,'Email test customer','OTRO','MAILTEST','C-001-000001',?,'customer@example.org')
                """,customer,company,store);
        jdbc.update("""
                insert into miembro(id,empresa_id,cliente_id,member_id,member_code_store_id,member_since,active)
                values(?,?,?,'M-001-000001',?,current_date,true)
                """,member,company,customer,store);
    }
    private static void insertLegacyDelivery(JdbcTemplate jdbc,UUID id,UUID member) {
        jdbc.update("""
                insert into member_card_delivery(id,miembro_id,email,subject,body,card_code_format,card_code,created_at)
                values(?,?,'customer@example.org','Welcome','Legacy body','QR','M-001-000001',current_timestamp)
                """,id,member);
    }
}
