package com.tpverp.backend.shared.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;

public record InstallationIdentity(
		String keyId,
		PublicKey publicKey,
		PrivateKey privateKey) {
}
