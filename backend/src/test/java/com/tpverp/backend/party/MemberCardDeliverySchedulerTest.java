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
class MemberCardDeliverySchedulerTest {

    @Mock MemberCardDeliveryWorker worker;
    @Mock Environment environment;

    @Test
    void disabledByDefaultDoesNotRun() {
        new MemberCardDeliveryScheduler(worker, environment).tick();

        verify(worker, never()).runOnce();
    }

    @Test
    void enabledPropertyRunsWorker() {
        when(environment.getProperty("tpv.members.card-delivery-enabled", Boolean.class, false))
                .thenReturn(true);

        new MemberCardDeliveryScheduler(worker, environment).tick();

        verify(worker).runOnce();
    }
}
