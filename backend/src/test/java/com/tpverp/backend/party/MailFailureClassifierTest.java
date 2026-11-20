package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.*;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

class MailFailureClassifierTest {
    @Test void retriesOnlyKnownConnectionFailuresAndFindsNestedProviderExceptions() {
        for (Exception cause : new Exception[]{new ConnectException("private host"), new UnknownHostException("private host")}) {
            assertThat(MailFailureClassifier.canRetry(new MailSendException("private",cause))).isTrue();
        }
        var nested = new MailSendException(Map.of("message",new ConnectException("private host")));
        assertThat(MailFailureClassifier.canRetry(nested)).isTrue();
        assertThat(MailFailureClassifier.isUncertain(new MailSendException("lost DATA reply",new SocketTimeoutException()))).isTrue();
    }
    @Test void partiallyAcceptedMessageNeverRetriesEvenWhenAnotherRecipientHasConnectionFailure() throws Exception {
        var accepted = new SendFailedException("partial",new ConnectException(),
                new Address[]{new InternetAddress("accepted@example.org")},null,
                new Address[]{new InternetAddress("rejected@example.org")});
        var failure = new MailSendException(Map.of("message",accepted));
        assertThat(MailFailureClassifier.canRetry(failure)).isFalse();
        assertThat(MailFailureClassifier.isUncertain(failure)).isTrue();
    }
    @Test void definiteRecipientRejectionAndTlsHandshakeFailureAreCorrectableNotAmbiguous() throws Exception {
        var rejected = new SendFailedException("rejected",null,null,null,
                new Address[]{new InternetAddress("rejected@example.org")});
        for (var cause : new Exception[]{rejected,new SSLHandshakeException("wrong certificate")}) {
            var failure = new MailSendException("provider details",cause);
            assertThat(MailFailureClassifier.canRetry(failure)).isFalse();
            assertThat(MailFailureClassifier.isUncertain(failure)).isFalse();
        }
    }
    @Test void authenticationAndKnownLocalConfigurationFailuresDoNotExposeSecrets() {
        var authentication = new MailAuthenticationException("private username/password response");
        assertThat(MailFailureClassifier.message(authentication)).isEqualTo("message.smtp.authentication_failed");
        assertThat(MailFailureClassifier.isUncertain(authentication)).isFalse();
        assertThat(MailFailureClassifier.isUncertain(new IllegalStateException("message.smtp.encryption_key_missing"))).isFalse();
        assertThat(MailFailureClassifier.isUncertain(new IllegalStateException("unexpected after submission"))).isTrue();
    }
}
