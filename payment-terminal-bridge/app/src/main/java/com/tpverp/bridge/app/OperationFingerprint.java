package com.tpverp.bridge.app;

import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.PairingRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

final class OperationFingerprint {
    private OperationFingerprint() {
    }

    static String of(PairingRequest request) {
        var digest = digest();
        add(digest, "PAIR");
        add(digest, request.provider());
        add(digest, request.terminalId());
        add(digest, request.pairingId());
        add(digest, request.idempotencyKey());
        add(digest, request.configurationReference());
        add(digest, Long.toString(request.configurationVersion()));
        add(digest, request.parameters());
        return HexFormat.of().formatHex(digest.digest());
    }

    static String of(OperationRequest request) {
        var digest = digest();
        add(digest, request.provider());
        add(digest, request.terminalId());
        add(digest, request.operationId());
        add(digest, request.idempotencyKey());
        add(digest, request.command());
        add(digest, Long.toString(request.amountMinor()));
        add(digest, request.currency());
        add(digest, request.originalOperationId());
        add(digest, request.reference());
        add(digest, request.configurationReference());
        add(digest, Long.toString(request.configurationVersion()));
        add(digest, request.parameters());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, Map<String, String> values) {
        var sorted = values == null ? Map.<String, String>of() : new java.util.TreeMap<>(values);
        add(digest, Integer.toString(sorted.size()));
        sorted.forEach((key, value) -> {
            add(digest, key);
            add(digest, value);
        });
    }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
