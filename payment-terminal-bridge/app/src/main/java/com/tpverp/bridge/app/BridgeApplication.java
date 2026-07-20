package com.tpverp.bridge.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class BridgeApplication {
    private BridgeApplication() {
    }

    public static void main(String[] arguments) throws Exception {
        var environment = System.getenv();
        var options = Options.parse(arguments, environment.getOrDefault("TPV_BRIDGE_CONFIG", "bridge-config.json"));
        var configurationFile = options.configurationFile();
        var loaded = BridgeConfigurationLoader.load(configurationFile);
        var configuration = loaded.configuration();
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var adapterRuntime = new FileAdapterRuntime(configuration.dataPath(loaded.baseDirectory()));
        if (options.command() != Command.RUN) {
            executeCommand(options, configuration, loaded.baseDirectory(), adapterRuntime);
            return;
        }
        var instanceLock = BridgeInstanceLock.acquire(configuration.dataPath(loaded.baseDirectory()));
        var adapters = AdapterRegistry.load(configuration.pluginsPath(loaded.baseDirectory()), configuration.pluginDigests(), adapterRuntime);
        var store = new FileIdempotencyStore(configuration.dataPath(loaded.baseDirectory()), mapper);
        var service = new BridgeService(new TerminalProfileRegistry(configuration.terminals()), adapters, store);
        var token = resolveToken(environment.get("TPV_PAYMENT_BRIDGE_TOKEN"), configuration.authenticationTokenReference(), adapterRuntime);
        final BridgeHttpServer server;
        try { server = new BridgeHttpServer(configuration.listenHost(), configuration.port(), token, service, mapper); }
        finally { java.util.Arrays.fill(token, '\0'); }
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            try {
                adapters.close();
            } catch (Exception ignored) {
                // Shutdown must continue even if a vendor SDK cannot close cleanly.
            }
            try {
                instanceLock.close();
            } catch (Exception ignored) {
                // The operating system also releases the lock when the process exits.
            }
            stopped.countDown();
        }, "payment-terminal-bridge-shutdown"));
        server.start();
        System.out.println("TPV payment terminal bridge " + BridgeService.VERSION + " listening on " + server.baseUri());
        stopped.await();
    }

    private static void executeCommand(Options options, BridgeConfiguration configuration, Path base,
            FileAdapterRuntime runtime) throws Exception {
        switch (options.command()) {
            case VALIDATE, DIAGNOSE -> {
                try (var adapters = AdapterRegistry.load(configuration.pluginsPath(base), configuration.pluginDigests(), runtime)) {
                    System.out.println("Configuration valid: " + options.configurationFile().toAbsolutePath());
                    for (var manifest : adapters.manifests()) System.out.println(manifest);
                    if (options.command() == Command.DIAGNOSE) {
                        for (var profile : configuration.terminals()) {
                            try {
                                var adapter = adapters.required(profile);
                                System.out.println(profile.terminalId() + " " + profile.mode() + " " + adapter.health(profile));
                            } catch (RuntimeException exception) {
                                System.out.println(profile.terminalId() + " " + profile.mode() + " UNAVAILABLE");
                            }
                        }
                    }
                }
            }
            case STORE_SECRET -> {
                var console = System.console();
                if (console == null) throw new IllegalStateException("An interactive console is required");
                var chars = console.readPassword("Secret for %s: ", options.reference());
                if (chars == null || chars.length == 0) throw new IllegalArgumentException("Secret is required");
                var bytes = utf8(chars);
                try { runtime.putSecret(options.reference(), bytes); }
                finally { java.util.Arrays.fill(chars, '\0'); java.util.Arrays.fill(bytes, (byte) 0); }
                System.out.println("Secret stored: " + options.reference());
            }
            case DELETE_SECRET -> {
                runtime.deleteSecret(options.reference());
                System.out.println("Secret deleted: " + options.reference());
            }
            case RUN -> throw new IllegalStateException("Unexpected command");
        }
    }

    private static char[] resolveToken(String environmentToken, String reference, FileAdapterRuntime runtime) {
        if (environmentToken != null && !environmentToken.isBlank()) return environmentToken.toCharArray();
        return runtime.withSecret(reference, BridgeApplication::decodeUtf8);
    }

    private static byte[] utf8(char[] value) {
        var buffer = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(value));
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static char[] decodeUtf8(byte[] value) {
        var buffer = java.nio.charset.StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(value));
        var chars = new char[buffer.remaining()];
        buffer.get(chars);
        return chars;
    }

    private enum Command { RUN, VALIDATE, DIAGNOSE, STORE_SECRET, DELETE_SECRET }

    private record Options(Path configurationFile, Command command, String reference) {
        static Options parse(String[] arguments, String defaultConfiguration) {
            var config = Path.of(defaultConfiguration);
            var command = Command.RUN;
            String reference = null;
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "--config" -> {
                        if (++index >= arguments.length) throw new IllegalArgumentException("--config requires a path");
                        config = Path.of(arguments[index]);
                    }
                    case "--validate" -> command = one(command, Command.VALIDATE);
                    case "--diagnose" -> command = one(command, Command.DIAGNOSE);
                    case "--store-secret" -> {
                        command = one(command, Command.STORE_SECRET);
                        if (++index >= arguments.length) throw new IllegalArgumentException("--store-secret requires a reference");
                        reference = arguments[index];
                    }
                    case "--delete-secret" -> {
                        command = one(command, Command.DELETE_SECRET);
                        if (++index >= arguments.length) throw new IllegalArgumentException("--delete-secret requires a reference");
                        reference = arguments[index];
                    }
                    default -> throw new IllegalArgumentException("Unknown bridge argument: " + arguments[index]);
                }
            }
            return new Options(config, command, reference);
        }

        private static Command one(Command current, Command next) {
            if (current != Command.RUN) throw new IllegalArgumentException("Only one bridge command is allowed");
            return next;
        }
    }
}
