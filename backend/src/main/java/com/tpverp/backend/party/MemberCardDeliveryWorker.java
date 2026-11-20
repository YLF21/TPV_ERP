package com.tpverp.backend.party;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Claim is committed before SMTP; a crashed sender is never blindly retried. */
@Service
public class MemberCardDeliveryWorker {
    private final MemberCardDeliveryRepository deliveries;
    private final MemberCardSender sender;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public MemberCardDeliveryWorker(MemberCardDeliveryRepository deliveries, MemberCardSender sender,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.sender = sender;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int runOnce() {
        transactions.executeWithoutResult(tx -> deliveries
                .findByStatusAndClaimedAtBefore(MemberCardDeliveryStatus.ENVIANDO, clock.instant().minusSeconds(600))
                .forEach(MemberCardDelivery::markUncertain));
        int sent = 0;
        for (int i = 0; i < 20; i++) {
            var delivery = transactions.execute(tx -> deliveries.claimNext(clock.instant()).map(value -> {
                value.claim(clock.instant());
                value.getMember().getCompany().getRazonSocial();
                return value;
            }).orElse(null));
            if (delivery == null) break;
            int claimedAttempt = delivery.getAttemptCount();
            RuntimeException failure = null;
            try { sender.send(delivery); }
            catch (RuntimeException exception) { failure = exception; }
            var sendFailure = failure;
            transactions.executeWithoutResult(tx -> {
                var current = deliveries.findById(delivery.getId()).orElseThrow();
                if (current.getStatus() != MemberCardDeliveryStatus.ENVIANDO
                        || current.getAttemptCount() != claimedAttempt) return;
                if (sendFailure == null) current.markSent(clock.instant());
                else if (MailFailureClassifier.canRetry(sendFailure)) current.retryLater(clock.instant());
                else if (MailFailureClassifier.isUncertain(sendFailure)) current.markUncertain();
                else current.markError(MailFailureClassifier.message(sendFailure));
            });
            if (failure == null) sent++;
        }
        return sent;
    }
}
