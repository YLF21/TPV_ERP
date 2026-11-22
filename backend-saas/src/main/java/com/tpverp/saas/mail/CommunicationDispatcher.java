package com.tpverp.saas.mail;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class CommunicationDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(CommunicationDispatcher.class);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("saas-communications").factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final JdbcTemplate jdbc;
    private final SmtpSettings settings;
    private final SmtpTransport transport;
    private final TransactionTemplate transactions;
    private final CommunicationAudience audience;

    CommunicationDispatcher(JdbcTemplate jdbc, SmtpSettings settings, SmtpTransport transport,
                            TransactionTemplate transactions, CommunicationAudience audience) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.transport = transport;
        this.transactions = transactions;
        this.audience = audience;
    }

    @Scheduled(initialDelayString="${tpv.saas.communications.poll-delay:1000}", fixedDelayString="${tpv.saas.communications.poll-delay:1000}")
    void poll() {
        if (busy.compareAndSet(false,true)) worker.execute(() -> {
            try { dispatchOne(); }
            catch (RuntimeException ex) { LOG.warn("Proceso de comunicaciones interrumpido ({})",ex.getClass().getSimpleName()); }
            finally { busy.set(false); }
        });
    }

    @PreDestroy void shutdown() { worker.shutdownNow(); }

    boolean dispatchOne() {
        // A crash after relay acceptance can precede commit. Never turn an expired claim back into a pending send.
        jdbc.update("""
                update saas_communication_recipient set status='UNCERTAIN',last_error='SMTP_DELIVERY_UNCERTAIN',claimed_at=null
                 where id in (select id from saas_communication_recipient where status='SENDING'
                   and claimed_at < clock_timestamp()-interval '2 minutes' for update skip locked)
                """);
        var claim = transactions.execute(tx -> claim());
        if (claim == null) return false;
        return Boolean.TRUE.equals(transactions.execute(tx -> deliver(claim)));
    }

    private record Claim(UUID id, int attempt) {}
    private Claim claim() {
        // The durable pacing row enforces one claim per second across all replicas.
        var slot = jdbc.query("""
                select singleton from saas_communication_pacing where next_claim_at<=clock_timestamp() for update skip locked
                """, (rs,i) -> rs.getBoolean(1));
        if (slot.isEmpty()) return null;
        UUID id = nextReadyRecipient();
        if (id == null) return null;
        int attempt = jdbc.queryForObject("""
                update saas_communication_recipient set status='SENDING',claimed_at=clock_timestamp(),attempts=attempts+1
                where id=? returning attempts
                """, Integer.class,id);
        jdbc.update("update saas_communication_pacing set next_claim_at=clock_timestamp()+interval '1 second' where singleton");
        return new Claim(id,attempt);
    }

    private record Candidate(UUID recipient, UUID sender) {}
    private UUID nextReadyRecipient() {
        var unavailable = new ArrayList<UUID>();
        while (true) {
            var candidate = jdbc.query("""
                    select r.id,c.sender_account_id from saas_communication_recipient r
                      join saas_communication c on c.id=r.communication_id
                     where r.status='PENDING' and c.cancelled_at is null and c.sender_account_id <> all(?::uuid[])
                     order by c.queued_at,r.id limit 1 for update of r skip locked
                    """,rs -> rs.next() ? new Candidate(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)) : null,
                    (Object)unavailable.toArray(UUID[]::new));
            if (candidate == null) return null;
            if (settings.get(candidate.sender()).testReady()) return candidate.recipient();
            // Temporarily unavailable accounts retain their pending envelopes, and other senders can progress.
            unavailable.add(candidate.sender());
        }
    }

    private record Envelope(String email, String subject, String html, String text,
                            String fromAddress, String fromName, String replyTo, UUID senderAccountId, boolean cancelled) {}

    private boolean deliver(Claim claim) {
        // Hold the row lock for the delivery so a slow live worker cannot be marked uncertain by another replica.
        var envelope = jdbc.query("""
                select r.email,c.subject,c.html,c.plain_text,c.from_address,c.from_name,c.reply_to,c.sender_account_id,c.cancelled_at
                  from saas_communication_recipient r join saas_communication c on c.id=r.communication_id
                 where r.id=? and r.status='SENDING' and r.attempts=? for update of r
                """,rs -> rs.next() ? new Envelope(rs.getString("email"),
                        rs.getString("subject"),rs.getString("html"),rs.getString("plain_text"),rs.getString("from_address"),
                        rs.getString("from_name"),rs.getString("reply_to"),rs.getObject("sender_account_id",UUID.class),rs.getTimestamp("cancelled_at") != null) : null,
                claim.id(),claim.attempt());
        if (envelope == null) return false;
        if (envelope.cancelled()) {
            finish(claim.id(),"CANCELLED",null);
            return true;
        }
        var saved = settings.lockForSending(envelope.senderAccountId());
        var config = saved.view();
        if (!config.testReady()) {
            // Cancellation can commit after the envelope was locked. Never recreate pending work after that.
            finish(claim.id(),"FAILED","SMTP_NOT_READY");
            return true;
        }
        if (!Objects.equals(envelope.fromAddress(),config.fromAddress()) || !Objects.equals(envelope.fromName(),config.fromName())
                || !Objects.equals(envelope.replyTo(),config.replyTo())) {
            finish(claim.id(),"FAILED","SMTP_SENDER_CHANGED");
            return true;
        }
        if (!audience.lockEligibleRecipient(claim.id())) {
            finish(claim.id(),"CANCELLED","COMMUNICATION_RECIPIENT_UNAVAILABLE");
            return true;
        }
        try {
            transport.sendManual(saved,claim.id(),envelope.email(),
                    new ClientDecisionEmail.Content(envelope.subject(),envelope.text(),envelope.html()));
        } catch (SmtpTransport.Failure ex) {
            String status = "SMTP_DELIVERY_UNCERTAIN".equals(ex.getMessage()) ? "UNCERTAIN" : "FAILED";
            finish(claim.id(),status,ex.getMessage());
            return true;
        }
        finish(claim.id(),"SENT",null);
        return true;
    }

    private void finish(UUID id, String status, String reason) {
        jdbc.update("""
                update saas_communication_recipient set status=?,last_error=?,claimed_at=null,
                  sent_at=case when ?='SENT' then clock_timestamp() else null end where id=?
                """,status,reason,status,id);
    }
}
