package com.tpverp.saas.mail;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DecisionEmailDispatcher {
    private static final int MAX_ATTEMPTS = 5;
    private static final Logger LOG = LoggerFactory.getLogger(DecisionEmailDispatcher.class);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("saas-decision-email").factory());
    private final AtomicBoolean active = new AtomicBoolean();
    private final JdbcTemplate jdbc;
    private final SmtpSettings settings;
    private final SmtpTransport smtp;
    private final TransactionTemplate transactions;

    public DecisionEmailDispatcher(JdbcTemplate jdbc, SmtpSettings settings, SmtpTransport smtp,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.smtp = smtp;
        this.transactions = transactions;
    }

    @Scheduled(initialDelayString="${tpv.saas.decision-email.poll-delay:15000}",
            fixedDelayString="${tpv.saas.decision-email.poll-delay:15000}")
    public void dispatchPending() {
        if (active.compareAndSet(false, true)) worker.execute(() -> {
            try {
                for (int i = 0; i < 10 && dispatchOne(); i++) {
                    // Claim one envelope at a time so a slow relay cannot exhaust other workers' leases.
                }
            } catch (RuntimeException ex) {
                LOG.warn("El proceso de envío de avisos SMTP se reanudará en la siguiente comprobación ({})",
                        ex.getClass().getSimpleName());
            } finally {
                active.set(false);
            }
        });
    }

    @PreDestroy
    void shutdown() { worker.shutdownNow(); }

    boolean dispatchOne() {
        var current = settings.get();
        if (!current.enabled() || !current.configured()) return false;
        UUID token = UUID.randomUUID();
        UUID id = jdbc.query("""
                with candidate as (
                  select request_id from saas_client_decision_email
                  where (status='PENDING' and (next_attempt_at is null or next_attempt_at <= now()))
                     or (status='PROCESSING' and claimed_at <= now() - interval '2 minutes')
                  order by created_at,request_id limit 1 for update skip locked
                )
                update saas_client_decision_email e set status='PROCESSING', claimed_at=now(), claim_token=?
                from candidate c where e.request_id=c.request_id returning e.request_id
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, token);
        if (id == null) return false;
        return Boolean.TRUE.equals(transactions.execute(tx -> deliver(id, token)));
    }

    private record Envelope(String status, String email, String locale, String name, String username, String reason,
                            boolean hasStores, int attempts) {}

    private boolean deliver(UUID id, UUID token) {
        // The shared settings lock serializes disable/credential changes with the actual SMTP send.
        // The row lock prevents reclaim during network I/O even if the original lease expires.
        var config = settings.lockForSending();
        var envelope = jdbc.query("""
                select r.status,r.email,r.locale,r.full_name,r.username,r.review_reason,e.has_stores,e.attempts
                  from saas_client_decision_email e join saas_client_access_request r on r.id=e.request_id
                 where e.request_id=? and e.claim_token=? for update of e
                """, rs -> rs.next() ? new Envelope(rs.getString("status"), rs.getString("email"), rs.getString("locale"),
                    rs.getString("full_name"), rs.getString("username"), rs.getString("review_reason"),
                    rs.getBoolean("has_stores"), rs.getInt("attempts")) : null, id, token);
        if (envelope == null) return false;
        if (!config.available()) {
            jdbc.update("""
                    update saas_client_decision_email set status='PENDING',claimed_at=null,claim_token=null
                    where request_id=?
                    """, id);
            return false;
        }
        var content = ClientDecisionEmail.render("APPROVED".equals(envelope.status()), envelope.locale(),
                envelope.name(), envelope.username(), envelope.reason(), config.view().portalUrl(), envelope.hasStores());
        try {
            smtp.send(config, id, envelope.email(), content);
        } catch (SmtpTransport.Failure ex) {
            int attempts = envelope.attempts() + 1;
            boolean exhausted = attempts >= MAX_ATTEMPTS || "SMTP_DELIVERY_UNCERTAIN".equals(ex.getMessage());
            jdbc.update("""
                    update saas_client_decision_email set status=?,attempts=?,last_error=?,claimed_at=null,claim_token=null,
                      next_attempt_at=case when ? then null else clock_timestamp() + ? * interval '1 minute' end
                    where request_id=?
                    """, exhausted ? "FAILED" : "PENDING", attempts, ex.getMessage(), exhausted, 1 << (attempts - 1), id);
            return true;
        }
        jdbc.update("""
                update saas_client_decision_email set status='DELIVERED',attempts=attempts+1,delivered_at=clock_timestamp(),
                  claimed_at=null,claim_token=null,next_attempt_at=null,last_error=null where request_id=?
                """, id);
        return true;
    }
}
