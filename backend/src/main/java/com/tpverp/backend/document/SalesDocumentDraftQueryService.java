package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesDocumentDraftQueryService {

    private static final int MAX_DRAFTS = 200;

    private final CommercialDocumentRepository documents;
    private final CurrentOrganization organization;
    private final CustomerRepository customers;

    public SalesDocumentDraftQueryService(
            CommercialDocumentRepository documents,
            CurrentOrganization organization,
            CustomerRepository customers) {
        this.documents = documents;
        this.organization = organization;
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public java.util.List<SalesDocumentDraftSummaryView> list(
            Collection<CommercialDocumentType> allowedTypes) {
        var types = requireTypes(allowedTypes);
        var storeId = organization.currentStore().getId();
        var ids = documents.findSalesDraftIds(
                storeId, types, PageRequest.of(0, MAX_DRAFTS));
        if (ids.isEmpty()) return java.util.List.of();
        var valuesById = documents.findSalesDraftsWithLines(
                        storeId, ids).stream()
                .collect(Collectors.toMap(CommercialDocument::getId, value -> value));
        var values = ids.stream()
                .map(valuesById::get)
                .filter(Objects::nonNull)
                .toList();
        var names = customerNames(values);
        return values.stream()
                .map(document -> SalesDocumentDraftSummaryView.from(
                        document, names.get(document.getClienteId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SalesDocumentDraftView detail(
            UUID id, Collection<CommercialDocumentType> allowedTypes) {
        var types = requireTypes(allowedTypes);
        var document = documents.findByIdAndTiendaId(
                        Objects.requireNonNull(id, "id"),
                        organization.currentStore().getId())
                .filter(value -> value.getEstado() == DocumentStatus.BORRADOR)
                .filter(value -> types.contains(value.getTipo()))
                .orElseThrow(() -> new IllegalArgumentException("documento no encontrado"));
        var customer = customers.findById(document.getClienteId()).orElse(null);
        return SalesDocumentDraftView.from(document, customerName(customer));
    }

    private Map<UUID, String> customerNames(Collection<CommercialDocument> values) {
        var ids = values.stream()
                .map(CommercialDocument::getClienteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return customers.findAllById(ids).stream()
                .collect(Collectors.toMap(Customer::getId, SalesDocumentDraftQueryService::customerName));
    }

    private static String customerName(Customer customer) {
        if (customer == null) return null;
        if (customer.getFiscalName() != null && !customer.getFiscalName().isBlank()) {
            return customer.getFiscalName();
        }
        return customer.getClientId();
    }

    private static java.util.Set<CommercialDocumentType> requireTypes(
            Collection<CommercialDocumentType> values) {
        var types = java.util.Set.copyOf(values == null ? java.util.Set.of() : values);
        if (types.isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "sales_document_draft_permission_required");
        }
        return types;
    }
}
