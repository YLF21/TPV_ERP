package com.tpverp.backend.shared.crypto;

import com.sun.jna.platform.win32.Crypt32Util;

public final class WindowsDpapiSecretProtector implements SecretProtector {

	@Override
	public byte[] protect(byte[] plaintext) {
		return Crypt32Util.cryptProtectData(plaintext);
	}

	@Override
	public byte[] unprotect(byte[] protectedValue) {
		return Crypt32Util.cryptUnprotectData(protectedValue);
	}
}
