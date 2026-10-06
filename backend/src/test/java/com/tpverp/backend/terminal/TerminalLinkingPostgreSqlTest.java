package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.cash.CashCurrentBalanceQueryRepository;
import com.tpverp.backend.cash.CashCurrentBalanceStatus;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.persistence.PostgreSqlTestDatabaseCleaner;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.*;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.shared.crypto.InstallationIdentity;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, TerminalLinkingService.class, AuthenticationService.class,
        CashCurrentBalanceQueryRepository.class, TerminalLinkingPostgreSqlTest.TestBeans.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TerminalLinkingPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "terminal_linking_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final String SERVER_SECRET = "existing-server-secret-with-32-characters";
    private static final KeyPair KEYS = keys();

    @Autowired TerminalLinkingService service;
    @Autowired AuthenticationService authentication;
    @Autowired TerminalRepository terminals;
    @Autowired TerminalPhysicalBindingRepository bindings;
    @Autowired UserSessionRepository sessions;
    @Autowired CashCurrentBalanceQueryRepository balances;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PasswordEncoder encoder;
    @MockitoBean CurrentOrganization organization;
    @MockitoBean InstallationStatusService installationStatus;
    @MockitoBean InstallationIdentityStore identity;
    @MockitoBean LicenseRepository licenses;
    @MockitoBean AuditService audit;
    @MockitoBean Clock clock;
    private Store store;
    private Terminal server;
    private UserAccount administrator;
    private UUID installationId;
    private License license;
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);

    @TestConfiguration
    static class TestBeans {
        @Bean PasswordEncoder testPasswordEncoder() { return new BCryptPasswordEncoder(4); }
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        values.add("spring.datasource.username", () -> USER);
        values.add("spring.datasource.password", () -> PASSWORD);
        values.add("spring.flyway.schemas", () -> SCHEMA);
        values.add("spring.flyway.default-schema", () -> SCHEMA);
        values.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @BeforeEach void fixture() throws Exception {
        PostgreSqlTestDatabaseCleaner.truncateInstallationAndCompanyGraphs(jdbc, SCHEMA);
        time.set(NOW);
        when(clock.instant()).thenAnswer(ignored -> time.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        var address = Map.of("linea1", "x", "ciudad", "x", "codigoPostal", "1", "provincia", "x", "pais", "ES");
        tx(() -> {
            var company = new Company("B12345678", "Linking test", address); em.persist(company);
            store = new Store(company, "Test", address, "test-hash", "Atlantic/Canary", "EUR", "es"); em.persist(store);
            server = new Terminal(store, "SERVIDOR", TerminalType.SERVIDOR, encoder.encode(SERVER_SECRET)); em.persist(server);
            var role = new Role(null, "ADMIN"); em.persist(role);
            administrator = new UserAccount(null, "ADMIN", encoder.encode("1234"), role); em.persist(administrator);
            var installation = new Installation("LINKTEST", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()), NOW);
            em.persist(installation); installationId = installation.getId();
            return null;
        });
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(store.getEmpresa());
        operational(OperationalMode.LICENSED);
        license = mock(License.class);
        when(license.getMaxWindows()).thenReturn(3);
        when(licenses.findFirstByTienda_IdAndActivaTrueOrderByValidaDesdeDesc(store.getId())).thenReturn(Optional.of(license));
        when(identity.loadOrCreate()).thenReturn(new InstallationIdentity("test", KEYS.getPublic(), KEYS.getPrivate()));
        when(identity.sign(any(byte[].class))).thenAnswer(invocation -> {
            var signer = Signature.getInstance("SHA256withRSA"); signer.initSign(KEYS.getPrivate());
            signer.update(invocation.getArgument(0, byte[].class)); return signer.sign();
        });
    }
    @AfterAll static void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test void chooses003Before002AndRetriesRequireThePersistedProof() {
        var request = request("003");
        var first = service.request(request);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(service.request(request)).isEqualTo(first);
        assertThat(service.status(proof(request)).bindingId()).isEqualTo(first.bindingId());
        assertThat(service.request(request("002")).terminalCode()).isEqualTo("002");
        assertThatThrownBy(() -> service.status(new TerminalLinkingService.Proof(request.requestId(), "wrong")))
                .isInstanceOf(TerminalLinkingException.class).hasMessage("LINK_PROOF_INVALID");
        assertThatThrownBy(() -> service.request(new TerminalLinkingService.LinkRequest(request.requestId(), request.deviceId(),
                request.credential(), "002", request.name(), request.deviceName())))
                .hasMessage("LINK_REQUEST_CONFLICT");
        assertThat(bindings.count()).isEqualTo(2);
    }

    @Test void concurrentDifferentRequestsReserveOneSlotAndSameRequestReplaysOneBinding() throws Exception {
        var first = request("003"); var second = request("003");
        var results = concurrently(() -> service.request(first), () -> service.request(second));
        assertThat(results.stream().filter(TerminalLinkingService.LinkState.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(TerminalLinkingException.class::isInstance).count()).isEqualTo(1);
        var retry = request("002");
        var repeated = concurrently(() -> service.request(retry), () -> service.request(retry));
        assertThat(repeated).allMatch(TerminalLinkingService.LinkState.class::isInstance);
        assertThat(repeated.get(0)).isEqualTo(repeated.get(1));
        assertThat(bindings.count()).isEqualTo(2);
    }

    @Test void expiryFreesReservationButOldApprovalCannotAffectReplacement() {
        var request = request("002"); var old = service.request(request);
        time.set(NOW.plusSeconds(901));
        var replacement = service.request(request("002"));
        assertThat(replacement.terminalId()).isEqualTo(old.terminalId());
        assertThat(replacement.bindingId()).isNotEqualTo(old.bindingId());
        assertThat(service.status(proof(request)).status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> service.action("002", old.bindingId(), "approve")).hasMessage("STALE_TERMINAL_BINDING");
        assertThat(service.management().slots()).filteredOn(slot -> slot.code().equals("002"))
                .extracting(TerminalLinkingService.Slot::bindingId).containsExactly(replacement.bindingId());
    }

    @Test void releaseRevokesOldCredentialsAndTokensPermanentlyAndPreservesOpenCash() {
        var request = request("002"); var linked = service.request(request);
        service.action("002", linked.bindingId(), "approve");
        var login = authentication.login(linked.terminalId(), request.credential(), "ADMIN", "1234");
        var sessionId = UUID.randomUUID();
        jdbc.update("""
                insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,estado,cierre_tardio)
                values (?,?,?,?,?,50,'ABIERTA',false)
                """, sessionId, store.getId(), linked.terminalId(), administrator.getId(), java.sql.Timestamp.from(NOW));
        service.action("002", linked.bindingId(), "release");
        assertThat(balances.findCurrentBalances(store.getId())).filteredOn(value -> value.terminalId().equals(linked.terminalId()))
                .singleElement().satisfies(value -> {
                    assertThat(value.status()).isEqualTo(CashCurrentBalanceStatus.ABIERTA);
                    assertThat(value.expectedCash()).isEqualByComparingTo("50");
                });
        assertThat(sessions.findByTokenHashAndRevocadaEnIsNull(authentication.hash(login.accessToken()))).isEmpty();
        var replacementRequest = request("002"); var replacement = service.request(replacementRequest);
        service.action("002", replacement.bindingId(), "approve");
        assertThat(replacement.terminalId()).isEqualTo(linked.terminalId());
        assertThatThrownBy(() -> authentication.login(linked.terminalId(), request.credential(), "ADMIN", "1234"))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(authentication.login(replacement.terminalId(), replacementRequest.credential(), "ADMIN", "1234")).isNotNull();
        service.action("002", linked.bindingId(), "release"); // Lost-response retry must not release the new PC.
        assertThat(service.status(proof(replacementRequest)).status()).isEqualTo("ACTIVE");
        assertThat(service.status(proof(request)).status()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("select terminal_id from sesion_caja where id=?", UUID.class, sessionId)).isEqualTo(linked.terminalId());
        assertThat(service.history("002")).hasSize(2);
    }

    @Test void disabledBindingKeepsSeatAndReleaseIsDistinct() {
        var request = request("002"); var state = service.request(request);
        service.action("002", state.bindingId(), "approve");
        service.action("002", state.bindingId(), "deactivate");
        assertThat(service.status(proof(request)).status()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> service.request(request("002"))).hasMessage("WORKSTATION_OCCUPIED");
        service.action("002", state.bindingId(), "approve");
        assertThat(service.status(proof(request)).status()).isEqualTo("ACTIVE");
    }

    @Test void concurrentLoginAndReleaseCannotLeaveAnOldLiveSession() throws Exception {
        var request = request("002"); var linked = service.request(request);
        service.action("002", linked.bindingId(), "approve");
        var results = concurrently(() -> authentication.login(linked.terminalId(), request.credential(), "ADMIN", "1234"),
                () -> service.action("002", linked.bindingId(), "release"));
        assertThat(results.get(1)).isInstanceOf(TerminalLinkingService.ManagementView.class);
        assertThat(sessions.findByTerminalIdAndRevocadaEnIsNull(linked.terminalId())).isEmpty();
        var replacement = request("002"); var next = service.request(replacement);
        service.action("002", next.bindingId(), "approve");
        if (results.get(0) instanceof com.tpverp.backend.security.application.LoginResult oldLogin)
            assertThatThrownBy(() -> authentication.renew(oldLogin.accessToken())).isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> authentication.login(linked.terminalId(), request.credential(), "ADMIN", "1234"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test void capacityDropDoesNotRenumberAndBlocksApprovalAndNewOutOfRangeRequests() {
        var request = request("003"); var state = service.request(request);
        when(license.getMaxWindows()).thenReturn(2);
        assertThat(service.management().slots()).filteredOn(slot -> slot.code().equals("003"))
                .singleElement().satisfies(slot -> assertThat(slot.outOfQuota()).isTrue());
        assertThatThrownBy(() -> service.action("003", state.bindingId(), "approve")).hasMessage("WORKSTATION_OUT_OF_QUOTA");
        service.cancel(proof(request));
        assertThatThrownBy(() -> service.request(request("003"))).hasMessage("WORKSTATION_OUT_OF_QUOTA");
        operational(OperationalMode.RESTRICTED);
        assertThatThrownBy(() -> service.request(request("002"))).hasMessage("LICENSE_REQUIRED");
    }

    @Test void serverAdoptionUses001WithoutRotatingItsSecretOrAddingASecondTerminal() {
        var adoption = new TerminalLinkingService.ServerAdoption(UUID.randomUUID(), UUID.randomUUID(), SERVER_SECRET, "BACKEND-PC", server.getId());
        assertThatThrownBy(() -> service.adoptServer(adoption, false, null)).hasMessage("SERVER_ADOPTION_REQUIRES_LOOPBACK");
        var adopted = service.adoptServer(adoption, true, null);
        assertThat(adopted.terminalCode()).isEqualTo("001");
        assertThat(adopted.terminalId()).isEqualTo(server.getId());
        assertThat(service.adoptServer(adoption, true, null)).isEqualTo(adopted);
        assertThat(terminals.findById(server.getId()).orElseThrow().getCredentialHash()).isEqualTo(server.getCredentialHash());
        assertThat(terminals.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.action("001", adopted.bindingId(), "release")).hasMessage("SERVER_SLOT_PROTECTED");
        assertThatThrownBy(() -> service.request(request("001"))).hasMessage("SERVER_SLOT_PROTECTED");
        var posRequest = request("002"); service.request(posRequest);
        assertThatThrownBy(() -> service.adoptServer(new TerminalLinkingService.ServerAdoption(UUID.randomUUID(), posRequest.deviceId(),
                SERVER_SECRET, "DUPLICATED-PC", server.getId()), true, administrator)).hasMessage("DEVICE_ALREADY_BOUND");
    }

    @Test void legacyCodeAssignmentPreservesIdentityAndCredentialAndCountsUnassignedActiveWindows() {
        var legacy = tx(() -> {
            var value = new Terminal(em.getReference(Store.class, store.getId()), "Legacy", TerminalType.TERMINAL_VENTA, encoder.encode("legacy"));
            em.persist(value); return value;
        });
        when(license.getMaxWindows()).thenReturn(2);
        assertThatThrownBy(() -> service.request(request("002"))).hasMessage("WORKSTATION_QUOTA_REACHED");
        service.assignLegacy(legacy.getId(), "002");
        var assigned = terminals.findById(legacy.getId()).orElseThrow();
        assertThat(assigned.getCredentialHash()).isEqualTo(legacy.getCredentialHash());
        assertThat(assigned.getWorkstationCode()).isEqualTo("002");
        assertThat(assigned.getCurrentBindingId()).isNotNull();
        assertThat(service.management().legacyTerminals()).isEmpty();
        var adoption = new TerminalLinkingService.ServerAdoption(UUID.randomUUID(), UUID.randomUUID(), "legacy", "OLD-PC", legacy.getId());
        var adopted = service.adoptLegacy(adoption);
        assertThat(adopted.bindingId()).isEqualTo(assigned.getCurrentBindingId());
        assertThat(service.adoptLegacy(adoption)).isEqualTo(adopted);
        assertThat(service.status(new TerminalLinkingService.Proof(adoption.requestId(), "legacy")).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.adoptLegacy(new TerminalLinkingService.ServerAdoption(UUID.randomUUID(), UUID.randomUUID(),
                "legacy", "OTHER-PC", legacy.getId()))).hasMessage("DEVICE_ALREADY_BOUND");
        service.action("002", adopted.bindingId(), "release");
        assertThatThrownBy(() -> service.adoptLegacy(adoption)).hasMessage("LINK_PROOF_INVALID");
    }

    @Test void explicitProtectedAdminServerReplacementFencesOldSessionAndCredential() {
        var old = new TerminalLinkingService.ServerAdoption(UUID.randomUUID(), UUID.randomUUID(), SERVER_SECRET, "BACKEND-PC", server.getId());
        var oldState = service.adoptServer(old, true, null);
        var oldLogin = authentication.login(server.getId(), SERVER_SECRET, "ADMIN", "1234");
        var newSecret = UUID.randomUUID().toString();
        var replacement = new TerminalLinkingService.ServerAdoption(UUID.randomUUID(), UUID.randomUUID(), newSecret, "NEW-BACKEND-PC", null, "Caja principal");
        var adopted = service.adoptServer(replacement, true, administrator);
        assertThat(adopted.terminalId()).isEqualTo(server.getId());
        assertThat(adopted.bindingId()).isNotEqualTo(oldState.bindingId());
        assertThat(adopted.terminalName()).isEqualTo("Caja principal");
        assertThat(sessions.findByTokenHashAndRevocadaEnIsNull(authentication.hash(oldLogin.accessToken()))).isEmpty();
        assertThatThrownBy(() -> authentication.login(server.getId(), SERVER_SECRET, "ADMIN", "1234"))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(authentication.login(server.getId(), newSecret, "ADMIN", "1234")).isNotNull();
        assertThat(service.adoptServer(replacement, true, administrator)).isEqualTo(adopted);
    }

    @Test void bootstrapSignsTheExactNonceAndRejectsDatabaseKeyMismatch() throws Exception {
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var bootstrap = service.bootstrap(nonce);
        assertThat(bootstrap.companyName()).isEqualTo("Linking test");
        var verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(KEYS.getPublic());
        verifier.update(("TPV-TERMINAL-LINKING-V1\n" + nonce + "\n" + installationId).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(bootstrap.signature()))).isTrue();
        assertThat(bootstrap.slots()).extracting(TerminalLinkingService.Slot::code).containsExactly("001", "002", "003");
        jdbc.update("update instalacion set public_key=?", Base64.getEncoder().encodeToString(keys().getPublic().getEncoded()));
        assertThatThrownBy(() -> service.bootstrap(nonce)).hasMessage("INSTALLATION_IDENTITY_MISMATCH");
    }

    private void operational(OperationalMode mode) {
        var status = new InstallationStatusService.InstallationStatus(installationId, "LINKTEST", NOW, NOW.plusSeconds(10000), mode, "TEST", true);
        when(installationStatus.status()).thenReturn(status);
        when(installationStatus.statusForStore(store.getId())).thenReturn(status);
    }
    private TerminalLinkingService.LinkRequest request(String code) {
        return new TerminalLinkingService.LinkRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), code,
                "PC-" + UUID.randomUUID(), "WINDOWS-PC");
    }
    private TerminalLinkingService.Proof proof(TerminalLinkingService.LinkRequest request) {
        return new TerminalLinkingService.Proof(request.requestId(), request.credential());
    }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactionManager).execute(ignored -> action.get()); }
    private static List<Object> concurrently(Supplier<?> first, Supplier<?> second) throws Exception {
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> attempt(first, gate));
            var b = executor.submit(() -> attempt(second, gate));
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }
    private static Object attempt(Supplier<?> action, CyclicBarrier gate) {
        try { gate.await(10, TimeUnit.SECONDS); return action.get(); }
        catch (Exception exception) { return exception; }
    }
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
