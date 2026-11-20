package com.tpverp.backend.communications;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CompanyMailService;
import com.tpverp.backend.party.MemberSmtpSettings;
import com.tpverp.backend.party.MemberSmtpSettingsCommand;
import com.tpverp.backend.party.MemberSmtpSettingsRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.support.TransactionTemplate;
import static com.tpverp.backend.communications.CommunicationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Exercises actual migration, locking, durable claims and opt-out against an explicitly supplied disposable PostgreSQL database. */
class CommunicationPostgreSqlTest {
    private Connection connection;
    private String schema;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private CommunicationService service;
    private CommunicationPublicLinks links;
    private CompanyMailService mail;
    private CurrentOrganization organization;
    private Company company;
    private UUID channel;
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"),ZoneOffset.UTC);

    @BeforeEach void database() throws Exception {
        String url=System.getenv("TPV_TEST_DB_URL");
        assumeTrue(url!=null&&!url.isBlank(),"Explicit TPV_TEST_DB_URL required; SMTP is always mocked");
        assertThat(url).matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]*_test(?:\\?.*)?");
        String user=System.getenv().getOrDefault("TPV_TEST_DB_USERNAME","tpv_erp");
        String password=System.getenv().getOrDefault("TPV_TEST_DB_PASSWORD","");
        connection=DriverManager.getConnection(url,user,password);
        schema="communication_test_"+UUID.randomUUID().toString().replace("-","");
        try(var sql=connection.createStatement()) {
            sql.execute("create schema "+schema);
            sql.execute("set search_path to "+schema);
            sql.execute("create table empresa(id uuid primary key)");
            sql.execute("create table commercial_contact_channel(id uuid primary key,empresa_id uuid not null,code text not null,active boolean not null)");
            sql.execute("create table cliente(id uuid primary key,empresa_id uuid not null references empresa(id),nombre_fiscal text not null,email text,activo boolean not null,commercial_consent boolean not null,preferred_commercial_channel_id uuid,version bigint not null default 0)");
            sql.execute("create table miembro(id uuid primary key,empresa_id uuid not null,cliente_id uuid not null,member_id text not null)");
            try(var input=getClass().getResourceAsStream("/db/migration/V302__email_communications.sql")) {
                sql.execute(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        company=new Company("DEMO","Tienda",Map.of("linea1","Calle","ciudad","Madrid","codigoPostal","28001","provincia","Madrid","pais","ES"));
        jdbc.update("insert into empresa values(?)",company.getId());
        channel=UUID.randomUUID();
        jdbc.update("insert into commercial_contact_channel values(?,?,'EMAIL',true)",channel,company.getId());
        organization=mock(CurrentOrganization.class);when(organization.currentCompany()).thenReturn(company);
        var smtp=mock(MemberSmtpSettingsRepository.class);
        when(smtp.findById(company.getId())).thenReturn(Optional.of(new MemberSmtpSettings(company.getId(),new MemberSmtpSettingsCommand(true,"smtp.tienda.es",587,"sender",null,"sender@tienda.es","Tienda",true,false))));
        mail=mock(CompanyMailService.class);
        links=new CommunicationPublicLinks(jdbc,clock,"https://correo.tienda.es",Base64.getEncoder().encodeToString(new byte[32]));
        service=new CommunicationService(jdbc,organization,new CommunicationTemplateRenderer(),links,smtp,mail,clock,true);
    }

    @AfterEach void cleanup() throws Exception {
        if(connection!=null) {
            var current=connection;
            try(current;var sql=current.createStatement()) { if(schema!=null) sql.execute("drop schema "+schema+" cascade"); }
        }
    }

    @Test void migrationAndEndToEndSchedulingHonorTenantConsentAndIdempotentUnsubscribe() {
        UUID first=customer("first@example.org"),second=customer("second@example.org");
        var template=service.createTemplate(templateInput());
        service.selectWelcomeTemplate(new WelcomeTemplateSelection(template.id()));
        var welcome=new CommunicationWelcomeTemplateService(jdbc,new CommunicationTemplateRenderer());
        assertThat(welcome.renderWelcome(company.getId(),"María","000123","Tienda")).get().extracting(CommunicationWelcomeTemplateService.RenderedWelcome::subject).isEqualTo("Hola María");
        assertThat(welcome.renderWelcome(UUID.randomUUID(),"María","000123","Otra")).isEmpty();
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of(first,second)));
        assertThat(service.previewCampaign(campaign.id()).eligible()).isEqualTo(2);
        assertThatThrownBy(()->tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),1))))
            .hasMessageContaining("destinatarios han cambiado");
        assertThat(jdbc.queryForObject("select count(*) from communication_delivery",Integer.class)).isZero();
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),2)));
        assertThatThrownBy(()->tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),2))))
            .hasMessageContaining("borrador");
        String url=jdbc.queryForObject("select unsubscribe_url from communication_delivery where customer_id=?",String.class,first);
        String token=url.substring(url.indexOf("token=")+6);
        links.verify(token);
        assertThat(jdbc.queryForObject("select commercial_consent from cliente where id=?",Boolean.class,first)).isTrue();
        tx.executeWithoutResult(status->links.unsubscribe(token));
        long versionAfterFirstUnsubscribe=jdbc.queryForObject("select version from cliente where id=?",Long.class,first);
        tx.executeWithoutResult(status->links.unsubscribe(token));
        assertThat(jdbc.queryForObject("select version from cliente where id=?",Long.class,first)).isEqualTo(versionAfterFirstUnsubscribe);
        assertThat(jdbc.queryForObject("select count(*) from communication_suppression",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from communication_consent_audit where customer_id=? and consent=false",Integer.class,first)).isEqualTo(1);
        doAnswer(invocation->{
            assertThat(jdbc.queryForObject("select status from communication_delivery where customer_id=?",String.class,second)).isEqualTo("SENDING");
            return null;
        }).when(mail).send(eq(company.getId()),eq("second@example.org"),anyString(),anyString(),anyString(),anyString());
        assertThat(worker().runOnce()).isEqualTo(1);
        assertThat(worker().runOnce()).isZero();
        verify(mail,times(1)).send(any(),any(),any(),any(),any(),any());
        assertThat(service.history()).extracting(DeliveryView::status).containsExactlyInAnyOrder("SUPPRESSED","ACCEPTED");
        assertThat(service.campaigns().getFirst().status()).isEqualTo("COMPLETED");
        var other=mock(Company.class);when(other.getId()).thenReturn(UUID.randomUUID());when(organization.currentCompany()).thenReturn(other);
        assertThat(service.templates()).isEmpty();assertThat(service.history()).isEmpty();assertThat(service.campaigns()).isEmpty();
        assertThatThrownBy(()->service.previewCampaign(campaign.id())).hasMessageContaining("no encontrada");
    }

    @Test void rechecksRevocationAfterSchedulingAndNeverBlindlyRetriesAmbiguousSubmission() {
        UUID first=customer("first@example.org"),second=customer("second@example.org");
        var template=service.createTemplate(templateInput());
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),2)));
        jdbc.update("update cliente set commercial_consent=false where id=?",first);
        doThrow(new MailSendException("lost reply after server accepted DATA")).when(mail).send(eq(company.getId()),eq("second@example.org"),anyString(),anyString(),anyString(),anyString());
        assertThat(worker().runOnce()).isZero();
        assertThat(worker().runOnce()).isZero();
        verify(mail,times(1)).send(any(),any(),any(),any(),any(),any());
        assertThat(service.history()).extracting(DeliveryView::status).containsExactlyInAnyOrder("SUPPRESSED","UNCERTAIN");
    }

    @Test void cancellationWhileAWorkerHasClaimedPreventsOtherPendingMessagesAndDuplicateClaims() throws Exception {
        customer("first@example.org");customer("second@example.org");
        var template=service.createTemplate(templateInput());
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),2)));
        var submitted=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(invocation->{submitted.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return null;})
            .when(mail).send(any(),any(),any(),any(),any(),any());
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<Integer> sending=executor.submit(()->worker().runOnce());
            try {
                assertThat(submitted.await(10,TimeUnit.SECONDS)).isTrue();
                tx.execute(status->service.cancel(campaign.id()));
                assertThat(worker().runOnce()).isZero();
            } finally { release.countDown(); }
            assertThat(sending.get(10,TimeUnit.SECONDS)).isEqualTo(1);
        }
        verify(mail,times(1)).send(any(),any(),any(),any(),any(),any());
        assertThat(service.history()).extracting(DeliveryView::status).containsExactlyInAnyOrder("CANCELLED","ACCEPTED");
    }

    @Test void explicitRetryOnlyAllowsConfirmedFailuresAndRepeatedRequestsDoNotResetPendingAttempts() {
        customer("first@example.org");
        var template=service.createTemplate(templateInput());
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),1)));
        doThrow(new org.springframework.mail.MailAuthenticationException("private server details")).when(mail).send(any(),any(),any(),any(),any(),any());
        worker().runOnce();
        var failure=service.history().getFirst();assertThat(failure.status()).isEqualTo("FAILED");
        assertThat(tx.execute(status->service.retry(failure.id())).status()).isEqualTo("PENDING");
        jdbc.update("update communication_delivery set attempts=2 where id=?",failure.id());
        assertThat(tx.execute(status->service.retry(failure.id())).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempts from communication_delivery where id=?",Integer.class,failure.id())).isEqualTo(2);
        jdbc.update("update communication_delivery set status='UNCERTAIN' where id=?",failure.id());
        assertThatThrownBy(()->tx.execute(status->service.retry(failure.id()))).hasMessageContaining("inciertos");
    }

    @Test void previewFingerprintRejectsChangedContentAndSameSizeRecipientReplacement() {
        UUID first=customer("first@example.org"),second=customer("second@example.org");
        jdbc.update("update cliente set commercial_consent=false where id=?",second);
        var template=service.createTemplate(templateInput());
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        var original=service.previewCampaign(campaign.id());
        assertThat(original.previewToken()).matches("[0-9a-f]{64}");
        assertThat(service.previewCampaign(campaign.id()).previewToken()).isEqualTo(original.previewToken());
        jdbc.update("update cliente set commercial_consent=false where id=?",first);
        jdbc.update("update cliente set commercial_consent=true where id=?",second);
        assertThat(service.previewCampaign(campaign.id()).eligible()).isEqualTo(original.eligible());
        assertThatThrownBy(()->tx.execute(status->service.schedule(campaign.id(),new ScheduleInput(null,1,original.previewToken()))))
            .hasMessageContaining("contenido o los destinatarios han cambiado");
        var refreshed=service.previewCampaign(campaign.id());
        service.updateTemplate(template.id(),new TemplateInput("Oferta","Nuevo asunto","Título","Contenido cambiado",null,null,null,"#123456",template.version()));
        assertThatThrownBy(()->tx.execute(status->service.schedule(campaign.id(),new ScheduleInput(null,1,refreshed.previewToken()))))
            .hasMessageContaining("contenido o los destinatarios han cambiado");
        assertThat(jdbc.queryForObject("select count(*) from communication_delivery",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from communication_unsubscribe_token",Integer.class)).isZero();
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),1)));
        assertThat(jdbc.queryForObject("select subject from communication_delivery",String.class)).isEqualTo("Nuevo asunto");
    }

    @Test void templateVersionTenantOwnershipAndQueuedContentArePreserved() {
        customer("first@example.org");
        var template=service.createTemplate(templateInput());
        var revised=new TemplateInput("Oferta","Asunto revisado","Título","Cuerpo revisado",null,null,null,"#345678",template.version());
        var saved=service.updateTemplate(template.id(),revised);
        assertThat(saved.version()).isEqualTo(template.version()+1);
        assertThatThrownBy(()->service.updateTemplate(template.id(),revised)).hasMessageContaining("plantilla cambió");
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),1)));
        service.updateTemplate(template.id(),new TemplateInput("Oferta","Asunto posterior","Título","Contenido posterior",null,null,null,"#123456",saved.version()));
        assertThat(jdbc.queryForObject("select subject from communication_delivery",String.class)).isEqualTo("Asunto revisado");
        UUID otherCompany=UUID.randomUUID(),foreignCustomer=UUID.randomUUID();
        jdbc.update("insert into empresa values(?)",otherCompany);
        jdbc.update("insert into cliente(id,empresa_id,nombre_fiscal,email,activo,commercial_consent) values(?,?,'Otro','foreign@example.org',true,false)",foreignCustomer,otherCompany);
        assertThatThrownBy(()->service.createCampaign(new CampaignInput("Ajena",template.id(),List.of(foreignCustomer)))).hasMessageContaining("Cliente no encontrado");
        var other=mock(Company.class);when(other.getId()).thenReturn(otherCompany);when(organization.currentCompany()).thenReturn(other);
        assertThatThrownBy(()->service.selectWelcomeTemplate(new WelcomeTemplateSelection(template.id()))).hasMessageContaining("Plantilla no encontrada");
        assertThatThrownBy(()->tx.execute(status->service.cancel(campaign.id()))).hasMessageContaining("Campaña no encontrada");
        UUID delivery=jdbc.queryForObject("select id from communication_delivery",UUID.class);
        assertThatThrownBy(()->tx.execute(status->service.retry(delivery))).hasMessageContaining("Envío no encontrado");
    }

    @Test void futureCampaignWaitsAndConnectionFailuresUseBoundedBackoff() {
        customer("first@example.org");
        var template=service.createTemplate(templateInput());
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        var preview=service.previewCampaign(campaign.id());
        assertThatThrownBy(()->tx.execute(status->service.schedule(campaign.id(),new ScheduleInput(clock.instant().minusSeconds(61),1,preview.previewToken()))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.execute(status->service.schedule(campaign.id(),new ScheduleInput(clock.instant().plusSeconds(367L*86400),1,preview.previewToken()))))
            .isInstanceOf(IllegalArgumentException.class);
        tx.execute(status->service.schedule(campaign.id(),new ScheduleInput(clock.instant().plusSeconds(3600),1,preview.previewToken())));
        assertThat(worker().runOnce()).isZero();verifyNoInteractions(mail);
        doThrow(new MailSendException("private socket details",new java.net.ConnectException("refused")))
            .when(mail).send(any(),any(),any(),any(),any(),any());
        workerAt(3600).runOnce();
        assertThat(service.history().getFirst().status()).isEqualTo("PENDING");
        workerAt(3719).runOnce();verify(mail,times(1)).send(any(),any(),any(),any(),any(),any());
        workerAt(3720).runOnce();
        workerAt(3959).runOnce();verify(mail,times(2)).send(any(),any(),any(),any(),any(),any());
        workerAt(3960).runOnce();
        assertThat(service.history().getFirst().status()).isEqualTo("FAILED");
        workerAt(10000).runOnce();verify(mail,times(3)).send(any(),any(),any(),any(),any(),any());
        assertThat(service.history().getFirst().errorMessage()).doesNotContain("socket","refused");
    }

    @Test void preflightFailureIsSafeToRetryAndStaleClaimsRemainUncertain() {
        customer("first@example.org");
        var template=service.createTemplate(templateInput());
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),1)));
        var unavailable=mock(CommunicationPublicLinks.class);
        when(unavailable.isCurrentLink(anyString())).thenThrow(new org.springframework.dao.TransientDataAccessResourceException("private database details"));
        new CommunicationDeliveryWorker(jdbc,mail,clock,unavailable,manager,true,20).runOnce();
        assertThat(service.history().getFirst().status()).isEqualTo("FAILED");
        assertThat(service.history().getFirst().errorMessage()).doesNotContain("database details");
        verifyNoInteractions(mail);
        UUID delivery=service.history().getFirst().id();
        tx.execute(status->service.retry(delivery));
        jdbc.update("update communication_delivery set status='SENDING',started_at=? where id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(601)),delivery);
        worker().runOnce();
        assertThat(service.history().getFirst().status()).isEqualTo("UNCERTAIN");
        assertThatThrownBy(()->tx.execute(status->service.retry(delivery))).hasMessageContaining("inciertos");
        verifyNoInteractions(mail);
    }

    @Test void optoutIsAddressScopedAndCannotRevokeReplacementAddressOrOtherCompany() {
        UUID customer=customer("old@example.org");
        var template=service.createTemplate(templateInput());
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),1)));
        String url=jdbc.queryForObject("select unsubscribe_url from communication_delivery",String.class);
        String token=url.substring(url.indexOf("token=")+6);
        jdbc.update("update cliente set email='new@example.org' where id=?",customer);
        UUID otherCompany=UUID.randomUUID(),otherCustomer=UUID.randomUUID();jdbc.update("insert into empresa values(?)",otherCompany);
        jdbc.update("insert into cliente(id,empresa_id,nombre_fiscal,email,activo,commercial_consent) values(?,?,'Otro','old@example.org',true,true)",otherCustomer,otherCompany);
        tx.executeWithoutResult(status->links.unsubscribe(token));
        assertThat(jdbc.queryForObject("select commercial_consent from cliente where id=?",Boolean.class,customer)).isTrue();
        assertThat(jdbc.queryForObject("select commercial_consent from cliente where id=?",Boolean.class,otherCustomer)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from communication_suppression where empresa_id=?",Integer.class,otherCompany)).isZero();
        assertThat(service.history().getFirst().status()).isEqualTo("SUPPRESSED");
    }

    @Test void disabledWorkerDoesNothingAndConcurrentWorkersSubmitEachMessageOnce() throws Exception {
        for(int n=0;n<12;n++) customer("recipient"+n+"@example.org");
        var template=service.createTemplate(templateInput());
        var campaign=service.createCampaign(new CampaignInput("Campaña",template.id(),List.of()));
        tx.execute(status->service.schedule(campaign.id(),scheduleInput(campaign.id(),12)));
        assertThat(new CommunicationDeliveryWorker(jdbc,mail,clock,links,manager,false,20).runOnce()).isZero();
        verifyNoInteractions(mail);
        var counts=new ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>();
        doAnswer(invocation->{counts.computeIfAbsent(invocation.getArgument(1,String.class),key->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();return null;})
            .when(mail).send(any(),any(),any(),any(),any(),any());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<Integer>>();
            for(int n=0;n<4;n++) futures.add(pool.submit(()->{assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return worker().runOnce();}));
            start.countDown();for(var future:futures) future.get(20,TimeUnit.SECONDS);
        }
        worker().runOnce();
        assertThat(counts).hasSize(12);assertThat(counts.values()).allSatisfy(count->assertThat(count.get()).isEqualTo(1));
        assertThat(service.history()).hasSize(12).allSatisfy(delivery->assertThat(delivery.status()).isEqualTo("ACCEPTED"));
    }

    private UUID customer(String email) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into cliente(id,empresa_id,nombre_fiscal,email,activo,commercial_consent,preferred_commercial_channel_id) values(?,?,?, ?,true,true,?)",id,company.getId(),"Cliente",email,channel);
        return id;
    }
    private CommunicationDeliveryWorker worker() { return new CommunicationDeliveryWorker(jdbc,mail,clock,links,manager,true,20); }
    private CommunicationDeliveryWorker workerAt(long seconds) { return new CommunicationDeliveryWorker(jdbc,mail,Clock.offset(clock,Duration.ofSeconds(seconds)),links,manager,true,20); }
    private ScheduleInput scheduleInput(UUID campaignId,int count) { return new ScheduleInput(null,count,service.previewCampaign(campaignId).previewToken()); }
    private TemplateInput templateInput() { return new TemplateInput("Oferta","Hola {name}","{storeName}","Oferta especial",null,null,null,"#123456",0); }
}
