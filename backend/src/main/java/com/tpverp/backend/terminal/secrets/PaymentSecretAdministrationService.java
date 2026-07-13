package com.tpverp.backend.terminal.secrets;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.terminal.TerminalPaymentConfigurationRepository;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class PaymentSecretAdministrationService {
    private final PaymentSecretStore store;
    private final AuditService audit;
    private final TerminalPaymentConfigurationRepository configurations;
    private final PaymentSecretOwnerResolver owners;
    PaymentSecretAdministrationService(PaymentSecretStore store, AuditService audit,TerminalPaymentConfigurationRepository configurations){this(store,audit,configurations,null);}
    @Autowired public PaymentSecretAdministrationService(PaymentSecretStore store, AuditService audit,TerminalPaymentConfigurationRepository configurations,PaymentSecretOwnerResolver owners){this.store=store;this.audit=audit;this.configurations=configurations;this.owners=owners;}
    @Transactional public PaymentSecretStore.SecretReferenceView create(String provider,char[] material){
        var result=store.create(provider,material); audit.record("PAYMENT_SECRET_CREATED",AuditResult.EXITO,details(result)); return result;
    }
    @Transactional public PaymentSecretStore.SecretReferenceView rotate(String reference,char[] material){
        var result=store.rotate(reference,material);var terminalId=owners==null?null:owners.current().terminalId();if(terminalId!=null)configurations.updateSecretVersion(reference,result.version(),terminalId); audit.record("PAYMENT_SECRET_ROTATED",AuditResult.EXITO,details(result)); return result;
    }
    @Transactional public void delete(String reference){var inUse=owners==null?configurations.existsBySecretReference(reference):configurations.existsBySecretReferenceAndTerminalId(reference,owners.current().terminalId());if(inUse)throw new IllegalStateException("message.payment_terminal.secret_in_use");store.delete(reference);audit.record("PAYMENT_SECRET_DELETED",AuditResult.EXITO,Map.of("reference",safeReference(reference)));}
    private static Map<String,Object> details(PaymentSecretStore.SecretReferenceView view){return Map.of("reference",view.reference(),"version",view.version());}
    private static String safeReference(String value){if(value==null||!value.matches("pts_[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid opaque secret reference");return value;}
}
