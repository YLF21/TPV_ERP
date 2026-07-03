package com.tpverp.backend.party;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class MemberBalanceExpirationSchedulerTest {

    @Mock MemberLoyaltyService members;
    @Mock Environment environment;

    @Test
    void disabledByDefaultDoesNotExpireLots() {
        new MemberBalanceExpirationScheduler(members, environment).expire();

        verify(members, never()).expireBalanceLots();
    }

    @Test
    void enabledPropertyExpiresLots() {
        when(environment.getProperty(
                "tpv.members.balance-expiration-enabled", Boolean.class, false))
                .thenReturn(true);

        new MemberBalanceExpirationScheduler(members, environment).expire();

        verify(members).expireBalanceLots();
    }
}
