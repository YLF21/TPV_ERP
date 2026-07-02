package com.tpverp.backend.party;

import com.tpverp.backend.organization.CurrentOrganization;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberSmtpSettingsService {

    private final MemberSmtpSettingsRepository settings;
    private final CurrentOrganization organization;
    private final MemberCardSender sender;

    public MemberSmtpSettingsService(
            MemberSmtpSettingsRepository settings,
            CurrentOrganization organization,
            MemberCardSender sender) {
        this.settings = settings;
        this.organization = organization;
        this.sender = sender;
    }

    @Transactional(readOnly = true)
    public MemberSmtpSettingsView get() {
        return settings.findById(organization.currentCompany().getId())
                .map(MemberSmtpSettingsView::from)
                .orElse(MemberSmtpSettingsView.empty());
    }

    @Transactional
    public MemberSmtpSettingsView update(MemberSmtpSettingsCommand command) {
        UUID companyId = organization.currentCompany().getId();
        var value = settings.findById(companyId)
                .orElseGet(() -> new MemberSmtpSettings(companyId, command));
        value.update(command);
        return MemberSmtpSettingsView.from(settings.save(value));
    }

    @Transactional
    public void test(MemberSmtpTestCommand command) {
        var company = organization.currentCompany();
        var config = settings.findById(company.getId())
                .filter(MemberSmtpSettings::isEnabled)
                .orElseThrow(() -> new IllegalStateException("message.member_card_delivery.sender_not_configured"));
        var customer = new Customer(company, "SMTP TEST", DocumentType.OTRO, "SMTP-TEST",
                null, null, command.toEmail(), null, CustomerRate.VENTA, java.math.BigDecimal.ZERO);
        var member = new Member(customer, "SMTP-TEST", java.time.LocalDate.now());
        sender.send(new MemberCardDelivery(
                member, command.toEmail(), command.subject(), command.body(),
                MemberCardCodeFormat.QR, java.time.Instant.now()));
    }
    // Sends a transient test message without creating a member-card delivery row.

    public record MemberSmtpSettingsView(
            boolean configured,
            boolean enabled,
            String host,
            int port,
            String username,
            String fromEmail,
            String fromName,
            boolean startTls,
            boolean sslEnabled) {

        static MemberSmtpSettingsView empty() {
            return new MemberSmtpSettingsView(false, false, null, 0, null, null, null, true, false);
        }

        static MemberSmtpSettingsView from(MemberSmtpSettings settings) {
            return new MemberSmtpSettingsView(
                    true, settings.isEnabled(), settings.getHost(), settings.getPort(),
                    settings.getUsername(), settings.getFromEmail(), settings.getFromName(),
                    settings.isStartTls(), settings.isSslEnabled());
        }
    }

    public record MemberSmtpTestCommand(String toEmail, String subject, String body) {
    }
}
