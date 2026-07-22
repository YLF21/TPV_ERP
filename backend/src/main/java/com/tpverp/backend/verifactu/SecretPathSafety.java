package com.tpverp.backend.verifactu;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class SecretPathSafety {

    private SecretPathSafety() {
    }

    static void rejectLinksAndReparsePoints(Path path) {
        var absolute = path.toAbsolutePath().normalize();
        var current = absolute.getRoot();
        for (var part : absolute) {
            current = current == null ? part : current.resolve(part);
            rejectIfUnsafe(current);
        }
    }

    private static void rejectIfUnsafe(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            var attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw unsafe(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo verificar la ruta segura de VERI*FACTU", exception);
        }
        if (Platform.isWindows()) {
            var attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString());
            if (attributes == WinBase.INVALID_FILE_ATTRIBUTES) {
                throw new IllegalStateException("No se pudieron consultar los atributos NTFS de VERI*FACTU");
            }
            if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw unsafe(path);
            }
        }
    }

    private static IllegalStateException unsafe(Path path) {
        return new IllegalStateException(
                "La ruta de secretos VERI*FACTU contiene un enlace o reparse point no permitido: "
                        + path.getFileName());
    }
}
