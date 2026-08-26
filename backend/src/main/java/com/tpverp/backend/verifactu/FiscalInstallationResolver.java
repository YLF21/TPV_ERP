package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves the installation that owns fiscal data for an explicit company or
 * operational context. The fallback is only the persisted singleton and its
 * cardinality is checked; no repository ordering is used as an identity rule.
 */
public final class FiscalInstallationResolver {

    private FiscalInstallationResolver() {
    }

    public static Installation resolveCurrent(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses) {
        Objects.requireNonNull(organization, "organization");
        var store = organization.currentStore();
        if (store == null) {
            var company = organization.currentCompany();
            if (company == null) {
                throw new IllegalStateException(
                        "El contexto de tienda y empresa no esta inicializado");
            }
            return resolveForCompany(company.getId(), installations, licenses);
        }
        var company = store.getEmpresa();
        if (company == null) {
            company = organization.currentCompany();
        }
        if (company == null) {
            throw new IllegalStateException("El contexto de tienda y empresa no esta inicializado");
        }
        return resolveForStore(company.getId(), store.getId(), installations, licenses);
    }

    public static Installation resolveForCompany(UUID companyId,
            InstallationRepository installations, LicenseRepository licenses) {
        Objects.requireNonNull(companyId, "companyId");
        if (licenses != null) {
            var activeLicenses = licenses.findActiveByCompanyId(companyId);
            return resolveLicensed(companyId, activeLicenses, installations);
        }
        return resolveSingleton(installations);
    }

    private static Installation resolveForStore(UUID companyId, UUID storeId,
            InstallationRepository installations, LicenseRepository licenses) {
        if (licenses != null) {
            var activeLicenses = licenses.findActiveByTiendaId(storeId);
            var companyLicenses = activeLicenses.stream()
                    .filter(license -> Objects.equals(license.getLocalCompanyId(), companyId))
                    .toList();
            return resolveLicensed(companyId, companyLicenses, installations);
        }
        return resolveSingleton(installations);
    }

    private static Installation resolveLicensed(UUID companyId, List<License> activeLicenses,
            InstallationRepository installations) {
        var installationIds = activeLicenses.stream()
                .map(License::getInstalacionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (installationIds.size() > 1) {
            throw new IllegalStateException(
                    "La empresa tiene varias instalaciones fiscales activas; no se puede determinar una unica");
        }
        if (installationIds.size() == 1) {
            return installations.findById(installationIds.getFirst())
                    .orElseThrow(() -> new IllegalStateException(
                            "La instalacion de la licencia activa no esta inicializada"));
        }
        return resolveSingleton(installations);
    }

    private static Installation resolveSingleton(InstallationRepository installations) {
        var candidates = installations.findAll();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("La instalacion fiscal no esta inicializada");
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException(
                    "Hay varias instalaciones fiscales y ninguna licencia permite resolver el contexto");
        }
        return candidates.getFirst();
    }
}
