package com.tpverp.backend.party;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.UUID;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/** Company-scoped transport shared by operational and commercial mail. */
@Service
public class CompanyMailService {
    private final MemberSmtpSettingsRepository settings;
    private final SmtpMailSenderFactory senders;

    public CompanyMailService(MemberSmtpSettingsRepository settings, SmtpMailSenderFactory senders) {
        this.settings = settings;
        this.senders = senders;
    }

    public void send(UUID companyId, String to, String subject, String text, String html) {
        send(companyId, to, subject, text, html, null);
    }

    public void send(UUID companyId, String to, String subject, String text, String html, String unsubscribeUrl) {
        var config = settings.findById(companyId).filter(MemberSmtpSettings::isEnabled)
                .orElseThrow(() -> new IllegalStateException("message.member_card_delivery.sender_not_configured"));
        var sender = senders.create(config);
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setValidateAddresses(true);
            helper.setFrom(new InternetAddress(config.getFromEmail(), config.getFromName()));
            helper.setTo(to);
            if (subject == null || subject.contains("\r") || subject.contains("\n")) {
                throw new IllegalArgumentException("message.smtp.subject_invalid");
            }
            helper.setSubject(subject);
            helper.setText(text, html);
            if (unsubscribeUrl != null) {
                URI uri = URI.create(unsubscribeUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || unsubscribeUrl.contains("\r") || unsubscribeUrl.contains("\n") || unsubscribeUrl.contains(">")) {
                    throw new IllegalArgumentException("message.smtp.unsubscribe_url_invalid");
                }
                message.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");
                message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            }
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException exception) {
            throw new IllegalStateException("message.smtp.message_invalid", exception);
        }
    }
}
