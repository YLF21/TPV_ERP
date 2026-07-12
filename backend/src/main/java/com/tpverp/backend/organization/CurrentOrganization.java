package com.tpverp.backend.organization;

import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentOrganization {

    private final StoreRepository stores;
    private final UserAccountRepository users;

    public CurrentOrganization(StoreRepository stores, UserAccountRepository users) {
        this.stores = stores;
        this.users = users;
    }

    public Store currentStore() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user) {
            return user.getTienda();
        }
        return stores.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "message.organization.store_not_initialized"));
    }

    public Company currentCompany() {
        Store current = currentStore();
        return stores.findWithCompanyById(current.getId())
                .map(Store::getEmpresa)
                .orElseGet(current::getEmpresa);
    }

    public UserAccount currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new IllegalStateException("No hay un usuario autenticado");
        }
        if (authentication.getPrincipal() instanceof UserAccount user) {
            if (!user.isActivo()) {
                throw new IllegalStateException("message.organization.authenticated_user_disabled");
            }
            return user;
        }
        var store = currentStore();
        var name = authentication.getName().trim().toUpperCase(Locale.ROOT);
        return users.findByTiendaIdAndNombre(store.getId(), name)
                .filter(UserAccount::isActivo)
                .orElseThrow(() -> new IllegalStateException(
                        "message.organization.authenticated_user_not_found"));
    }
}
