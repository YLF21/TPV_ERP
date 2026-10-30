package com.tpverp.backend.party.loyalty.points.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.party.MemberPointsOperationRepository;
import com.tpverp.backend.party.loyalty.central.HttpMemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.PointsBootstrapStoreRequest;
import com.tpverp.backend.party.loyalty.points.MemberPointsProjectionCoordinator;
import com.tpverp.backend.party.loyalty.points.MemberPointsOfficialFeedWorker;
import com.tpverp.backend.party.loyalty.points.MemberPointsOfficialProjectionService;
import com.tpverp.backend.shared.crypto.WindowsDpapiSecretProtector;
import com.tpverp.backend.sync.SyncOutboxEventRepository;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Explicit opt-in rehearsal against full restored clones; not selected by Surefire's default names.
 * Root infrastructure must start the clone bootstrap through its admin API first.
 * All environment names below have the TPV_POINTS_REHEARSAL_ prefix.
 * Required: ENABLED=true, MODE=MIGRATE, PREFLIGHT or ACTIVATE, RUN_ID, ROOT, CREDENTIALS,
 * STORE_URL/USER/PASSWORD, SAAS_URL/USER/PASSWORD, CENTRAL_URL. ACTIVATE also requires BOOTSTRAP_ID.
 * Both databases require public.codex_points_rehearsal_guard(run_id uuid, kind text),
 * with the same run UUID and kinds STORE/SAAS. ROOT/.points-rehearsal-id contains that UUID.
 * Credentials must be a real subdirectory of ROOT holding the copied DPAPI token.
 * No SpringApplication, component scan, scheduling, startup runners or migrations are used.
 */
@EnabledIfEnvironmentVariable(named = "TPV_POINTS_REHEARSAL_ENABLED", matches = "true")
class MemberPointsBootstrapCloneRehearsal {
    private static final String PREFIX = "TPV_POINTS_REHEARSAL_";

