package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberSmtpSettingsServiceTest {

    @Mock MemberSmtpSettingsRepository settings;
    @Mock CurrentOrganization organization;
    @Mock MemberCardSender sender;

    @Test
    void savesSettingsWithoutExposingPassword() {
        var company = PartyTestData.company();
        var command = command("secret");
        when(organization.currentCompany()).thenReturn(company);
        when(settings.findById(company.getId())).thenReturn(Optional.empty());
        when(settings.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        var view = service().update(command);

        assertThat(view.configured()).isTrue();
        assertThat(view.host()).isEqualTo("smtp.example.com");
        assertThat(view.fromEmail()).isEqualTo("noreply@example.com");
        assertThat(view).hasNoNullFieldsOrPropertiesExcept("fromName");
        verify(settings).save(org.mockito.ArgumentMatchers.any(MemberSmtpSettings.class));
    }

    @Test
    void blankPasswordKeepsEncryptedCredentialAndLegacyCredentialIsUpgradedOnSave() {
        var company = PartyTestData.company();
        var existing = new MemberSmtpSettings(company.getId(), command("legacy-secret"));
        when(organization.currentCompany()).thenReturn(company);
        when(settings.findById(company.getId())).thenReturn(Optional.of(existing));
        when(settings.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        service().update(command(""));
        var protectedPassword = existing.getPassword();
        assertThat(protectedPassword).startsWith("enc:v1:").doesNotContain("legacy-secret");
        service().update(command(null));
        assertThat(existing.getPassword()).isEqualTo(protectedPassword);
        assertThat(service().get().passwordConfigured()).isTrue();
    }

    @Test
    void missingDeploymentKeyFailsBeforeSavingSecret() {
        when(organization.currentCompany()).thenReturn(PartyTestData.company());
        var service = new MemberSmtpSettingsService(settings, organization, sender,
                new MailSecretCipher(""), new org.springframework.mock.env.MockEnvironment());
        assertThatThrownBy(() -> service.update(command("secret")))
                .hasMessage("message.smtp.encryption_key_missing");
        org.mockito.Mockito.verifyNoInteractions(settings);
    }

    @Test
    void canDisableLegacyAccountWhenDeploymentEncryptionKeyIsUnavailable() {
        var company = PartyTestData.company();
        var existing = new MemberSmtpSettings(company.getId(), command("legacy-secret"));
        when(organization.currentCompany()).thenReturn(company);
        when(settings.findById(company.getId())).thenReturn(Optional.of(existing));
        when(settings.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new MemberSmtpSettingsService(settings, organization, sender,
                new MailSecretCipher(""), new org.springframework.mock.env.MockEnvironment());
        var result = service.update(new MemberSmtpSettingsCommand(false, "smtp.example.com",587,"user",null,
                "noreply@example.com",null,true,false));
        assertThat(result.enabled()).isFalse();
        assertThat(result.credentialsReady()).isFalse();
        assertThat(existing.getPassword()).isEqualTo("legacy-secret");
        org.mockito.Mockito.verifyNoInteractions(sender);
    }

    @Test
    void accountStatusDetectsChangedEncryptionKeyInsteadOfClaimingCredentialIsReady() {
        var company = PartyTestData.company();
        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey,(byte)1);
        var oldCipher = new MailSecretCipher(java.util.Base64.getEncoder().encodeToString(otherKey));
        var existing = new MemberSmtpSettings(company.getId(),command(oldCipher.encrypt("secret",company.getId())));
        when(organization.currentCompany()).thenReturn(company);
        when(settings.findById(company.getId())).thenReturn(Optional.of(existing));
        assertThat(service().get().passwordConfigured()).isTrue();
        assertThat(service().get().credentialsReady()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(sender);
    }

    @Test
    void testRequiresConfiguredAndEnabledAccount() {
        var company = PartyTestData.company();
        when(organization.currentCompany()).thenReturn(company);
        when(settings.findById(company.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().test(new MemberSmtpSettingsService.MemberSmtpTestCommand(
                "recipient@example.com","Subject","Body")))
                .hasMessage("message.member_card_delivery.sender_not_configured");
        var disabled = new MemberSmtpSettings(company.getId(),new MemberSmtpSettingsCommand(false,
                "smtp.example.org",587,"user",null,"sender@example.org",null,true,false));
        when(settings.findById(company.getId())).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> service().test(new MemberSmtpSettingsService.MemberSmtpTestCommand(
                "recipient@example.com","Subject","Body")))
                .hasMessage("message.member_card_delivery.sender_not_configured");
        org.mockito.Mockito.verifyNoInteractions(sender);
    }

    private MemberSmtpSettingsService service() {
        return new MemberSmtpSettingsService(settings, organization, sender,
                new MailSecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32])),
                new org.springframework.mock.env.MockEnvironment());
    }

    private static MemberSmtpSettingsCommand command(String password) {
        return new MemberSmtpSettingsCommand(
                true, "smtp.example.com", 587, "user", password,
                "noreply@example.com", null, true, false);
    }
}
