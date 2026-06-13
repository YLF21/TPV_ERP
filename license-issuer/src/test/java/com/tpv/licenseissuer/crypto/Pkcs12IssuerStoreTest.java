package com.tpv.licenseissuer.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pkcs12IssuerStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void createsAndReloadsTheSamePasswordProtectedIdentity() {
        Path storePath = tempDir.resolve("issuer.p12");
        char[] password = "correct horse battery staple".toCharArray();
        Pkcs12IssuerStore store = new Pkcs12IssuerStore();

        IssuerIdentity created = store.loadOrCreate(storePath, password);
        IssuerIdentity loaded = store.loadOrCreate(storePath, password);

        assertTrue(storePath.toFile().isFile());
        assertArrayEquals(created.publicKey().getEncoded(), loaded.publicKey().getEncoded());
    }

    @Test
    void rejectsAnIncorrectPassword() {
        Path storePath = tempDir.resolve("issuer.p12");
        Pkcs12IssuerStore store = new Pkcs12IssuerStore();
        store.loadOrCreate(storePath, "first password".toCharArray());

        assertThrows(IllegalArgumentException.class,
                () -> store.loadOrCreate(storePath, "wrong password".toCharArray()));
    }
}
