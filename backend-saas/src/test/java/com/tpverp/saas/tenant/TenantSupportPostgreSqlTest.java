package com.tpverp.saas.tenant;

import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.access.*;
import com.tpverp.saas.admin.*;
import com.tpverp.saas.license.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TenantSupportPostgreSqlTest {
    private static final String SCHEMA="tenant_support_"+UUID.randomUUID().toString().replace("-","");
    private static final AtomicInteger NUMBER=new AtomicInteger(9458000);
    @DynamicPropertySource static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema",()->SCHEMA);
        registry.add("spring.datasource.hikari.schema",()->SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired TenantAccessService access;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasTenantUserRepository users;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void commentsRetryUsesSameReceiptAndRejectsChangedBodyAndAuthor() throws Exception {
        Client client=client(); UUID ticket=create(client); UUID key=UUID.randomUUID();
        String original=comment(client,ticket,new CreateSupportTicketCommentRequest("  Customer confirms printing works  ",key),200);
        assertThat(comment(client,ticket,new CreateSupportTicketCommentRequest("Customer confirms printing works",key),200)).isEqualTo(original);
        comment(client,ticket,new CreateSupportTicketCommentRequest("Changed confirmation",key),409);
        Client other=user(client.company());
        comment(other,ticket,new CreateSupportTicketCommentRequest("Customer confirms printing works",key),409);
        var saved=mapper.readTree(original);
        assertThat(saved.get("requestId").asText()).isEqualTo(key.toString());
        String listed=call(client,get("/api/v1/tenant/tickets/"+ticket+"/comments"),null,200);
        assertThat(mapper.readTree(listed).get(0)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("select count(*) from saas_support_ticket_comment where ticket_id=?",Long.class,ticket)).isEqualTo(1);
    }

    @Test void concurrentRetriesCreateOneCommentAndInvalidTextDoesNotConsumeKey() throws Exception {
        Client client=client(); UUID ticket=create(client); UUID key=UUID.randomUUID();
        comment(client,ticket,new CreateSupportTicketCommentRequest("Invalid"+((char)0)+"text",key),400);
        comment(client,ticket,new CreateSupportTicketCommentRequest("   ",key),400);
        var request=new CreateSupportTicketCommentRequest("Same concurrent message",key);
        CountDownLatch ready=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{ ready.await(); return comment(client,ticket,request,200); });
            var second=executor.submit(()->{ ready.await(); return comment(client,ticket,request,200); });
            ready.countDown(); assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_support_ticket_comment where ticket_id=?",Long.class,ticket)).isEqualTo(1);
    }

    @Test void companyScopeAndSupportGrantProtectReadsWritesAndReceipts() throws Exception {
        Client owner=client(),foreign=client(); UUID ticket=create(owner); UUID key=UUID.randomUUID();
        comment(owner,ticket,new CreateSupportTicketCommentRequest("Only our company sees this",key),200);
        call(foreign,get("/api/v1/tenant/tickets/"+ticket+"/comments"),null,404);
        comment(foreign,ticket,new CreateSupportTicketCommentRequest("Only our company sees this",key),404);
        assertThat(mapper.readTree(call(foreign,get("/api/v1/tenant/tickets"),null,200))).isEmpty();
        access.replace(owner.username(),owner.company().getId(),new UpdateTenantAccessRequest("VIEWER",Set.of(),Set.of()));
        call(owner,get("/api/v1/tenant/tickets"),null,403);
        call(owner,get("/api/v1/tenant/tickets/"+ticket+"/comments"),null,403);
        comment(owner,ticket,new CreateSupportTicketCommentRequest("Only our company sees this",key),403);
        assertThat(mapper.readTree(call(owner,get("/api/v1/tenant/dashboard"),null,200)).get("openTickets").isNull()).isTrue();
    }

    @Test void publicPhaseDatesAndCounterNeverExposeInternalInterventionData() throws Exception {
        Client client=client(); UUID ticket=create(client); UUID closed=create(client); UUID legacy=create(client);
        jdbc.update("update saas_support_ticket set status='RESUELTO' where id=?",closed);
        jdbc.update("update saas_support_ticket set status='CERRADO' where id=?",legacy);
        Instant review=Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS),visit=review.plusSeconds(3600);
        jdbc.update("update saas_support_ticket set created_by='PRIVATE_TECHNICIAN',description='PRIVATE_TECHNICAL_IDS_AND_NOTE' where id=?",ticket);
        jdbc.update("insert into saas_store_failure_manual(failure_key,company_id,ticket_id,requested_by,reason,created_at) values (?,?,?,'PRIVATE_TECHNICIAN','PRIVATE_NOTE',now())",
                "LOCAL_APPLICATION:"+UUID.randomUUID(),client.company().getId(),ticket);
        jdbc.update("""
                insert into saas_support_intervention(ticket_id,status,version,team_viewer_id,assignee,visit_at,next_review_at,resume_status,resolution_summary,verification_notes,confirmed_by)
                values (?,'WAITING_CUSTOMER',1,'123456789','PRIVATE_TECHNICIAN',?,?,'SAAS_IN_PROGRESS','PRIVATE_SOLUTION','PRIVATE_CHECKS','PRIVATE_CONFIRMATION')
                """,ticket,java.sql.Timestamp.from(visit),java.sql.Timestamp.from(review));
        String json=call(client,get("/api/v1/tenant/tickets"),null,200);
        assertThat(json).doesNotContain("PRIVATE_","123456789","assignee","failureKey","teamViewer","verificationNotes","resolutionSummary","createdBy");
        var row=java.util.stream.StreamSupport.stream(mapper.readTree(json).spliterator(),false).filter(t->t.get("id").asText().equals(ticket.toString())).findFirst().orElseThrow();
        assertThat(row.get("interventionStatus").asText()).isEqualTo("WAITING_CUSTOMER");
        assertThat(Instant.parse(row.get("nextReviewAt").asText())).isEqualTo(review);
        assertThat(Instant.parse(row.get("visitAt").asText())).isEqualTo(visit);
        assertThat(row.get("description").isNull()).isTrue();
        assertThat(mapper.readTree(call(client,get("/api/v1/tenant/dashboard"),null,200)).get("openTickets").asLong()).isEqualTo(1);
        jdbc.update("update saas_support_ticket set status='RESUELTO' where id=?",ticket);
        assertThat(mapper.readTree(call(client,get("/api/v1/tenant/dashboard"),null,200)).get("openTickets").asLong()).isZero();
    }

    @Test void permissionRevocationDuringIdempotencyWaitPreventsReceiptOrWrite() throws Exception {
        Client client=client(); UUID ticket=create(client),key=UUID.randomUUID();
        var request=new CreateSupportTicketCommentRequest("Must be rejected after revoke",key);
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<?>[] pending=new Future<?>[1];
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",(rs,row)->0,"support-comment:"+ticket+":"+key);
                pending[0]=executor.submit(()->comment(client,ticket,request,403));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and not granted)",Boolean.class));
                    if(waiting) break;
                    try { Thread.sleep(25); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                }
                assertThat(waiting).isTrue();
                jdbc.update("update saas_tenant_company_access set company_privileges='{}' where company_id=?",client.company().getId());
            });
            pending[0].get(15,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_support_ticket_comment where ticket_id=?",Long.class,ticket)).isZero();
    }

    private Client client() {
        return user(companies.saveAndFlush(new SaasCompany(UUID.randomUUID(),"Tenant support test",validCif("B"+NUMBER.getAndIncrement()+"0"),TaxpayerType.SOCIEDAD,TaxRegime.IVA,Instant.now())));
    }
    private Client user(SaasCompany company) {
        var user=users.saveAndFlush(new SaasTenantUser(UUID.randomUUID(),company,"support-"+UUID.randomUUID(),"not-a-login-password","VIEWER",true,Instant.now()));
        access.replace(user.getUsername(),company.getId(),new UpdateTenantAccessRequest("VIEWER",Set.of(TenantCompanyPrivilege.SUPPORT),Set.of()));
        return new Client(company,user.getUsername(),sessions.issue("tenant",user.getUsername()).token());
    }
    private UUID create(Client client) throws Exception {
        String json=call(client,post("/api/v1/tenant/tickets"),new CreateSupportTicketRequest("Printing needs help","Public customer description","NORMAL"),200);
        var row=mapper.readTree(json); assertThat(row.get("interventionStatus").asText()).isEqualTo("REMOTE_PENDING");
        assertThat(row.get("description").asText()).isEqualTo("Public customer description");
        return UUID.fromString(row.get("id").asText());
    }
    private String comment(Client client,UUID ticket,CreateSupportTicketCommentRequest request,int code) throws Exception {
        return call(client,post("/api/v1/tenant/tickets/"+ticket+"/comments"),request,code);
    }
    private String call(Client client,MockHttpServletRequestBuilder request,Object body,int code) throws Exception {
        request.header("Authorization","Bearer "+client.token()).header("X-TPV-Company-Id",client.company().getId());
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();
    }
    private record Client(SaasCompany company,String username,String token) { }
}
