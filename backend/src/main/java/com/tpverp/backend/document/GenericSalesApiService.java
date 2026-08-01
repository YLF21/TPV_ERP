package com.tpverp.backend.document;

import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fail-closed application-service boundary for legacy generic sales APIs.
 *
 * <p>Purchase documents keep their existing permission model. Sales documents
 * must pass the same configurable operation policies as the POS endpoints
 * immediately before delegating to the mutating document service.</p>
 */
@Service
public class GenericSalesApiService {

    private static final EnumSet<CommercialDocumentType> SALES_TYPES = EnumSet.of(
            CommercialDocumentType.ALBARAN_VENTA,
            CommercialDocumentType.FACTURA_VENTA,
            CommercialDocumentType.RECTIFICATIVA_VENTA,
            CommercialDocumentType.TICKET);

    private final DocumentService documents;
    private final SaleDocumentMutationAuthorizationService authorizations;
    private final CustomerPendingSaleService pendingSales;
    private final SaleDocumentAuthorizationManifestService manifests;

    public GenericSalesApiService(
            DocumentService documents,
            SaleDocumentMutationAuthorizationService authorizations,
            CustomerPendingSaleService pendingSales,
            SaleDocumentAuthorizationManifestService manifests) {
        this.documents = documents;
        this.authorizations = authorizations;
        this.pendingSales = pendingSales;
        this.manifests = manifests;
    }

    @Transactional
    public CommercialDocument createInvoice(
            DocumentRequest request, Authentication authentication) {
        var command = Objects.requireNonNull(request, "request").toCommand();
        var proof = authorizeDocument(
                command, request.operationAuthorizations(), authentication,
                "GENERIC_INVOICE_CREATE", null);
        var document = documents.createInvoice(command, authentication);
        if (isSale(command.tipo())) {
            manifests.record(document, proof);
        }
        return document;
    }

    @Transactional
    public CommercialDocument createAndConfirmInvoice(
            DocumentRequest request, Authentication authentication) {
        var command = Objects.requireNonNull(request, "request").toCommand();
        if (!isSale(command.tipo())) {
            return documents.createAndConfirmInvoice(command, authentication);
        }
        authorizations.authorize(
                command, request.operationAuthorizations(), authentication,
                "GENERIC_INVOICE_CREATE_CONFIRMED", null);
        var draft = documents.createInvoice(command, authentication);
        return confirmAuthorizedSale(
                draft,
                request.operationAuthorizations(),
                request.creditOverrideReason(),
                authentication);
    }

    @Transactional
    public CommercialDocument createDeliveryNote(
            DocumentRequest request, Authentication authentication) {
        var command = Objects.requireNonNull(request, "request").toCommand();
        var proof = authorizeDocument(
                command, request.operationAuthorizations(), authentication,
                "GENERIC_DELIVERY_NOTE_CREATE", null);
        var document = documents.createDeliveryNote(command, authentication);
        if (isSale(command.tipo())) {
            manifests.record(document, proof);
        }
        return document;
    }

    @Transactional
    public CommercialDocument createAndConfirmDeliveryNote(
            DocumentRequest request, Authentication authentication) {
        var command = Objects.requireNonNull(request, "request").toCommand();
        if (!isSale(command.tipo())) {
            return documents.createAndConfirmDeliveryNote(command, authentication);
        }
        authorizations.authorize(
                command, request.operationAuthorizations(), authentication,
                "GENERIC_DELIVERY_NOTE_CREATE_CONFIRMED", null);
        var draft = documents.createDeliveryNote(command, authentication);
        return confirmAuthorizedSale(
                draft,
                request.operationAuthorizations(),
                request.creditOverrideReason(),
                authentication);
    }

    @Transactional
    public CommercialDocument confirm(
            UUID documentId,
            SaleOperationAuthorizationsRequest request,
            Authentication authentication) {
        var document = documents.find(documentId);
        if (isSale(document.getTipo())) {
            var envelope = request == null
                    ? SaleOperationAuthorizationsRequest.empty()
                    : request;
            var validation = manifests.validate(document);
            if (validation.policyChanged()) {
                var proof = authorizations.reauthorize(
                        document,
                        validation.operations(),
                        envelope.operationAuthorizations(),
                        authentication,
                        "GENERIC_SALE_CONFIRMATION_REAUTHORIZATION",
                        document.getId());
                manifests.refresh(document, proof);
            }
            return confirmAuthorizedSale(
                    document,
                    envelope.operationAuthorizations(),
                    envelope.creditOverrideReason(),
                    authentication);
        }
        return documents.confirm(documentId, authentication);
    }

    @Transactional
    public CommercialDocument payInvoice(
            UUID documentId, PaymentRequest request, Authentication authentication) {
        var document = documents.find(documentId);
        authorizePaymentsIfSale(
                document, request, authentication, "GENERIC_INVOICE_PAYMENT");
        return documents.payInvoice(documentId, request.toCommands(), authentication);
    }

