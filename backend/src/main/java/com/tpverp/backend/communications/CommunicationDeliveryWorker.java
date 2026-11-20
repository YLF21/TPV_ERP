package com.tpverp.backend.communications;

import com.tpverp.backend.party.CompanyMailService;
import com.tpverp.backend.party.MailFailureClassifier;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A committed reservation precedes SMTP. A crash after reservation is an uncertain outcome, never an automatic resend. */
@Service
public class CommunicationDeliveryWorker {
    private final JdbcTemplate jdbc;
    private final CompanyMailService mail;
    private final Clock clock;
    private final CommunicationPublicLinks links;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int batchSize;

    public CommunicationDeliveryWorker(JdbcTemplate jdbc,CompanyMailService mail,Clock clock,CommunicationPublicLinks links,
            PlatformTransactionManager manager,@Value("${tpv.communications.delivery-enabled:false}") boolean enabled,
            @Value("${tpv.communications.batch-size:20}") int batchSize) {
        this.jdbc=jdbc;this.mail=mail;this.clock=clock;this.links=links;this.enabled=enabled;this.batchSize=Math.max(1,Math.min(100,batchSize));
        transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Scheduled(fixedDelayString="${tpv.communications.poll-interval-ms:60000}",initialDelayString="${tpv.communications.poll-interval-ms:60000}")
    public void scheduled() { if(enabled) runOnce(); }

    public int runOnce() {
        if(!enabled) return 0;
        transactions.executeWithoutResult(tx -> jdbc.update("update communication_delivery set status='UNCERTAIN',error_message='No se puede confirmar el resultado. No se reenvía automáticamente.' where status='SENDING' and started_at<?",
                Timestamp.from(clock.instant().minusSeconds(600))));
        int accepted=0;
        for(int n=0;n<batchSize;n++) {
            Job job=transactions.execute(tx->claim());
            if(job==null) break;
            String preflightStatus=null, preflightError=null;
            try {
                if(!links.isCurrentLink(job.unsubscribeUrl())) {
                    preflightStatus="FAILED";preflightError="El enlace de baja ya no está disponible. Revisa la configuración pública.";
                } else if(!eligible(job)) {
                    // Consent and cancellation are rechecked immediately before submitting to the provider.
                    preflightStatus="SUPPRESSED";preflightError="Destinatario sin autorización vigente o campaña cancelada";
                }
            } catch(RuntimeException beforeSubmission) {
                // No SMTP call has taken place. Preserve a confirmed failure instead of an ambiguous delivery.
                preflightStatus="FAILED";preflightError="No se pudieron comprobar las preferencias de correo. Reintenta cuando el servicio esté disponible.";
            }
            if(preflightStatus!=null) { finish(job,preflightStatus,preflightError,false);continue; }
            RuntimeException failure=null;
            try { mail.send(job.company(),job.email(),job.subject(),job.text(),job.html(),job.unsubscribeUrl()); }
            catch(RuntimeException ex) { failure=ex; }
            if(failure==null) { finish(job,"ACCEPTED",null,true); accepted++; }
            else if(MailFailureClassifier.canRetry(failure) && job.attempts()<3) {
                transactions.executeWithoutResult(tx -> jdbc.update("update communication_delivery d set status=case when exists(select 1 from communication_campaign c where c.id=d.campaign_id and c.empresa_id=d.empresa_id and c.status='SCHEDULED') then 'PENDING' else 'CANCELLED' end,next_attempt_at=?,error_message='Conexión no disponible.' where id=? and empresa_id=? and status='SENDING'",
                    Timestamp.from(clock.instant().plusSeconds(60L*(1L<<job.attempts()))),job.id(),job.company()));
            } else {
                String status=MailFailureClassifier.isUncertain(failure)?"UNCERTAIN":"FAILED";
                finish(job,status,status.equals("UNCERTAIN")?"Resultado de envío incierto. Comprueba el proveedor antes de reenviar.":"No se pudo enviar. Revisa la conexión y configuración de correo.",false);
            }
        }
        transactions.executeWithoutResult(tx -> jdbc.update("""
            update communication_campaign c set status='COMPLETED',version=version+1
            where c.status='SCHEDULED' and not exists
              (select 1 from communication_delivery d where d.campaign_id=c.id and d.empresa_id=c.empresa_id and d.status in ('PENDING','SENDING'))
            """));
        return accepted;
    }

    private Job claim() {
        var jobs=jdbc.query("""
            select d.* from communication_delivery d
            join communication_campaign c on c.id=d.campaign_id and c.empresa_id=d.empresa_id
            where d.status='PENDING' and c.status='SCHEDULED' and c.scheduled_at<=?
              and (d.next_attempt_at is null or d.next_attempt_at<=?)
            order by d.created_at,d.id limit 1 for update of c,d skip locked
            """,(rs,n)->new Job(rs.getObject("id",UUID.class),rs.getObject("empresa_id",UUID.class),rs.getObject("customer_id",UUID.class),
                rs.getString("email"),rs.getString("subject"),rs.getString("text_body"),rs.getString("html_body"),rs.getString("unsubscribe_url"),rs.getInt("attempts")+1),
                Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        if(jobs.isEmpty()) return null;
        Job job=jobs.getFirst();
        jdbc.update("update communication_delivery set status='SENDING',started_at=?,attempts=attempts+1 where id=? and empresa_id=? and status='PENDING'",
                Timestamp.from(clock.instant()),job.id(),job.company());
        return job;
    }
    private boolean eligible(Job job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from cliente c
              join commercial_contact_channel ch on ch.id=c.preferred_commercial_channel_id and ch.empresa_id=c.empresa_id
              join communication_delivery d on d.customer_id=c.id and d.empresa_id=c.empresa_id
              join communication_campaign ca on ca.id=d.campaign_id and ca.empresa_id=c.empresa_id
              where c.id=? and c.empresa_id=? and c.activo and c.commercial_consent and ch.active and upper(ch.code)='EMAIL'
                and lower(trim(c.email))=? and d.id=? and d.status='SENDING' and ca.status='SCHEDULED'
                and not exists(select 1 from communication_suppression s where s.empresa_id=c.empresa_id and s.email=?))
            """,Boolean.class,job.customer(),job.company(),job.email(),job.id(),job.email()));
    }
    private void finish(Job job,String status,String error,boolean accepted) {
        transactions.executeWithoutResult(tx -> jdbc.update("update communication_delivery set status=?,error_message=?,sent_at=? where id=? and empresa_id=? and status='SENDING'",
                status,error,accepted?Timestamp.from(clock.instant()):null,job.id(),job.company()));
    }
    record Job(UUID id,UUID company,UUID customer,String email,String subject,String text,String html,String unsubscribeUrl,int attempts) { }
}
