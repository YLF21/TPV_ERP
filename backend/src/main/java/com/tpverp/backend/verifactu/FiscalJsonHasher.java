package com.tpverp.backend.verifactu;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FiscalJsonHasher {

    private final JsonMapper mapper;

    // Crea un serializador canonico privado e independiente de la aplicacion.
    public FiscalJsonHasher() {
        mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    // Calcula una huella estable del snapshot fiscal con claves JSON ordenadas.
    public String hash(Map<String, Object> snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot es obligatorio");
        }
        try {
            var json = mapper.writeValueAsBytes(normalize(snapshot));
            var digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo calcular el hash fiscal", exception);
        }
    }

    // Copia la estructura para canonizar decimales sin modificar el snapshot recibido.
    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }
        if (value instanceof Double decimal && Double.isFinite(decimal)) {
            return BigDecimal.valueOf(decimal).stripTrailingZeros();
        }
        if (value instanceof Float decimal && Float.isFinite(decimal)) {
            return new BigDecimal(decimal.toString()).stripTrailingZeros();
        }
        if (value instanceof BigInteger
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            var normalized = new LinkedHashMap<String, Object>(map.size());
            map.forEach((key, nested) -> {
                if (!(key instanceof String textKey)) {
                    throw unsupported(key);
                }
                normalized.put(textKey, normalize(nested));
            });
            return normalized;
        }
        if (value instanceof List<?> list) {
            var normalized = new ArrayList<>(list.size());
            list.forEach(nested -> normalized.add(normalize(nested)));
            return normalized;
        }
        throw unsupported(value);
    }

    private static IllegalArgumentException unsupported(Object value) {
        var type = value == null ? "null" : value.getClass().getName();
        return new IllegalArgumentException("Tipo fiscal no permitido: " + type);
    }
}
