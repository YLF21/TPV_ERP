package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_card_delivery")
public class MemberCardDelivery {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "miembro_id", nullable = false)
    private Member member;
    @Column(nullable = false, length = 320)
    private String email;
    @Column(nullable = false, columnDefinition = "text")
    private String subject;
    @Column(nullable = false, columnDefinition = "text")
    private String body;
    @Enumerated(EnumType.STRING)
    @Column(name = "card_code_format", nullable = false, length = 16)
    private MemberCardCodeFormat cardCodeFormat;
    @Column(name = "card_code", nullable = false, length = 64)
    private String cardCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemberCardDeliveryStatus status = MemberCardDeliveryStatus.PENDIENTE;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
    @Version
    private long version;

    protected MemberCardDelivery() {
    }

    public MemberCardDelivery(Member member, String email, String subject, String body,
            MemberCardCodeFormat cardCodeFormat, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.member = Objects.requireNonNull(member, "member");
        this.email = PartyValues.required(email, "email");
        this.subject = PartyValues.required(subject, "subject");
        this.body = PartyValues.required(body, "body");
        this.cardCodeFormat = Objects.requireNonNull(cardCodeFormat, "cardCodeFormat");
        this.cardCode = member.getMemberId();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public Member getMember() {
        return member;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public MemberCardCodeFormat getCardCodeFormat() {
        return cardCodeFormat;
    }

    public String getCardCode() {
        return cardCode;
    }

    public MemberCardDeliveryStatus getStatus() {
        return status;
    }

    public void markSent(Instant sentAt) {
        status = MemberCardDeliveryStatus.ENVIADO;
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
        errorMessage = null;
    }

    public void markError(String message) {
        status = MemberCardDeliveryStatus.ERROR;
        errorMessage = PartyValues.optional(message);
    }

    public void retry() {
        status = MemberCardDeliveryStatus.PENDIENTE;
        errorMessage = null;
    }
    // Keeps the same queued card content while allowing a failed delivery to be retried.

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
