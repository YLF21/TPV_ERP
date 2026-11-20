package com.tpverp.backend.party;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class SmtpMemberCardSenderTest {

    @Mock MemberSmtpSettingsRepository settings;
    @Mock SmtpMailSenderFactory factory;
    @Mock JavaMailSender mailSender;

    @Test
    void sendsMemberCardUsingCompanySmtpSettings() throws Exception {
        var company = PartyTestData.company();
        var config = new MemberSmtpSettings(company.getId(), new MemberSmtpSettingsCommand(
                true, "smtp.example.com", 587, "user", "secret",
                "noreply@example.com", "TPV", true, false));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, "cliente@example.com", null, CustomerRate.VENTA, java.math.BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var delivery = new MemberCardDelivery(
                member, "cliente@example.com", "Tarjeta", "Codigo",
                MemberCardCodeFormat.QR, Instant.parse("2026-07-02T12:00:00Z"));
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(settings.findById(company.getId())).thenReturn(Optional.of(config));
        when(factory.create(config)).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(message);

        new SmtpMemberCardSender(settings, factory).send(delivery);

        verify(mailSender).send(message);
        message.saveChanges();
        var bytes = new java.io.ByteArrayOutputStream();
        message.writeTo(bytes);
        var mime = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(mime).contains("multipart/mixed", "text/plain", "text/html", "image/png", "member-card.png");
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(SmtpMemberCardSender.cardImage(delivery)));
        var decoded = new com.google.zxing.MultiFormatReader().decode(new com.google.zxing.BinaryBitmap(
                new com.google.zxing.common.HybridBinarizer(
                        new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image))));
        assertThat(decoded.getText()).isEqualTo(member.getMemberId());
    }

    @Test
    void barcodeAttachmentDecodesToMemberNumber() throws Exception {
        var delivery = delivery("Card", "Body", "client@example.org", MemberCardCodeFormat.BARCODE);
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(SmtpMemberCardSender.cardImage(delivery)));
        var decoded = new com.google.zxing.MultiFormatReader().decode(new com.google.zxing.BinaryBitmap(
                new com.google.zxing.common.HybridBinarizer(new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image))));
        assertThat(decoded.getText()).isEqualTo(delivery.getCardCode());
        assertThat(decoded.getBarcodeFormat()).isEqualTo(com.google.zxing.BarcodeFormat.CODE_128);
    }

    @Test
    void defaultHtmlEscapesInputAndRetainsUnicodeAndPlainTextAlternative() throws Exception {
        var delivery = delivery("欢迎 María", "Hola <script>alert('x')</script> & 中文", "client@example.org", MemberCardCodeFormat.QR);
        var message = prepare(delivery);
        new SmtpMemberCardSender(settings, factory).send(delivery);
        message.saveChanges();
        var html = textPart(message,"text/html");
        assertThat(html).contains("欢迎 María", "&lt;script&gt;", "&amp; 中文", "cid:member-card").doesNotContain("<script>");
        assertThat(textPart(message,"text/plain")).contains(delivery.getBody(),delivery.getCardCode());
    }

    @Test
    void selectedTemplateSnapshotKeepsItsDesignAndReceivesTheActualCard() throws Exception {
        var delivery = delivery("Card", "Fallback", "client@example.org", MemberCardCodeFormat.QR);
        delivery.setHtmlSnapshot("<!doctype html><html><body><h1>Saved design</h1></body></html>");
        var message = prepare(delivery);
        new SmtpMemberCardSender(settings,factory).send(delivery);
        message.saveChanges();
        assertThat(textPart(message,"text/html")).contains("Saved design", "cid:member-card", delivery.getCardCode());
    }

    @Test
    void rejectsSubjectHeaderInjectionBeforeResolvingTransport() {
        var delivery = delivery("Card\r\nBcc: other@example.org", "Body", "client@example.org", MemberCardCodeFormat.QR);
        assertThatThrownBy(() -> new SmtpMemberCardSender(settings,factory).send(delivery))
                .hasMessage("message.smtp.subject_invalid");
        verifyNoInteractions(settings,factory,mailSender);
    }

    @Test
    void rejectsInvalidRecipientWithoutSending() {
        var delivery = delivery("Card", "Body", "bad@@example.org", MemberCardCodeFormat.QR);
        prepare(delivery);
        assertThatThrownBy(() -> new SmtpMemberCardSender(settings,factory).send(delivery))
                .hasMessage("message.smtp.message_invalid");
        verify(mailSender,never()).send(any(MimeMessage.class));
    }

    private MimeMessage prepare(MemberCardDelivery delivery) {
        var config = new MemberSmtpSettings(delivery.getMember().getCompany().getId(),new MemberSmtpSettingsCommand(
                true,"smtp.example.org",587,"user","secret","sender@example.org","Store",true,false));
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(settings.findById(config.getCompanyId())).thenReturn(Optional.of(config));
        when(factory.create(config)).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(message);
        return message;
    }
    private static MemberCardDelivery delivery(String subject,String body,String email,MemberCardCodeFormat format) {
        var customer = new Customer(PartyTestData.company(),"Cliente",DocumentType.NIF,"1",null,null,email,null,
                CustomerRate.VENTA,java.math.BigDecimal.ZERO);
        var member = new Member(customer,"M-001-000001",LocalDate.of(2026,7,2));
        return new MemberCardDelivery(member,email,subject,body,format,Instant.parse("2026-07-02T12:00:00Z"));
    }
    private static String textPart(jakarta.mail.Part part,String mimeType) throws Exception {
        if (part.isMimeType(mimeType)) return (String) part.getContent();
        if (part.getContent() instanceof jakarta.mail.Multipart multipart) {
            for (int i=0; i<multipart.getCount(); i++) {
                String value = textPart(multipart.getBodyPart(i),mimeType);
                if (value != null) return value;
            }
        }
        return null;
    }
}
