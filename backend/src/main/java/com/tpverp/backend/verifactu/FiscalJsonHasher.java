package com.tpverp.backend.verifactu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public final class FiscalJsonHasher {

    private final ObjectMapper mapper;

    // Aisla la configuracion canonica sin modificar el ObjectMapper compartido.
    public FiscalJsonHasher(ObjectMapper source) {
        mapper = source.copy();
        mapper.setConfig(mapper.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .with(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN));
    }

    // Calcula una huella estable del snapshot fiscal con claves JSON ordenadas.
    public String hash(Map<String, Object> snapshot) {
        try {
            var json = mapper.writeValueAsBytes(snapshot);
            var digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo calcular el hash fiscal", exception);
        }
    }
}
