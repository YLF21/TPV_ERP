package com.tpverp.backend.installation;

import com.tpverp.backend.security.domain.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Identifies the untouched first-installation account without changing credentials. */
@Component
public class InitialAdminPasswordState {
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;

    public InitialAdminPasswordState(UserAccountRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public boolean isRequired() {
        return users.findGlobalByUserName("ADMIN")
                .filter(user -> user.getTienda() == null && user.isProtegido() && user.isActivo()
                        && user.getRol().isProtegido() && user.mustChangePassword())
                .filter(user -> passwords.matches("0000", user.getPasswordHash()))
                .isPresent();
    }
}
