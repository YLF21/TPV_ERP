package com.tpverp.backend.document;

import com.tpverp.backend.terminal.CardTerminalResult;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pos_card_checkout")
public class PosCardCheckout {
    @Id private UUID id;
    @Column(name="terminal_id", nullable=false) private UUID terminalId;
    @Column(name="schema_version", nullable=false) private short schemaVersion = 1;
    @Column(name="request_hash", nullable=false, length=64) private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="document_snapshot",nullable=false,columnDefinition="jsonb") private String documentSnapshot;
    @Column(nullable=false, precision=19, scale=2) private BigDecimal total;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=16) private PaymentTerminalOperationStatus status;
    @Column(length=128) private String reference;
    @Column(name="authorization_code", length=64) private String authorization;
    @Column(length=512) private String message;
    @Column(name="documento_id") private UUID documentId;
    @Column(name="ticket_number", length=32) private String ticketNumber;
    @Column(name="requested_by", length=128) private String requestedBy;
    @Column(name="requested_user_id") private UUID requestedUserId;
    @Column(name="requested_store_id") private UUID requestedStoreId;
    @Column(name="requested_company_id") private UUID requestedCompanyId;
    @Column(name="gateway_owner") private UUID gatewayOwner;
    @Column(name="gateway_lease_until") private Instant gatewayLeaseUntil;
    @Column(name="ticket_owner") private UUID ticketOwner;
    @Column(name="ticket_lease_until") private Instant ticketLeaseUntil;
    @Column(nullable=false) private int attempt = 1;
    @Column(name="creado_en", nullable=false) private Instant createdAt;
    @Column(name="actualizado_en", nullable=false) private Instant updatedAt;
    @Column(name="completado_en") private Instant completedAt;
    @Version private long version;

    protected PosCardCheckout() {}

    static PosCardCheckout reserve(UUID id, UUID terminalId, String hash, String snapshot, BigDecimal total,
            UUID owner, Instant now, Instant leaseUntil) {
        var value = new PosCardCheckout();
        value.id=Objects.requireNonNull(id); value.terminalId=Objects.requireNonNull(terminalId);
        value.requestHash=Objects.requireNonNull(hash); value.documentSnapshot=Objects.requireNonNull(snapshot); value.total=Money.euros(total);
        value.status=PaymentTerminalOperationStatus.PENDING; value.gatewayOwner=owner;
        value.createdAt=now; value.updatedAt=now; value.gatewayLeaseUntil=leaseUntil;
        return value;
    }

    public static PosCardCheckout reserve(UUID id, UUID terminalId, String hash, BigDecimal total) {
        var now=Instant.now(); return reserve(id, terminalId, hash, "{\"schemaVersion\":1,\"ticket\":{}}", total, UUID.randomUUID(), now, now.plusSeconds(30));
    }

    void recordGatewayResult(UUID owner, CardTerminalResult result, Instant now) {
        if (status != PaymentTerminalOperationStatus.PENDING || !Objects.equals(gatewayOwner, owner))
            throw new IllegalStateException("El checkout no pertenece a este intento");
        status=result.status(); reference=optional(result.reference()); authorization=optional(result.authorization());
        message=optional(result.message()); gatewayLeaseUntil=null; completedAt=now; updatedAt=now;
    }

    public void complete(CardTerminalResult result, UUID documentId, String ticketNumber) {
        if (isCompleted()) throw new IllegalStateException("El checkout ya esta completado");
        recordGatewayResult(gatewayOwner, result, Instant.now());
        if (documentId != null) linkDocument(documentId, ticketNumber, Instant.now());
    }

    void expire(Instant now) {
        if (status != PaymentTerminalOperationStatus.PENDING) return;
        status=PaymentTerminalOperationStatus.ERROR;
        message="Resultado del datafono incierto; revise el terminal antes de reintentar";
        completedAt=now; updatedAt=now; gatewayLeaseUntil=null;
    }

    void linkDocument(UUID id, String number, Instant now) {
        if (status != PaymentTerminalOperationStatus.APPROVED || documentId != null)
            throw new IllegalStateException("No se puede enlazar el ticket");
        documentId=Objects.requireNonNull(id); ticketNumber=Objects.requireNonNull(number);
        ticketOwner=null; ticketLeaseUntil=null; updatedAt=now;
    }

    void releaseTicket(UUID owner, Instant now) {
        if (Objects.equals(ticketOwner, owner)) { ticketOwner=null; ticketLeaseUntil=null; updatedAt=now; }
    }
    void diagnostic(String value,Instant now){message=optional(value);updatedAt=now;}
    void requestedBy(String username,Instant now){if(requestedBy==null){requestedBy=Objects.requireNonNull(username).trim();updatedAt=now;}}
    void recoveryIdentity(UUID userId,UUID storeId,UUID companyId,String username,Instant now){
        if(requestedUserId==null){requestedUserId=Objects.requireNonNull(userId);requestedStoreId=Objects.requireNonNull(storeId);
            requestedCompanyId=Objects.requireNonNull(companyId);requestedBy=Objects.requireNonNull(username).trim();updatedAt=now;}}
    void recoverApproved(String recoveredReference,String recoveredAuthorization,String recoveredMessage,Instant now){
        if(status==PaymentTerminalOperationStatus.APPROVED)return;
        if(status!=PaymentTerminalOperationStatus.TIMEOUT&&status!=PaymentTerminalOperationStatus.ERROR&&status!=PaymentTerminalOperationStatus.PENDING)
            throw new IllegalStateException("El checkout no admite recuperacion");
        status=PaymentTerminalOperationStatus.APPROVED;reference=optional(recoveredReference);authorization=optional(recoveredAuthorization);
        message=optional(recoveredMessage);gatewayOwner=null;gatewayLeaseUntil=null;completedAt=now;updatedAt=now;
    }

    public UUID getId(){return id;} public UUID getTerminalId(){return terminalId;}
    public String getRequestHash(){return requestHash;} public BigDecimal getTotal(){return total;}
    public String getDocumentSnapshot(){return documentSnapshot;}
    public String getRequestedBy(){return requestedBy;}
    public UUID getRequestedUserId(){return requestedUserId;} public UUID getRequestedStoreId(){return requestedStoreId;}
    public UUID getRequestedCompanyId(){return requestedCompanyId;}
    public PaymentTerminalOperationStatus getStatus(){return status;} public UUID getDocumentId(){return documentId;}
    public UUID getGatewayOwner(){return gatewayOwner;} public Instant getGatewayLeaseUntil(){return gatewayLeaseUntil;}
    public boolean isCompleted(){return completedAt!=null;}
    public PosCardService.Result toResult(){return new PosCardService.Result(status,documentId,ticketNumber,total,reference,authorization,message);}
    private static String optional(String v){return v==null||v.isBlank()?null:v.trim();}
}
