package com.tpverp.backend.document;

import com.tpverp.backend.document.template.CustomerModel347JasperRenderer;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.security.application.PermissionChecks;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerModel347Service {

    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final CustomerModel347Repository reports;
    private final CustomerModel347JasperRenderer renderer;

    public CustomerModel347Service(CurrentOrganization organization, CustomerRepository customers,
            CustomerModel347Repository reports, CustomerModel347JasperRenderer renderer) {
        this.organization = organization;
        this.customers = customers;
        this.reports = reports;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] generate(UUID customerId, int year, String locale, Authentication authentication) {
        if (!PermissionChecks.hasSalesDocumentRead(authentication, "INVOICES_READ")) {
            throw new AccessDeniedException("Invoice read permission required");
        }
        if (customerId == null || year < 1 || year > 9998 || locale == null
                || !Set.of("es", "en", "zh").contains(locale)) {
            throw new IllegalArgumentException("Invalid annual customer report parameters");
        }
        var company = organization.currentCompany();
        var customer = customers.findByIdAndCompanyId(customerId, company.getId())
                .orElseThrow(() -> new NoSuchElementException("Customer not found"));
        var totals = reports.quarterTotals(company.getId(), customerId, year).stream()
                .collect(Collectors.toMap(CustomerModel347Report.Quarter::number, quarter -> quarter));
        var quarters = new ArrayList<CustomerModel347Report.Quarter>(4);
        var annual = new BigDecimal("0.00");
        for (int number = 1; number <= 4; number++) {
            var quarter = totals.getOrDefault(number,
                    new CustomerModel347Report.Quarter(number, new BigDecimal("0.00"), 0));
            quarters.add(quarter);
            annual = annual.add(quarter.total());
        }
        var issuerAddress = company.getDomicilioFiscal();
        var address = customer.getFiscalAddress();
        var report = new CustomerModel347Report(year,
                new CustomerModel347Report.Party("", company.getTaxId(), company.getRazonSocial(),
                        address(issuerAddress.get("linea1"), issuerAddress.get("linea2"),
                                issuerAddress.get("codigoPostal"), issuerAddress.get("ciudad"),
                                issuerAddress.get("provincia"), issuerAddress.get("pais"))),
                new CustomerModel347Report.Party(customer.getClientId(), customer.getDocumentNumber(),
                        customer.getFiscalName(), address == null ? "" : address(address.getAddress(),
                                address.getPostalCode(), address.getCity(), address.getProvince(), address.getCountry())),
                quarters, annual);
        return renderer.render(report, locale);
    }

    private static String address(String... parts) {
        return Stream.of(parts).filter(part -> part != null && !part.isBlank())
                .map(String::trim).collect(Collectors.joining(", "));
    }
}
