package com.tpverp.backend.shared.crypto;

import com.sun.jna.Platform;

public final class SecretProtectorFactory {

    private SecretProtectorFactory() {
    }

    public static SecretProtector portableOrWindowsDpapi(String portableSecretKey) {
        if (portableSecretKey != null && !portableSecretKey.isBlank()) {
            return AesGcmSecretProtector.fromBase64(portableSecretKey.trim());
        }
        if (!Platform.isWindows()) {
            throw new IllegalStateException(
                    "TPV_INSTALLATION_PORTABLE_SECRET_KEY es obligatorio fuera de Windows");
        }
        return new WindowsDpapiSecretProtector();
    }
}
