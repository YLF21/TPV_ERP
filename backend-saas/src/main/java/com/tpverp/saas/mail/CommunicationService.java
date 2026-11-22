package com.tpverp.saas.mail;

import static com.tpverp.saas.mail.Communications.*;
import com.tpverp.saas.admin.AdminAuditService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class CommunicationService {
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    private final CommunicationAudience audience;
    private final CommunicationStore store;
    private final SmtpSettings settings;
    private final SmtpTransport transport;
    private final TransactionTemplate transactions;

    CommunicationService(JdbcTemplate jdbc, AdminAuditService audit, CommunicationAudience audience, CommunicationStore store,
                         SmtpSettings settings, SmtpTransport transport, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.audience = audience;
        this.store = store;
        this.settings = settings;
        this.transport = transport;
        this.transactions = transactions;
    }

    @Transactional
    public Detail save(UUID existing, Draft input) {
        if (input.subject().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        var body = CommunicationHtml.sanitize(input.html());
        String subject = input.subject().strip();
        var ids = List.copyOf(new LinkedHashSet<>(input.recipientIds()));
        if (input.audienceMode() == Mode.ALL && !ids.isEmpty()) throw invalid();
        UUID id = existing != null ? existing : UUID.randomUUID();
        UUID sender = input.senderAccountId();
        if (existing == null) {
            if (sender == null) throw invalid();
            settings.lockForSending(sender);
            jdbc.update("""
                    insert into saas_communication(id,subject,html,plain_text,audience_mode,recipient_ids,created_by,sender_account_id)
                    values(?,?,?,?,?,?::uuid[],?,?)
                    """,id,subject,body.html(),body.text(),input.audienceMode().name(),ids.toArray(UUID[]::new),audit.currentUsername(),sender);
        } else {
            var draft = store.lock(id);
            requireDraft(draft,input.version());
            ids = store.retainLegacySelections(draft.ids(),ids);
            if (sender == null) sender = draft.senderAccountId();
            settings.lockForSending(sender);
            jdbc.update("delete from saas_communication_recipient where communication_id=?",id);
            jdbc.update("""
                    update saas_communication set subject=?,html=?,plain_text=?,audience_mode=?,recipient_ids=?::uuid[],
                        updated_at=clock_timestamp(),version=version+1,review_id=null,reviewed_count=0,
                        smtp_version=null,from_address=null,from_name=null,reply_to=null,sender_account_id=? where id=?
                    """,subject,body.html(),body.text(),input.audienceMode().name(),ids.toArray(UUID[]::new),sender,id);
        }
        log(existing == null ? "CREATE_COMMUNICATION" : "UPDATE_COMMUNICATION", id, null);
        return store.detail(id);
    }

    @Transactional
    public Review review(UUID id, long version) {
        var draft = store.lock(id);
        requireDraft(draft,version);
        requireContent(draft.subject(),draft.text());
        var config = settings.lockForSending(draft.senderAccountId()).view();
        jdbc.update("delete from saas_communication_recipient where communication_id=?",id);
        var counts = audience.snapshot(id,draft.mode(),draft.ids());
        UUID review = UUID.randomUUID();
        jdbc.update("""
                update saas_communication set review_id=?,reviewed_count=?,smtp_version=?,from_address=?,from_name=?,reply_to=? where id=?
                """,review,counts.included(),config.version(),config.fromAddress(),config.fromName(),config.replyTo(),id);
        return new Review(review,version,draft.subject(),draft.html(),draft.text(), counts.included(),counts.omitted(),
                counts.duplicate(),config.testReady(),config.fromAddress(),config.fromName(),config.replyTo(),config.version(),config.id());
    }

    @Transactional
    public Detail send(UUID id, Confirmation confirmation) {
        var draft = store.lock(id);
        if (!confirmation.reviewId().equals(draft.reviewId()) || confirmation.recipientCount() != draft.reviewedCount())
            throw staleReview();
        if (draft.queued() && Objects.equals(draft.queuedVersion(),confirmation.version())) return store.detail(id);
        requireDraft(draft,confirmation.version());
        var config = settings.lockForSending(draft.senderAccountId()).view();
        if (!Objects.equals(draft.smtpVersion(),config.version())) throw staleReview();
        if (!config.testReady()) throw SmtpSettings.conflict("SMTP_NOT_READY","Configure el correo antes de enviar");
        if (!audience.stillEligible(id,null)) throw staleReview();
        int queued = jdbc.update("update saas_communication_recipient set status='PENDING' where communication_id=? and status='REVIEW'",id);
        if (queued == 0 || queued != draft.reviewedCount()) throw staleReview();
        jdbc.update("""
                update saas_communication set queued_at=clock_timestamp(),updated_at=clock_timestamp(),
                    queued_version=version,version=version+1 where id=?
                """,id);
        log("QUEUE_COMMUNICATION",id,"recipients=" + queued);
        return store.detail(id);
    }

    @Transactional
    public Detail cancel(UUID id, long version) {
        var state = store.lock(id);
        if (state.cancelled()) return store.detail(id);
        if (state.version() != version) throw changed();
        if (!state.queued()) throw wrongState();
        int count = jdbc.update("""
                update saas_communication_recipient set status='CANCELLED'
                where communication_id=? and status='PENDING'
                """,id);
        if (count == 0) throw wrongState();
        jdbc.update("update saas_communication set cancelled_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1 where id=?",id);
        log("CANCEL_COMMUNICATION",id,"cancelled=" + count);
        return store.detail(id);
    }

    private record Attempt(String status, int count) {}
    @Transactional
    public Detail retry(UUID id, UUID recipient, Retry retry) {
        var state = store.lock(id);
        if (!state.queued() || state.cancelled()) throw wrongState();
        settings.lockForSending(state.senderAccountId());
        var attempt = jdbc.query("select status,attempts from saas_communication_recipient where communication_id=? and id=? for update",
                (rs,i) -> new Attempt(rs.getString("status"),rs.getInt("attempts")),id,recipient)
                .stream().findFirst().orElseThrow(Communications::missing);
        if (attempt.count() != retry.attempts()) throw changed();
        if (!List.of("FAILED","UNCERTAIN").contains(attempt.status())) throw wrongState();
        if ("UNCERTAIN".equals(attempt.status()) && !retry.confirmUncertain())
            throw SmtpSettings.conflict("COMMUNICATION_CONFIRM_UNCERTAIN","Confirme el riesgo de entrega duplicada");
        if (!audience.stillEligible(id,recipient))
            throw SmtpSettings.conflict("COMMUNICATION_RECIPIENT_UNAVAILABLE","El destinatario ya no está disponible");
        jdbc.update("update saas_communication_recipient set status='PENDING',last_error=null where id=?",recipient);
        jdbc.update("update saas_communication set updated_at=clock_timestamp(),version=version+1 where id=?",id);
        log("RETRY_COMMUNICATION_RECIPIENT",id,"recipientId=" + recipient + ", priorState=" + attempt.status());
        return store.detail(id);
    }

    public void test(UUID id, TestMail input) {
        SmtpSettings.validateAddress(input.recipient());
        var draft = store.detail(id);
        if (!"DRAFT".equals(draft.summary().status())) throw wrongState();
        if (draft.summary().version() != input.version()) throw changed();
        requireContent(draft.summary().subject(),draft.text());
        long configVersion = settings.reserveTest(draft.senderAccountId());
        var failure = transactions.execute(tx -> {
            var locked = store.lock(id);
            requireDraft(locked,input.version());
            var config = settings.lockForSending(locked.senderAccountId());
            if (!config.view().testReady() || config.view().version() != configVersion)
                throw SmtpSettings.conflict("SMTP_NOT_READY","La configuración de correo ha cambiado");
            try {
                transport.sendTest(config,UUID.randomUUID(),input.recipient(),
                        new ClientDecisionEmail.Content(locked.subject(),locked.text(),locked.html()));
            } catch (SmtpTransport.Failure ex) {
                log("TEST_COMMUNICATION",id,ex.getMessage());
                return ex.getMessage();
            }
            log("TEST_COMMUNICATION",id,"accepted");
            return null;
        });
        if (failure != null) throw SmtpSettings.problem(HttpStatus.BAD_GATEWAY,failure,"No se pudo confirmar la prueba");
    }

    private static void requireDraft(CommunicationStore.Locked draft, Long version) {
        if (version == null || version != draft.version()) throw changed();
        if (draft.queued()) throw wrongState();
    }

    private void log(String action, UUID id, String details) {
        audit.log(action,"COMMUNICATION",id.toString(),details);
    }
}
