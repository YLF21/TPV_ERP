package com.tpverp.saas.loyalty;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The SaaS-admin bootstrap must complete before the wallet can be used at a POS. */
public final class MemberWalletBootstrapRequiredException extends ResponseStatusException {

    public MemberWalletBootstrapRequiredException(String reason) {
        super(HttpStatus.CONFLICT, reason);
    }
}
