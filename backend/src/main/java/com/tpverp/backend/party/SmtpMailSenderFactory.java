package com.tpverp.backend.party;

import java.util.Properties;
import com.tpverp.backend.mailoauth.MailOAuthService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSenderFactory {

    private final MailSecretCipher cipher;
    private final ObjectProvider<MailOAuthService> oauth;

    public SmtpMailSenderFactory(MailSecretCipher cipher) {
        this.cipher = cipher;
        this.oauth = null;
    }

    @Autowired
    public SmtpMailSenderFactory(MailSecretCipher cipher, ObjectProvider<MailOAuthService> oauth) {
        this.cipher = cipher;
        this.oauth = oauth;
    }

    public JavaMailSender create(MemberSmtpSettings settings) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        sender.setUsername(settings.getUsername());
        java.util.Optional<MailOAuthService.SmtpCredentials> oauthCredentials;
        try {
            oauthCredentials = oauth == null ? java.util.Optional.empty()
                    : oauth.getObject().smtpCredentials(settings.getCompanyId());
        } catch (RuntimeException preparationFailure) {
            // Credential lookup/refresh precedes message submission; it cannot be an uncertain delivery.
            throw new org.springframework.mail.MailPreparationException("message.smtp.configuration_failed");
        }
        if (oauthCredentials.isPresent()
                && oauthCredentials.get().provider() == com.tpverp.backend.mailoauth.MailOAuthProvider.GOOGLE) {
            return oauth.getObject().googleSender(oauthCredentials.get());
        }
        if (oauthCredentials.isEmpty()) {
            if (!settings.isStartTls() && !settings.isSslEnabled()) {
                throw new IllegalStateException("message.smtp.tls_required");
            }
            sender.setPassword(cipher.decrypt(settings.getPassword(), settings.getCompanyId()));
        }
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", String.valueOf(settings.getUsername() != null));
        properties.put("mail.smtp.starttls.enable", String.valueOf(settings.isStartTls()));
        properties.put("mail.smtp.starttls.required", String.valueOf(settings.isStartTls()));
        properties.put("mail.smtp.ssl.enable", String.valueOf(settings.isSslEnabled()));
        properties.put("mail.smtp.ssl.checkserveridentity", "true");
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "15000");
        properties.put("mail.smtp.writetimeout", "15000");
        oauthCredentials.ifPresent(credentials -> {
            sender.setHost(credentials.host());
            sender.setPort(credentials.port());
            sender.setUsername(credentials.username());
            sender.setPassword(credentials.accessToken());
            properties.put("mail.smtp.auth", "true");
            properties.put("mail.smtp.auth.mechanisms", "XOAUTH2");
            properties.put("mail.smtp.auth.login.disable", "true");
            properties.put("mail.smtp.auth.plain.disable", "true");
            properties.put("mail.smtp.auth.xoauth2.disable", "false");
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.enable", "false");
        });
        return sender;
    }
}
