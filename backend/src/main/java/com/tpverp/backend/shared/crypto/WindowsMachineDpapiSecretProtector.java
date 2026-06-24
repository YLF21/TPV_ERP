package com.tpverp.backend.shared.crypto;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

public final class WindowsMachineDpapiSecretProtector implements SecretProtector {

    private static final int FLAGS = WinCrypt.CRYPTPROTECT_LOCAL_MACHINE
            | WinCrypt.CRYPTPROTECT_UI_FORBIDDEN;

    @Override
    public byte[] protect(byte[] plaintext) {
        return Crypt32Util.cryptProtectData(required(plaintext), FLAGS);
    }

    @Override
    public byte[] unprotect(byte[] protectedValue) {
        return Crypt32Util.cryptUnprotectData(required(protectedValue), FLAGS);
    }

    private static byte[] required(byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("El secreto DPAPI es obligatorio");
        }
        return value.clone();
    }
}
