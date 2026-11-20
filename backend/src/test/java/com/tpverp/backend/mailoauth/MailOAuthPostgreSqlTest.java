package com.tpverp.backend.mailoauth;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.MailSecretCipher;
import com.tpverp.backend.party.MemberSmtpSettingsRepository;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;

class MailOAuthPostgreSqlTest {
    private Connection connection;
    private String schema;
    private JdbcTemplate jdbc;
    private MailOAuthService service;
    private MailOAuthClient client;
    private CurrentOrganization organization;
    private Company company;
    private MailSecretCipher cipher;
    private MemberSmtpSettingsRepository smtp;

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("TPV_TEST_DB_URL");
        assumeTrue(url!=null&&!url.isBlank(),"Requires an explicit disposable PostgreSQL test database");
        assertThat(url).matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]*_test(?:\\?.*)?");
        String user=System.getenv().getOrDefault("TPV_TEST_DB_USERNAME","tpv_erp_test");
        String password=System.getenv().getOrDefault("TPV_TEST_DB_PASSWORD","");
        connection=DriverManager.getConnection(url,user,password);
        schema="mail_oauth_test_"+UUID.randomUUID().toString().replace("-","");
        try(var sql=connection.createStatement()) {
            sql.execute("create schema "+schema);
            sql.execute("set search_path to "+schema);
            sql.execute("create table empresa(id uuid primary key)");
            sql.execute(Files.readString(Path.of("src/main/resources/db/migration/V303__mail_oauth.sql")));
        }
        var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(dataSource);
        company=new Company("B00000000","Test",Map.of("linea1","Test","ciudad","Madrid","provincia","Madrid","codigoPostal","28001","pais","ES"));
        jdbc.update("insert into empresa(id) values(?)",company.getId());
        organization=mock(CurrentOrganization.class);when(organization.currentCompany()).thenReturn(company);
        client=mock(MailOAuthClient.class);
        when(client.exchange(any(),any())).thenReturn(new MailOAuthClient.Tokens("access-1","refresh-1",3600));
        smtp=mock(MemberSmtpSettingsRepository.class);
        cipher=new MailSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var env=new MockEnvironment().withProperty("tpv.mail.oauth.google.client-id","client")
                .withProperty("tpv.mail.oauth.google.client-secret","secret")
                .withProperty("tpv.mail.oauth.redirect-uri","http://127.0.0.1:8080/api/v1/mail-oauth/callback");
        service=new MailOAuthService(jdbc,organization,env,cipher,client,smtp,Clock.systemUTC(),new DataSourceTransactionManager(dataSource));
    }
    @AfterEach void cleanup() throws Exception {
        if(connection==null)return;
        try(var owned=connection;var sql=owned.createStatement()) {
            if(schema!=null&&schema.matches("mail_oauth_test_[0-9a-f]{32}"))sql.execute("drop schema "+schema+" cascade");
        }
    }
    private String begin() {
        String url=service.connect(MailOAuthProvider.GOOGLE,"store@example.com").authorizationUrl();
        return Arrays.stream(URI.create(url).getRawQuery().split("&")).filter(part->part.startsWith("state="))
                .map(part->URLDecoder.decode(part.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    @Test void codeIsSingleUseAndTokensAreEncryptedAndRefreshed() {
        String state=begin();
        assertThat(jdbc.queryForObject("select state_hash from mail_oauth_flow",String.class)).isEqualTo(MailOAuthService.hash(state));
        service.callback(state,"code",null);
        assertThat(service.status().connection().email()).isEqualTo("store@example.com");
        String encrypted=jdbc.queryForObject("select access_token from mail_oauth_connection",String.class);
        assertThat(encrypted).startsWith("enc:v1:").doesNotContain("access-1");
        assertThat(cipher.decrypt(encrypted,company.getId())).isEqualTo("access-1");
        assertThatThrownBy(()->cipher.decrypt(encrypted,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.callback(state,"code",null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.smtpCredentials(company.getId()).orElseThrow().accessToken()).isEqualTo("access-1");
        jdbc.update("update mail_oauth_connection set expires_at=?",Timestamp.from(Instant.now().minusSeconds(60)));
        when(client.exchange(any(),any())).thenReturn(new MailOAuthClient.Tokens("access-2","refresh-2",3600));
        assertThat(service.smtpCredentials(company.getId()).orElseThrow().accessToken()).isEqualTo("access-2");
        assertThat(cipher.decrypt(jdbc.queryForObject("select refresh_token from mail_oauth_connection",String.class),company.getId())).isEqualTo("refresh-2");
        verify(client,times(2)).exchange(any(),any());
        service.disconnect();
        assertThat(service.smtpCredentials(company.getId())).isEmpty();
    }
    @Test void newerAttemptInvalidatesOldConsentAndCancellationDuringExchangeCannotReconnect() {
        String old=begin();String current=begin();
        assertThatThrownBy(()->service.callback(old,"oldcode",null)).isInstanceOf(IllegalArgumentException.class);
        when(client.exchange(any(),any())).thenAnswer(invocation->{
            service.disconnect();
            return new MailOAuthClient.Tokens("access","refresh",3600);
        });
        assertThatThrownBy(()->service.callback(current,"code",null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from mail_oauth_connection",Integer.class)).isZero();
    }
    @Test void expiredAndFailedConsentCannotBeReplayed() {
        String expired=begin();
        jdbc.update("update mail_oauth_flow set expires_at=?",Timestamp.from(Instant.now().minusSeconds(5)));
        assertThatThrownBy(()->service.callback(expired,"code",null)).isInstanceOf(IllegalArgumentException.class);
        String cancelled=begin();
        assertThatThrownBy(()->service.callback(cancelled,null,"access_denied")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.callback(cancelled,"code",null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test void differentOrUnauthorizedMailboxDoesNotBecomeConnected() {
        String state = begin();
        doThrow(MailOAuthClient.unavailable()).when(client).verifyIdentity(any(), anyString(), anyString());
        assertThatThrownBy(() -> service.callback(state, "code", null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from mail_oauth_connection", Integer.class)).isZero();
        assertThatThrownBy(() -> service.callback(state, "code", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pkceVerifierIsEncryptedAndMatchesAuthorizationChallenge() {
        String authorization = service.connect(MailOAuthProvider.GOOGLE, "store@example.com").authorizationUrl();
        var parameters = new HashMap<String,String>();
        for (var part : URI.create(authorization).getRawQuery().split("&")) {
            var pair = part.split("=", 2);
            parameters.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        String stored = jdbc.queryForObject("select verifier from mail_oauth_flow", String.class);
        String verifier = cipher.decrypt(stored, company.getId());
        assertThat(stored).startsWith("enc:v1:").doesNotContain(verifier);
        assertThat(parameters).containsEntry("code_challenge_method", "S256")
                .containsEntry("code_challenge", MailOAuthService.challenge(verifier));
        service.callback(parameters.get("state"), "code", null);
        verify(client).exchange(eq(MailOAuthProvider.GOOGLE), argThat(form ->
                verifier.equals(form.get("code_verifier")) && "authorization_code".equals(form.get("grant_type"))));
    }

    @Test @Timeout(20) void concurrentCallbacksExchangeTheCodeOnlyOnce() throws Exception {
        String state = begin();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.exchange(any(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Callback exchange was not released");
            return new MailOAuthClient.Tokens("access", "refresh", 3600);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.callback(state, "code", null));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.callback(state, "code", null)).isInstanceOf(IllegalArgumentException.class);
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        verify(client, times(1)).exchange(any(), any());
        assertThat(jdbc.queryForObject("select count(*) from mail_oauth_connection", Integer.class)).isEqualTo(1);
    }

    @Test @Timeout(20) void concurrentExpiredTokenReadersRefreshOnceAndPreserveOmittedRefreshToken() throws Exception {
        service.callback(begin(), "code", null);
        jdbc.update("update mail_oauth_connection set expires_at=?", Timestamp.from(Instant.now().minusSeconds(60)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.exchange(any(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Refresh was not released");
            return new MailOAuthClient.Tokens("access-2", "", 3600);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.smtpCredentials(company.getId()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var secondStarted = new CountDownLatch(1);
            var second = executor.submit(() -> { secondStarted.countDown(); return service.smtpCredentials(company.getId()); });
            try { assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).orElseThrow().accessToken()).isEqualTo("access-2");
            assertThat(second.get(10, TimeUnit.SECONDS).orElseThrow().accessToken()).isEqualTo("access-2");
        } finally { release.countDown(); }
        verify(client, times(2)).exchange(any(), any()); // Initial authorization plus one refresh.
        assertThat(cipher.decrypt(jdbc.queryForObject("select refresh_token from mail_oauth_connection", String.class), company.getId()))
                .isEqualTo("refresh-1");
    }

    @Test void failedRefreshRetainsConnectionButCannotReturnExpiredCredentialsOrSmtpFallback() {
        service.callback(begin(), "code", null);
        jdbc.update("update mail_oauth_connection set expires_at=?", Timestamp.from(Instant.now().minusSeconds(60)));
        String before = jdbc.queryForObject("select access_token from mail_oauth_connection", String.class);
        when(client.exchange(any(), any())).thenThrow(MailOAuthClient.unavailable());
        assertThatThrownBy(() -> service.smtpCredentials(company.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select access_token from mail_oauth_connection", String.class)).isEqualTo(before);
        assertThat(service.status().connection()).isNotNull();
    }

    @Test void missingRefreshTokenNeverCreatesConnectionAndConsumesCallback() {
        String state = begin();
        when(client.exchange(any(), any())).thenReturn(new MailOAuthClient.Tokens("access", "", 3600));
        assertThatThrownBy(() -> service.callback(state, "code", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.callback(state, "code", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.status().connection()).isNull();
        verify(client, never()).verifyIdentity(any(), anyString(), anyString());
    }

    @Test void companyCannotReadOrDisconnectAnotherCompanyConnection() {
        UUID originalCompany = company.getId();
        service.callback(begin(), "code", null);
        var other = new Company("B00000001", "Other", Map.of("linea1","Test","ciudad","Madrid","provincia","Madrid","codigoPostal","28001","pais","ES"));
        jdbc.update("insert into empresa(id) values(?)", other.getId());
        when(organization.currentCompany()).thenReturn(other);
        assertThat(service.status().connection()).isNull();
        assertThat(service.smtpCredentials(other.getId())).isEmpty();
        service.disconnect();
        assertThat(service.smtpCredentials(originalCompany)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from mail_oauth_connection where empresa_id=?", Integer.class, originalCompany)).isEqualTo(1);
    }

    @Test void rotatedRefreshTokenSurvivesFailureAndRollbackInCallingTransaction() {
        service.callback(begin(), "code", null);
        jdbc.update("update mail_oauth_connection set expires_at=?", Timestamp.from(Instant.now().minusSeconds(60)));
        when(client.exchange(any(), any())).thenReturn(new MailOAuthClient.Tokens("access-rotated", "refresh-rotated", 3600));
        var caller = new org.springframework.transaction.support.TransactionTemplate(
                new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        assertThatThrownBy(() -> caller.executeWithoutResult(tx -> {
            assertThat(service.smtpCredentials(company.getId()).orElseThrow().accessToken()).isEqualTo("access-rotated");
            throw new IllegalStateException("Simulated failure after provider rotated refresh token");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(cipher.decrypt(jdbc.queryForObject("select refresh_token from mail_oauth_connection", String.class), company.getId()))
                .isEqualTo("refresh-rotated");
        assertThat(service.smtpCredentials(company.getId()).orElseThrow().accessToken()).isEqualTo("access-rotated");
        verify(client, times(2)).exchange(any(), any());
    }

    @Test void verifiedOauthReplacesObsoleteSmtpPasswordButFailedConsentPreservesIt() {
        var settings = new com.tpverp.backend.party.MemberSmtpSettings(company.getId(),
                new com.tpverp.backend.party.MemberSmtpSettingsCommand(true,"smtp.example.org",587,"old-user",
                        "legacy-password","old@example.org","Store",true,false));
        when(smtp.findById(company.getId())).thenReturn(Optional.of(settings));
        doThrow(MailOAuthClient.unavailable()).when(client).verifyIdentity(any(), anyString(), anyString());
        assertThatThrownBy(() -> service.callback(begin(), "bad-consent", null)).isInstanceOf(IllegalStateException.class);
        assertThat(settings.getPassword()).isEqualTo("legacy-password");
        verify(smtp, never()).save(any());
        doNothing().when(client).verifyIdentity(any(), anyString(), anyString());
        service.callback(begin(), "valid-consent", null);
        verify(smtp).save(settings);
        assertThat(settings.getPassword()).isNull();
        assertThat(settings.getUsername()).isEqualTo("store@example.com");
        assertThat(service.smtpCredentials(company.getId())).isPresent();
        service.disconnect();
        assertThat(settings.isEnabled()).isFalse();
        assertThat(settings.getPassword()).isNull();
    }
}
