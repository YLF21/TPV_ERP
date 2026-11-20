package com.tpverp.backend.communications;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CommunicationModels {
    private CommunicationModels() { }
    public interface TemplateUpdate { }

    public record TemplateInput(@NotBlank @Size(max=120) String name,
            @NotBlank @Size(max=250) String subject, @NotBlank @Size(max=250) String heading,
            @NotBlank @Size(max=20000) String body, @Size(max=80) String buttonText,
            @Size(max=2048) String buttonUrl, @Size(max=2048) String logoUrl, @Pattern(regexp="#[0-9a-fA-F]{6}") @NotNull String accentColor,
            @NotNull(groups=TemplateUpdate.class) @PositiveOrZero Long version) {
        public TemplateInput(String name,String subject,String heading,String body,String buttonText,String buttonUrl,
                String logoUrl,String accentColor,long version) {
            this(name,subject,heading,body,buttonText,buttonUrl,logoUrl,accentColor,Long.valueOf(version));
        }
    }
    public record TemplateView(UUID id, String name, String subject, String heading, String body,
            String buttonText, String buttonUrl, String logoUrl, String accentColor, long version) {
        public TemplateInput input() { return new TemplateInput(name,subject,heading,body,buttonText,buttonUrl,logoUrl,accentColor,version); }
    }
    public record Rendered(String html, String text) { }
    public record WelcomeTemplateSelection(UUID templateId) { }
    public record CampaignInput(@NotBlank @Size(max=120) String name, @NotNull UUID templateId,
            @Size(max=10000) List<@NotNull UUID> customerIds) { }
    public record ScheduleInput(Instant scheduledAt, @Min(1) int expectedRecipients,
            @NotBlank @Pattern(regexp="[0-9a-f]{64}") String previewToken) { }
    public record CampaignView(UUID id, String name, UUID templateId, String status,
            Instant scheduledAt, Instant createdAt, long version, int recipientCount) { }
    public record AudiencePreview(int eligible, int total, int excludedNoConsent, int excludedNoEmail,
            int excludedChannel, int excludedSuppressed, int excludedInactive, int excludedDuplicate,
            String html, String text, String previewToken) { }
    public record DeliveryView(UUID id, UUID campaignId, String campaignName, String email, String status,
            Instant createdAt, Instant sentAt, String errorMessage) { }
    public record StatusView(boolean campaignsEnabled, boolean publicUnsubscribeConfigured, boolean workerEnabled) { }
}
