package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DocumentViewAssembler {

    private final CustomerRepository customers;
    private final CurrentOrganization organization;
    private final DocumentAttributionResolver attributions;

    public DocumentViewAssembler(
            CustomerRepository customers,
            CurrentOrganization organization,
            DocumentAttributionResolver attributions) {
        this.customers = customers;
        this.organization = organization;
        this.attributions = attributions;
    }

    public DocumentView documentView(CommercialDocument document) {
        return documentView(document, null);
    }

    public DocumentView documentView(CommercialDocument document, String qrUrl) {
        var attribution = attributions.resolve(List.of(document)).get(document.getId());
        return DocumentView.from(document, customerName(document), qrUrl, attribution);
    }

    public List<DocumentView> documentViews(
            List<CommercialDocument> documents,
            Function<UUID, String> qrUrlResolver) {
        if (documents.isEmpty()) {
            return List.of();
        }

        var attributionIndex = attributions.resolve(documents);
        var customerIds = documents.stream()
                .map(CommercialDocument::getClienteId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> customerNames;
        if (customerIds.isEmpty()) {
            customerNames = Map.of();
        } else {
            var companyId = organization.currentCompany().getId();
            customerNames = customers.findByCompanyIdAndIdIn(companyId, customerIds).stream()
                    .collect(Collectors.toMap(
                            customer -> customer.getId(),
                            customer -> customer.getFiscalName()));
        }

        return documents.stream()
                .map(document -> DocumentView.from(
                        document,
                        customerNames.get(document.getClienteId()),
                        qrUrlResolver.apply(document.getId()),
                        attributionIndex.get(document.getId())))
                .toList();
    }

    public CustomerReceivableView receivableView(
            CommercialDocument document, LocalDate businessDate) {
        return CustomerReceivableView.from(
                document, customerName(document), businessDate);
    }

    public CustomerReceivablePaymentHistoryView receivablePaymentHistory(
            DocumentPayment payment) {
        return CustomerReceivablePaymentHistoryView.from(
                payment, customerName(payment.getDocumento()));
    }

    private String customerName(CommercialDocument document) {
        if (document.getClienteId() == null) {
            return null;
        }
        var companyId = organization.currentCompany().getId();
        return customers.findByIdAndCompanyId(document.getClienteId(), companyId)
                .map(customer -> customer.getFiscalName())
                .orElse(null);
    }
}
