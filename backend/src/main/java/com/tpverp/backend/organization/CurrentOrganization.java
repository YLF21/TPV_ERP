package com.tpverp.backend.organization;

import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentOrganization {

    private final TiendaRepository stores;
    private final UsuarioRepository users;

    public CurrentOrganization(TiendaRepository stores, UsuarioRepository users) {
        this.stores = stores;
        this.users = users;
    }

    public Tienda currentStore() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Usuario user) {
            return user.getTienda();
        }
        return stores.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "La tienda no está inicializada"));
    }

    public Empresa currentCompany() {
        return currentStore().getEmpresa();
    }

    public Usuario currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new IllegalStateException("No hay un usuario autenticado");
        }
        if (authentication.getPrincipal() instanceof Usuario user) {
            if (!user.isActivo()) {
                throw new IllegalStateException("El usuario autenticado está desactivado");
            }
            return user;
        }
        var store = currentStore();
        var name = authentication.getName().trim().toUpperCase(Locale.ROOT);
        return users.findByTiendaIdAndNombre(store.getId(), name)
                .filter(Usuario::isActivo)
                .orElseThrow(() -> new IllegalStateException(
                        "Usuario autenticado no encontrado"));
    }
}