    @Test
    @EnabledIfEnvironmentVariable(named = "TPV_POINTS_REHEARSAL_MODE", matches = "MIGRATE")
    void migrateOnlyGuardedStoreCloneFrom284To285() throws Exception {
        Settings settings = Settings.loadAndValidate();
        JdbcTemplate local = new JdbcTemplate(settings.store().dataSource());
        JdbcTemplate central = new JdbcTemplate(settings.saas().dataSource());
        var before = stateFingerprints(local, central);
        String centralHistory = fingerprint(central, "flyway_schema_history");
        Map<String, String> preserved = new LinkedHashMap<>();
        for (String name : List.of("ck_member_points_projection_sequences",
                "ck_member_points_projection_state_status_v170", "ck_member_points_official_revision_nonnegative_v170")) {
            preserved.put(name, constraint(local, name));
        }
        var flyway = Flyway.configure().dataSource(settings.store().dataSource())
                .locations("classpath:db/migration").cleanDisabled(true).load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("284");
        assertThat(java.util.Arrays.stream(flyway.info().pending())
                .map(migration -> migration.getVersion().getVersion()).toList()).containsExactly("285");
        var migrated = flyway.migrate();
        assertThat(migrated.migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("285");
        assertThat(local.queryForObject("select max(version::integer) from flyway_schema_history "
                + "where success and version ~ '^[0-9]+$'", Integer.class)).isEqualTo(285);
        assertThat(constraint(local, "ck_member_points_projection_status")).contains("CENTRAL_ACTIVE");
        preserved.forEach((name, definition) -> assertThat(constraint(local, name)).isEqualTo(definition));
        assertThat(stateFingerprints(local, central)).isEqualTo(before);
        assertThat(fingerprint(central, "flyway_schema_history")).isEqualTo(centralHistory);
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValue(
                settings.root().resolve("points-bootstrap-migration-result.json").toFile(),
                Map.of("runId", settings.runId().toString(), "mode", "MIGRATE", "fromVersion", 284,
                        "toVersion", 285, "executedMigrations", 1, "financialDataUnchanged", true,
                        "centralMigrationHistoryUnchanged", true));
    }

    private static String constraint(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint "
                + "where conrelid='member_points_projection_state'::regclass and conname=?", String.class, name);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TPV_POINTS_REHEARSAL_MODE", matches = "PREFLIGHT")
    void readOnlyContractPreflightBeforeStartingBootstrap() throws Exception {
        Settings settings = Settings.loadAndValidate();
        JdbcTemplate local = new JdbcTemplate(settings.store().dataSource());
        JdbcTemplate central = new JdbcTemplate(settings.saas().dataSource());
        try (var context = openContext(settings)) {
            verifyContracts(context, local, central);
            assertThat(central.queryForObject("select count(*) from saas_member_points_bootstrap",
                    Long.class)).isZero();
            context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValue(
                    settings.root().resolve("points-bootstrap-preflight-result.json").toFile(),
                    Map.of("runId", settings.runId().toString(), "mode", "PREFLIGHT",
                            "matchedOriginalContracts", 23, "matchedPendingOperations", 23,
                            "localOperations", fingerprint(local, "member_points_operation"),
                            "localOutbox", fingerprint(local, "sync_outbox"),
                            "centralOperations", fingerprint(central, "saas_member_points_operation")));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TPV_POINTS_REHEARSAL_MODE", matches = "ACTIVATE")
    void initializeCentralPointsFromClonedStoreAndVerifyIdempotence() throws Exception {
        Settings settings = Settings.loadAndValidate();
        JdbcTemplate local = new JdbcTemplate(settings.store().dataSource());
        JdbcTemplate central = new JdbcTemplate(settings.saas().dataSource());
        String originalOutbox = fingerprint(local, "sync_outbox");
        String originalReceipts = fingerprint(central, "saas_sync_event");
        String originalLots = fingerprint(central, "saas_member_balance_lot");

        try (var context = openContext(settings)) {
            var site = verifyContracts(context, local, central);
            var gateway = context.getBean(MemberBalanceCentralGateway.class);
            var request = new PointsBootstrapStoreRequest(site.companyId(), site.storeId());
            assertThat(central.queryForObject("select company_id from saas_member_points_bootstrap where id=?",
                    UUID.class, settings.bootstrapId())).isEqualTo(site.companyId());
            // A randomly created clone-only bootstrap ID must resolve at the configured HTTP origin
            // before the worker can discover or upload anything there.
            var remote = gateway.pointsBootstrapStatus(settings.bootstrapId(), request);
            assertThat(remote.bootstrapId()).isEqualTo(settings.bootstrapId());
            assertThat(remote.companyId()).isEqualTo(site.companyId());
            assertThat(remote.expectedStoreIds()).containsExactly(site.storeId());
            assertThat(remote.status()).isEqualTo("COLLECTING");
            assertThat(central.queryForObject("select count(*) from saas_member_points_operation "
                    + "where company_id=? and status='PENDING_BOOTSTRAP'", Long.class, site.companyId()))
                    .isEqualTo(23L);
            var localPoints = local.queryForList("select id,member_points points,loyalty_points_debt debt "
                    + "from miembro where empresa_id=? order by id", site.localCompanyId());
            var localMoney = local.queryForList("select id,member_balance,return_credit_balance,loyalty_balance_debt "
                    + "from miembro where empresa_id=? order by id", site.localCompanyId());
            var centralMoney = central.queryForList("select member_id,balance,return_credit_balance "
                    + "from saas_member_balance_account where company_id=? order by member_id", site.companyId());

            var worker = context.getBean(MemberPointsBootstrapWorker.class);
            boolean completed = false;
            String previousProgress = null;
            for (int run = 0; run < 100; run++) {
                worker.runOnce();
                remote = gateway.pointsBootstrapStatus(settings.bootstrapId(), request);
                assertThat(remote.status()).as("Central bootstrap conflict: %s", remote.conflictReason())
                        .isNotIn("CONFLICT", "CANCELLED");
                String state = local.queryForObject("select status from member_points_projection_state where tienda_id=?",
                        String.class, site.localStoreId());
                if ("COMPLETED".equals(remote.status()) && "CENTRAL_ACTIVE".equals(state)) {
                    completed = true;
                    break;
                }
                String progress = state + ":" + remote.status() + ":"
                        + fingerprint(local, "member_points_bootstrap_upload");
                assertThat(progress).as("Worker swallowed an error or stopped progressing; inspect its warning")
                        .isNotEqualTo(previousProgress);
                previousProgress = progress;
            }
            assertThat(completed).as("Bootstrap must finish within 100 bounded manual runs").isTrue();
            var feed = context.getBean(MemberPointsOfficialFeedWorker.class);
            feed.runOnce();
            assertThat(local.queryForObject("select official_revision from member_points_projection_state where tienda_id=?",
                    Long.class, site.localStoreId())).as("Official feed must advance after bootstrap").isPositive();
            assertThat(local.queryForObject("select count(*) from member_points_official_inbox where tienda_id=? "
                    + "and applied_at is null", Long.class, site.localStoreId())).isZero();
            assertThat(central.queryForObject("select status from saas_member_points_authority where company_id=?",
                    String.class, site.companyId())).isEqualTo("ACTIVE");
            assertThat(central.queryForObject("select count(*) from saas_member_points_operation where company_id=?",
                    Long.class, site.companyId())).isEqualTo(23L);
            assertThat(central.queryForObject("select count(*) from saas_member_points_operation "
                    + "where company_id=? and status='ABSORBED_BOOTSTRAP'", Long.class, site.companyId()))
                    .isEqualTo(23L);
            assertThat(local.queryForList("select id,member_points points,loyalty_points_debt debt "
                    + "from miembro where empresa_id=? order by id", site.localCompanyId())).isEqualTo(localPoints);
            for (var points : localPoints) {
                assertThat(central.queryForObject("select points::bigint from saas_member_balance_account "
                        + "where company_id=? and member_id=?", Long.class, site.companyId(), points.get("id")))
                        .isEqualTo(((Number) points.get("points")).longValue());
                assertThat(central.queryForObject("select points_debt::bigint from saas_member_balance_account "
                        + "where company_id=? and member_id=?", Long.class, site.companyId(), points.get("id")))
                        .isEqualTo(((Number) points.get("debt")).longValue());
            }
            assertThat(local.queryForList("select id,member_balance,return_credit_balance,loyalty_balance_debt "
                    + "from miembro where empresa_id=? order by id", site.localCompanyId())).isEqualTo(localMoney);
            for (var money : centralMoney) {
                assertThat(central.queryForMap("select member_id,balance,return_credit_balance "
                        + "from saas_member_balance_account where company_id=? and member_id=?",
                        site.companyId(), money.get("member_id"))).isEqualTo(money);
            }
            assertThat(fingerprint(local, "sync_outbox")).isEqualTo(originalOutbox);
            assertThat(fingerprint(central, "saas_sync_event")).isEqualTo(originalReceipts);
            assertThat(fingerprint(central, "saas_member_balance_lot")).isEqualTo(originalLots);

            Map<String, String> completedState = stateFingerprints(local, central);
            worker.runOnce();
            worker.runOnce();
            feed.runOnce();
            assertThat(stateFingerprints(local, central)).isEqualTo(completedState);
            int duplicateReceipts = resendOriginalPointsEvents(context, settings, local, site);
            assertThat(stateFingerprints(local, central)).isEqualTo(completedState);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("runId", settings.runId().toString());
            report.put("bootstrapId", settings.bootstrapId().toString());
            report.put("status", "COMPLETED");
            report.put("authority", "ACTIVE");
            report.put("localStatus", "CENTRAL_ACTIVE");
            report.put("absorbedOperations", 23);
            report.put("memberCount", localPoints.size());
            report.put("idempotentWorkerReplays", 2);
            report.put("idempotentOfficialFeedReplay", true);
            report.put("acceptedOriginalEventReplays", duplicateReceipts);
            report.put("fingerprints", completedState);
            context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter()
                    .writeValue(settings.root().resolve("points-bootstrap-rehearsal-result.json").toFile(), report);
        }
    }

    private static int resendOriginalPointsEvents(AnnotationConfigApplicationContext context, Settings settings,
            JdbcTemplate local, MemberBalanceCentralContextResolver.BootstrapContext site) throws Exception {
        var ids = local.queryForList("select id from sync_outbox where empresa_id=? and tienda_id=? "
                + "and tipo_entidad='MEMBER_POINTS_OPERATION' order by store_sequence", UUID.class,
                site.localCompanyId(), site.localStoreId());
        assertThat(ids).hasSize(23);
        var repository = context.getBean(SyncOutboxEventRepository.class);
        var mapper = context.getBean(ObjectMapper.class);
        String token = context.getBean(LicenseSaasCredentialStore.class).readToken().orElseThrow();
        int accepted = 0;
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (UUID id : ids) {
                // Read the same JPA Map and serialize the same envelope as HttpSyncEventSender.
                // Deliberately do not claim, retry or mark any local outbox row as sent.
                var event = repository.findById(id).orElseThrow();
                var translated = new LinkedHashMap<>(event.getPayload());
                if (translated.containsKey("companyId")) translated.put("companyId", site.companyId());
                if (translated.containsKey("storeId")) translated.put("storeId", site.storeId());
                var body = new LinkedHashMap<String, Object>();
                body.put("eventId", event.getEventId());
                body.put("companyId", site.companyId());
                body.put("storeId", site.storeId());
                body.put("terminalId", event.getTerminalId());
                body.put("storeSequence", event.getStoreSequence());
                body.put("entityType", event.getEntityType());
                body.put("entityId", event.getEntityId());
                body.put("operation", event.getOperation());
                body.put("payload", translated);
                var request = HttpRequest.newBuilder(URI.create(settings.centralUrl().replaceAll("/+$", "")
                                + "/api/v1/sync/events"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-TPV-Installation-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as("Original event replay %s", event.getEventId()).isEqualTo(200);
                var receipt = mapper.readTree(response.body());
                // SyncEventReceipt exposes accepted, not a separate duplicate flag. The immutable
                // before/after table fingerprints establish that this was an idempotent receipt.
                assertThat(receipt.path("accepted").asBoolean()).isTrue();
                assertThat(receipt.path("eventId").asText()).isEqualTo(event.getEventId().toString());
                accepted++;
            }
        }
        return accepted;
    }

    private static AnnotationConfigApplicationContext openContext(Settings settings) {
        if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
            for (String name : List.of("org.hibernate.SQL", "org.hibernate.orm.jdbc.bind",
                    "org.hibernate.orm.jdbc.extract")) logs.getLogger(name).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        var context = new AnnotationConfigApplicationContext();
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "clone-rehearsal", Map.of("tpv.sync.central-url", settings.centralUrl())));
            context.registerBean(Settings.class, () -> settings);
            context.register(RehearsalConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.boot.ApplicationRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.boot.CommandLineRunner.class)).isEmpty();
            return context;
        } catch (RuntimeException | Error exception) {
            context.close();
            throw exception;
        }
    }

