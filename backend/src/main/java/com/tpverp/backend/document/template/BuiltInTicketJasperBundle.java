package com.tpverp.backend.document.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Materializes the versioned built-in ticket JRXML sources as runtime Jasper files. */
@Component
class BuiltInTicketJasperBundle {

    private static final String RESOURCE_ROOT = "reports/tickets/";
    private static final String SUBREPORT_ROOT = RESOURCE_ROOT + "subreport/";

    private final TicketJrxmlBundleCompiler compiler;
    private final DocumentTemplateArtifactStorage storage;
    private volatile Path cachedMaster;

    BuiltInTicketJasperBundle(
            TicketJrxmlBundleCompiler compiler,
            DocumentTemplateArtifactStorage storage) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    Path compiledMaster() {
        Path current = cachedMaster;
        if (current != null && Files.isRegularFile(current)) {
            return current;
        }
        synchronized (this) {
            current = cachedMaster;
            if (current != null && Files.isRegularFile(current)) {
                return current;
            }
            try {
                Map<String, byte[]> sources = readSources();
                String sha256 = TicketJrxmlBundleCompiler.bundleSha256(sources);
                UUID artifactId = UUID.nameUUIDFromBytes(
                        ("tpv-built-in-ticket:" + sha256).getBytes(StandardCharsets.UTF_8));
                String reference = artifactId.toString();
                if (!usable(reference, sha256)) {
                    var compiled = compiler.compile(sources);
                    storage.writeBundle(artifactId,
                            TicketJrxmlBundleCompiler.MASTER_FILENAME, compiled.reports());
                }
                cachedMaster = storage.compiledBundleMaster(
                        reference, TicketJrxmlBundleCompiler.MASTER_FILENAME);
                return cachedMaster;
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "ticket_jasper_builtin_bundle_compile_failed", exception);
            }
        }
    }

    private boolean usable(String reference, String sha256) {
        try {
            if (!storage.isBundle(reference)
                    || !sha256.equals(TicketJrxmlBundleCompiler.bundleSha256(
                            storage.readBundleSources(reference)))) {
                return false;
            }
            Path compiledDirectory = storage.compiledBundleMaster(
                    reference, TicketJrxmlBundleCompiler.MASTER_FILENAME).getParent();
            return TicketJrxmlBundleCompiler.REQUIRED_FILENAMES.stream()
                    .map(BuiltInTicketJasperBundle::compiledFilename)
                    .allMatch(name -> Files.isRegularFile(compiledDirectory.resolve(name)));
        } catch (IOException exception) {
            return false;
        }
    }

    private static Map<String, byte[]> readSources() throws IOException {
        var sources = new LinkedHashMap<String, byte[]>();
        for (String filename : TicketJrxmlBundleCompiler.REQUIRED_FILENAMES) {
            String resourceName = TicketJrxmlBundleCompiler.MASTER_FILENAME.equals(filename)
                    ? RESOURCE_ROOT + filename : SUBREPORT_ROOT + filename;
            var resource = new ClassPathResource(resourceName);
            if (!resource.exists()) {
                throw new IOException("ticket_jasper_builtin_source_missing:" + resourceName);
            }
            try (var input = resource.getInputStream()) {
                sources.put(filename, input.readAllBytes());
            }
        }
        return Map.copyOf(sources);
    }

    private static String compiledFilename(String sourceFilename) {
        return sourceFilename.substring(0, sourceFilename.lastIndexOf('.')) + ".jasper";
    }
}
