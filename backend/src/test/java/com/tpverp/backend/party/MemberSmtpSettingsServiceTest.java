package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
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

    private MemberSmtpSettingsService service() {
        return new MemberSmtpSettingsService(settings, organization, sender);
    }

    private static MemberSmtpSettingsCommand command(String password) {
        return new MemberSmtpSettingsCommand(
                true, "smtp.example.com", 587, "user", password,
                "noreply@example.com", null, true, false);
    }
}