    @Transactional
    public CommercialDocument payDeliveryNote(
            UUID documentId, PaymentRequest request, Authentication authentication) {
        var document = documents.find(documentId);
        authorizePaymentsIfSale(
                document, request, authentication, "GENERIC_DELIVERY_NOTE_PAYMENT");
        return documents.payDeliveryNote(documentId, request.toCommands(), authentication);
    }

    @Transactional
    public CommercialDocument createTicket(
            TicketController.CreateTicketRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var command = request.document().toCommand();
        var credentials = mergedAuthorizations(
                mergedAuthorizations(
                        request.document().operationAuthorizations(),
                        request.operationAuthorizations()),
                request.payments() == null
                        ? Map.of()
                        : request.payments().operationAuthorizations());
        authorizations.authorize(
                command,
                credentials,
                authentication,
                "LEGACY_TICKET",
                null);
        if (request.payments() != null) {
            authorizations.authorizePayments(
                    request.payments(),
                    credentials,
                    SaleDocumentMutationAuthorizationService.IntegratedPaymentPolicy
                            .REJECT_UNPROVEN,
                    authentication,
                    "LEGACY_TICKET",
                    null);
        }
        return documents.createTicket(
                command,
                request.paymentCommands(),
                authentication);
    }

    private SaleDocumentMutationAuthorizationService.AuthorizationProof authorizeDocument(
            DocumentCommand command,
            Map<SaleOperationCode, OperationAuthorizationRequest> credentials,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        if (isSale(command.tipo())) {
            return authorizations.authorize(
                    command, credentials, authentication, sourceType, sourceId);
        }
        return SaleDocumentMutationAuthorizationService.AuthorizationProof.empty();
    }

    private void authorizePaymentsIfSale(
            CommercialDocument document,
            PaymentRequest request,
            Authentication authentication,
            String sourceType) {
        Objects.requireNonNull(request, "request");
        if (isSale(document.getTipo())) {
            authorizations.authorizePayments(
                    request,
                    request.operationAuthorizations(),
                    SaleDocumentMutationAuthorizationService.IntegratedPaymentPolicy
                            .REJECT_UNPROVEN,
                    authentication,
                    sourceType,
                    document.getId());
        }
    }

    private CommercialDocument confirmAuthorizedSale(
            CommercialDocument document,
            Map<SaleOperationCode, OperationAuthorizationRequest> credentials,
            String creditOverrideReason,
            Authentication authentication) {
        var customerId = Objects.requireNonNull(
                document.getClienteId(),
                "customer_receivable_customer_required");
        var pendingCredentials = credential(
                credentials, SaleOperationCode.CREATE_PENDING_RECEIVABLE);
        var overrideCredentials = credential(
                credentials, SaleOperationCode.CREDIT_OVERRIDE);
        var pendingAuthorization = pendingSales.authorizePendingTicket(
                customerId,
                document.getFecha(),
                document.getPendingTotal(),
                creditOverrideReason,
                pendingCredentials.authorizerUsername(),
                pendingCredentials.authorizerPassword(),
                overrideCredentials.authorizerUsername(),
                overrideCredentials.authorizerPassword(),
                authentication);
        var saved = documents.confirm(document.getId(), authentication);
        pendingSales.recordPendingTicketAuthorization(
                document.getId(),
                saved,
                customerId,
                creditOverrideReason,
                pendingAuthorization);
        return saved;
    }

    private static OperationAuthorizationRequest credential(
            Map<SaleOperationCode, OperationAuthorizationRequest> credentials,
            SaleOperationCode operation) {
        return (credentials == null ? Map
                .<SaleOperationCode, OperationAuthorizationRequest>of() : credentials)
                .getOrDefault(operation, OperationAuthorizationRequest.empty());
    }

    private static boolean isSale(CommercialDocumentType type) {
        return SALES_TYPES.contains(type);
    }

    private static Map<SaleOperationCode, OperationAuthorizationRequest>
            mergedAuthorizations(
                    Map<SaleOperationCode, OperationAuthorizationRequest> primary,
                    Map<SaleOperationCode, OperationAuthorizationRequest> secondary) {
        var merged = new EnumMap<SaleOperationCode, OperationAuthorizationRequest>(
                SaleOperationCode.class);
        merged.putAll(primary == null ? Map.of() : primary);
        for (var entry : (secondary == null ? Map
                .<SaleOperationCode, OperationAuthorizationRequest>of() : secondary)
                .entrySet()) {
            var existing = merged.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new IllegalArgumentException(
                        "conflicting_operation_authorization");
            }
        }
        return Map.copyOf(merged);
    }
}