    private static MemberBalanceCentralContextResolver.BootstrapContext verifyContracts(
            AnnotationConfigApplicationContext context, JdbcTemplate local, JdbcTemplate central) {
        var sites = context.getBean(MemberBalanceCentralContextResolver.class).resolveBootstrapContexts();
        assertThat(sites).as("Exactly the intended cloned store must be linked").hasSize(1);
        var site = sites.getFirst();
        long cut = local.queryForObject("select last_sequence from member_points_projection_state where tienda_id=?",
                Long.class, site.localStoreId());
        var rows = context.getBean(MemberPointsOperationRepository.class).findBootstrapOperations(
                site.localCompanyId(), site.localStoreId(), cut, PageRequest.of(0, 500));
        assertThat(rows.hasNext()).isFalse();
        assertThat(rows.getContent()).hasSize(23);
        var ids = rows.getContent().stream().map(row -> row.getOperationId()).toList();
        var originals = context.getBean(SyncOutboxEventRepository.class)
                .findPointsContractOriginals(ids, ids.stream().map(UUID::toString).toList());
        assertThat(originals).as("Every operation must retain exactly one immutable outbox original").hasSize(23);
        assertThat(originals.stream().map(row -> row.getEntityId()).toList()).containsExactlyInAnyOrderElementsOf(ids);
        var hashes = context.getBean(MemberPointsBootstrapContractResolver.class)
                .resolve(site.localCompanyId(), site.localStoreId(), rows.getContent());
        assertThat(central.queryForObject("select count(*) from saas_member_points_operation where company_id=?",
                Long.class, site.companyId())).isEqualTo(23L);
        for (var row : rows) {
            var pending = central.queryForMap("select operation_id,payload_hash,status,store_id,store_sequence,source_event_id "
                    + "from saas_member_points_operation where company_id=? and operation_id=?",
                    site.companyId(), row.getOperationId());
            assertThat(pending.get("status")).isEqualTo("PENDING_BOOTSTRAP");
            assertThat(pending.get("payload_hash")).as("Original contract hash for %s", row.getOperationId())
                    .isEqualTo(hashes.get(row.getOperationId()));
            assertThat(pending.get("store_id")).isEqualTo(site.storeId());
            assertThat(((Number) pending.get("store_sequence")).longValue()).isEqualTo(row.getStoreSequence());
            var original = originals.stream().filter(value -> value.getEntityId().equals(row.getOperationId()))
                    .findFirst().orElseThrow();
            assertThat(pending.get("source_event_id")).isEqualTo(original.getEventId());
        }
        return site;
    }

