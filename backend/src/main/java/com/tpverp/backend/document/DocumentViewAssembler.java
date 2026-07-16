package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class DocumentViewAssembler {

    private final CustomerRepository customers;
    private final CurrentOrganization organization;

    public DocumentViewAssembler(
            CustomerRepository customers, CurrentOrganization organization) {
        this.customers = customers;
        this.organization = organization;
    }

    public DocumentView documentView(CommercialDocument document) {
        return documentView(document, null);
    }

    public DocumentView documentView(CommercialDocument document, String qrUrl) {
        return DocumentView.from(document, customerName(document), qrUrl);
    }

    public CustomerReceivableView receivableView(
            CommercialDocument document, LocalDate businessDate) {
        return CustomerReceivableView.from(
                document, customerName(document), businessDate);
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
