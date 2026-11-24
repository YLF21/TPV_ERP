package com.tpverp.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;

/** HTTP uses Jackson 3; catalog requests and stored bulk drafts share a Jackson 2 tree. */
final class QuantityPricingHttpJson {
    private static final ObjectMapper CATALOG_JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final ObjectReader HTTP_VALUE = JsonMapper.builder().build().readerFor(Object.class)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private QuantityPricingHttpJson() {}

    private static JsonNode readNode(JsonParser parser) {
        Object value = HTTP_VALUE.readValue(parser);
        return CATALOG_JSON.valueToTree(value);
    }

    public static final class ReadField extends ValueDeserializer<JsonNode> {
        @Override
        public JsonNode deserialize(JsonParser parser, DeserializationContext context) {
            return readNode(parser);
        }

        @Override
        public JsonNode getNullValue(DeserializationContext context) {
            return NullNode.getInstance(); // Explicit null clears the rule.
        }

        @Override
        public JsonNode getAbsentValue(DeserializationContext context) {
            return null; // An omitted field leaves the rule untouched.
        }
    }

    public static final class WriteField extends ValueSerializer<JsonNode> {
        @Override
        public void serialize(JsonNode node, JsonGenerator generator, SerializationContext context) {
            generator.writePOJO(CATALOG_JSON.convertValue(node, Object.class));
        }
    }

    public static final class ReadRule extends ValueDeserializer<QuantityPricing> {
        @Override
        public QuantityPricing deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode node = readNode(parser);
            try {
                return QuantityPricing.fromJson(node);
            } catch (IllegalArgumentException exception) {
                return context.reportInputMismatch(QuantityPricing.class, "%s", exception.getMessage());
            }
        }
    }
}
