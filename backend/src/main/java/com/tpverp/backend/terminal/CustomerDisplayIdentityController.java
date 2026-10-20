package com.tpverp.backend.terminal;

import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hardware/customer-display-identity")
public class CustomerDisplayIdentityController {

    private final CurrentOrganization organization;
    private final StoreDocumentPrintConfigurationService printConfiguration;

    public CustomerDisplayIdentityController(CurrentOrganization organization,
            StoreDocumentPrintConfigurationService printConfiguration) {
        this.organization = organization;
        this.printConfiguration = printConfiguration;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','CONFIGURACION_TERMINAL')")
    @Transactional(readOnly = true)
    public Identity current() {
        var store = organization.currentStore();
        var presentation = printConfiguration.presentation(DocumentTemplateType.TICKET);
        return new Identity(store.getNombreEfectivo(), store.getEmpresa().getRazonSocial(),
                printConfiguration.logoDataUri(store.getId(), presentation.logo()),
                store.getTimezone(), store.getMoneda());
    }

    public record Identity(String storeName, String companyName, String logoDataUri,
            String timeZone, String currency) {
    }
}
