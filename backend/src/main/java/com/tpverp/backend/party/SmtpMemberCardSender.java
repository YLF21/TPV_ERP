package com.tpverp.backend.party;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Primary
@Component
public class SmtpMemberCardSender implements MemberCardSender {

    private final MemberSmtpSettingsRepository settings;
    private final SmtpMailSenderFactory senders;

    public SmtpMemberCardSender(MemberSmtpSettingsRepository settings, SmtpMailSenderFactory senders) {
        this.settings = settings;
        this.senders = senders;
    }

    @Override
    public void send(MemberCardDelivery delivery) {
        var config = settings.findById(delivery.getMember().getCompany().getId())
                .filter(MemberSmtpSettings::isEnabled)
                .orElseThrow(() -> new IllegalStateException("message.member_card_delivery.sender_not_configured"));
        var sender = senders.create(config);
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from(config));
            helper.setTo(delivery.getEmail());
            helper.setSubject(delivery.getSubject());
            helper.setText(delivery.getBody(), false);
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }
    // Sends the queued member card using the SMTP settings of the member company.

    private static InternetAddress from(MemberSmtpSettings settings)
            throws AddressException, UnsupportedEncodingException {
        return settings.getFromName() == null
                ? new InternetAddress(settings.getFromEmail())
                : new InternetAddress(settings.getFromEmail(), settings.getFromName());
    }
}
