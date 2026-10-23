package com.tpverp.backend.servertransfer;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ServerTransferEarlyGuardTest {
    @TempDir Path keys;
    @Test void retiredSourceCannotBootEvenWhenDatabaseWasReplaced(){
        new ServerTransferJournal(keys).freeze(UUID.randomUUID());
        var environment=new MockEnvironment().withProperty("tpv.installation.key-directory",keys.toString());
        assertThatThrownBy(()->new ServerTransferEarlyGuard().postProcessEnvironment(environment,null)).hasMessageContaining("SERVER_TRANSFER_FROZEN");
    }
    @Test void normalOfflineInstallationNeedsNoSaasProof(){
        var environment=new MockEnvironment().withProperty("tpv.installation.key-directory",keys.toString());
        assertThatCode(()->new ServerTransferEarlyGuard().postProcessEnvironment(environment,null)).doesNotThrowAnyException();
    }
}
