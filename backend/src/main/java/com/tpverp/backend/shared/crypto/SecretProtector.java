package com.tpverp.backend.shared.crypto;

public interface SecretProtector {

	byte[] protect(byte[] plaintext);

	byte[] unprotect(byte[] protectedValue);
}
