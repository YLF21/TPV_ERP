package com.tpverp.saas.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.IntegrationSecretCipher;
import com.tpverp.saas.admin.SaasSessionTokenStore;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMultipart;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties={"spring.flyway.default-schema=smtp_decision_test",
        "spring.datasource.hikari.schema=smtp_decision_test","spring.jpa.properties.hibernate.default_schema=smtp_decision_test",
        "tpv.saas.decision-email.poll-delay=3600000"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SmtpDecisionPostgreSqlTest {
    private static final String SMTP = "/api/v1/admin/smtp-settings";
    private static final String REQUESTS = "/api/v1/admin/client-access-requests/";
    private static final String SECRET = " synthetic mail password ";
    private static final String RECIPIENT = "client@example.test";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired IntegrationSecretCipher cipher;
    @Autowired DecisionEmailDispatcher dispatcher;
    @Autowired SmtpSettings settings;
    @Autowired DataSource dataSource;
    @MockitoBean SmtpTransport smtp;
    String staff;

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo("smtp_decision_test");
        jdbc.update("delete from saas_client_decision_email");
        jdbc.update("update saas_communication_pacing set last_test_at=null");
        jdbc.update("delete from saas_smtp_settings");
        jdbc.update("insert into saas_smtp_settings(singleton) values(true)");
        staff = "Bearer " + sessions.issue("admin","viewer").token();
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void existingGmailAccountAndReviewedQueueSurviveMigration(boolean oauth) throws Exception {
        String schema = "mail_upgrade_" + UUID.randomUUID().toString().replace("-","");
        var migrations = Flyway.configure().configuration(Map.of("flyway.postgresql.transactional.lock","false"))
                .dataSource(dataSource).locations("classpath:db/migration").defaultSchema(schema).schemas(schema);
        migrations.target("90").load().migrate();
        try (var connection = dataSource.getConnection()) {
            connection.setSchema(schema);
            var old = new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            String secret = cipher.encryptExact("synthetic Gmail app password");
            String access = cipher.encryptExact("synthetic access");
            String refresh = cipher.encryptExact("synthetic refresh");
            old.update("""
                    update saas_smtp_settings set host='smtp.gmail.com',username='existing@gmail.com',from_address='existing@gmail.com',
                    encrypted_password=?,oauth_provider=?,version=27,enabled=true,portal_url='https://portal.example.test'
                    """,oauth ? null : secret,oauth ? "GOOGLE" : null);
            if (oauth) old.update("""
                    insert into saas_mail_oauth_connection(singleton,provider,email,access_token,refresh_token,expires_at)
                    values(true,'GOOGLE','existing@gmail.com',?,?,now())
                    """,access,refresh);
            var review = UUID.randomUUID();
            old.update("""
                    insert into saas_communication(id,subject,html,plain_text,audience_mode,recipient_ids,created_by,
                        review_id,reviewed_count,queued_at,queued_version,smtp_version,from_address)
                    values(gen_random_uuid(),'Existing','Existing','Existing','ALL','{}','viewer',?,1,now(),2,27,'existing@gmail.com')
                    """,review);
            migrations.target("91").load().migrate();
            var originalId = old.queryForObject("select id from saas_smtp_settings where singleton",UUID.class);
            assertThat(old.queryForMap("select sender_account_id,review_id,smtp_version from saas_communication"))
                    .containsEntry("sender_account_id",originalId).containsEntry("review_id",review).containsEntry("smtp_version",27L);
            assertThat(old.queryForMap("select version,enabled,from_address from saas_smtp_settings where id=?",originalId))
                    .containsEntry("version",27L).containsEntry("enabled",true).containsEntry("from_address","existing@gmail.com");
            if (oauth) {
                assertThat(old.queryForMap("select account_id,access_token,refresh_token from saas_mail_oauth_connection"))
                        .containsEntry("account_id",originalId).containsEntry("access_token",access).containsEntry("refresh_token",refresh);
            } else {
                assertThat(old.queryForObject("select encrypted_password from saas_smtp_settings",String.class)).isEqualTo(secret);
            }
        }
    }

    @Test
    void settingsAreStaffOnlyWriteOnlyVersionedAndGuardExistingPasswords() throws Exception {
        String tenant = "Bearer " + sessions.issue("tenant","viewer").token();
        for (String url : List.of(SMTP, REQUESTS + UUID.randomUUID())) {
            mvc.perform(get(url).header("Authorization",tenant)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(SMTP+"/test").header("Authorization",tenant)).andExpect(status().isUnauthorized());
        mvc.perform(put(SMTP).header("Authorization",tenant)).andExpect(status().isUnauthorized());
        mvc.perform(post(REQUESTS+UUID.randomUUID()+"/retry-email").header("Authorization",tenant)).andExpect(status().isUnauthorized());
        mvc.perform(get(SMTP).header("Authorization",staff)).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.configured").value(false)).andExpect(jsonPath("$.testReady").value(false))
                .andExpect(jsonPath("$.hasPassword").value(false));
        var config = config();
        var response = save(config).andExpect(status().isOk()).andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.version").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SECRET,"encryptedPassword","\"password\"");
        String encrypted = jdbc.queryForObject("select encrypted_password from saas_smtp_settings",String.class);
        assertThat(encrypted).startsWith("v1:").doesNotContain(SECRET);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(SECRET);
        save(config).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_VERSION_CONFLICT"));
        config.remove("password");
        config.put("version",1);
        config.put("fromName","Soporte esPOS");
        save(config).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true));
        assertThat(jdbc.queryForObject("select encrypted_password from saas_smtp_settings",String.class)).isEqualTo(encrypted);
        config.put("version",2);
        config.put("host","different.example.test");
        save(config).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_PASSWORD_REQUIRED"));
        config.put("username","");
        config.put("clearPassword",true);
        config.put("portalUrl","javascript:alert(1)");
        save(config).andExpect(status().isBadRequest());
        config.put("portalUrl","https://portal.example.test/cliente");
        config.put("fromName","nombre\r\nBcc: injection@example.test");
        save(config).andExpect(status().isBadRequest());
        config.put("fromName","esPOS");
        save(config).andExpect(status().isOk()).andExpect(jsonPath("$.hasPassword").value(false));
        verifyNoInteractions(smtp);
    }

    @Test
    void explicitTestUsesSavedConfigAndGlobalCooldownEvenOnFailure() throws Exception {
        testMail().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_NOT_READY"));
        save(config()).andExpect(status().isOk());
        doThrow(new SmtpTransport.Failure("SMTP_AUTH_FAILED")).when(smtp).sendTest(any(),any(),eq(RECIPIENT),any());
        testMail().andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("SMTP_AUTH_FAILED"));
        testMail().andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","30"));
        jdbc.update("update saas_communication_pacing set last_test_at=now()-interval '1 minute'");
        doNothing().when(smtp).sendTest(any(),any(),eq(RECIPIENT),any());
        testMail().andExpect(status().isNoContent());
        verify(smtp,times(2)).sendTest(any(),any(),eq(RECIPIENT),any());
    }

    @Test
    void gmailTestWithoutPortalOrAutomaticNoticesDoesNotTouchPendingDecisions() throws Exception {
        UUID id = request("es");
        postJson(REQUESTS+id+"/reject",Map.of("version",0,"reason","Fixture"))
                .andExpect(status().isOk());
        var config = config();
        config.put("enabled",false);
        config.put("host","smtp.gmail.com");
        config.put("username","sender@gmail.com");
        config.put("fromAddress","sender@gmail.com");
        config.put("portalUrl","");
        save(config).andExpect(status().isOk()).andExpect(jsonPath("$.testReady").value(true))
                .andExpect(jsonPath("$.configured").value(false));
        long auditBefore = jdbc.queryForObject("select count(*) from saas_admin_audit_log where action='TEST_SMTP_SETTINGS'",Long.class);
        testMail().andExpect(status().isNoContent());
        var sent = ArgumentCaptor.forClass(SmtpSettings.Saved.class);
        var content = ArgumentCaptor.forClass(ClientDecisionEmail.Content.class);
        verify(smtp).sendTest(sent.capture(),any(),eq(RECIPIENT),content.capture());
        assertThat(sent.getValue().view().host()).isEqualTo("smtp.gmail.com");
        assertThat(content.getValue().text()).contains("prueba").doesNotContain("http", "Fixture");
        assertThat(content.getValue().html()).doesNotContain("href", "Fixture");
        assertThat(settings.get().enabled()).isFalse();
        assertThat(settings.get().portalUrl()).isEmpty();
        assertThat(settings.get().version()).isEqualTo(1);
        assertThat(dispatcher.dispatchOne()).isFalse();
        mvc.perform(get(REQUESTS+id).header("Authorization",staff))
                .andExpect(jsonPath("$.delivery.status").value("PENDING"))
                .andExpect(jsonPath("$.delivery.attempts").value(0));
        verify(smtp,never()).send(any(),any(),any(),any());
        assertThat(jdbc.queryForObject("select count(*) from saas_admin_audit_log where action='TEST_SMTP_SETTINGS'",Long.class))
                .isEqualTo(auditBefore+1);
        testMail().andExpect(status().isTooManyRequests());
        config.remove("password");
        config.put("version",1);
        config.put("enabled",true);
        save(config).andExpect(status().isBadRequest());
        assertThat(settings.get().enabled()).isFalse();
        config.put("portalUrl","http://localhost:8088/tienda.html");
        save(config).andExpect(status().isBadRequest());
    }

    @Test
    void testsStillRequireSavedReadableCredentialsAndSender() throws Exception {
        var config = config();
        config.put("enabled",false);
        config.put("portalUrl","");
        config.remove("password");
        save(config).andExpect(status().isOk()).andExpect(jsonPath("$.testReady").value(false));
        testMail().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_NOT_READY"));
        config.put("version",1);
        config.put("password",SECRET);
        config.put("fromAddress","");
        save(config).andExpect(status().isOk()).andExpect(jsonPath("$.testReady").value(false));
        testMail().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_NOT_READY"));
        config.put("version",2);
        config.put("fromAddress","sender@example.test");
        save(config).andExpect(status().isOk()).andExpect(jsonPath("$.testReady").value(true));
        jdbc.update("update saas_smtp_settings set encrypted_password='v1:unreadable'");
        assertThat(settings.get().testReady()).isFalse();
        testMail().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_NOT_READY"));
        verifyNoInteractions(smtp);
    }

    @Test
    void disabledOrUnreadableSettingsPreserveQueueAndSmtpFailureCannotChangeDecision() throws Exception {
        UUID id = request("en");
        String url = REQUESTS + id;
        postJson(url+"/reject",Map.of("version",0,"reason","Customer reason"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.status").value("PENDING"));
        assertThat(dispatcher.dispatchOne()).isFalse();
        save(config()).andExpect(status().isOk());
        String encrypted = jdbc.queryForObject("select encrypted_password from saas_smtp_settings",String.class);
        jdbc.update("update saas_smtp_settings set encrypted_password='v1:unreadable'");
        assertThat(settings.get().configured()).isFalse();
        assertThat(dispatcher.dispatchOne()).isFalse();
        mvc.perform(get(url).header("Authorization",staff)).andExpect(jsonPath("$.delivery.attempts").value(0));
        jdbc.update("update saas_smtp_settings set encrypted_password=?",encrypted);
        doThrow(new SmtpTransport.Failure("SMTP_DELIVERY_FAILED")).when(smtp).send(any(),eq(id),eq(RECIPIENT),any());
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(dispatcher.dispatchOne()).isFalse();
        mvc.perform(get(url).header("Authorization",staff)).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewReason").value("Customer reason")).andExpect(jsonPath("$.delivery.attempts").value(1))
                .andExpect(jsonPath("$.delivery.lastError").value("SMTP_DELIVERY_FAILED"))
                .andExpect(jsonPath("$.delivery.nextAttemptAt").isNotEmpty());
        for (int i=1;i<5;i++) {
            jdbc.update("update saas_client_decision_email set next_attempt_at=now() where request_id=?",id);
            assertThat(dispatcher.dispatchOne()).isTrue();
        }
        mvc.perform(get(url).header("Authorization",staff)).andExpect(jsonPath("$.delivery.status").value("FAILED"));
        doNothing().when(smtp).send(any(),eq(id),eq(RECIPIENT),any());
        mvc.perform(post(url+"/retry-email").header("Authorization",staff))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.attempts").value(0));
        assertThat(dispatcher.dispatchOne()).isTrue();
        var message = ArgumentCaptor.forClass(ClientDecisionEmail.Content.class);
        verify(smtp,times(6)).send(any(),eq(id),eq(RECIPIENT),message.capture());
        assertThat(message.getValue().text()).contains("Customer reason");
        mvc.perform(get(url).header("Authorization",staff)).andExpect(jsonPath("$.delivery.status").value("DELIVERED"))
                .andExpect(jsonPath("$.delivery.deliveredAt").isNotEmpty()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(url+"/retry-email").header("Authorization",staff)).andExpect(status().isConflict());
        assertThat(dispatcher.dispatchOne()).isFalse();
    }

    @Test
    void lostProviderConfirmationWaitsForManualRetry() throws Exception {
        UUID id = request("es");
        postJson(REQUESTS+id+"/reject",Map.of("version",0,"reason","Fixture")).andExpect(status().isOk());
        save(config()).andExpect(status().isOk());
        doThrow(new SmtpTransport.Failure("SMTP_DELIVERY_UNCERTAIN")).when(smtp).send(any(),eq(id),eq(RECIPIENT),any());
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(dispatcher.dispatchOne()).isFalse();
        mvc.perform(get(REQUESTS+id).header("Authorization",staff)).andExpect(jsonPath("$.delivery.status").value("FAILED"))
                .andExpect(jsonPath("$.delivery.attempts").value(1)).andExpect(jsonPath("$.delivery.nextAttemptAt").isEmpty())
                .andExpect(jsonPath("$.delivery.lastError").value("SMTP_DELIVERY_UNCERTAIN"));
    }

    @Test
    void concurrentWorkersSendOnceAndRecoverExpiredClaimWithStableMessageId() throws Exception {
        UUID id = request("zh");
        postJson(REQUESTS+id+"/approve",Map.of("version",0,"grants",List.of())).andExpect(status().isOk());
        save(config()).andExpect(status().isOk());
        jdbc.update("""
                update saas_client_decision_email set status='PROCESSING',claim_token=?,claimed_at=now()-interval '3 minutes'
                where request_id=?
                """,UUID.randomUUID(),id);
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { sending.countDown(); release.await(8,TimeUnit.SECONDS); return null; })
                .when(smtp).send(any(),eq(id),eq(RECIPIENT),any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(dispatcher::dispatchOne);
            try {
                assertThat(sending.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(dispatcher.dispatchOne()).isFalse();
            } finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
        var content = ArgumentCaptor.forClass(ClientDecisionEmail.Content.class);
        verify(smtp).send(any(),eq(id),eq(RECIPIENT),content.capture());
        var view = settings.get();
        var props = SmtpTransport.properties(view);
        assertThat(props).containsEntry("mail.smtp.starttls.required","true").containsEntry("mail.smtp.ssl.checkserveridentity","true");
        var session = Session.getInstance(props);
        var message = SmtpTransport.message(session,view,id,RECIPIENT,content.getValue());
        assertThat(message.getMessageID()).isEqualTo(SmtpTransport.message(session,view,id,RECIPIENT,content.getValue()).getMessageID());
        assertThat(message.getRecipients(jakarta.mail.Message.RecipientType.TO)).hasSize(1);
        assertThat(message.getContentType()).contains("multipart/alternative");
        var parts = (MimeMultipart)message.getContent();
        assertThat(parts.getBodyPart(0).getContentType()).contains("text/plain");
        assertThat(parts.getBodyPart(1).getContent().toString()).contains("lang=\"zh").doesNotContain(SECRET);
    }

    private UUID request(String locale) throws Exception {
        UUID submission = UUID.randomUUID();
        var body = new HashMap<String,Object>();
        body.putAll(Map.of("submissionId",submission,"username","client-"+submission,"password","Example-client-12",
                "fullName","Client","taxId","test","email",RECIPIENT,"phone","123","address","Test street","postalCode","0"));
        body.putAll(Map.of("city","Test city","province","Test province","country","ES","requestedStores",List.of(),"locale",locale));
        mvc.perform(post("/api/v1/public/client-access-requests").contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isAccepted());
        return jdbc.queryForObject("select id from saas_client_access_request where submission_id=?",UUID.class,submission);
    }
    private Map<String,Object> config() {
        var config = new HashMap<String,Object>();
        config.putAll(Map.of("enabled",true,"host","smtp.example.test","port",587,"security","STARTTLS",
                "username","operator","password",SECRET,"fromAddress","sender@example.test","fromName","esPOS",
                "portalUrl","https://portal.example.test/cliente","version",0));
        return config;
    }
    private ResultActions testMail() throws Exception { return postJson(SMTP+"/test",Map.of("recipient",RECIPIENT)); }
    private ResultActions postJson(String path,Object body) throws Exception {
        return mvc.perform(post(path).header("Authorization",staff).contentType("application/json").content(json.writeValueAsBytes(body)));
    }
    private ResultActions save(Map<String,Object> config) throws Exception {
        return mvc.perform(put(SMTP).header("Authorization",staff).contentType("application/json").content(json.writeValueAsBytes(config)));
    }
}
