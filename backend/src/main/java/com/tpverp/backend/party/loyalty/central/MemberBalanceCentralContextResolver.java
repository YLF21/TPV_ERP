package com.tpverp.backend.party.loyalty.central;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.StoreRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberBalanceCentralContextResolver {

    private final LicenseRepository licenses;
    private final StoreRepository stores;
    private final Environment environment;

    public MemberBalanceCentralContextResolver(
            LicenseRepository licenses,
            StoreRepository stores,
            Environment environment) {
        this.licenses = licenses;
        this.stores = stores;
        this.environment = environment;
    }

    @Transactional(readOnly = true)
    public CentralContext resolve(UUID localStoreId) {
        if (localStoreId == null) {
            throw unavailable("La tienda local es obligatoria");
        }
        License license = licenses.findFirstByTienda_IdAndActivaTrueOrderByValidaDesdeDesc(localStoreId)
                .orElse(null);
        if (license == null) {
            return developmentContextOrThrow(
                    localStoreId,
                    "No existe una licencia SaaS activa para la tienda");
        }
        UUID companyId = license.getSaasCompanyId();
        UUID storeId = license.getSaasStoreId();
        if (companyId == null || storeId == null) {
            return developmentContextOrThrow(
                    localStoreId,
                    "La licencia activa no contiene identidad SaaS de empresa y tienda");
        }
        return new CentralContext(companyId, storeId);
    }

    @Transactional(readOnly = true)
    public List<BootstrapContext> resolveBootstrapContexts() {
        var byCentralStore = new LinkedHashMap<UUID, BootstrapContext>();
        for (License license : licenses.findByActivaTrueOrderByValidaDesdeDesc()) {
            UUID companyId = license.getSaasCompanyId();
            UUID storeId = license.getSaasStoreId();
            if (companyId == null || storeId == null || byCentralStore.containsKey(storeId)) {
                continue;
            }
            byCentralStore.put(storeId, new BootstrapContext(
                    license.getLocalCompanyId(),
                    license.getTiendaId(),
                    companyId,
                    storeId));
        }
        return List.copyOf(byCentralStore.values());
    }

    private CentralContext developmentContextOrThrow(UUID localStoreId, String productionMessage) {
        if (!environment.acceptsProfiles(Profiles.of("dev"))) {
            throw unavailable(productionMessage);
        }
        var store = stores.findById(localStoreId)
                .orElseThrow(() -> unavailable("La tienda local no existe"));
        return new CentralContext(store.getEmpresa().getId(), store.getId());
    }

    private MemberBalanceCentralException unavailable(String message) {
        return new MemberBalanceCentralException(MemberBalanceCentralException.Kind.UNAVAILABLE, message);
    }

    public record CentralContext(UUID companyId, UUID storeId) {
    }

    public record BootstrapContext(
            UUID localCompanyId,
            UUID localStoreId,
            UUID companyId,
            UUID storeId) {
    }
}
