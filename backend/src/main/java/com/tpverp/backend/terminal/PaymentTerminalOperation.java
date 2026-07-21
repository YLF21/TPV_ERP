package com.tpverp.backend.terminal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payment_terminal_operation")
public class PaymentTerminalOperation {
    @Id private UUID id;
    @Column(name = "terminal_id", nullable = false) private UUID terminalId;
    @Column(name = "store_id", nullable = false) private UUID storeId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private PaymentTerminalProvider provider;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private PaymentTerminalMode mode;
    @Enumerated(EnumType.STRING) @Column(name = "operation_type", nullable = false, length = 16) private PaymentTerminalOperationType operationType;
    @Column(name = "original_operation_id") private UUID originalOperationId;
    @Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 2) private BigDecimal refundedAmount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private PaymentTerminalOperationStatus status;
    @Column(name = "external_reference", length = 128) private String externalReference;
    @Column(name = "authorization_code", length = 64) private String authorizationCode;
    @Column(name = "configuration_hash", nullable = true, length = 64) private String configurationHash;
    @Column(name = "configuration_version", nullable = false) private long configurationVersion;
    @Column(name = "legacy_configuration_fingerprint", nullable = false) private boolean legacyConfigurationFingerprint;
    @Column(name = "document_id") private UUID documentId;
    @Column(name = "document_payment_id") private UUID documentPaymentId;
    @Column(name = "document_managed_externally", nullable = false) private boolean documentManagedExternally;
    @Column(name = "refund_line_selection", columnDefinition = "text") private String refundLineSelection;
    @Column(name = "processing_owner") private UUID processingOwner;
    @Column(name = "processing_lease_until") private Instant processingLeaseUntil;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Column(name = "document_retry_count", nullable = false) private int documentRetryCount;
    @Column(name = "next_retry_at") private Instant nextRetryAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Version private long version;

    @OneToMany(mappedBy = "operation", cascade = CascadeType.ALL, orphanRemoval = false)
    @OrderBy("createdAt ASC")
    private List<PaymentTerminalEvent> events = new ArrayList<>();

    protected PaymentTerminalOperation() {}

    public static PaymentTerminalOperation reserve(UUID id, UUID terminalId, UUID storeId,
            PaymentTerminalProvider provider, PaymentTerminalMode mode,
            PaymentTerminalOperationType operationType, UUID originalOperationId,
            String idempotencyKey, String requestHash, BigDecimal amount,
            String configurationHash, long configurationVersion, Instant now) {
        var operation = new PaymentTerminalOperation();
        operation.id = Objects.requireNonNull(id, "id");
        operation.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        operation.storeId = Objects.requireNonNull(storeId, "storeId");
        operation.provider = Objects.requireNonNull(provider, "provider");
        operation.mode = Objects.requireNonNull(mode, "mode");
        operation.operationType = Objects.requireNonNull(operationType, "operationType");
        if ((operationType == PaymentTerminalOperationType.CHARGE) != (originalOperationId == null)) {
            throw new IllegalArgumentException("La relacion con la operacion original no corresponde al tipo");
        }
        operation.originalOperationId = originalOperationId;
        operation.idempotencyKey = required(idempotencyKey, 128, "idempotencyKey");
        operation.requestHash = hash(requestHash, "requestHash");
        operation.amount = positive(amount, "amount");
        operation.currency = "EUR";
        operation.refundedAmount = new BigDecimal("0.00");
        if ((configurationHash == null && configurationVersion != -1)
                || (configurationHash != null && configurationVersion < 0)) {
            throw new IllegalArgumentException("Identidad de configuracion incoherente");
        }
        operation.configurationHash = configurationHash == null ? null : hash(configurationHash, "configurationHash");
        operation.configurationVersion = configurationVersion;
        operation.status = PaymentTerminalOperationStatus.PENDING;
        operation.createdAt = Objects.requireNonNull(now, "now");
        operation.updatedAt = now;
        operation.append(null, PaymentTerminalOperationStatus.PENDING, "RESERVED", null, Map.of(), now);
        return operation;
    }

    public void markSent(String code, Instant at) {
        markSent(code, at, Map.of());
    }

    public void assignRefundLineSelection(String canonical) {
        if (operationType != PaymentTerminalOperationType.REFUND || status != PaymentTerminalOperationStatus.PENDING) {
            throw new IllegalStateException("El desglose solo puede asignarse a una devolucion reservada");
        }
        var value = canonical == null ? "" : canonical;
        if (value.length() > 16_384) throw new IllegalArgumentException("El desglose de devolucion es demasiado grande");
        PaymentTerminalRefundLineSelection.parse(value);
        refundLineSelection = value;
    }

    public void manageDocumentExternally() {
        if (operationType != PaymentTerminalOperationType.REFUND || documentId != null) {
            throw new IllegalStateException("Solo una devolucion sin documento puede delegar su documentacion");
        }
        documentManagedExternally = true;
    }

    public void markSent(String code, Instant at, Map<String, ?> metadata) {
        transition(PaymentTerminalOperationStatus.SENT, code, null, metadata, at,
                PaymentTerminalOperationStatus.PENDING);
    }

    public void approve(String reference, String authorization, Instant at) {
        rejectVoidGenericApproval();
        requireCurrent(PaymentTerminalOperationStatus.SENT);
        externalReference = PaymentTerminalSensitiveData.storageIdentifier(reference,128);
        authorizationCode = PaymentTerminalSensitiveData.storageIdentifier(authorization,64);
        transition(PaymentTerminalOperationStatus.APPROVED, "APPROVED", null, Map.of(), at,
                PaymentTerminalOperationStatus.SENT);
    }

    public void approveFromQuery(String reference, String authorization, Instant at) {
        rejectVoidGenericApproval();
        externalReference = PaymentTerminalSensitiveData.storageIdentifier(reference,128);
        authorizationCode = PaymentTerminalSensitiveData.storageIdentifier(authorization,64);
        transition(PaymentTerminalOperationStatus.APPROVED, "QUERY_APPROVED", null, Map.of(), at,
                PaymentTerminalOperationStatus.PENDING, PaymentTerminalOperationStatus.SENT,
                PaymentTerminalOperationStatus.TIMEOUT, PaymentTerminalOperationStatus.ERROR);
    }

    public void declineFromQuery(String code, String diagnostic, Instant at) {
        transition(PaymentTerminalOperationStatus.DECLINED, code, diagnostic, Map.of(), at,
                PaymentTerminalOperationStatus.PENDING, PaymentTerminalOperationStatus.SENT,
                PaymentTerminalOperationStatus.TIMEOUT, PaymentTerminalOperationStatus.ERROR);
    }

    public void decline(String code, String diagnostic, Instant at) {
        transition(PaymentTerminalOperationStatus.DECLINED, code, diagnostic, Map.of(), at,
                PaymentTerminalOperationStatus.SENT);
    }

    public void timeout(String code, String diagnostic, Instant at) {
        transition(PaymentTerminalOperationStatus.TIMEOUT, code, diagnostic, Map.of(), at,
                PaymentTerminalOperationStatus.SENT);
    }

    public void markReviewRequired(String code, String diagnostic, Instant at) {
        transition(PaymentTerminalOperationStatus.REVIEW_REQUIRED, code, diagnostic, Map.of(), at,
                PaymentTerminalOperationStatus.PENDING, PaymentTerminalOperationStatus.TIMEOUT,
                PaymentTerminalOperationStatus.SENT, PaymentTerminalOperationStatus.ERROR);
    }
    public void markDocumentReviewRequired(String code,String diagnostic,Instant at){transition(PaymentTerminalOperationStatus.REVIEW_REQUIRED,
            code,diagnostic,Map.of("phase","DOCUMENT"),at,PaymentTerminalOperationStatus.APPROVED);}

    public void recordRefund(BigDecimal refund, Instant at) {
        if (status != PaymentTerminalOperationStatus.APPROVED
                && status != PaymentTerminalOperationStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("La operacion no admite devoluciones");
        }
        var value = positive(refund, "refund");
        var total = refundedAmount.add(value);
        if (total.compareTo(amount) > 0) throw new IllegalArgumentException("La devolucion supera el importe aprobado");
        refundedAmount = total;
        var target = total.compareTo(amount) == 0
                ? PaymentTerminalOperationStatus.REFUNDED
                : PaymentTerminalOperationStatus.PARTIALLY_REFUNDED;
        transition(target, "REFUND_APPROVED", null, Map.of(), at,
                PaymentTerminalOperationStatus.APPROVED, PaymentTerminalOperationStatus.PARTIALLY_REFUNDED);
    }

    public void recordVoid(Instant at) {
        if(operationType!=PaymentTerminalOperationType.CHARGE || status!=PaymentTerminalOperationStatus.APPROVED
                || refundedAmount.signum()!=0) throw new IllegalStateException("El cobro no admite anulacion");
        transition(PaymentTerminalOperationStatus.CANCELLED,"VOID_APPROVED",null,Map.of(),at,
                PaymentTerminalOperationStatus.APPROVED);
    }

    public void cancelBeforeSend(String code, Instant at) {
        transition(PaymentTerminalOperationStatus.CANCELLED, code, null, Map.of(), at,
                PaymentTerminalOperationStatus.PENDING);
    }

    public void voidApproved(String reference, String authorization, Instant at) {
        if (operationType != PaymentTerminalOperationType.VOID) {
            throw new IllegalStateException("Solo una anulacion enviada puede aprobarse como void");
        }
        requireCurrent(PaymentTerminalOperationStatus.SENT);
        externalReference = PaymentTerminalSensitiveData.storageIdentifier(reference,128);
        authorizationCode = PaymentTerminalSensitiveData.storageIdentifier(authorization,64);
        transition(PaymentTerminalOperationStatus.APPROVED, "VOID_APPROVED", null, Map.of(), at,
                PaymentTerminalOperationStatus.SENT);
    }

    public void voidApprovedFromQuery(String reference, String authorization, Instant at) {
        if (operationType != PaymentTerminalOperationType.VOID) {
            throw new IllegalStateException("Solo una anulacion puede resolverse como void");
        }
        externalReference = PaymentTerminalSensitiveData.storageIdentifier(reference,128);
        authorizationCode = PaymentTerminalSensitiveData.storageIdentifier(authorization,64);
        transition(PaymentTerminalOperationStatus.APPROVED, "QUERY_VOID_APPROVED", null, Map.of(), at,
                PaymentTerminalOperationStatus.PENDING, PaymentTerminalOperationStatus.TIMEOUT);
    }

    public void fail(String code, String diagnostic, Instant at) {
        fail(code, diagnostic, false, at);
    }

    public void fail(String code, String diagnostic, boolean finalOutcome, Instant at) {
        transition(PaymentTerminalOperationStatus.ERROR, code, diagnostic,
                Map.of("finalOutcome", finalOutcome), at,
                PaymentTerminalOperationStatus.PENDING, PaymentTerminalOperationStatus.SENT,
                PaymentTerminalOperationStatus.TIMEOUT, PaymentTerminalOperationStatus.ERROR);
    }

    public boolean claimProcessing(UUID owner, Instant leaseUntil, Instant now) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(now, "now");
        if (!leaseUntil.isAfter(now)) throw new IllegalArgumentException("El lease debe finalizar en el futuro");
        if (processingOwner != null && processingLeaseUntil != null && processingLeaseUntil.isAfter(now)) return false;
        processingOwner = owner;
        processingLeaseUntil = leaseUntil;
        updatedAt = now;
        return true;
    }

    public void scheduleRetry(Instant nextAttempt, Instant now) {
        Objects.requireNonNull(nextAttempt, "nextAttempt");
        Objects.requireNonNull(now, "now");
        if (!nextAttempt.isAfter(now)) throw new IllegalArgumentException("El reintento debe ser futuro");
        retryCount++;
        nextRetryAt = nextAttempt;
        processingOwner = null;
        processingLeaseUntil = null;
        updatedAt = now;
    }
    public void scheduleDocumentRetry(Instant nextAttempt,Instant now){Objects.requireNonNull(nextAttempt);Objects.requireNonNull(now);
        if(!nextAttempt.isAfter(now))throw new IllegalArgumentException("El reintento debe ser futuro");documentRetryCount++;nextRetryAt=nextAttempt;
        processingOwner=null;processingLeaseUntil=null;updatedAt=now;}

    public void linkDocument(UUID documentId, UUID documentPaymentId, Instant at) {
        if ((operationType != PaymentTerminalOperationType.CHARGE && operationType != PaymentTerminalOperationType.REFUND)
                || (status != PaymentTerminalOperationStatus.APPROVED
                    && status != PaymentTerminalOperationStatus.REFUNDED)) {
            throw new IllegalStateException("La operacion aprobada no admite vinculo documental");
        }
        if (operationType == PaymentTerminalOperationType.CHARGE && documentPaymentId == null)
            throw new IllegalArgumentException("El cobro requiere el pago documental");
        if (operationType == PaymentTerminalOperationType.REFUND && documentPaymentId != null)
            throw new IllegalArgumentException("La devolucion no crea un pago documental negativo");
        if (this.documentId != null || this.documentPaymentId != null) throw new IllegalStateException("Documento ya vinculado");
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.documentPaymentId = documentPaymentId;
        updatedAt = Objects.requireNonNull(at, "at");
    }

    private void transition(PaymentTerminalOperationStatus target, String code, String diagnostic,
            Map<String, ?> metadata, Instant at, PaymentTerminalOperationStatus... allowed) {
        var previous = status;
        var valid = false;
        for (var candidate : allowed) valid |= previous == candidate;
        if (!valid) throw new IllegalStateException("Transicion no permitida: " + previous + " -> " + target);
        status = target;
        updatedAt = Objects.requireNonNull(at, "at");
        if (target == PaymentTerminalOperationStatus.DECLINED
                || target == PaymentTerminalOperationStatus.CANCELLED
                || target == PaymentTerminalOperationStatus.REFUNDED
                || (target == PaymentTerminalOperationStatus.ERROR
                    && Boolean.TRUE.equals(metadata.get("finalOutcome")))) completedAt = at;
        append(previous, target, code, diagnostic, metadata, at);
    }

    private void append(PaymentTerminalOperationStatus previous, PaymentTerminalOperationStatus target,
            String code, String diagnostic, Map<String, ?> metadata, Instant at) {
        events.add(PaymentTerminalEvent.transition(this, previous, target, code, diagnostic, metadata, at));
    }

    private void requireCurrent(PaymentTerminalOperationStatus expected) {
        if (status != expected) throw new IllegalStateException("Estado esperado: " + expected);
    }

    private void rejectVoidGenericApproval() {
        if (operationType == PaymentTerminalOperationType.VOID) {
            throw new IllegalStateException("VOID requiere una ruta de aprobacion tipada");
        }
    }

    public UUID getId() { return id; }
    public UUID getTerminalId() { return terminalId; }
    public UUID getStoreId() { return storeId; }
    public PaymentTerminalProvider getProvider() { return provider; }
    public PaymentTerminalMode getMode() { return mode; }
    public PaymentTerminalOperationType getOperationType() { return operationType; }
    public UUID getOriginalOperationId() { return originalOperationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public BigDecimal getAmount() { return amount; }
    public PaymentTerminalOperationStatus getStatus() { return status; }
    public boolean isFinalOutcome() {
        if (status == PaymentTerminalOperationStatus.ERROR) return completedAt != null;
        return status == PaymentTerminalOperationStatus.APPROVED
                || status == PaymentTerminalOperationStatus.DECLINED
                || status == PaymentTerminalOperationStatus.CANCELLED
                || status == PaymentTerminalOperationStatus.REFUNDED
                || status == PaymentTerminalOperationStatus.PARTIALLY_REFUNDED;
    }
    public String getCurrency() { return currency; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public boolean isDocumentManagedExternally() { return documentManagedExternally; }
    public String getConfigurationHash() { return configurationHash; }
    public long getConfigurationVersion() { return configurationVersion; }
    public boolean matchesConfigurationIdentity(CardTerminalConfiguration configuration) {
        if (provider != configuration.provider()) return false;
        var strict = configurationVersion == configuration.configurationVersion()
                && Objects.equals(configurationHash, configuration.configurationHash());
        var migratedLegacy = legacyConfigurationFingerprint
                && configurationVersion >= 0
                && configurationHash != null
                && configurationVersion == configuration.configurationVersion();
        return strict || migratedLegacy;
    }
    public UUID getProcessingOwner() { return processingOwner; }
    public Instant getProcessingLeaseUntil() { return processingLeaseUntil; }
    public int getRetryCount() { return retryCount; }
    public int getDocumentRetryCount(){return documentRetryCount;}
    public Instant getNextRetryAt() { return nextRetryAt; }
    public UUID getDocumentId() { return documentId; }
    public UUID getDocumentPaymentId() { return documentPaymentId; }
    public List<PaymentTerminalRefundLineSelection> getRefundLineSelections() {
        return PaymentTerminalRefundLineSelection.parse(refundLineSelection);
    }
    public String getExternalReference() { return externalReference; }
    public String getAuthorizationCode() { return authorizationCode; }
    public void releaseProcessing(Instant now) { processingOwner=null; processingLeaseUntil=null; updatedAt=Objects.requireNonNull(now); }
    public List<PaymentTerminalEvent> getEvents() { return Collections.unmodifiableList(events); }

    private static String required(String value, int maximum, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " es obligatorio");
        var trimmed = value.trim();
        if (trimmed.length() > maximum) throw new IllegalArgumentException(field + " es demasiado largo");
        return trimmed;
    }
    private static String hash(String value, String field) {
        var result = required(value, 64, field);
        if (!result.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " no es SHA-256");
        return result;
    }
    private static BigDecimal positive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) throw new IllegalArgumentException(field + " debe ser positivo");
        try { return value.setScale(2, java.math.RoundingMode.UNNECESSARY); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException(field + " admite maximo dos decimales", exception); }
    }
    private static String optional(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        var trimmed = value.trim();
        if (trimmed.length() > maximum) throw new IllegalArgumentException(field + " es demasiado largo");
        return trimmed;
    }
}
