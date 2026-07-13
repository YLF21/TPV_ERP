package com.tpverp.backend.terminal.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSecretStoreTest {
    private final PaymentSecretReferenceRepository repository = mock(PaymentSecretReferenceRepository.class);
    private final SecretProtector protector = new XorProtector();
    private final PaymentSecretStore store = new ProtectedPaymentSecretStore(
            repository, protector, Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void createsOpaqueReferenceAndResolvesProtectedMaterial() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var view = store.create("REDSYS_TPV_PC", "merchantPassword".toCharArray());
        var persisted = captureSaved();
        when(repository.findActiveScoped(any(),any(),any(),any())).thenReturn(Optional.of(persisted));

        assertThat(view.reference()).startsWith("pts_").doesNotContain("merchantPassword");
        assertThat(view.version()).isEqualTo(1);
        assertThat(new String(store.resolve(view.reference()), StandardCharsets.UTF_8)).isEqualTo("merchantPassword");
        assertThat(new String(persisted.getProtectedValue(), StandardCharsets.UTF_8)).isNotEqualTo("merchantPassword");
    }

    @Test
    void rotationCreatesNewVersionAndRetiresPreviousMaterial() {
        var original = PaymentSecretReference.createVersion(UUID.randomUUID(),PaymentSecretOwnerScope.testing(), "pts_existing", "PAYTEF",1,
                protector.protect("old".getBytes(StandardCharsets.UTF_8)), Instant.parse("2026-07-12T08:00:00Z"));
        when(repository.findActiveForUpdate(any(),any(),any(),any())).thenReturn(Optional.of(original));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var rotated = store.rotate("pts_existing", "new".toCharArray());

        assertThat(rotated.reference()).isEqualTo("pts_existing");
        assertThat(rotated.version()).isEqualTo(2);
        assertThat(original.isActive()).isFalse();
    }

    @Test
    void deletionMakesReferenceUnresolvable() {
        var original = PaymentSecretReference.createVersion(UUID.randomUUID(),PaymentSecretOwnerScope.testing(), "pts_existing", "PAYCOMET",1,
                protector.protect("old".getBytes(StandardCharsets.UTF_8)), Instant.parse("2026-07-12T08:00:00Z"));
        when(repository.findActiveForUpdate(any(),any(),any(),any())).thenReturn(Optional.of(original));
        store.delete("pts_existing");
        when(repository.findActiveScoped(any(),any(),any(),any())).thenReturn(Optional.empty());

        assertThat(original.isActive()).isFalse();
        assertThatThrownBy(() -> store.resolve("pts_existing"))
                .isInstanceOf(PaymentSecretUnavailableException.class);
    }

    private PaymentSecretReference captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(PaymentSecretReference.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static final class XorProtector implements SecretProtector {
        public byte[] protect(byte[] value) { return xor(value); }
        public byte[] unprotect(byte[] value) { return xor(value); }
        private static byte[] xor(byte[] input) {
            var result = input.clone();
            for (int i = 0; i < result.length; i++) result[i] ^= 0x5a;
            return result;
        }
    }
}
