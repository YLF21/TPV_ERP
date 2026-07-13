package com.tpverp.backend.terminal.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentSecretAdministrationServiceTest {
    @Test
    void resultAndAuditExposeOnlyOpaqueReferenceVersionAndPresence() throws Exception {
        var store=mock(PaymentSecretStore.class); var audit=mock(AuditService.class);
        when(store.create(eq("REDSYS_TPV_PC"),any())).thenReturn(new PaymentSecretStore.SecretReferenceView("pts_0123456789abcdef0123456789abcdef",1,true));
        var result=new PaymentSecretAdministrationService(store,audit,mock(com.tpverp.backend.terminal.TerminalPaymentConfigurationRepository.class)).create("REDSYS_TPV_PC","super-secret".toCharArray());
        var json=new ObjectMapper().writeValueAsString(result);

        assertThat(json).contains("pts_0123456789abcdef0123456789abcdef","\"version\":1","\"present\":true")
                .doesNotContain("super-secret","material","protectedValue");
        verify(audit).record(eq("PAYMENT_SECRET_CREATED"),eq(AuditResult.EXITO),eq(Map.of(
                "reference","pts_0123456789abcdef0123456789abcdef","version",1)));
    }
    @Test void refusesDeletionWhileConfigurationUsesReference(){var store=mock(PaymentSecretStore.class);var audit=mock(AuditService.class);var configurations=mock(com.tpverp.backend.terminal.TerminalPaymentConfigurationRepository.class);var ref="pts_0123456789abcdef0123456789abcdef";when(configurations.existsBySecretReference(ref)).thenReturn(true);org.assertj.core.api.Assertions.assertThatThrownBy(()->new PaymentSecretAdministrationService(store,audit,configurations).delete(ref)).isInstanceOf(IllegalStateException.class).hasMessage("message.payment_terminal.secret_in_use");org.mockito.Mockito.verifyNoInteractions(store);}
    @Test void rotationUpdatesInUseConfigurationVersionInSameScopedServiceCall(){var store=mock(PaymentSecretStore.class);var audit=mock(AuditService.class);var configurations=mock(com.tpverp.backend.terminal.TerminalPaymentConfigurationRepository.class);var owners=mock(PaymentSecretOwnerResolver.class);var terminal=java.util.UUID.randomUUID();when(owners.current()).thenReturn(new PaymentSecretOwnerScope(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),terminal));var ref="pts_0123456789abcdef0123456789abcdef";when(store.rotate(eq(ref),any())).thenReturn(new PaymentSecretStore.SecretReferenceView(ref,2,true));new PaymentSecretAdministrationService(store,audit,configurations,owners).rotate(ref,"new-secret".toCharArray());verify(configurations).updateSecretVersion(ref,2,terminal);}
}
