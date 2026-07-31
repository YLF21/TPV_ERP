package com.tpverp.backend.verifactu;

import java.nio.file.Files;
import java.nio.file.Path;

final class KeytoolTestSupport {

    private KeytoolTestSupport() {
    }

    static String executable() {
        var binDirectory = Path.of(System.getProperty("java.home"), "bin");
        var unixExecutable = binDirectory.resolve("keytool");
        return Files.isExecutable(unixExecutable)
                ? unixExecutable.toString()
                : binDirectory.resolve("keytool.exe").toString();
    }
}
