package com.tpverp.backend.terminal;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PaymentTerminalReauthenticationService {

    private final CurrentOrganization organization;
    private final PasswordEncoder passwordEncoder;
    private final boolean required;

    public PaymentTerminalReauthenticationService(
            CurrentOrganization organization,
            PasswordEncoder passwordEncoder,
            @Value("${tpv.payment-terminal.adjustments.reauthentication-required:true}") boolean required) {
        this.organization = organization;
        this.passwordEncoder = passwordEncoder;
        this.required = required;
    }

    public void require(Authentication authentication, String password) {
        if (!required) {
            return;
        }
        if (password == null || password.isBlank()) {
            throw new AuthenticationFailedException();
        }
        var user = organization.currentUser(authentication);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthenticationFailedException();
        }
    }
}
