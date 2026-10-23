package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import com.tpverp.backend.servertransfer.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class DisasterRecoveryEarlyGuardTest {
    @TempDir Path keys;
    @Test void restoredDatabaseCannotBypassIndependentQuarantineAndScheduledBarrier()throws Exception {
        UUID id=UUID.randomUUID();DisasterRecoveryJournal.freeze(keys,id);
        assertThatThrownBy(()->DisasterRecoveryJournal.freeze(keys,UUID.randomUUID())).hasMessageContaining("CONFLICT");
        var environment=new MockEnvironment().withProperty("tpv.installation.key-directory",keys.toString());
        assertThatThrownBy(()->new DisasterRecoveryEarlyGuard().postProcessEnvironment(environment,null)).hasMessageContaining("QUARANTINED");
        var fence=new ServerTransferFence(new ServerTransferJournal(keys));assertThat(fence.enter()).isFalse();
        assertThat(Files.exists(keys.resolve("installation-private.dpapi"))).isFalse();
        DisasterRecoveryJournal.release(keys,id);assertThat(fence.enter()).isTrue();fence.leave();
    }
    @Test void ordinaryOfflineStartupRequiresNoRecoveryAuthority() {
        var environment=new MockEnvironment().withProperty("tpv.installation.key-directory",keys.toString());
        assertThatCode(()->new DisasterRecoveryEarlyGuard().postProcessEnvironment(environment,null)).doesNotThrowAnyException();
    }
}
