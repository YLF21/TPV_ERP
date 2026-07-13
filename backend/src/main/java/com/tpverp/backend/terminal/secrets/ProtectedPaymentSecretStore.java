package com.tpverp.backend.terminal.secrets;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public final class ProtectedPaymentSecretStore implements PaymentSecretStore {
    private final PaymentSecretReferenceRepository repository;
    private final SecretProtector protector;
    private final Clock clock;
    private final PaymentSecretOwnerResolver owners;
    ProtectedPaymentSecretStore(PaymentSecretReferenceRepository repository, SecretProtector protector, Clock clock) {
        this(repository,protector,clock,null);
    }
    public ProtectedPaymentSecretStore(PaymentSecretReferenceRepository repository, SecretProtector protector, Clock clock,PaymentSecretOwnerResolver owners) {
        this.repository=repository; this.protector=protector; this.clock=clock;this.owners=owners;
    }
    @Override @Transactional public SecretReferenceView create(String provider, char[] material) {
        var reference="pts_"+UUID.randomUUID().toString().replace("-","");
        var entity=PaymentSecretReference.createVersion(UUID.randomUUID(),owner(),reference,providerName(provider),1,protect(material),Instant.now(clock));
        repository.save(entity); return view(entity);
    }
    @Override @Transactional public SecretReferenceView rotate(String reference, char[] material) {
        var current=requiredForUpdate(reference); var now=Instant.now(clock); var provider=current.getProvider(); var version=current.getVersion()+1;
        current.retire(now);
        repository.flush();
        var next=PaymentSecretReference.createVersion(UUID.randomUUID(), owner(),current.getOpaqueReference(), provider, version, protect(material), now);
        repository.save(next); return view(next);
    }
    @Override @Transactional public void delete(String reference) { requiredForUpdate(reference).retire(Instant.now(clock)); }
    @Override @Transactional(readOnly=true) public byte[] resolve(String reference) {
        try { return protector.unprotect(required(reference).getProtectedValue()); }
        catch (PaymentSecretUnavailableException ex) { throw ex; }
        catch (RuntimeException ex) { throw new PaymentSecretUnavailableException("Payment secret protection unavailable",ex); }
    }
    @Override @Transactional(readOnly=true) public SecretMetadata describe(String reference){var value=required(reference);return new SecretMetadata(value.getOpaqueReference(),value.getProvider(),value.getVersion());}
    private PaymentSecretReference required(String reference){var o=owner();return repository.findActiveScoped(reference,o.companyId(),o.storeId(),o.terminalId())
            .orElseThrow(()->new PaymentSecretUnavailableException("Payment secret reference unavailable"));}
    private PaymentSecretReference requiredForUpdate(String reference){var o=owner();return repository.findActiveForUpdate(reference,o.companyId(),o.storeId(),o.terminalId())
            .orElseThrow(()->new PaymentSecretUnavailableException("Payment secret reference unavailable"));}
    private PaymentSecretOwnerScope owner(){return owners==null?PaymentSecretOwnerScope.testing():owners.current();}
    private byte[] protect(char[] material){
        if(material==null||material.length==0)throw new IllegalArgumentException("Secret material is required");
        var bytes=new String(material).getBytes(StandardCharsets.UTF_8);
        try{return protector.protect(bytes);}catch(RuntimeException ex){throw new PaymentSecretUnavailableException("Payment secret protection unavailable",ex);}
        finally{Arrays.fill(bytes,(byte)0);Arrays.fill(material,'\0');}
    }
    private static String providerName(String provider){var value=Objects.requireNonNull(provider).trim().toUpperCase(Locale.ROOT);if(value.isBlank())throw new IllegalArgumentException("provider");return value;}
    private static SecretReferenceView view(PaymentSecretReference entity){return new SecretReferenceView(entity.getOpaqueReference(),entity.getVersion(),true);}
}
