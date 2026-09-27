package com.tpverp.backend.document;

import com.tpverp.backend.control.ControlAlertReadRepository;
import com.tpverp.backend.security.sales.SaleOperationCode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Attribution from the checkout that produced the ticket, independent of the reader's session. */
@Service
public class DocumentOperationAttributionService {

    private final ControlAlertReadRepository read;

    public DocumentOperationAttributionService(ControlAlertReadRepository read) {
        this.read = read;
    }

    @Transactional(readOnly = true)
    public Attribution resolve(CommercialDocument document) {
        if (document.getTipo() != CommercialDocumentType.TICKET || document.getConfirmadoEn() == null) {
            return new Attribution(null, List.of());
        }
        var documentIds = List.of(document.getId());
        var operators = read.checkoutOperators(document.getTiendaId(), documentIds).stream()
                .filter(row -> document.getId().equals(row.getDocumentId())).toList();
        var cashiers = operators.stream()
                .filter(row -> row.getUserId() != null && row.getUserName() != null
                        && !row.getUserName().isBlank())
                .map(row -> new SalesDocumentDetailView.CashierView(row.getUserId(), row.getUserName()))
                .distinct().toList();
        var saleAuthorizations = read.authorizationEvidence(document.getTiendaId(), documentIds).stream()
                .filter(row -> document.getId().equals(row.getDocumentId()))
                // Replaying an already completed checkout can audit another credential check;
                // it did not authorize the original ticket.
                .filter(row -> row.getAuthorizedAt() != null
                        && !row.getAuthorizedAt().isAfter(document.getConfirmadoEn()));
        var paymentAuthorizations = read.paymentSessionAuthorizations(document.getTiendaId(), documentIds).stream();
        var authorizations = java.util.stream.Stream.concat(saleAuthorizations, paymentAuthorizations)
                .map(DocumentOperationAttributionService::delegatedAuthorization)
                .filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparing(SalesDocumentDetailView.AuthorizationView::operationCode)
                        .thenComparing(SalesDocumentDetailView.AuthorizationView::authorizerName)
                        .thenComparing(SalesDocumentDetailView.AuthorizationView::authorizerId))
                .toList();
        boolean completeCashierEvidence = operators.stream().allMatch(row -> row.getUserId() != null
                && row.getUserName() != null && !row.getUserName().isBlank());
        return new Attribution(completeCashierEvidence && cashiers.size() == 1 ? cashiers.get(0) : null,
                authorizations);
    }

    private static SalesDocumentDetailView.AuthorizationView delegatedAuthorization(
            ControlAlertReadRepository.AuthorizationEvidence row) {
        if (!"true".equals(row.getDelegated()) || row.getOperatorId() == null
                || row.getAuthorizerId() == null || row.getAuthorizerName() == null
                || row.getAuthorizerName().isBlank() || row.getOperationCode() == null) return null;
        try {
            var authorizerId = UUID.fromString(row.getAuthorizerId());
            if (authorizerId.equals(row.getOperatorId())) return null;
            var operation = SaleOperationCode.valueOf(row.getOperationCode());
            return new SalesDocumentDetailView.AuthorizationView(
                    operation.name(), authorizerId, row.getAuthorizerName(), true);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record Attribution(SalesDocumentDetailView.CashierView cashier,
            List<SalesDocumentDetailView.AuthorizationView> authorizations) { }
}
