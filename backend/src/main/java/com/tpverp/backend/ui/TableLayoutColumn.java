package com.tpverp.backend.ui;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

public record TableLayoutColumn(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z0-9._-]+")
        String key,
        @Min(56) @Max(800)
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        Integer width,
        Boolean visible) {

    public TableLayoutColumn {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("column.key es obligatorio");
        }
        key = key.trim();
        if (width != null && (width < 56 || width > 800)) {
            throw new IllegalArgumentException("column.width debe estar entre 56 y 800");
        }
        visible = visible == null ? Boolean.TRUE : visible;
    }

    public static final class StrictIntegerDeserializer extends StdDeserializer<Integer> {

        public StrictIntegerDeserializer() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }

        @Override
        public Integer getNullValue(DeserializationContext context) {
            return null;
        }
    }
}
