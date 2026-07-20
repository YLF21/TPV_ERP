package com.tpverp.bridge.spi;

import java.util.Optional;

/** Runtime services supplied by the local bridge without exposing its internals. */
public interface AdapterRuntime {
    <T> T withSecret(String reference, SecretUse<T> use);
    Optional<byte[]> readState(String namespace, String key);
    void writeState(String namespace, String key, byte[] value);
    void deleteState(String namespace, String key);

    @FunctionalInterface
    interface SecretUse<T> {
        T apply(byte[] secret);
    }

    static AdapterRuntime unavailable() {
        return new AdapterRuntime() {
            @Override public <T> T withSecret(String reference, SecretUse<T> use) {
                throw new IllegalStateException("Local secret vault is unavailable");
            }
            @Override public Optional<byte[]> readState(String namespace, String key) { return Optional.empty(); }
            @Override public void writeState(String namespace, String key, byte[] value) { }
            @Override public void deleteState(String namespace, String key) { }
        };
    }
}
