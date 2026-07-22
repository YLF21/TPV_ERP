package com.tpverp.backend.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class WindowsMachineDpapiSecretProtectorTest {

    @Test
    void protectsAndUnprotectsForTheCurrentWindowsMachine() {
        var protector = new WindowsMachineDpapiSecretProtector();
        var plaintext = "verifactu-machine-secret".getBytes(StandardCharsets.UTF_8);
        byte[] protectedValue = null;
        byte[] recovered = null;
        try {
            protectedValue = protector.protect(plaintext);
            recovered = protector.unprotect(protectedValue);

            assertThat(protectedValue).isNotEqualTo(plaintext);
            assertThat(containsSequence(protectedValue, plaintext)).isFalse();
            assertThat(recovered).isEqualTo(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (protectedValue != null) {
                Arrays.fill(protectedValue, (byte) 0);
            }
            if (recovered != null) {
                Arrays.fill(recovered, (byte) 0);
            }
        }
    }

    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        for (var start = 0; start <= haystack.length - needle.length; start++) {
            var matches = true;
            for (var index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
