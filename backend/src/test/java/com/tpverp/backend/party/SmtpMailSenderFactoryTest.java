package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.mockito.Mockito.*;
import com.tpverp.backend.mailoauth.MailOAuthService;
import com.tpverp.backend.mailoauth.MailOAuthProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailPreparationException;

class SmtpMailSenderFactoryTest {
    private final MailSecretCipher cipher = new MailSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test void decryptsOnlyAtTransportBoundaryAndRequiresTlsIdentityAndTimeouts() {
        var companyId = UUID.randomUUID();
        var config = new MemberSmtpSettings(companyId, new MemberSmtpSettingsCommand(true, "smtp.example.org", 587,
                "store@example.org", cipher.encrypt("secret", companyId), "store@example.org", "Store", true, false));
        var sender = (JavaMailSenderImpl) new SmtpMailSenderFactory(cipher).create(config);
        assertThat(sender.getPassword()).isEqualTo("secret");
        assertThat(config.getPassword()).startsWith("enc:v1:");
        assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.connectiontimeout", "10000")
                .containsEntry("mail.smtp.timeout", "15000");
    }

    @Test void refusesLegacyPlaintextAtSendBoundary() {
        var config = new MemberSmtpSettings(UUID.randomUUID(), new MemberSmtpSettingsCommand(true, "smtp.example.org",587,
                "user", "legacy-secret", "store@example.org", "Store", true,false));
        assertThatThrownBy(() -> new SmtpMailSenderFactory(cipher).create(config))
                .hasMessage("message.smtp.credentials_require_resave");
    }

    @Test void rejectsConflictingOrMissingTlsConfiguration() {
        assertThatThrownBy(() -> new MemberSmtpSettings(UUID.randomUUID(), new MemberSmtpSettingsCommand(
                true,"smtp.example.org",587,"user",null,"store@example.org",null,true,true)))
                .hasMessage("message.smtp.tls_conflict");
        assertThatThrownBy(() -> new MemberSmtpSettings(UUID.randomUUID(), new MemberSmtpSettingsCommand(
                true,"smtp.example.org",587,"user",null,"store@example.org",null,false,false)))
                .hasMessage("message.smtp.tls_required");
    }

    @SuppressWarnings("unchecked")
    @Test void oauthRefreshFailureIsConfirmedPreSubmissionFailureWithoutSecretLeak() {
        var oauth = mock(MailOAuthService.class);
        var provider = (ObjectProvider<MailOAuthService>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(oauth);
        UUID company = UUID.randomUUID();
        var config = new MemberSmtpSettings(company, new MemberSmtpSettingsCommand(
                true,"smtp.example.org",587,"user",null,"store@example.org",null,true,false));
        when(oauth.smtpCredentials(company)).thenThrow(new IllegalStateException("provider secret-token"));
        assertThatThrownBy(() -> new SmtpMailSenderFactory(cipher, provider).create(config))
                .isInstanceOf(MailPreparationException.class).hasMessage("message.smtp.configuration_failed").hasNoCause()
                .satisfies(failure -> { assertThat(MailFailureClassifier.isUncertain(failure)).isFalse();
                    assertThat(MailFailureClassifier.canRetry(failure)).isFalse(); });
    }

    @SuppressWarnings("unchecked")
    @Test void oauthSelectsGmailApiAndMicrosoftXoauth2WithoutUsingLegacyPassword() {
        var oauth = mock(MailOAuthService.class);
        var provider = (ObjectProvider<MailOAuthService>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(oauth);
        UUID company = UUID.randomUUID();
        var config = new MemberSmtpSettings(company, new MemberSmtpSettingsCommand(
                true,"smtp.example.org",587,"user","obsolete-plaintext","store@example.org",null,true,false));
        var google = new MailOAuthService.SmtpCredentials("google@example.org","token-google","smtp.gmail.com",587,MailOAuthProvider.GOOGLE);
        var googleSender = mock(org.springframework.mail.javamail.JavaMailSender.class);
        when(oauth.smtpCredentials(company)).thenReturn(java.util.Optional.of(google));
        when(oauth.googleSender(google)).thenReturn(googleSender);
        var factory = new SmtpMailSenderFactory(cipher, provider);
        assertThat(factory.create(config)).isSameAs(googleSender);
        var microsoft = new MailOAuthService.SmtpCredentials("microsoft@example.org","token-microsoft","smtp.office365.com",587,MailOAuthProvider.MICROSOFT);
        when(oauth.smtpCredentials(company)).thenReturn(java.util.Optional.of(microsoft));
        var sender = (JavaMailSenderImpl) factory.create(config);
        assertThat(sender.getHost()).isEqualTo("smtp.office365.com");
        assertThat(sender.getUsername()).isEqualTo("microsoft@example.org");
        assertThat(sender.getPassword()).isEqualTo("token-microsoft");
        assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth.mechanisms","XOAUTH2")
                .containsEntry("mail.smtp.auth.login.disable","true")
                .containsEntry("mail.smtp.auth.plain.disable","true")
                .containsEntry("mail.smtp.starttls.required","true")
                .containsEntry("mail.smtp.ssl.checkserveridentity","true");
    }
}
