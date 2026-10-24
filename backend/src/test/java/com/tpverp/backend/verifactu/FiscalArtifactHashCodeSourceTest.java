package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalArtifactHashCodeSourceTest {

    @TempDir Path directory;

    @Test
    void verifiesTheCompletePhysicalJarThroughAFileCodeSource() throws Exception {
        var jar = createJar();
        var hash = writeSidecar(jar);

        assertThat(resolve(jar.toUri().toURL())).isEqualTo(hash);
    }

    @Test
    void verifiesTheCompletePhysicalJarThroughAJarConnection() throws Exception {
        var jar = createJar();
        var hash = writeSidecar(jar);
        var location = new URL("jar:" + jar.toUri().toASCIIString() + "!/BOOT-INF/classes/");

        assertThat(resolve(location)).isEqualTo(hash);
    }

    @Test
    void verifiesTheOuterJarOfBootNestedCodeSourceWithoutAJarFileSystem() throws Exception {
        var jar = createJar();
        var hash = writeSidecar(jar);
        var location = bootNestedCodeSource(jar);

        assertThat(resolve(location)).isEqualTo(hash);
        verify(((JarURLConnection) location.openConnection()).getJarFile(), never()).close();
    }

    @Test
    void doesNotSelectADifferentSignedFileBesideADirectoryEndingInBang() throws Exception {
        var distractor = Files.writeString(directory.resolve("store"), "another artifact");
        var distractorHash = writeSidecar(distractor);
        var actualDirectory = Files.createDirectory(directory.resolve("store!"));
        var jar = createJar(actualDirectory);
        var hash = writeSidecar(jar);

        assertThat(resolve(bootNestedCodeSource(jar))).isEqualTo(hash).isNotEqualTo(distractorHash);
    }

    @Test
    void rejectsAnUnrecognizedNestedEntryInsteadOfGuessingTheOuterArtifact() throws Exception {
        var jar = createJar();
        writeSidecar(jar);

        assertThatThrownBy(() -> resolve(bootNestedCodeSource(jar, "BOOT-INF/other/")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocolo del CodeSource");
    }

    @Test
    void rejectsAnAlteredOuterJarEvenWhenItsEmbeddedSidecarIsPresent() throws Exception {
        var jar = createJar();
        writeSidecar(jar);
        Files.writeString(jar, "changed complete artifact");

        assertThatThrownBy(() -> resolve(bootNestedCodeSource(jar)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no coincide con su sidecar");
    }

    @Test
    void doesNotUseTheEmbeddedSidecarWhenTheAdjacentOneIsAbsent() throws Exception {
        var jar = createJar();

        assertThat(resolve(bootNestedCodeSource(jar))).isNull();
    }

    @Test
    void rejectsAMalformedAdjacentSidecar() throws Exception {
        var jar = createJar();
        Files.writeString(Path.of(jar + ".sha256"), "not-a-sha256");

        assertThatThrownBy(() -> resolve(bootNestedCodeSource(jar)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no es SHA-256");
    }

    @Test
    void preservesExplodedDirectoryHashingAndExcludesItsSidecar() throws Exception {
        var classes = Files.createDirectory(directory.resolve("classes"));
        var content = "class bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(classes.resolve("Example.class"), content);
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update("Example.class".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(content);
        digest.update((byte) 0);
        var hash = HexFormat.of().withUpperCase().formatHex(digest.digest());
        Files.writeString(classes.resolve("tpv-erp-release.sha256"), hash);

        assertThat(resolve(classes.toUri().toURL())).isEqualTo(hash);
    }

    @Test
    void rejectsUnsupportedSourcesWithoutFallingBackToAnotherArtifact() throws Exception {
        assertThatThrownBy(() -> resolve(new URL("https://example.invalid/application.jar")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocolo del CodeSource");
    }

    private Path createJar() throws Exception {
        return createJar(directory);
    }

    private Path createJar(Path parent) throws Exception {
        var jar = parent.resolve("aplicación + 100% #!.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("BOOT-INF/classes/"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("BOOT-INF/classes/tpv-erp-release.sha256"));
            output.write("A".repeat(64).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private String writeSidecar(Path jar) throws Exception {
        var hash = HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        Files.writeString(Path.of(jar + ".sha256"), hash.toLowerCase() + "  application.jar\n");
        return hash;
    }

    private URL bootNestedCodeSource(Path jar) throws Exception {
        return bootNestedCodeSource(jar, "BOOT-INF/classes/");
    }

    private URL bootNestedCodeSource(Path jar, String entry) throws Exception {
        var archive = mock(JarFile.class);
        // Forward slashes also exercise POSIX/canonical JarFile names on Windows.
        when(archive.getName()).thenReturn(jar.toString().replace('\\', '/') + "!/" + entry);
        var connection = mock(JarURLConnection.class);
        when(connection.getJarFile()).thenReturn(archive);
        // The handler stands in for Boot's launcher. No ZIP filesystem is
        // opened by this test; the physical archive still must be hashed.
        var handler = new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                return connection;
            }
        };
        var nested = new URL(null, "nested:" + jar.toUri().getRawPath() + "/!" + entry, handler);
        when(connection.getJarFileURL()).thenReturn(nested);
        return new URL(null, "jar:" + nested + "!/", handler);
    }

    private String resolve(URL location) {
        return FiscalRuntimeProperties.resolveArtifactHashFromCodeSource(location);
    }
}
