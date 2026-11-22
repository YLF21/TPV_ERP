package com.tpverp.saas.mail;

import static com.tpverp.saas.mail.Communications.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.SaasSessionTokenStore;
import jakarta.mail.Message;
import jakarta.mail.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties={"spring.flyway.default-schema=communication_test","spring.datasource.hikari.schema=communication_test",
        "spring.jpa.properties.hibernate.default_schema=communication_test","tpv.saas.communications.poll-delay=3600000",
        "tpv.saas.decision-email.poll-delay=3600000"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CommunicationPostgreSqlTest {
    private static final String BASE = "/api/v1/admin/communications";
    private static final String BODY = "<h2>Hola</h2><p><strong>Información</strong> <a href='https://example.test/info'>Leer</a></p>";
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired CommunicationService service;
    @Autowired CommunicationDispatcher dispatcher;
    @Autowired CommunicationStore store;
    @Autowired CommunicationAudience audience;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SaasSessionTokenStore sessions;
    @MockitoBean SmtpTransport smtp;
    @MockitoSpyBean SmtpSettings settings;
    String staff;

    @BeforeEach void setup() {
        assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo("communication_test");
        jdbc.update("delete from saas_communication");
        jdbc.update("delete from saas_smtp_settings where singleton is null");
        jdbc.update("delete from saas_client_access_request where username like 'comm-%'");
        jdbc.update("delete from saas_tenant_company_access where user_id in (select id from saas_tenant_user where username like 'comm-%')");
        jdbc.update("delete from saas_tenant_user where username like 'comm-%'");
        jdbc.update("delete from saas_company_operations where company_id in (select id from saas_company where tax_id like 'COMM-MAIL-%')");
        jdbc.update("delete from saas_company where tax_id like 'COMM-MAIL-%'");
        jdbc.update("update saas_communication_pacing set next_claim_at='-infinity',last_test_at=null");
        jdbc.update("""
                update saas_smtp_settings set enabled=false,portal_url='',host='smtp.example.test',username='',
                    encrypted_password=null,from_address='sender@example.test',oauth_provider=null,version=0
                """);
        staff = "Bearer " + sessions.issue("admin","viewer").token();
    }

    @Test void allRoutesAreStaffOnlyAndPreviewUsesAStrictAllowlist() throws Exception {
        String tenant = "Bearer " + sessions.issue("tenant","viewer").token();
        for (String suffix : List.of("","/audience","/"+UUID.randomUUID(),"/"+UUID.randomUUID()+"/recipients"))
            mvc.perform(get(BASE+suffix).header("Authorization",tenant)).andExpect(status().isUnauthorized());
        String path = "/"+UUID.randomUUID();
        for (String suffix : List.of("","/preview",path+"/review",path+"/send",path+"/cancel",path+"/test",
                path+"/recipients/"+UUID.randomUUID()+"/retry"))
            mvc.perform(post(BASE+suffix).header("Authorization",tenant)).andExpect(status().isUnauthorized());
        mvc.perform(put(BASE+path).header("Authorization",tenant)).andExpect(status().isUnauthorized());
        String unsafe = BODY+"<script>hidden()</script><img src='https://example.test/image'>"
                +"<p onclick='handler()' style='position:fixed'>Texto</p><a href='javascript:bad()'>Otro</a>"
                +"<form><input name='p'></form><iframe src='https://example.test/frame'></iframe>";
        String safe = postJson("/preview",Map.of("html",unsafe)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(safe).contains("https://example.test/info","<strong>").doesNotContain("hidden()","onclick","style=","javascript:",
                "<form","<input","<img","<iframe","example.test/frame");
        var draftResponse = postJson("",new Draft("Asunto",unsafe,Mode.SELECTED,List.of(),null,settings.get().id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.counts.pending").value(0)).andReturn().getResponse().getContentAsString();
        var draft = json.readTree(draftResponse);
        assertThat(draft.get("html").asText()).isEqualTo(json.readTree(safe).get("html").asText());
        String id = draft.get("id").asText();
        var edit = new Draft("Editado",BODY,Mode.SELECTED,List.of(),0L,settings.get().id());
        mvc.perform(put(BASE+"/"+id).header("Authorization",staff).contentType("application/json").content(json.writeValueAsBytes(edit)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(BASE+"/"+id).header("Authorization",staff).contentType("application/json").content(json.writeValueAsBytes(edit)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMUNICATION_VERSION_CONFLICT"));
        mvc.perform(get(BASE).header("Authorization",staff)).andExpect(jsonPath("$.items[0].subject").value("Editado"));
        postJson("", new Draft("bad\r\nsubject",BODY,Mode.ALL,List.of(),null,settings.get().id())).andExpect(status().isBadRequest());
        verifyNoInteractions(smtp);
    }

    @Test void reviewsIncludeActiveAccountsWithoutStoresAndFreezeDeduplicatedAddresses() throws Exception {
        UUID first = account("first","same@example.test",true);
        account("second","SAME@example.test",true);
        account("inactive","inactive@example.test",false);
        account("legacy",null,true);
        account("invalid","invalid-address",true);
        var directory = audience.page(0,100,"comm-");
        assertThat(directory.items()).extracting(AudienceEntry::reason).contains("INACTIVE","MISSING_EMAIL","INVALID_EMAIL");
        var id = newDraft(Mode.ALL,List.of());
        var review = service.review(id,0);
        assertThat(review.recipientCount()).isEqualTo(1);
        assertThat(review.duplicateCount()).isEqualTo(1);
        assertThat(review.omittedCount()).isGreaterThanOrEqualTo(3);
        assertThat(review.smtpReady()).isTrue();
        assertThat(review.fromAddress()).isEqualTo("sender@example.test");
        var recipient = store.recipients(id,review.reviewId(),0,1).items().getFirst();
        assertThat(recipient.status()).isEqualTo("REVIEW");
        account("later","later@example.test",true);
        var confirm = confirmation(review);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> service.send(id,confirm));
            var two = pool.submit(() -> service.send(id,confirm));
            assertThat(one.get(5,TimeUnit.SECONDS).summary().counts().pending()).isEqualTo(1);
            assertThat(two.get(5,TimeUnit.SECONDS).summary().counts().pending()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_admin_audit_log where action='QUEUE_COMMUNICATION' and target_id=?",
                Long.class,id.toString())).isEqualTo(1);
        assertThat(dispatcher.dispatchOne()).isTrue();
        verify(smtp).sendManual(any(),eq(recipient.id()),eq(recipient.email()),any());
        assertThat(dispatcher.dispatchOne()).isFalse();
        assertThat(store.detail(id).summary().counts().sent()).isEqualTo(1);
        assertThat(store.recipients(id,null,0,10).items().getFirst().sentAt()).isNotNull();
        var selected = newDraft(Mode.SELECTED,List.of(first,UUID.randomUUID()));
        assertThat(service.review(selected,0).omittedCount()).isEqualTo(1);
    }

    @Test void changesOfReviewedAddressOrSenderRequireANewReviewAndEligibilityIsCheckedAtDelivery() throws Exception {
        var user = account("move","first@example.test",true);
        var id = newDraft(Mode.SELECTED,List.of(user));
        var review = service.review(id,0);
        jdbc.update("update saas_client_access_request set email='next@example.test' where tenant_user_id=?",user);
        postJson("/"+id+"/send",confirmation(review)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMUNICATION_REVIEW_CHANGED"));
        review = service.review(id,0);
        jdbc.update("update saas_smtp_settings set version=version+1");
        postJson("/"+id+"/send",confirmation(review)).andExpect(status().isConflict());
        service.send(id,confirmation(service.review(id,0)));
        jdbc.update("update saas_tenant_user set active=false where id=?",user);
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(store.recipients(id,null,0,1).items().getFirst().lastError()).isEqualTo("COMMUNICATION_RECIPIENT_UNAVAILABLE");
        verifyNoInteractions(smtp);
    }

    @Test void testMessageUsesReviewedHtmlAndSharedCooldownWithoutChangingTheQueue() throws Exception {
        var id = newDraft(Mode.ALL,List.of());
        var detail = store.detail(id);
        postJson("/"+id+"/test",new TestMail(0L,"operator@example.test")).andExpect(status().isNoContent());
        postJson("/"+id+"/test",new TestMail(0L,"operator@example.test")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SMTP_TEST_RATE_LIMITED"));
        var content = ArgumentCaptor.forClass(ClientDecisionEmail.Content.class);
        verify(smtp).sendTest(any(),any(),eq("operator@example.test"),content.capture());
        assertThat(content.getValue().html()).isEqualTo(detail.html());
        assertThat(content.getValue().text()).contains("Información","https://example.test/info");
        var mime = SmtpTransport.message(Session.getInstance(SmtpTransport.properties(settings.get())),settings.get(),UUID.randomUUID(),
                "operator@example.test",content.getValue());
        assertThat(mime.getAllRecipients()).hasSize(1);
        assertThat(mime.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(store.detail(id).summary().version()).isZero();
        assertThat(store.recipients(id,null,0,1).total()).isZero();
        var audit = jdbc.queryForMap("select username,details from saas_admin_audit_log where action='TEST_COMMUNICATION' and target_id=?",id.toString());
        assertThat(audit).containsEntry("username","viewer").containsEntry("details","accepted");
    }

    @Test void failuresAndAmbiguousDeliveryNeedExplicitGuardedRetry() throws Exception {
        var id = newDraft(Mode.SELECTED,List.of(account("retry","retry@example.test",true)));
        service.send(id,confirmation(service.review(id,0)));
        var entry = store.recipients(id,null,0,1).items().getFirst();
        jdbc.update("update saas_smtp_settings set from_address='other@example.test'");
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(store.recipients(id,null,0,1).items().getFirst().lastError()).isEqualTo("SMTP_SENDER_CHANGED");
        jdbc.update("update saas_smtp_settings set from_address='sender@example.test'");
        service.retry(id,entry.id(),new Retry(1,false));
        // Deterministic rate-limit check regardless of the speed of the test runner.
        jdbc.update("update saas_communication_pacing set next_claim_at=now()+interval '1 minute'");
        assertThat(dispatcher.dispatchOne()).isFalse();
        openSlot();
        doThrow(new SmtpTransport.Failure("SMTP_DELIVERY_UNCERTAIN")).when(smtp).sendManual(any(),any(),any(),any());
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(store.detail(id).summary().counts().uncertain()).isEqualTo(1);
        String path = "/"+id+"/recipients/"+entry.id()+"/retry";
        postJson(path,new Retry(2,false)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMUNICATION_CONFIRM_UNCERTAIN"));
        postJson(path,new Retry(1,true)).andExpect(status().isConflict());
        postJson(path,new Retry(2,true)).andExpect(status().isOk());
        postJson(path,new Retry(2,true)).andExpect(status().isConflict());
        openSlot();
        doNothing().when(smtp).sendManual(any(),any(),any(),any());
        assertThat(dispatcher.dispatchOne()).isTrue();
        verify(smtp,times(2)).sendManual(any(),eq(entry.id()),eq(entry.email()),any());
    }

    @Test void expiredClaimsAfterCrashAreNeverAutomaticallyResent() {
        var id = newDraft(Mode.SELECTED,List.of(account("claim","claim@example.test",true)));
        service.send(id,confirmation(service.review(id,0)));
        jdbc.update("""
                update saas_communication_recipient set status='SENDING',attempts=1,claimed_at=now()-interval '3 minutes'
                where communication_id=?
                """,id);
        assertThat(dispatcher.dispatchOne()).isFalse();
        assertThat(store.detail(id).summary().counts().uncertain()).isEqualTo(1);
        assertThat(dispatcher.dispatchOne()).isFalse();
        verifyNoInteractions(smtp);
    }

    @Test void cancellationDuringSendPreservesAcceptedResultAndCancelsOnlyPending() throws Exception {
        var id = twoQueued();
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { sending.countDown(); assertThat(release.await(8,TimeUnit.SECONDS)).isTrue(); return null; })
                .when(smtp).sendManual(any(),any(),any(),any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var inFlight = pool.submit(dispatcher::dispatchOne);
            try {
                assertThat(sending.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(service.cancel(id,1).summary().counts().cancelled()).isEqualTo(1);
            } finally { release.countDown(); }
            assertThat(inFlight.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(store.detail(id).summary().counts().sent()).isEqualTo(1);
        assertThat(dispatcher.dispatchOne()).isFalse();
        verify(smtp).sendManual(any(),any(),any(),any());
    }

    @Test void losingTransportDuringCancellationCannotLeavePendingMailInACancelledCampaign() throws Exception {
        var id = twoQueued();
        var envelopeLocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            envelopeLocked.countDown();
            assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(settings).lockForSending(any(UUID.class));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var inFlight = pool.submit(dispatcher::dispatchOne);
            try {
                assertThat(envelopeLocked.await(5,TimeUnit.SECONDS)).isTrue();
                jdbc.update("update saas_smtp_settings set host=''");
                assertThat(service.cancel(id,1).summary().counts().cancelled()).isEqualTo(1);
            } finally { release.countDown(); }
            assertThat(inFlight.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(store.detail(id).summary().counts()).isEqualTo(new Counts(0,0,0,1,0,1));
        verifyNoInteractions(smtp);
    }

    @Test void deactivationWhileWaitingForTransportPreventsDelivery() throws Exception {
        var user = account("disabled","disabled@example.test",true);
        var id = newDraft(Mode.SELECTED,List.of(user));
        service.send(id,confirmation(service.review(id,0)));
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            waiting.countDown();
            assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(settings).lockForSending(any(UUID.class));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var inFlight = pool.submit(dispatcher::dispatchOne);
            try {
                assertThat(waiting.await(5,TimeUnit.SECONDS)).isTrue();
                jdbc.update("update saas_tenant_user set active=false where id=?",user);
            } finally { release.countDown(); }
            assertThat(inFlight.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(store.recipients(id,null,0,1).items().getFirst().lastError()).isEqualTo("COMMUNICATION_RECIPIENT_UNAVAILABLE");
        verifyNoInteractions(smtp);
    }

    @Test void eachCampaignUsesItsChosenAccountIncludingWhenTheDefaultHasNoTransport() throws Exception {
        var first = settings.get();
        var second = settings.create(new SmtpSettings.Update(false,"relay.example.test",587,SmtpSettings.Security.STARTTLS,
                "editor","fixture-password",false,"other@example.test","Other","","",0L));
        var selected = List.of(account("select","select@example.test",true));
        var waiting = newDraft(Mode.SELECTED,selected);
        service.send(waiting,confirmation(service.review(waiting,0)));
        var id = newDraft(Mode.SELECTED,selected);
        var old = service.review(id,0);
        var edit = service.save(id,new Draft("Comunicado",BODY,Mode.SELECTED,selected,0L,second.id()));
        assertThat(edit.senderAccountId()).isEqualTo(second.id());
        postJson("/"+id+"/send",confirmation(old)).andExpect(status().isConflict());
        var review = service.review(id,1);
        assertThat(review.senderAccountId()).isEqualTo(second.id());
        assertThat(review.fromAddress()).isEqualTo(second.fromAddress());
        service.send(id,confirmation(review));
        var accountPath = "/api/v1/admin/smtp-settings/accounts/"+second.id();
        mvc.perform(delete(accountPath).param("version",Long.toString(second.version())).header("Authorization",staff))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_ACCOUNT_IN_USE"));
        jdbc.update("update saas_smtp_settings set host='' where id=?",first.id());
        assertThat(dispatcher.dispatchOne()).isTrue();
        verify(smtp).sendManual(argThat(saved -> saved.view().id().equals(second.id())),any(),eq("select@example.test"),any());
        assertThat(store.detail(waiting).summary().counts().pending()).isOne();
        assertThat(store.detail(id).summary().counts().sent()).isOne();

        mvc.perform(delete(accountPath).param("version","0").header("Authorization",staff)).andExpect(status().isConflict());
        mvc.perform(delete(accountPath).param("version",Long.toString(second.version())).header("Authorization",staff))
                .andExpect(status().isNoContent());
        mvc.perform(get(accountPath).header("Authorization",staff)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select encrypted_password from saas_smtp_settings where id=?",String.class,second.id())).isNull();
        assertThat(store.detail(id).senderAccountId()).isEqualTo(second.id());
        assertThat(service.send(id,confirmation(review)).summary().counts().sent()).isOne();
        assertThat(settings.accounts()).extracting(SmtpSettings.View::id).containsExactly(first.id());
    }

    @Test void queuePacingLockDoesNotRejectATestAndTestCooldownAppliesAcrossAccounts() throws Exception {
        var first = settings.get();
        var second = settings.create(new SmtpSettings.Update(false,"relay.example.test",587,SmtpSettings.Security.STARTTLS,
                "",null,false,"second@example.test","Second","","",0L));
        var started = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reservation = transactions.execute(tx -> {
                jdbc.queryForObject("select singleton from saas_communication_pacing for update",Boolean.class);
                var pending = pool.submit(() -> {
                    started.countDown();
                    return settings.reserveTest(second.id());
                });
                try {
                    assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> pending.get(200,TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
                return pending;
            });
            assertThat(reservation.get(5,TimeUnit.SECONDS)).isEqualTo(second.version());
        }
        assertThatThrownBy(() -> settings.reserveTest(first.id())).hasMessageContaining("429");
        verifyNoInteractions(smtp);
    }

    @Test void concurrentRemovalCannotRetireAnAccountAfterQueueConfirmationAcquiresItsLock() throws Exception {
        var sender = settings.get();
        var id = newDraft(Mode.SELECTED,List.of(account("race","race@example.test",true)));
        var review = service.review(id,0);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            var saved = call.callRealMethod();
            locked.countDown();
            assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();
            return saved;
        }).when(settings).lockForSending(sender.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var sending = pool.submit(() -> service.send(id,confirmation(review)));
            assertThat(locked.await(8,TimeUnit.SECONDS)).isTrue();
            var removal = pool.submit(() -> settings.remove(sender.id(),sender.version()));
            release.countDown();
            assertThat(sending.get(8,TimeUnit.SECONDS).summary().counts().pending()).isOne();
            assertThatThrownBy(() -> removal.get(8,TimeUnit.SECONDS)).hasRootCauseMessage("409 CONFLICT \"Hay envíos pendientes con esta cuenta\"");
        } finally { release.countDown(); }
        assertThat(settings.get(sender.id()).id()).isEqualTo(sender.id());
    }

    @Test void defaultChangesWithPendingNoticesRequireConfirmationAndConnectionEditsPreserveNoticeSettings() throws Exception {
        var first = settings.get();
        var params = new SmtpSettings.Update(false,"relay.example.test",587,SmtpSettings.Security.STARTTLS,
                "",null,false,"other@example.test","Other","","",0L);
        var second = settings.create(params);
        account("pending","pending@example.test",true);
        jdbc.update("""
                insert into saas_client_decision_email(request_id,has_stores)
                select id,false from saas_client_access_request where username='comm-pending'
                """);
        try {
            var base = "/api/v1/admin/smtp-settings";
            mvc.perform(delete(base+"/accounts/"+first.id()).param("version",Long.toString(first.version())).header("Authorization",staff))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_NOTICES_PENDING"));
            var change = new SmtpSettings.Notices(second.id(),true,"https://portal.example.test",second.version(),
                    first.id(),first.version(),false);
            mvc.perform(put(base+"/notices").contentType("application/json").content(json.writeValueAsBytes(change))
                    .header("Authorization",staff)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SMTP_NOTICES_PENDING"));
            var confirmed = new SmtpSettings.Notices(second.id(),true,change.portalUrl(),second.version(),first.id(),first.version(),true);
            var saved = settings.saveNotices(confirmed);
            assertThat(settings.get().id()).isEqualTo(second.id());
            var edited = settings.save(second.id(),new SmtpSettings.Update(false,params.host(),587,params.security(),
                    "",null,false,params.fromAddress(),"Changed","","",saved.version()));
            assertThat(edited.enabled()).isTrue();
            assertThat(edited.portalUrl()).isEqualTo(change.portalUrl());
            assertThatThrownBy(() -> settings.saveNotices(confirmed)).hasMessageContaining("409");
        } finally {
            jdbc.update("delete from saas_client_decision_email where request_id in (select id from saas_client_access_request where username='comm-pending')");
        }
    }

    @Test void directoryGroupsBeforeSearchAndPaginationAndReviewsOneEnvelopePerMailbox() throws Exception {
        UUID shop = company("Directorio Azul", "Marta", "  SHARED@example.test  ");
        UUID otherShop = company("Directorio Verde", null, null);
        owner(otherShop,"prop-1","Julia","shared@example.test");
        owner(otherShop,"prop-2","Julia","second@example.test");
        owner(shop,"prop-3","Sin correo",null);
        UUID client = account("Rocio","Shared@example.test",true);
        jdbc.update("insert into saas_tenant_company_access(user_id,company_id,role_name) values(?,?,'VIEWER')",client,shop);
        jdbc.update("insert into saas_tenant_company_access(user_id,company_id,role_name) values(?,?,'VIEWER')",client,otherShop);

        var shared = audience.page(0,1,"Marta").items().getFirst();
        assertThat(shared.label()).contains("Julia","Marta","Rocio");
        assertThat(shared.sources()).containsExactly("CLIENT_USER","COMPANY_CONTACT","COMPANY_OWNER");
        assertThat(shared.companyNames()).containsExactly("Directorio Azul","Directorio Verde");
        assertThat(audience.page(0,1,"comm-Rocio").items()).containsExactly(shared);
        assertThat(audience.page(0,2,"Julia").items()).contains(shared).hasSize(2);
        assertThat(audience.page(0,10,"Directorio Verde").items()).contains(shared).hasSize(2);
        var pageZero = audience.page(0,1,"Directorio");
        var pageOne = audience.page(1,1,"Directorio");
        var pageTwo = audience.page(2,1,"Directorio");
        assertThat(pageZero.total()).isEqualTo(3);
        assertThat(List.of(pageZero.items().getFirst(),pageOne.items().getFirst(),pageTwo.items().getFirst()))
                .contains(shared).doesNotHaveDuplicates();
        assertThat(audience.page(0,1,"Sin correo").items().getFirst().reason()).isEqualTo("MISSING_EMAIL");
        mvc.perform(get(BASE+"/audience").param("q","Marta").header("Authorization",staff))
                .andExpect(jsonPath("$.items[0].sources.length()").value(3))
                .andExpect(jsonPath("$.items[0].companyNames.length()").value(2))
                .andExpect(jsonPath("$.items[0].taxId").doesNotExist());

        var all = newDraft(Mode.ALL,List.of());
        var allReview = service.review(all,0);
        assertThat(allReview.recipientCount()).isEqualTo(2);
        assertThat(allReview.duplicateCount()).isEqualTo(2);
        assertThat(store.recipients(all,allReview.reviewId(),0,10).items()).extracting(Recipient::email)
                .containsExactlyInAnyOrder(shared.email(),"second@example.test");
        var selected = newDraft(Mode.SELECTED,List.of(shared.id()));
        var selectedReview = service.review(selected,0);
        assertThat(selectedReview.recipientCount()).isOne();
        assertThat(selectedReview.duplicateCount()).isZero();
        service.send(selected,confirmation(selectedReview));
        service.send(selected,confirmation(selectedReview));
        jdbc.update("update saas_tenant_user set active=false where id=?",client);
        jdbc.update("update saas_company_operations set contact_email='moved@example.test' where company_id=?",shop);
        // The owner still has the reviewed mailbox, with different casing from the envelope.
        assertThat(dispatcher.dispatchOne()).isTrue();
        openSlot();
        assertThat(dispatcher.dispatchOne()).isFalse();
        verify(smtp).sendManual(any(),any(),eq(shared.email()),any());
    }

    @Test void ownerReplacementCannotInheritAReviewedEnvelopeEvenAtTheSameAddress() throws Exception {
        UUID shop = company("Reemplazo",null,null);
        owner(shop,"old-id","Titular","owner@example.test");
        var mailbox = audience.page(0,1,"Titular").items().getFirst();
        var id = newDraft(Mode.SELECTED,List.of(mailbox.id()));
        var firstReview = service.review(id,0);
        jdbc.update("update saas_company set owners='[]' where id=?",shop);
        owner(shop,"next-id","Nueva titular","OWNER@example.test");
        postJson("/"+id+"/send",confirmation(firstReview)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNICATION_REVIEW_CHANGED"));
        var secondReview = service.review(id,0);
        service.send(id,confirmation(secondReview));
        jdbc.update("update saas_company set owners='[]' where id=?",shop);
        owner(shop,"other-id","Otro titular","owner@example.test");
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(store.recipients(id,null,0,1).items().getFirst().lastError()).isEqualTo("COMMUNICATION_RECIPIENT_UNAVAILABLE");
        verifyNoInteractions(smtp);
    }

    @Test void legacyDraftsDisplayCurrentMailboxAndLegacyEnvelopesStayBoundToTheirUser() {
        var client = account("historico","legacy@example.test",true);
        company("Con el mismo correo","Persona","LEGACY@example.test");
        var mailbox = audience.page(0,1,"comm-historico").items().getFirst().id();
        var id = newDraft(Mode.SELECTED,List.of(client));
        assertThat(store.detail(id).recipientIds()).containsExactly(mailbox);
        var review = service.review(id,0);
        jdbc.update("update saas_communication_recipient set reviewed_sources=null,source_id=? where communication_id=?",client,id);
        jdbc.update("update saas_client_access_request set email='  LEGACY@example.test  ' where tenant_user_id=?",client);
        service.send(id,confirmation(review));
        jdbc.update("update saas_tenant_user set active=false where id=?",client);
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(store.detail(id).summary().counts().cancelled()).isOne();
        verifyNoInteractions(smtp);
    }

    @Test void editingOtherDraftFieldsKeepsInactiveLegacySelectionsBoundToTheOriginalIdentity() {
        var client = account("antiguo","shared-legacy@example.test",false);
        company("Empresa nueva","Otro origen","shared-legacy@example.test");
        var id = newDraft(Mode.SELECTED,List.of(client));
        var checked = store.detail(id).recipientIds();
        assertThat(checked).containsExactly(audience.page(0,1,"comm-antiguo").items().getFirst().id());
        service.save(id,new Draft("Asunto editado",BODY,Mode.SELECTED,checked,0L,settings.get().id()));
        var review = service.review(id,1);
        assertThat(review.omittedCount()).isOne();
        assertThat(review.recipientCount()).isZero();
        var deselected = service.save(id,new Draft("Sin selección",BODY,Mode.SELECTED,List.of(),1L,settings.get().id()));
        assertThat(deselected.recipientIds()).isEmpty();
        verifyNoInteractions(smtp);
    }

    @Test void widelySharedMailboxKeepsAllNamesSearchableAndFitsTheSnapshotIndex() {
        var shop = company("Muchos propietarios",null,null);
        jdbc.update("""
                update saas_company set owners=(select jsonb_agg(jsonb_build_object(
                    'taxId',n::text,'name','Titular ' || lpad(n::text,3,'0') || ' ' || md5(n::text) || md5((-n)::text),
                    'email','shared-large@example.test')) from generate_series(1,150) n) where id=?
                """,shop);
        var mailbox = audience.page(0,1,"Titular 150").items().getFirst();
        assertThat(mailbox.label()).startsWith("Titular 001").endsWith("…").doesNotContain("Titular 150");
        var id = newDraft(Mode.SELECTED,List.of(mailbox.id()));
        assertThat(service.review(id,0).recipientCount()).isOne();
        verifyNoInteractions(smtp);
    }

    @Test void companyContactAndOwnerRowsStayLockedDuringTheirSmtpCall() throws Exception {
        var shop = company("Bloqueos","Correo de contacto","contact-lock@example.test");
        owner(shop,"lock-id","Titular","owner-lock@example.test");
        for (String email : List.of("contact-lock@example.test","owner-lock@example.test")) {
            var mailbox = audience.page(0,1,email).items().getFirst().id();
            var id = newDraft(Mode.SELECTED,List.of(mailbox));
            service.send(id,confirmation(service.review(id,0)));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(call -> {
                entered.countDown();
                assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();
                return null;
            }).when(smtp).sendManual(any(),any(),eq(email),any());
            try (var pool = Executors.newSingleThreadExecutor()) {
                openSlot();
                var dispatch = pool.submit(dispatcher::dispatchOne);
                try {
                    assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                    String update = email.startsWith("contact")
                            ? "update saas_company_operations set contact_email=null where company_id=?"
                            : "update saas_company set owners='[]' where id=?";
                    assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
                        jdbc.execute("set local lock_timeout='250ms'");
                        jdbc.update(update,shop);
                    })).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class,
                            cause -> assertThat(cause.getSQLState()).isEqualTo("55P03"));
                } finally { release.countDown(); }
                assertThat(dispatch.get(5,TimeUnit.SECONDS)).isTrue();
            }
            assertThat(store.detail(id).summary().counts().sent()).isOne();
        }
    }

    private UUID company(String name, String contact, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into saas_company(id,name,tax_id,taxpayer_type,created_at) values(?,?,?,'SOCIEDAD',now())",
                id,name,"COMM-MAIL-"+id.toString().substring(0,16));
        jdbc.update("""
                insert into saas_company_operations(company_id,plan_name,billing_status,support_status,contact_name,contact_email,updated_at)
                values(?,'STANDARD','PENDIENTE','NORMAL',?,?,now())
                """,id,contact,email);
        return id;
    }
    private void owner(UUID company, String identity, String name, String email) {
        jdbc.update("""
                update saas_company set owners=owners || jsonb_build_array(jsonb_build_object(
                    'taxId',?::text,'name',?::text,'email',?::text)) where id=?
                """,identity,name,email,company);
    }
    private UUID twoQueued() {
        var id = newDraft(Mode.SELECTED,List.of(account("one","one@example.test",true),account("two","two@example.test",true)));
        service.send(id,confirmation(service.review(id,0)));
        return id;
    }
    private void openSlot() { jdbc.update("update saas_communication_pacing set next_claim_at='-infinity'"); }
    private UUID newDraft(Mode mode, List<UUID> ids) {
        return service.save(null,new Draft("Comunicado",BODY,mode,ids,null,settings.get().id())).summary().id();
    }
    private Confirmation confirmation(Review review) {
        return new Confirmation(review.version(),review.reviewId(),review.recipientCount());
    }
    private UUID account(String name, String email, boolean active) {
        UUID user = UUID.randomUUID();
        String username = "comm-"+name;
        jdbc.update("insert into saas_tenant_user(id,username,password_hash,role_name,active,created_at) values(?,?,'fixture','VIEWER',?,now())",
                user,username,active);
        if (email != null) jdbc.update("""
                insert into saas_client_access_request(id,submission_id,username,full_name,tax_id,email,phone,address,postal_code,
                    city,province,country,requested_stores,status,version,reviewed_at,reviewed_by,tenant_user_id)
                values(gen_random_uuid(),gen_random_uuid(),?,?,'test',?,'0','test','0','test','test','ES','{}','APPROVED',1,now(),'viewer',?)
                """,username,name,email,user);
        return user;
    }
    private ResultActions postJson(String path, Object payload) throws Exception {
        return mvc.perform(post(BASE+path).header("Authorization",staff).contentType("application/json").content(json.writeValueAsBytes(payload)));
    }
}
