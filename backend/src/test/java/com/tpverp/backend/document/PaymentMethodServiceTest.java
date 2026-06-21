package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Empresa;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentMethodServiceTest {

    @Mock
    private MetodoPagoRepository repository;
    @Mock
    private CurrentOrganization organization;
    @Mock
    private Empresa company;

    private PaymentMethodService service() {
        return new PaymentMethodService(repository, organization);
    }

    private UUID currentCompany() {
        var companyId = UUID.randomUUID();
        when(organization.currentCompany()).thenReturn(company);
        when(company.getId()).thenReturn(companyId);
        return companyId;
    }

    @Test
    void protectedMethodCannotBeDisabled() {
        var companyId = currentCompany();
        var method = new MetodoPago(companyId, "EFECTIVO", true);
        when(repository.findByIdAndEmpresaId(method.getId(), companyId)).thenReturn(Optional.of(method));

        assertThatThrownBy(() -> service().setActive(method.getId(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protegido");
    }

    @Test
    void rejectsCompanyFromAnotherTenant() {
        currentCompany();
        var foreignCompanyId = UUID.randomUUID();

        assertThatThrownBy(() -> service().list(foreignCompanyId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empresa no encontrada");
        verify(repository, never()).findAllByEmpresaIdOrderByNombre(foreignCompanyId);
    }

    @Test
    void rejectsPaymentMethodUuidFromAnotherTenant() {
        var companyId = currentCompany();
        var foreignMethodId = UUID.randomUUID();
        when(repository.findByIdAndEmpresaId(foreignMethodId, companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().setActive(foreignMethodId, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("método de pago no encontrado");
    }
}
