package com.tpverp.saas.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.IntegrationSecretCipher;
import com.tpverp.saas.admin.SaasSessionTokenStore;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties={"spring.flyway.default-schema=saas_mail_oauth_test",
        "spring.datasource.hikari.schema=saas_mail_oauth_test","spring.jpa.properties.hibernate.default_schema=saas_mail_oauth_test",
        "tpv.saas.decision-email.poll-delay=3600000","tpv.saas.mail.oauth.google.client-id=mock-client",
        "tpv.saas.mail.oauth.google.client-secret=mock-secret",
        "tpv.saas.mail.oauth.redirect-uri=https://mail.example.test/api/v1/mail-oauth/callback"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MailOAuthPostgreSqlTest {
    private static final String OAUTH = "/api/v1/admin/mail-oauth";
    private static final String CALLBACK = "/api/v1/mail-oauth/callback";
    private static final String SMTP = "/api/v1/admin/smtp-settings";
    private static final String EMAIL = "sender@example.test";
    private static final String PORTAL = "https://panel.example.test/client";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired IntegrationSecretCipher cipher;
    @Autowired SmtpSettings settings;
    @Autowired ConfigurableEnvironment environment;
    @MockitoBean MailOAuthClient client;
    String staff;

    @BeforeEach void setup() {
        assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo("saas_mail_oauth_test");
        jdbc.update("delete from saas_mail_oauth_flow");
        jdbc.update("delete from saas_mail_oauth_connection");
        jdbc.update("update saas_communication_pacing set last_test_at=null");
        jdbc.update("delete from saas_smtp_settings");
        jdbc.update("insert into saas_smtp_settings(singleton) values(true)");
        jdbc.update("update saas_admin_user set active=true where username='viewer'");
        staff = "Bearer " + sessions.issue("admin","viewer").token();
        when(client.exchange(eq(MailOAuthProvider.GOOGLE),anyMap())).thenReturn(
                new MailOAuthClient.Tokens("mock-access","mock-refresh",3600));
    }

    @Test void staffOnlyRegistrationValidationAndPublicErrorPrivacy() throws Exception {
        String tenant = "Bearer " + sessions.issue("tenant","viewer").token();
        mvc.perform(get(OAUTH+"/status").header("Authorization",tenant)).andExpect(status().isUnauthorized());
        mvc.perform(post(OAUTH+"/connect").header("Authorization",tenant)).andExpect(status().isUnauthorized());
        mvc.perform(delete(OAUTH+"/connection").param("version","0").header("Authorization",tenant)).andExpect(status().isUnauthorized());
        mvc.perform(get(OAUTH+"/status").header("Authorization",staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].available").value(true))
                .andExpect(jsonPath("$.providers[1].available").value(false));
        postConnect("MICROSOFT",0,true,PORTAL).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAIL_OAUTH_UNAVAILABLE"));
        postConnect("GOOGLE",0,true,"").andExpect(status().isBadRequest());
        postConnect("GOOGLE",0,false,"javascript:alert(1)").andExpect(status().isBadRequest());
        String state = start(0,false,"");
        var response = mvc.perform(get(CALLBACK).param("state",state).param("error","sensitive-provider-text")
                        .header("Accept-Language","zh-CN").locale(java.util.Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Language","zh"))
                .andExpect(header().string("Referrer-Policy","no-referrer")).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("无法连接邮箱").doesNotContain(state,"sensitive-provider-text");
        finish(state).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void callbackCommitsSnapshotAndAuditWithoutSecretsAndDisconnectPreventsFallback() throws Exception {
        jdbc.update("""
                update saas_smtp_settings set enabled=true,host='relay.example.test',username='user',encrypted_password=?,
                from_address='old@example.test',portal_url=? where singleton
                """, cipher.encryptExact("old-password"),PORTAL);
        String state = start(0,true,PORTAL);
        assertThat(settings.get().host()).isEqualTo("relay.example.test");
        assertThat(settings.get().configured()).isTrue();
        String encryptedVerifier = jdbc.queryForObject("select verifier from saas_mail_oauth_flow",String.class);
        assertThat(encryptedVerifier).startsWith("v1:");
        assertThat(cipher.decrypt(encryptedVerifier)).hasSize(43);
        assertThat(jdbc.queryForObject("select state_hash from saas_mail_oauth_flow",String.class))
                .isEqualTo(MailOAuthService.hash(state));
        finish(state).andExpect(status().isOk());
        finish(state).andExpect(status().isBadRequest());
        verify(client,times(1)).verifyIdentity(MailOAuthProvider.GOOGLE,EMAIL,"mock-access");
        var view = settings.get();
        assertThat(view.enabled() && view.configured()).isTrue();
        assertThat(view.oauthProvider()).isEqualTo(MailOAuthProvider.GOOGLE);
        assertThat(view.hasPassword()).isFalse();
        assertThat(view.host()).isEqualTo("smtp.gmail.com");
        assertThat(view.username()).isEqualTo(EMAIL);
        assertThat(view.version()).isEqualTo(1);
        assertThat(audit("CONNECT_MAIL_OAUTH")).isEqualTo("viewer");
        var payload = mvc.perform(get(OAUTH+"/status").header("Authorization",staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.connection.email").value(EMAIL)).andReturn().getResponse().getContentAsString();
        assertThat(payload).doesNotContain("mock-access","mock-refresh","mock-secret","v1:");
        assertThat(jdbc.queryForObject("select access_token from saas_mail_oauth_connection",String.class)).startsWith("v1:");
        mvc.perform(put(SMTP).header("Authorization",staff).contentType("application/json").content(json.writeValueAsBytes(
                Map.of("version",1,"enabled",true,"host","relay.example.test","port",587,"security","STARTTLS",
                        "username",EMAIL,"fromAddress",EMAIL,"portalUrl",PORTAL))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MAIL_OAUTH_DISCONNECT_REQUIRED"));
        disconnect(0).andExpect(status().isConflict());
        disconnect(1).andExpect(status().isNoContent());
        assertThat(settings.get().enabled()).isFalse();
        assertThat(settings.get().configured()).isFalse();
        assertThat(settings.get().version()).isEqualTo(2);
        assertThat(audit("DISCONNECT_MAIL_OAUTH")).isEqualTo("viewer");
        assertThat(jdbc.queryForObject("select count(*) from saas_mail_oauth_connection",Integer.class)).isZero();
    }

    @Test void changedVersionExpiredStateAndDeactivatedStaffCannotInstallCredentials() throws Exception {
        String expired = start(0,true,PORTAL);
        jdbc.update("update saas_mail_oauth_flow set expires_at=now()-interval '1 second'");
        finish(expired).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
        String stale = start(0,true,PORTAL);
        jdbc.update("update saas_smtp_settings set version=1");
        finish(stale).andExpect(status().isBadRequest());
        String deactivated = start(1,true,PORTAL);
        jdbc.update("update saas_admin_user set active=false where username='viewer'");
        finish(deactivated).andExpect(status().isBadRequest());
        assertThat(settings.get().oauthProvider()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from saas_mail_oauth_connection",Integer.class)).isZero();
    }

    @Test void overlappingCallbackAndReplacementOnlyInstallTheLatestAuthorization() throws Exception {
        String first = start(0,true,PORTAL);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return null; })
                .when(client).verifyIdentity(MailOAuthProvider.GOOGLE,EMAIL,"mock-access");
        try (var pool = Executors.newSingleThreadExecutor()) {
            var older = pool.submit(() -> finish(first).andReturn().getResponse().getStatus());
            String second;
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                finish(first).andExpect(status().isBadRequest());
                second = start(0,false,"");
            } finally { release.countDown(); }
            assertThat(older.get(5,TimeUnit.SECONDS)).isEqualTo(400);
            finish(second).andExpect(status().isOk());
        }
        assertThat(settings.get().enabled()).isFalse();
        assertThat(settings.get().configured()).isFalse();
        assertThat(settings.get().oauthProvider()).isEqualTo(MailOAuthProvider.GOOGLE);
        mvc.perform(put(SMTP).header("Authorization",staff).contentType("application/json").content(json.writeValueAsBytes(
                Map.of("version",1,"enabled",true,"host","smtp.gmail.com","port",587,"security","STARTTLS",
                        "username",EMAIL,"fromAddress",EMAIL,"portalUrl",PORTAL))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true));
    }

    @Test void rotatedTokenSurvivesFailedTestTransactionAndKeepsStableMime() throws Exception {
        finish(start(0,true,PORTAL)).andExpect(status().isOk());
        jdbc.update("update saas_mail_oauth_connection set expires_at=now()");
        when(client.exchange(eq(MailOAuthProvider.GOOGLE),argThat(form -> "mock-refresh".equals(form.get("refresh_token")))))
                .thenReturn(new MailOAuthClient.Tokens("new-access","rotated-refresh",3600));
        doAnswer(call -> {
            MimeMessage mime = call.getArgument(0);
            assertThat(mime.getMessageID()).endsWith("@saas.espos>");
            assertThat(mime.getContentType()).contains("multipart/alternative");
            throw new MailSendException("private provider diagnostic");
        }).when(client).sendGoogle(any(),eq("new-access"));
        mvc.perform(post(SMTP+"/test").header("Authorization",staff).contentType("application/json")
                .content("{\"recipient\":\"recipient@example.test\"}")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SMTP_DELIVERY_UNCERTAIN"));
        assertThat(cipher.decrypt(jdbc.queryForObject("select refresh_token from saas_mail_oauth_connection",String.class)))
                .isEqualTo("rotated-refresh");
        verify(client).sendGoogle(any(),eq("new-access"));
    }

    @Test void microsoftVerifiesMailboxThenUsesOnlyItsAuthenticatedSubmissionRoute() throws Exception {
        var registration = new MockPropertySource("mockMicrosoftRegistration")
                .withProperty("tpv.saas.mail.oauth.microsoft.client-id","mock-ms-client")
                .withProperty("tpv.saas.mail.oauth.microsoft.client-secret","mock-ms-secret");
        environment.getPropertySources().addFirst(registration);
        try {
            when(client.exchange(eq(MailOAuthProvider.MICROSOFT),anyMap())).thenReturn(
                    new MailOAuthClient.Tokens("ms-access","ms-refresh",3600));
            var started = postConnect("MICROSOFT",0,true,PORTAL).andExpect(status().isOk()).andReturn().getResponse();
            URI uri = URI.create(json.readTree(started.getContentAsString()).get("authorizationUrl").asText());
            assertThat(uri.getHost()).isEqualTo("login.microsoftonline.com");
            assertThat(uri.getRawQuery()).contains("offline_access","SMTP.Send");
            finish(UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("state")).andExpect(status().isOk());
            verify(client).verifyIdentity(MailOAuthProvider.MICROSOFT,EMAIL,"ms-access");
            assertThat(settings.get().host()).isEqualTo("smtp.office365.com");
            try (var transports = mockConstruction(DecisionSmtpConnection.class)) {
                mvc.perform(post(SMTP+"/test").header("Authorization",staff).contentType("application/json")
                        .content("{\"recipient\":\"recipient@example.test\"}")).andExpect(status().isNoContent());
                var smtp = transports.constructed().getFirst();
                var commands = inOrder(smtp);
                commands.verify(smtp).connectMicrosoft(EMAIL,"ms-access");
                commands.verify(smtp).deliver(any(MimeMessage.class));
                commands.verify(smtp).closeQuietly();
                verify(client,never()).sendGoogle(any(),any());
            }
        } finally { environment.getPropertySources().remove(registration.getName()); }
    }

    private String audit(String action) {
        return jdbc.queryForObject("""
                select username from saas_admin_audit_log where action=? and origin='SAAS_ADMIN'
                order by created_at desc,id desc limit 1
                """,String.class,action);
    }
    @Test void separateAccountsCanAuthorizeSimultaneouslyAndDisconnectIndependently() throws Exception {
        var one = settings.create(new SmtpSettings.Update(false,"",587,SmtpSettings.Security.STARTTLS,"",null,false,
                "one@example.test","One","","",0L));
        var two = settings.create(new SmtpSettings.Update(false,"",587,SmtpSettings.Security.STARTTLS,"",null,false,
                "two@example.test","Two","","",0L));
        String stateOne = startAccount(one);
        String stateTwo = startAccount(two);
        finish(stateTwo).andExpect(status().isOk());
        finish(stateOne).andExpect(status().isOk());
        assertThat(settings.get(one.id()).testReady()).isTrue();
        mvc.perform(delete(OAUTH+"/connection").param("accountId",two.id().toString())
                .param("version",Long.toString(settings.get(two.id()).version())).header("Authorization",staff))
                .andExpect(status().isNoContent());
        assertThat(settings.get(two.id()).testReady()).isFalse();
        mvc.perform(get(OAUTH+"/status").param("accountId",one.id().toString()).header("Authorization",staff))
                .andExpect(jsonPath("$.connection.email").value(one.fromAddress()));
        assertThat(jdbc.queryForObject("select account_id from saas_mail_oauth_connection",java.util.UUID.class)).isEqualTo(one.id());
        verify(client).verifyIdentity(MailOAuthProvider.GOOGLE,one.fromAddress(),"mock-access");
        verify(client).verifyIdentity(MailOAuthProvider.GOOGLE,two.fromAddress(),"mock-access");
    }

    private String startAccount(SmtpSettings.View account) throws Exception {
        var input = new MailOAuthService.Connect(MailOAuthProvider.GOOGLE,account.fromAddress(),account.version(),false,
                account.fromName(),"","",account.id());
        var response = mvc.perform(post(OAUTH+"/connect").header("Authorization",staff).contentType("application/json")
                .content(json.writeValueAsBytes(input))).andExpect(status().isOk()).andReturn().getResponse();
        return UriComponentsBuilder.fromUri(URI.create(json.readTree(response.getContentAsString()).get("authorizationUrl").asText()))
                .build().getQueryParams().getFirst("state");
    }

    private ResultActions postConnect(String provider,int version,boolean enabled,String portal) throws Exception {
        return mvc.perform(post(OAUTH+"/connect").header("Authorization",staff).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("provider",provider,"version",version,"enabled",enabled,
                        "portalUrl",portal,"email",EMAIL,"replyTo","","fromName","esPOS"))));
    }
    private String start(int version,boolean enabled,String portal) throws Exception {
        var response = postConnect("GOOGLE",version,enabled,portal).andExpect(status().isOk()).andReturn().getResponse();
        URI uri = URI.create(json.readTree(response.getContentAsString()).get("authorizationUrl").asText());
        assertThat(uri.getHost()).isEqualTo("accounts.google.com");
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("code_challenge")).hasSize(43);
        return query.getFirst("state");
    }
    private ResultActions finish(String state) throws Exception {
        return mvc.perform(get(CALLBACK).param("state",state).param("code","mock-code"));
    }
    private ResultActions disconnect(int version) throws Exception {
        return mvc.perform(delete(OAUTH+"/connection").header("Authorization",staff).param("version",Integer.toString(version)));
    }
}
