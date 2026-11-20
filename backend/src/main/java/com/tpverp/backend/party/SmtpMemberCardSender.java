package com.tpverp.backend.party;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.io.ByteArrayOutputStream;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.util.HtmlUtils;
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
        if (delivery.getSubject().contains("\r") || delivery.getSubject().contains("\n")) {
            throw new IllegalArgumentException("message.smtp.subject_invalid");
        }
        var config = settings.findById(delivery.getMember().getCompany().getId())
                .filter(MemberSmtpSettings::isEnabled)
                .orElseThrow(() -> new IllegalStateException("message.member_card_delivery.sender_not_configured"));
        var sender = senders.create(config);
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setValidateAddresses(true);
            helper.setFrom(from(config));
            helper.setTo(delivery.getEmail());
            helper.setSubject(delivery.getSubject());
            var cardHtml = "<div style=\"padding:24px;text-align:center\"><p><strong>"
                    + HtmlUtils.htmlEscape(delivery.getCardCode(), "UTF-8") + "</strong></p>"
                    + "<img src=\"cid:member-card\" alt=\"" + HtmlUtils.htmlEscape(delivery.getCardCode(), "UTF-8")
                    + "\" style=\"max-width:100%;height:auto\"></div>";
            var html = "<!doctype html><html><body style=\"font-family:Arial,sans-serif;color:#173b5f\">"
                    + "<main style=\"max-width:600px;margin:auto;padding:24px\"><h1>"
                    + HtmlUtils.htmlEscape(delivery.getSubject(), "UTF-8") + "</h1><p style=\"white-space:pre-wrap\">"
                    + HtmlUtils.htmlEscape(delivery.getBody(), "UTF-8") + "</p>" + cardHtml + "</main></body></html>";
            if (delivery.getHtmlBody() != null) {
                html = delivery.getHtmlBody().replace("</body>", cardHtml + "</body>");
            }
            helper.setText(delivery.getBody() + "\n" + delivery.getCardCode(), html);
            byte[] card = cardImage(delivery);
            helper.addInline("member-card", new ByteArrayResource(card), "image/png");
            helper.addAttachment("member-card.png", new ByteArrayResource(card), "image/png");
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException exception) {
            throw new IllegalStateException("message.smtp.message_invalid", exception);
        }
    }

    static byte[] cardImage(MemberCardDelivery delivery) {
        try {
            boolean qr = delivery.getCardCodeFormat() == MemberCardCodeFormat.QR;
            var matrix = new MultiFormatWriter().encode(delivery.getCardCode(),
                    qr ? BarcodeFormat.QR_CODE : BarcodeFormat.CODE_128, qr ? 256 : 480, qr ? 256 : 140);
            var output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("message.smtp.card_generation_failed", exception);
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
