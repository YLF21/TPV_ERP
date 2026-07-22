package com.tpverp.backend.verifactu;

import java.nio.file.Path;

interface SecretDirectoryAccessPolicy {

    void prepareRoot(Path root);

    void secureDirectory(Path directory);

    void secureFile(Path file);

    void verifyDirectory(Path directory);

    void verifyFile(Path file);
}
