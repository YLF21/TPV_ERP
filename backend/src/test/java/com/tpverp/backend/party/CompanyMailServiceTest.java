package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class CompanyMailServiceTest {
    @Mock MemberSmtpSettingsRepository settings;
    @Mock SmtpMailSenderFactory factory;
    @Mock JavaMailSender sender;

    @Test void companyScopedMailContainsUnsubscribeHeadersAndOnlyTheChosenRecipient() throws Exception {
        UUID company = UUID.randomUUID();
        var message = prepare(company);
        new CompanyMailService(settings,factory).send(company,"recipient@example.org","Oferta 中文","Plain text",
                "<html><body>Oferta 中文</body></html>","https://mail.example.org/unsubscribe?token=opaque");
        message.saveChanges();
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("recipient@example.org");
        assertThat(message.getFrom()[0].toString()).contains("sender@example.org");
        assertThat(message.getSubject()).isEqualTo("Oferta 中文");
        assertThat(message.getHeader("List-Unsubscribe",null)).isEqualTo("<https://mail.example.org/unsubscribe?token=opaque>");
        assertThat(message.getHeader("List-Unsubscribe-Post",null)).isEqualTo("List-Unsubscribe=One-Click");
        verify(sender).send(message);
    }
    @Test void operationalMailDoesNotPretendToBeAMarketingList() throws Exception {
        UUID company = UUID.randomUUID();
        var message = prepare(company);
        new CompanyMailService(settings,factory).send(company,"recipient@example.org","Welcome","Plain","<p>Welcome</p>");
        assertThat(message.getHeader("List-Unsubscribe",null)).isNull();
        verify(sender).send(message);
    }
    @Test void refusesUnsafeUnsubscribeUrlWithoutSending() {
        UUID company = UUID.randomUUID();
        prepare(company);
        assertThatThrownBy(() -> new CompanyMailService(settings,factory).send(company,"recipient@example.org","Subject",
                "Plain","<p>Body</p>","http://localhost/unsubscribe"))
                .hasMessage("message.smtp.unsubscribe_url_invalid");
        verify(sender,never()).send(any(MimeMessage.class));
    }
    @Test void doesNotBorrowAnotherCompanyMailboxWhenRequestedCompanyIsUnconfigured() {
        UUID company = UUID.randomUUID();
        when(settings.findById(company)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new CompanyMailService(settings,factory).send(company,"recipient@example.org","Subject","Plain","<p>Body</p>"))
                .hasMessage("message.member_card_delivery.sender_not_configured");
        verifyNoInteractions(factory,sender);
    }
    private MimeMessage prepare(UUID company) {
        var config = new MemberSmtpSettings(company,new MemberSmtpSettingsCommand(true,"smtp.example.org",587,
                "sender",null,"sender@example.org","Store",true,false));
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(settings.findById(company)).thenReturn(Optional.of(config));
        when(factory.create(config)).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message);
        return message;
    }
}
