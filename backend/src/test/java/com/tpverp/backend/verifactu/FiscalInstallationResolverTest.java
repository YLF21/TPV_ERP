package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalInstallationResolverTest {

    @Test
    void usaLaInstalacionDeLaLicenciaActivaDeLaEmpresa() {
        var companyId = UUID.randomUUID();
        var installation = new Installation("INST-LICENSE", "public-key", Instant.now());
        var license = mock(License.class);
        var installations = mock(InstallationRepository.class);
        var licenses = mock(LicenseRepository.class);
        when(license.getInstalacionId()).thenReturn(installation.getId());
        when(licenses.findActiveByCompanyId(companyId)).thenReturn(List.of(license));
        when(installations.findById(installation.getId())).thenReturn(Optional.of(installation));

        assertThat(FiscalInstallationResolver.resolveForCompany(companyId, installations, licenses))
                .isSameAs(installation);
        verify(installations).findById(installation.getId());
        verify(installations, org.mockito.Mockito.never()).findAll();
    }

    @Test
    void rechazaDosInstalacionesActivasParaLaMismaEmpresa() {
        var companyId = UUID.randomUUID();
        var first = mock(License.class);
        var second = mock(License.class);
        var installations = mock(InstallationRepository.class);
        var licenses = mock(LicenseRepository.class);
        when(first.getInstalacionId()).thenReturn(UUID.randomUUID());
        when(second.getInstalacionId()).thenReturn(UUID.randomUUID());
        when(licenses.findActiveByCompanyId(companyId)).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> FiscalInstallationResolver.resolveForCompany(
                companyId, installations, licenses))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("varias instalaciones fiscales activas");
        verifyNoInteractions(installations);
    }

    @Test
    void contextoDeTiendaSeleccionaSuInstalacionSinMirarLasDeOtrasTiendas() {
        var company = mock(Company.class);
        var companyId = UUID.randomUUID();
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        var installation = new Installation("INST-CURRENT-STORE", "public-key", Instant.now());
        var license = mock(License.class);
        var organization = mock(CurrentOrganization.class);
        var installations = mock(InstallationRepository.class);
        var licenses = mock(LicenseRepository.class);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(store.getEmpresa()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(license.getLocalCompanyId()).thenReturn(companyId);
        when(license.getInstalacionId()).thenReturn(installation.getId());
        when(licenses.findActiveByTiendaId(storeId)).thenReturn(List.of(license));
        when(installations.findById(installation.getId())).thenReturn(Optional.of(installation));

        assertThat(FiscalInstallationResolver.resolveCurrent(organization, installations, licenses))
                .isSameAs(installation);
        verify(licenses).findActiveByTiendaId(storeId);
        verify(licenses, org.mockito.Mockito.never()).findActiveByCompanyId(companyId);
    }

    @Test
    void soloUsaElSingletonCuandoNoHayLicenciaYExigeCardinalidadUnica() {
        var companyId = UUID.randomUUID();
        var installation = new Installation("INST-SINGLETON", "public-key", Instant.now());
        var installations = mock(InstallationRepository.class);
        var licenses = mock(LicenseRepository.class);
        when(licenses.findActiveByCompanyId(companyId)).thenReturn(List.of());
        when(installations.findAll()).thenReturn(List.of(installation));

        assertThat(FiscalInstallationResolver.resolveForCompany(companyId, installations, licenses))
                .isSameAs(installation);

        var another = new Installation("INST-OTHER", "public-key", Instant.now());
        when(installations.findAll()).thenReturn(List.of(installation, another));
        assertThatThrownBy(() -> FiscalInstallationResolver.resolveForCompany(
                companyId, installations, licenses))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("varias instalaciones fiscales");
    }
}
