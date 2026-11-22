package com.tpverp.saas.mail;

import com.tpverp.saas.admin.IntegrationSecretCipher;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

@Component
public class SmtpTransport {
    private final IntegrationSecretCipher cipher;
    private final MailOAuthService oauth;
    private final MailOAuthClient client;
    public SmtpTransport(IntegrationSecretCipher cipher, MailOAuthService oauth, MailOAuthClient client) {
        this.cipher = cipher;
        this.oauth = oauth;
        this.client = client;
    }

    static final class Failure extends RuntimeException {
        Failure(String code) { super(code); }
    }

    void send(SmtpSettings.Saved saved, UUID messageId, String recipient, ClientDecisionEmail.Content content) {
        if (!saved.available()) throw new IllegalStateException("SMTP_DISABLED");
        deliver(saved, messageId, recipient, content);
    }

    void sendTest(SmtpSettings.Saved saved, UUID messageId, String recipient, ClientDecisionEmail.Content content) {
        sendManual(saved, messageId, recipient, content);
    }

    void sendManual(SmtpSettings.Saved saved, UUID messageId, String recipient, ClientDecisionEmail.Content content) {
        if (!saved.view().testReady()) throw new IllegalStateException("SMTP_NOT_READY");
        deliver(saved, messageId, recipient, content);
    }

    private void deliver(SmtpSettings.Saved saved, UUID messageId, String recipient, ClientDecisionEmail.Content content) {
        var config = saved.view();
        var provider = config.oauthProvider();
        var session = Session.getInstance(provider == MailOAuthProvider.MICROSOFT ? microsoftProperties() : properties(config));
        try {
            SmtpSettings.validateAddress(recipient);
            MimeMessage message = message(session, config, messageId, recipient, content);
            String password;
            if (provider == null) {
                password = cipher.decrypt(saved.encryptedPassword());
            } else {
                try { password = oauth.accessToken(config.id(), provider, config.username()); }
                catch (RuntimeException unavailable) { throw new Failure("SMTP_AUTH_FAILED"); }
                if (provider == MailOAuthProvider.GOOGLE) {
                    client.sendGoogle(message, password);
                    return;
                }
                if (provider != MailOAuthProvider.MICROSOFT) throw new Failure("SMTP_AUTH_FAILED");
            }
            var transport = new DecisionSmtpConnection(session);
            try {
                if (provider == MailOAuthProvider.MICROSOFT) transport.connectMicrosoft(config.username(), password);
                else transport.connect(config.host(), config.port(), config.username().isEmpty() ? null : config.username(), password);
                transport.deliver(message);
            } finally {
                // Once the relay accepted DATA, a failed QUIT must not cause a second delivery.
                transport.closeQuietly();
            }
        } catch (Failure known) {
            throw known;
        } catch (AuthenticationFailedException | MailAuthenticationException ex) {
            throw new Failure("SMTP_AUTH_FAILED");
        } catch (MailSendException ambiguous) {
            throw new Failure("SMTP_DELIVERY_UNCERTAIN");
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException ex) {
            // SMTP diagnostic strings can include provider responses, usernames and recipient addresses.
            throw new Failure("SMTP_DELIVERY_FAILED");
        }
    }

    static Properties properties(SmtpSettings.View config) {
        return properties(!config.username().isEmpty(), config.security());
    }

    static Properties microsoftProperties() {
        var props = properties(true, SmtpSettings.Security.STARTTLS);
        props.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
        props.setProperty("mail.smtp.auth.xoauth2.disable", "false");
        props.setProperty("mail.smtp.auth.login.disable", "true");
        props.setProperty("mail.smtp.auth.plain.disable", "true");
        return props;
    }

    private static Properties properties(boolean auth, SmtpSettings.Security security) {
        var props = new Properties();
        props.setProperty("mail.smtp.auth", Boolean.toString(auth));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        if (security == SmtpSettings.Security.STARTTLS) {
            props.setProperty("mail.smtp.starttls.enable", "true");
            props.setProperty("mail.smtp.starttls.required", "true");
        } else {
            props.setProperty("mail.smtp.ssl.enable", "true");
        }
        props.setProperty("mail.smtp.connectiontimeout", "5000");
        props.setProperty("mail.smtp.timeout", "7000");
        props.setProperty("mail.smtp.writetimeout", "7000");
        // The SMTP DATA reply establishes acceptance. Do not wait for a QUIT reply to classify delivery.
        props.setProperty("mail.smtp.quitwait", "false");
        return props;
    }

    static MimeMessage message(Session session, SmtpSettings.View config, UUID messageId, String recipient,
            ClientDecisionEmail.Content content) throws MessagingException, UnsupportedEncodingException {
        var mail = new MimeMessage(session);
        mail.setFrom(new InternetAddress(config.fromAddress(), config.fromName(), "UTF-8"));
        mail.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient, true));
        if (!config.replyTo().isBlank()) mail.setReplyTo(new InternetAddress[]{new InternetAddress(config.replyTo(), true)});
        mail.setSubject(content.subject(), "UTF-8");
        mail.setSentDate(new Date());
        var plain = new MimeBodyPart();
        plain.setText(content.text(), "UTF-8");
        var html = new MimeBodyPart();
        html.setText(content.html(), "UTF-8", "html");
        var alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(html);
        mail.setContent(alternative);
        mail.saveChanges();
        mail.setHeader("Message-ID", "<" + messageId + "@saas.espos>");
        return mail;
    }
}
