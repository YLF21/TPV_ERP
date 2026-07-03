package com.tpverp.backend.party;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void sendsMemberCardUsingCompanySmtpSettings() {
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
    }
}
