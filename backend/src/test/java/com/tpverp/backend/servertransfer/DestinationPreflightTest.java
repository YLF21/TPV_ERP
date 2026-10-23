package com.tpverp.backend.servertransfer;

import static org.assertj.core.api.Assertions.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;

class DestinationPreflightTest {
    static KeyPair keys;static Properties release;
    @BeforeAll static void prepare()throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);keys=generator.generateKeyPair();release=new Properties();
        try(var input=DestinationPreflightTest.class.getResourceAsStream("/META-INF/tpv-erp-release.properties")){release.load(input);}
    }
    @Test void matchingReleaseAndStorageProofAcceptedBeforeFreezing()throws Exception {
        String proof=DestinationPreflight.issue(keys,release.getProperty("release.id"),release.getProperty("schema.version"),8L*1024*1024*1024);
        assertThatCode(()->DestinationPreflight.requireCompatible(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),proof)).doesNotThrowAnyException();
    }
    @Test void wrongReleaseOrInsufficientStorageRejectedBeforeFreezing()throws Exception {
        String key=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        String wrongRelease=DestinationPreflight.issue(keys,"other-release",release.getProperty("schema.version"),8L*1024*1024*1024);
        assertThatThrownBy(()->DestinationPreflight.requireCompatible(key,wrongRelease)).hasMessageContaining("release/schema");
        String insufficient=DestinationPreflight.issue(keys,release.getProperty("release.id"),release.getProperty("schema.version"),1024);
        assertThatThrownBy(()->DestinationPreflight.requireCompatible(key,insufficient)).hasMessageContaining("insufficient space");
    }
}
