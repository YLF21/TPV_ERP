package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.document.QuantityPriceSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductQuantityPricingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void inactiveRuleSurvivesCatalogBulkDraftAndSnapshotRoundTripsWithAllTiers() throws Exception {
        var rule = new QuantityPricing(QuantityPricing.Mode.PACK, List.of(
                new QuantityPricing.Tier(3, new BigDecimal("5.99")),
                new QuantityPricing.Tier(6, new BigDecimal("10.50"))), false);
        var product = new Product(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                "Cafe", null, BigDecimal.ONE, true);
        product.configureQuantityPricing(json.readValue(json.writeValueAsString(rule), QuantityPricing.class));
        assertThat(product.getQuantityPricing()).isEqualTo(rule);
        var view = json.readTree(json.writeValueAsString(ProductView.publicView(product))).get("quantityPricing");
        assertThat(view.get("enabled").booleanValue()).isFalse();
        assertThat(QuantityPricing.fromJson(view)).isEqualTo(rule);
        var draft = ProductBulkEditContent.ProductData.empty().withQuantityPricing(rule.toJson());
        var reopened = json.readValue(json.writeValueAsString(draft), ProductBulkEditContent.ProductData.class);
        assertThat(QuantityPricing.fromJson(reopened.quantityPricing())).isEqualTo(rule);
        var snapshot = new QuantityPriceSnapshot(rule, new BigDecimal("2.50"));
        assertThat(json.readValue(json.writeValueAsString(snapshot), QuantityPriceSnapshot.class)).isEqualTo(snapshot);
    }

    @Test
    void legacyStoredRulesAndDocumentSnapshotsRemainEnabled() throws Exception {
        String legacy = """
                {"mode":"PACK","tiers":[{"quantity":3,"price":"5.99"}]}
                """;
        var parsed = QuantityPricing.fromJson(json.readTree(legacy));
        assertThat(parsed.enabled()).isTrue();
        assertThat(json.readValue(legacy, QuantityPricing.class)).isEqualTo(parsed);
        var snapshot = json.readValue("{\"rule\":" + legacy + ",\"salePrice\":2.50}", QuantityPriceSnapshot.class);
        assertThat(snapshot.rule()).isEqualTo(parsed);
        var largeLegacy = json.readValue("""
                {"rule":{"mode":"PACK","tiers":[{"quantity":3,"price":99999999999999999.999}]},"salePrice":2.50}
                """, QuantityPriceSnapshot.class);
        assertThat(largeLegacy.rule().enabled()).isTrue();
        assertThat(largeLegacy.rule().tiers().getFirst().price()).isEqualByComparingTo("99999999999999999.999");
    }

    @Test
    void enabledMustBeBooleanAndInactiveRulesStillRequireValidTiers() throws Exception {
        for (String invalid : List.of("null", "0", "1", "\"false\"", "{}", "[]")) {
            String payload = "{\"enabled\":" + invalid
                    + ",\"mode\":\"PACK\",\"tiers\":[{\"quantity\":3,\"price\":5.99}]}";
            assertThatThrownBy(() -> QuantityPricing.fromJson(json.readTree(payload)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> json.readValue(payload, QuantityPricing.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
        for (String payload : List.of(
                "{\"enabled\":false,\"mode\":\"PACK\",\"tiers\":[]}",
                "{\"enabled\":false,\"mode\":\"PACK\",\"tiers\":[{\"quantity\":0,\"price\":5.99}]}",
                "{\"enabled\":false,\"mode\":\"PACK\",\"tiers\":[{\"quantity\":3,\"price\":5.99}],\"extra\":true}")) {
            assertThatThrownBy(() -> QuantityPricing.fromJson(json.readTree(payload)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void enforcesPositiveUniqueSortedQuantitiesAndCatalogPricePrecision() throws Exception {
        var valid = QuantityPricing.fromJson(json.readTree("""
                {"mode":"UNIT_THRESHOLD","tiers":[{"quantity":2,"price":1.235},{"quantity":5,"price":1.10}]}
                """));
        assertThat(valid.tiers().getFirst().price()).isEqualByComparingTo("1.235");
        assertThatThrownBy(() -> new QuantityPricing(QuantityPricing.Mode.PACK, List.of(
                new QuantityPricing.Tier(2, BigDecimal.ONE),
                new QuantityPricing.Tier(2, BigDecimal.TEN))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuantityPricing.Tier(0, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuantityPricing.Tier(2, new BigDecimal("1.2345")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuantityPricing.fromJson(json.readTree("""
                {"mode":"PACK","tiers":[{"quantity":2,"price":-1}]}
                """))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productRequestDistinguishesOmittedClearAndReplacement() throws Exception {
        String base = """
                {"familyId":"%s","taxId":"%s","productType":"UNIT","discountType":"NORMAL",
                 "name":"Cafe","purchasePrice":1,"salePrice":2,"taxesIncluded":true}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        var omitted = json.readValue(base, CatalogService.ProductRequest.class);
        var clear = json.readValue(base.replace("}", ",\"quantityPricing\":null}"),
                CatalogService.ProductRequest.class);
        var replace = json.readValue(base.replace("}", """
                ,"quantityPricing":{"mode":"PACK","tiers":[{"quantity":3,"price":5.99}]}}"""),
                CatalogService.ProductRequest.class);
        assertThat(omitted.quantityPricing()).isNull();
        assertThat(clear.quantityPricing()).isNotNull();
        assertThat(clear.quantityPricing().isNull()).isTrue();
        assertThat(QuantityPricing.fromJson(replace.quantityPricing()).mode())
                .isEqualTo(QuantityPricing.Mode.PACK);
        var http = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(http.writeValueAsString(omitted)).doesNotContain("quantityPricing");
        assertThat(http.writeValueAsString(clear)).contains("\"quantityPricing\":null");
    }

    @Test
    void bulkDraftOverlayDistinguishesOmittedAndExplicitClear() throws Exception {
        var current = json.readValue("""
                {"quantityPricing":{"mode":"PACK","tiers":[{"quantity":3,"price":5.99}]}}
                """, ProductBulkEditContent.ProductData.class);
        var omitted = json.readValue("{}", ProductBulkEditContent.ProductData.class);
        var clear = json.readValue("{\"quantityPricing\":null}",
                ProductBulkEditContent.ProductData.class);
        assertThat(current.overlay(omitted).quantityPricing()).isEqualTo(current.quantityPricing());
        assertThat(current.overlay(clear).quantityPricing().isNull()).isTrue();
        assertThat(json.writeValueAsString(omitted)).doesNotContain("quantityPricing");
        assertThat(json.writeValueAsString(clear)).contains("\"quantityPricing\":null");
    }

    @Test
    void httpMapperPreservesBulkFieldPresenceAndExactNestedPrices() {
        var http = tools.jackson.databind.json.JsonMapper.builder().build();
        var type = ProductBulkEditContent.ProductData.class;
        assertThat(http.writeValueAsString(http.readValue("{}", type))).doesNotContain("quantityPricing");
        var cleared = http.readValue("{\"quantityPricing\":null}", type);
        assertThat(cleared.quantityPricing().isNull()).isTrue();
        assertThat(http.writeValueAsString(cleared)).contains("\"quantityPricing\":null");

        String rule = """
                {"mode":"PACK","tiers":[{"quantity":3,"price":99999999999999999.999}]}
                """;
        var row = http.readValue("{\"quantityPricing\":" + rule + ",\"name\":\"Café\"}", type);
        assertThat(row.name()).isEqualTo("Café");
        var reopened = http.readValue(http.writeValueAsBytes(row), type);
        var pricing = QuantityPricing.fromJson(reopened.quantityPricing());
        assertThat(pricing.tiers().getFirst().price()).isEqualByComparingTo("99999999999999999.999");

        var snapshot = http.readValue("{\"rule\":" + rule + ",\"salePrice\":2.50}", QuantityPriceSnapshot.class);
        assertThat(snapshot.rule()).isEqualTo(pricing);
        assertThat(snapshot.rule().enabled()).isTrue();
        assertThatThrownBy(() -> http.readValue(rule.replace("3,", "\"3\","), QuantityPricing.class))
                .isInstanceOf(tools.jackson.databind.DatabindException.class);
    }

    @Test
    void catalogViewIncludesNullForProductsWithoutQuantityPricing() throws Exception {
        var product = new Product(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                "Cafe", null, BigDecimal.ONE, true);
        assertThat(json.writeValueAsString(ProductView.publicView(product)))
                .contains("\"quantityPricing\":null");
    }

    @Test
    void largeDecimalPriceStaysExactInCatalogJsonAndDocumentSnapshot() throws Exception {
        String exact = "99999999999999999.999";
        var rule = QuantityPricing.fromJson(json.readTree("""
                {"mode":"PACK","tiers":[{"quantity":3,"price":"99999999999999999.999"}]}
                """));
        assertThat(rule.tiers().getFirst().price()).isEqualByComparingTo(exact);
        assertThat(rule.toJson().at("/tiers/0/price").textValue()).isEqualTo(exact);
        var product = new Product(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                "Cafe", null, BigDecimal.ONE, true);
        product.configureQuantityPricing(rule);
        var viewJson = json.readTree(json.writeValueAsString(ProductView.publicView(product)));
        assertThat(viewJson.at("/quantityPricing/tiers/0/price").textValue()).isEqualTo(exact);
        assertThat(QuantityPricing.fromJson(viewJson.get("quantityPricing"))).isEqualTo(rule);
        var snapshot = new QuantityPriceSnapshot(rule, new BigDecimal("2.50"));
        var restored = json.readValue(json.writeValueAsString(snapshot), QuantityPriceSnapshot.class);
        assertThat(restored).isEqualTo(snapshot);
        assertThat(json.readTree(json.writeValueAsString(snapshot))
                .at("/rule/tiers/0/price").textValue()).isEqualTo(exact);
    }
}
