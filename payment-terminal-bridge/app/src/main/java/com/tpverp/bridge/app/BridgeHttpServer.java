package com.tpverp.bridge.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.PairingRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BridgeHttpServer implements AutoCloseable {
    private static final int MAXIMUM_REQUEST_BYTES = 64 * 1024;
    private final HttpServer server;
    private final ExecutorService executor;
    private final BridgeService service;
    private final ObjectMapper mapper;
    private final byte[] authorization;

    public BridgeHttpServer(String host, int port, String token, BridgeService service, ObjectMapper mapper) throws IOException {
        this(host, port, token == null ? null : token.toCharArray(), service, mapper);
    }

    public BridgeHttpServer(String host, int port, char[] token, BridgeService service, ObjectMapper mapper) throws IOException {
        if (token == null || token.length < 24 || token.length > 512 || containsControl(token)) {
            throw new IllegalArgumentException("A strong local bridge token is required");
        }
        this.server = HttpServer.create(new InetSocketAddress(host, port), 32);
        this.executor = Executors.newFixedThreadPool(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
        this.server.setExecutor(executor);
        this.service = service;
        this.mapper = mapper;
        var buffer = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(token));
        var tokenBytes = new byte[buffer.remaining()];
        buffer.get(tokenBytes);
        this.authorization = new byte["Bearer ".length() + tokenBytes.length];
        System.arraycopy("Bearer ".getBytes(StandardCharsets.US_ASCII), 0, authorization, 0, "Bearer ".length());
        System.arraycopy(tokenBytes, 0, authorization, "Bearer ".length(), tokenBytes.length);
        java.util.Arrays.fill(tokenBytes, (byte) 0);
        server.createContext("/health", exchange -> handle(exchange, this::health));
        server.createContext("/capabilities", exchange -> handle(exchange, this::capabilities));
        server.createContext("/adapters", exchange -> handle(exchange, this::adapters));
        server.createContext("/pair", exchange -> handle(exchange, this::pair));
        server.createContext("/operation", exchange -> handle(exchange, this::operation));
    }

    private static boolean containsControl(char[] value) {
        for (var character : value) if (Character.isISOControl(character)) return true;
        return false;
    }

    public void start() {
        server.start();
    }

    public URI baseUri() {
        var address = server.getAddress();
        var host = address.getHostString();
        if (host.contains(":") && !host.startsWith("[")) host = '[' + host + ']';
        return URI.create("http://" + host + ':' + address.getPort());
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
        java.util.Arrays.fill(authorization, (byte) 0);
    }

    private void handle(HttpExchange exchange, ExchangeHandler handler) throws IOException {
        try (exchange) {
            try {
                addSecurityHeaders(exchange);
                if (!authenticated(exchange)) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    send(exchange, 401, Map.of("code", "UNAUTHORIZED"));
                    return;
                }
                handler.handle(exchange);
            } catch (IllegalArgumentException exception) {
                if (exchange.getResponseCode() < 0) send(exchange, 400, Map.of("code", "INVALID_REQUEST"));
            } catch (RuntimeException exception) {
                if (exchange.getResponseCode() < 0) send(exchange, 500, Map.of("code", "INTERNAL_ERROR"));
            }
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        require(exchange, "GET", "/health");
        send(exchange, 200, service.health());
    }

    private void capabilities(HttpExchange exchange) throws IOException {
        require(exchange, "GET", "/capabilities");
        var provider = queryParameter(exchange.getRequestURI().getRawQuery(), "provider");
        var modeValue = queryParameter(exchange.getRequestURI().getRawQuery(), "mode");
        if (modeValue == null) throw new IllegalArgumentException("mode");
        final com.tpverp.bridge.spi.TerminalExecutionMode mode;
        try {
            mode = com.tpverp.bridge.spi.TerminalExecutionMode.valueOf(modeValue);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("mode", exception);
        }
        send(exchange, 200, Map.of("capabilities", service.capabilities(provider, mode)));
    }

    private void adapters(HttpExchange exchange) throws IOException {
        require(exchange, "GET", "/adapters");
        send(exchange, 200, Map.of("adapters", service.adapters()));
    }

    private void pair(HttpExchange exchange) throws IOException {
        requireJsonPost(exchange, "/pair");
        send(exchange, 200, service.pair(read(exchange, PairingRequest.class)));
    }

    private void operation(HttpExchange exchange) throws IOException {
        requireJsonPost(exchange, "/operation");
        send(exchange, 200, service.operate(read(exchange, OperationRequest.class)));
    }

    private <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
        var bytes = exchange.getRequestBody().readNBytes(MAXIMUM_REQUEST_BYTES + 1);
        if (bytes.length > MAXIMUM_REQUEST_BYTES) throw new IllegalArgumentException("Request too large");
        try {
            return mapper.readValue(bytes, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private void send(HttpExchange exchange, int status, Object value) throws IOException {
        var body = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private boolean authenticated(HttpExchange exchange) {
        var value = exchange.getRequestHeaders().getFirst("Authorization");
        if (value == null) return false;
        var supplied = value.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(authorization, supplied);
    }

    private static void require(HttpExchange exchange, String method, String path) throws IOException {
        if (!path.equals(exchange.getRequestURI().getPath())) throw new IllegalArgumentException("path");
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            exchange.sendResponseHeaders(405, -1);
            throw new ResponseAlreadySentException();
        }
    }

    private static void requireJsonPost(HttpExchange exchange, String path) throws IOException {
        require(exchange, "POST", path);
        var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new IllegalArgumentException("Content-Type");
        }
    }

    private static String queryParameter(String query, String name) {
        if (query == null || query.isBlank()) return null;
        String result = null;
        for (var entry : query.split("&")) {
            var parts = entry.split("=", 2);
            if (parts.length != 2) throw new IllegalArgumentException("query");
            var key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            var value = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (!Set.of("provider", "mode").contains(key) || value.length() > 32
                    || !value.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("query");
            if (name.equals(key)) {
                if (result != null) throw new IllegalArgumentException("query");
                result = value;
            }
        }
        return result;
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class ResponseAlreadySentException extends RuntimeException {
    }
}
