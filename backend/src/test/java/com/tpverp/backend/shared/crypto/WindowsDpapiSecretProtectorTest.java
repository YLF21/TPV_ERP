package com.tpverp.backend.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class WindowsDpapiSecretProtectorTest {

	@Test
	void protectsAndUnprotectsForTheCurrentWindowsAccount() {
		var protector = new WindowsDpapiSecretProtector();
		var plaintext = "local-installation-secret".getBytes();

		var protectedValue = protector.protect(plaintext);

		assertThat(protectedValue).isNotEqualTo(plaintext);
		assertThat(protector.unprotect(protectedValue)).isEqualTo(plaintext);
	}
}
