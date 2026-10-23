package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecoverySecretsCodecTest {
    @Test void capsuleRequiresExactKeyInstallationAndBackupAndAuthenticatesEveryByte()throws Exception {
        byte[] brk=new byte[32];new java.security.SecureRandom().nextBytes(brk);
        UUID installation=UUID.randomUUID(),backup=UUID.randomUUID();
        var material=Map.of("identity.private",new byte[]{4,5,6},"fiscal/company/certificate/private-key.dpapi",new byte[]{1,2,3});
        byte[] sealed=RecoverySecretsCodec.seal(material,brk,installation,backup);
        assertThat(RecoverySecretsCodec.open(sealed,brk,installation,backup)).containsExactlyInAnyOrderEntriesOf(material);
        assertThatThrownBy(()->RecoverySecretsCodec.open(sealed,new byte[32],installation,backup)).isInstanceOf(java.security.GeneralSecurityException.class);
        assertThatThrownBy(()->RecoverySecretsCodec.open(sealed,brk,UUID.randomUUID(),backup)).isInstanceOf(java.security.GeneralSecurityException.class);
        assertThatThrownBy(()->RecoverySecretsCodec.open(sealed,brk,installation,UUID.randomUUID())).isInstanceOf(java.security.GeneralSecurityException.class);
        sealed[sealed.length-1]^=1;
        assertThatThrownBy(()->RecoverySecretsCodec.open(sealed,brk,installation,backup)).isInstanceOf(java.security.GeneralSecurityException.class);
    }
    @Test void rawBackupKeyCannotBeArchived() {
        assertThatThrownBy(()->RecoverySecretsCodec.seal(Map.of("backup.brk",new byte[32]),new byte[32],UUID.randomUUID(),UUID.randomUUID())).hasMessageContaining("Raw recovery keys");
    }
}
