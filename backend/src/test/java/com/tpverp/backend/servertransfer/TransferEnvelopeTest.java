package com.tpverp.backend.servertransfer;

import static org.assertj.core.api.Assertions.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class TransferEnvelopeTest {
    static KeyPair key,other;
    @BeforeAll static void keys()throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);key=generator.generateKeyPair();other=generator.generateKeyPair();}
    @Test void onlyPersistedDestinationCanOpenBoundCapsule()throws Exception {
        byte[] material="private identity and fiscal material".getBytes(StandardCharsets.UTF_8);
        byte[] capsule=TransferEnvelope.seal(key.getPublic(),material,"installation/transfer/key");
        assertThat(TransferEnvelope.open(key.getPrivate(),capsule,"installation/transfer/key")).isEqualTo(material);
        assertThatThrownBy(()->TransferEnvelope.open(other.getPrivate(),capsule,"installation/transfer/key")).isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(()->TransferEnvelope.open(key.getPrivate(),capsule,"another-transfer")).isInstanceOf(GeneralSecurityException.class);
    }
    @Test void tamperingIsDetectedBeforeAnyPlaintextIsReturned()throws Exception {
        byte[] capsule=TransferEnvelope.seal(key.getPublic(),new byte[4096],"context");capsule[capsule.length-1]^=1;
        assertThatThrownBy(()->TransferEnvelope.open(key.getPrivate(),capsule,"context")).isInstanceOf(GeneralSecurityException.class);
    }
    @Test void malformedAndWeakDestinationKeysAreRejected()throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        assertThatThrownBy(()->TransferEnvelope.publicKey(Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()))).isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(()->TransferEnvelope.open(key.getPrivate(),new byte[20],"context")).isInstanceOf(java.io.IOException.class);
    }
}