    private static Map<String, String> stateFingerprints(JdbcTemplate local, JdbcTemplate central) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String table : List.of("miembro", "member_points_projection_state", "member_points_bootstrap_upload",
                "member_points_official_inbox", "sync_outbox")) values.put("local." + table, fingerprint(local, table));
        for (String table : List.of("saas_member_balance_account", "saas_member_balance_lot",
                "saas_member_points_operation", "saas_member_points_bootstrap",
                "saas_member_points_bootstrap_absorbed_operation", "saas_member_points_authority", "saas_sync_event"))
            values.put("central." + table, fingerprint(central, table));
        return values;
    }

    private static String fingerprint(JdbcTemplate jdbc, String constantTable) {
        return jdbc.queryForObject("select md5(coalesce(string_agg(row_value,'|' order by row_value),'')) "
                + "from (select to_jsonb(t)::text row_value from " + constantTable + " t) rows", String.class);
    }

    record Database(String url, String user, String password) {
        DataSource dataSource() {
            var source = new DriverManagerDataSource(url, user, password);
            if ("PREFLIGHT".equals(System.getenv(PREFIX + "MODE"))) {
                var properties = new java.util.Properties();
                properties.setProperty("options", "-c default_transaction_read_only=on");
                source.setConnectionProperties(properties);
            }
            return source;
        }

        void validate(UUID runId, String kind) throws Exception {
            URI address = URI.create(url.substring("jdbc:".length()));
            require(url.startsWith("jdbc:postgresql://127.0.0.1:") && address.getPort() >= 1024
                    && address.getPort() != 5432 && address.getQuery() == null
                    && address.getPath().matches("/espos_points_rehearsal_[a-z0-9_]+"),
                    "Only dedicated loopback clone databases are allowed");
            try (var connection = DriverManager.getConnection(url, user, password)) {
                connection.setReadOnly(true);
                try (var statement = connection.prepareStatement(
                        "select count(*) from public.codex_points_rehearsal_guard where run_id=? and kind=?")) {
                    statement.setObject(1, runId);
                    statement.setString(2, kind);
                    try (var result = statement.executeQuery()) {
                        require(result.next() && result.getInt(1) == 1, "Clone marker does not match " + kind);
                    }
                }
            }
        }
    }

    record Settings(UUID runId, UUID bootstrapId, Path root, Path credentials,
            Database store, Database saas, String centralUrl) {
        static Settings loadAndValidate() throws Exception {
            UUID runId = UUID.fromString(required("RUN_ID"));
            Path root = Path.of(required("ROOT")).toRealPath();
            require(Files.readString(root.resolve(".points-rehearsal-id")).trim().equals(runId.toString()),
                    "Rehearsal filesystem marker does not match");
            Path credentials = Path.of(required("CREDENTIALS")).toRealPath();
            require(credentials.startsWith(root) && !credentials.equals(root), "Use a credential COPY under rehearsal root");
            require(credentials.resolve("saas-installation-token.dpapi").toRealPath().startsWith(root),
                    "Copied token escapes rehearsal root");
            Database store = database("STORE");
            Database saas = database("SAAS");
            require(!store.url().equals(saas.url()), "Store and SaaS must be distinct clones");
            store.validate(runId, "STORE");
            saas.validate(runId, "SAAS");
            String centralUrl = required("CENTRAL_URL");
            URI http = URI.create(centralUrl);
            require("http".equals(http.getScheme()) && "127.0.0.1".equals(http.getHost())
                    && http.getPort() >= 1024 && http.getPort() != 8080 && http.getPort() != 8081
                    && http.getUserInfo() == null && http.getQuery() == null && http.getFragment() == null
                    && (http.getPath().isEmpty() || "/".equals(http.getPath())), "Use a dedicated clone HTTP origin");
            String mode = required("MODE");
            require(List.of("MIGRATE", "PREFLIGHT", "ACTIVATE").contains(mode), "Unknown rehearsal mode");
            UUID bootstrapId = "ACTIVATE".equals(mode) ? UUID.fromString(required("BOOTSTRAP_ID")) : null;
            return new Settings(runId, bootstrapId, root, credentials,
                    store, saas, centralUrl);
        }

        private static Database database(String name) {
            return new Database(required(name + "_URL"), required(name + "_USER"), required(name + "_PASSWORD"));
        }
    }

    private static String required(String suffix) {
        String value = System.getenv(PREFIX + suffix);
        require(value != null && !value.isBlank(), "Missing rehearsal variable " + PREFIX + suffix);
        return value;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {"com.tpverp.backend.party", "com.tpverp.backend.licensing",
            "com.tpverp.backend.organization", "com.tpverp.backend.sync"})
    @Import({MemberPointsBootstrapWorker.class, MemberPointsBootstrapCaptureService.class,
            MemberPointsBootstrapSealService.class, MemberPointsBootstrapProgressService.class,
            MemberPointsBootstrapContractResolver.class, MemberPointsProjectionCoordinator.class,
            MemberPointsOfficialFeedWorker.class, MemberPointsOfficialProjectionService.class,
            MemberBalanceCentralContextResolver.class, HttpMemberBalanceCentralGateway.class})
    static class RehearsalConfiguration {
        @Bean DataSource dataSource(Settings settings) { return settings.store().dataSource(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().findAndAddModules().build(); }
        @Bean LicenseSaasCredentialStore credentials(Settings settings) {
            return new LicenseSaasCredentialStore(settings.credentials(), new WindowsDpapiSecretProtector());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.tpverp.backend");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.show_sql", "false",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
