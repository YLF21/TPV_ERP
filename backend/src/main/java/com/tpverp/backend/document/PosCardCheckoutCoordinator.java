package com.tpverp.backend.document;

import com.tpverp.backend.terminal.CardTerminalResult;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCardCheckoutCoordinator {
    private static final Duration LEASE=Duration.ofSeconds(30);
    private final PosCardCheckoutRepository repository;
    private final Clock clock;
    @Autowired
    public PosCardCheckoutCoordinator(PosCardCheckoutRepository repository){this(repository,Clock.systemUTC());}
    PosCardCheckoutCoordinator(PosCardCheckoutRepository repository,Clock clock){this.repository=repository;this.clock=clock;}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public java.util.Optional<PosCardCheckout> existing(UUID id,UUID terminalId,String hash){
        var found=repository.findById(id); found.ifPresent(c->{
            verify(c,terminalId,hash);
            var now=Instant.now(clock);
            if(c.getStatus()==PaymentTerminalOperationStatus.PENDING&&c.getGatewayLeaseUntil()!=null
                    && c.getGatewayLeaseUntil().isBefore(now)){c.expire(now);repository.save(c);}
        }); return found;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Reservation reserve(UUID id,UUID terminalId,String hash,String snapshot,BigDecimal total,UUID owner){
        return reserve(id,terminalId,hash,snapshot,total,owner,"SYSTEM");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Reservation reserve(UUID id,UUID terminalId,String hash,String snapshot,BigDecimal total,UUID owner,String requestedBy){
        var now=Instant.now(clock);
        boolean acquired=repository.reserve(id,terminalId,hash,snapshot,Money.euros(total),owner,now,now.plus(LEASE))==1;
        var checkout=repository.findById(id).orElseThrow();checkout.requestedBy(requestedBy,now);repository.save(checkout);
        verify(checkout,terminalId,hash);
        if (!acquired && checkout.getStatus()==PaymentTerminalOperationStatus.PENDING
                && checkout.getGatewayLeaseUntil()!=null && checkout.getGatewayLeaseUntil().isBefore(now)) {
            checkout.expire(now); repository.save(checkout);
        }
        return new Reservation(repository.findById(id).orElseThrow(),acquired);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Reservation reserve(UUID id,UUID terminalId,String hash,String snapshot,BigDecimal total,UUID owner,RecoveryIdentity identity){
        var result=reserve(id,terminalId,hash,snapshot,total,owner,identity.username());var checkout=repository.findById(id).orElseThrow();
        checkout.recoveryIdentity(identity.userId(),identity.storeId(),identity.companyId(),identity.username(),Instant.now(clock));repository.save(checkout);
        return new Reservation(repository.findById(id).orElseThrow(),result.acquired());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public PosCardCheckout recordResult(UUID id,UUID owner,CardTerminalResult result){
        var checkout=repository.findById(id).orElseThrow();
        checkout.recordGatewayResult(owner,result,Instant.now(clock)); return repository.save(checkout);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean claimTicket(UUID id,UUID owner){var now=Instant.now(clock);return repository.claimTicket(id,owner,now,now.plus(LEASE))==1;}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public PosCardCheckout linkDocument(UUID id,UUID documentId,String number){var c=repository.findById(id).orElseThrow();c.linkDocument(documentId,number,Instant.now(clock));return repository.save(c);}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void releaseTicket(UUID id,UUID owner){var c=repository.findById(id).orElseThrow();c.releaseTicket(owner,Instant.now(clock));repository.save(c);}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void diagnostic(UUID id,String message){var c=repository.findById(id).orElseThrow();c.diagnostic(message,Instant.now(clock));repository.save(c);}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public PosCardCheckout recoverApproved(UUID id,String reference,String authorization,String message){var c=repository.findById(id).orElseThrow();
        c.recoverApproved(reference,authorization,message,Instant.now(clock));return repository.save(c);}

    private static void verify(PosCardCheckout c,UUID terminal,String hash){
        if(!c.getTerminalId().equals(terminal))throw new IllegalStateException("El checkout pertenece a otra terminal");
        if(!c.getRequestHash().equals(hash))throw new IllegalStateException("El checkout ya pertenece a otra venta");
    }
    public record Reservation(PosCardCheckout checkout,boolean acquired){}
    public record RecoveryIdentity(UUID userId,UUID storeId,UUID companyId,String username){}
}
