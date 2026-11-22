package com.tpverp.saas.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.saas.admin.IntegrationSecretCipher;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SmtpTransportTest {
    private final IntegrationSecretCipher cipher = mock(IntegrationSecretCipher.class);
    private final MailOAuthService oauth = mock(MailOAuthService.class);
    private final MailOAuthClient client = mock(MailOAuthClient.class);
    private final SmtpTransport transport = new SmtpTransport(cipher, oauth, client);
    private final ClientDecisionEmail.Content content = new ClientDecisionEmail.Content("Prueba", "Prueba", "<p>Prueba</p>");

    @Test
    void explicitTestUsesSameTransportWhileAutomaticDeliveryRemainsDisabled() throws Exception {
        var saved = saved(true);
        var id = UUID.randomUUID();
        when(oauth.accessToken(saved.view().id(), MailOAuthProvider.GOOGLE,"sender@gmail.com")).thenReturn("synthetic-token");
        assertThatThrownBy(() -> transport.send(saved,id,"recipient@example.test",content))
                .isInstanceOf(IllegalStateException.class).hasMessage("SMTP_DISABLED");
        verifyNoInteractions(cipher,oauth,client);
        transport.sendTest(saved,id,"recipient@example.test",content);
        var message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(client).sendGoogle(message.capture(),eq("synthetic-token"));
        assertThat(message.getValue().getAllRecipients()).hasSize(1);
        assertThat(message.getValue().getAllRecipients()[0].toString()).isEqualTo("recipient@example.test");
        assertThat(saved.view().enabled()).isFalse();
        assertThat(saved.view().portalUrl()).isEmpty();
    }

    @Test
    void explicitTestRejectsAnIncompleteConnectionBeforeAccessingCredentialsOrNetwork() {
        assertThatThrownBy(() -> transport.sendTest(saved(false),UUID.randomUUID(),"recipient@example.test",content))
                .isInstanceOf(IllegalStateException.class).hasMessage("SMTP_NOT_READY");
        verifyNoInteractions(cipher,oauth,client);
    }

    private SmtpSettings.Saved saved(boolean testReady) {
        return new SmtpSettings.Saved(new SmtpSettings.View(false,"smtp.gmail.com",587,SmtpSettings.Security.STARTTLS,
                "sender@gmail.com",false,"sender@gmail.com","esPOS","","",1,true,false,testReady,MailOAuthProvider.GOOGLE,UUID.randomUUID(),true),null);
    }
}
