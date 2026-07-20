package com.tpverp.bridge.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BridgeConfigurationLoader {
    private static final long MAXIMUM_CONFIGURATION_BYTES = 256 * 1024L;

    private BridgeConfigurationLoader() {
    }

    public static LoadedConfiguration load(Path path) throws IOException {
        var absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute) || Files.size(absolute) > MAXIMUM_CONFIGURATION_BYTES) {
            throw new IllegalArgumentException("Invalid bridge configuration file");
        }
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var configuration = mapper.readValue(absolute.toFile(), BridgeConfiguration.class);
        var base = absolute.getParent();
        return new LoadedConfiguration(configuration, base == null ? Path.of(".").toAbsolutePath() : base);
    }

    public record LoadedConfiguration(BridgeConfiguration configuration, Path baseDirectory) {
    }
}
