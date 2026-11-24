package com.tpverp.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** PACK tiers carry the price of a complete pack; UNIT_THRESHOLD tiers carry a unit price. */
@JsonDeserialize(using = QuantityPricing.Deserializer.class)
@tools.jackson.databind.annotation.JsonDeserialize(using = QuantityPricingHttpJson.ReadRule.class)
public record QuantityPricing(Mode mode, List<Tier> tiers, boolean enabled) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TIERS = 100;
    private static final Pattern DECIMAL_TEXT = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");

    public enum Mode { PACK, UNIT_THRESHOLD }

    public record Tier(int quantity, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price) {
        public Tier {
            if (quantity <= 0) throw new IllegalArgumentException("quantity debe ser mayor que cero");
            if (price == null || price.signum() < 0) {
                throw new IllegalArgumentException("price debe ser mayor o igual que cero");
            }
            price = com.tpverp.backend.document.Money.exactUnitPrice(price);
        }
    }

    public QuantityPricing(Mode mode, List<Tier> tiers) {
        this(mode, tiers, true);
    }

    public QuantityPricing {
        if (mode == null) throw new IllegalArgumentException("mode es obligatorio");
        if (tiers == null || tiers.isEmpty() || tiers.size() > MAX_TIERS) {
            throw new IllegalArgumentException("tiers debe contener entre 1 y " + MAX_TIERS + " tramos");
        }
        tiers = List.copyOf(tiers);
        Set<Integer> quantities = new HashSet<>();
        int previous = 0;
        for (Tier tier : tiers) {
            if (tier == null) throw new IllegalArgumentException("tiers no admite nulos");
            if (!quantities.add(tier.quantity())) throw new IllegalArgumentException("quantity duplicada");
            if (tier.quantity() <= previous) throw new IllegalArgumentException("tiers debe ordenarse por quantity");
            previous = tier.quantity();
        }
    }

    // Use the same strict parser for requests, persisted JSON and historical snapshots.
    // Existing configurations predate enabled and remain active when it is absent.
    public static QuantityPricing fromJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject() || node.size() != (node.has("enabled") ? 3 : 2)
                || !node.has("mode") || !node.has("tiers")
                || !node.get("mode").isTextual() || !node.get("tiers").isArray()) {
            throw new IllegalArgumentException("quantityPricing debe contener mode y tiers");
        }
        if (node.has("enabled") && !node.get("enabled").isBoolean()) {
            throw new IllegalArgumentException("quantityPricing.enabled debe ser booleano");
        }
        if (node.get("tiers").size() > MAX_TIERS) {
            throw new IllegalArgumentException("quantityPricing supera " + MAX_TIERS + " tramos");
        }
        Mode mode;
        try {
            mode = Mode.valueOf(node.get("mode").textValue());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("quantityPricing.mode no es valido", exception);
        }
        var tiers = new java.util.ArrayList<Tier>();
        for (JsonNode item : node.get("tiers")) {
            if (!item.isObject() || item.size() != 2 || !item.has("quantity") || !item.has("price")
                    || !item.get("quantity").isIntegralNumber() || !item.get("quantity").canConvertToInt()
                    || !(item.get("price").isNumber() || item.get("price").isTextual())) {
                throw new IllegalArgumentException("quantityPricing.tiers contiene un tramo no valido");
            }
            JsonNode priceNode = item.get("price");
            BigDecimal price;
            if (priceNode.isTextual()) {
                String value = priceNode.textValue();
                if (value.length() > 64 || !DECIMAL_TEXT.matcher(value).matches()) {
                    throw new IllegalArgumentException("quantityPricing.tiers.price no es un decimal valido");
                }
                price = new BigDecimal(value);
            } else {
                price = priceNode.decimalValue();
            }
            tiers.add(new Tier(item.get("quantity").intValue(), price));
        }
        return new QuantityPricing(mode, tiers, !node.has("enabled") || node.get("enabled").booleanValue());
    }

    public JsonNode toJson() {
        return JSON.valueToTree(this);
    }

    public static final class Deserializer extends StdDeserializer<QuantityPricing> {
        public Deserializer() {
            super(QuantityPricing.class);
        }

        @Override
        public QuantityPricing deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            // Old persisted rules can contain numeric prices. Avoid a double intermediary.
            JsonNode node = JSON.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(parser);
            try {
                return fromJson(node);
            } catch (IllegalArgumentException exception) {
                throw JsonMappingException.from(parser, exception.getMessage(), exception);
            }
        }
    }
}
