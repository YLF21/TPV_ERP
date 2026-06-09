package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.security.domain.UsuarioRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock SupplierRepository suppliers;
    @Mock SalesRepresentativeRepository representatives;
    @Mock SupplierRepresentativeRepository links;
    @Mock TiendaRepository stores;
    @Mock UsuarioRepository users;

    private Empresa company;
    private Supplier supplier;
    private SalesRepresentative representative;

    @BeforeEach
    void setUp() {
        company = PartyTestData.company();
        Tienda store = PartyTestData.store(company);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ADMIN", "token"));
        when(stores.findAll()).thenReturn(List.of(store));
        supplier = new Supplier(
                company, "Proveedor", null, DocumentType.CIF, "B1",
                null, null, null, null);
        representative = new SalesRepresentative(company, "Comercial", null, null, null);
    }

    @Test
    void linksRepresentativeAsTheOnlyPrimaryForSupplier() {
        when(suppliers.findByIdAndCompanyId(supplier.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(supplier));
        when(representatives.findByIdAndCompanyId(
                representative.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(representative));
        when(links.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service().linkRepresentative(
                supplier.getId(), representative.getId(), true);

        assertThat(result.primary()).isTrue();
        assertThat(supplier.getRepresentatives())
                .filteredOn(SupplierRepresentative::isPrimary)
                .hasSize(1);
    }

    @Test
    void listsSuppliersByDocumentNumber() {
        when(suppliers.findByCompanyIdOrderByDocumentNumberAsc(PartyTestData.id(company)))
                .thenReturn(List.of(supplier));

        assertThat(service().list())
                .extracting(SupplierService.SupplierView::documentNumber)
                .containsExactly("B1");
        verify(suppliers)
                .findByCompanyIdOrderByDocumentNumberAsc(PartyTestData.id(company));
    }

    private SupplierService service() {
        var organization = new CurrentOrganization(stores, users);
        return new SupplierService(suppliers, representatives, links,
                new PartyContext(organization));
    }
}
