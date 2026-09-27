package com.tpverp.backend.catalog;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.promotion.Promotion;
import com.tpverp.backend.promotion.PromotionTarget;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCommercialHistoryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final CurrentOrganization organization;

    public ProductCommercialHistoryService(NamedParameterJdbcTemplate jdbc,
            EntityManager entityManager, CurrentOrganization organization) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.organization = organization;
    }

    @Transactional
    public void recordProduct(Product product) {
        recordProducts(List.of(product));
    }

    @Transactional
    public void recordOffer(Product product) {
        entityManager.flush();
        capture(product.getStoreId(), List.of(product.getId()), true);
    }

    @Transactional
    public void recordProducts(Collection<Product> products) {
        if (products.isEmpty()) return;
        entityManager.flush();
        products.stream().collect(Collectors.groupingBy(Product::getStoreId,
                Collectors.mapping(Product::getId, Collectors.toList())))
                .forEach((store, ids) -> capture(store, ids, false));
    }

    /** Reads fresh database state after bulk JPQL classification changes. */
    @Transactional
    public void recordProductsByIds(UUID storeId, Collection<UUID> productIds) {
        if (productIds.isEmpty()) return;
        entityManager.flush();
        capture(storeId, productIds, false);
    }

    @Transactional
    public void recordPromotion(Promotion promotion, List<PromotionTarget> targets) {
        entityManager.flush();
        // Promotion scope is company-wide; SALE includes every store in the company.
        var productIds = jdbc.queryForList("""
                select p.id from producto p join tienda s on s.id = p.tienda_id
                where s.empresa_id = :companyId
                  and (:scope = 'SALE' or exists (
                      select 1 from promocion_objetivo o where o.promocion_id = :promotionId
                        and ((o.tipo = 'PRODUCT' and o.objetivo_id = p.id)
                          or (o.tipo = 'FAMILY' and o.objetivo_id = p.familia_id)
                          or (o.tipo = 'SUBFAMILY' and o.objetivo_id = p.subfamilia_id)))
                    or exists (select 1 from producto_historial_comercial h
                        where h.producto_id = p.id and h.source = 'PROMOTION'
                          and h.source_id = :promotionId))
                order by p.id
                """, Map.of("companyId", promotion.empresaId(), "scope", promotion.scope().name(),
                        "promotionId", promotion.id()), UUID.class);
        captureChunks(productIds, false);
    }

    private void capture(UUID storeId, Collection<UUID> productIds, boolean offersOnly) {
        var scopedIds = jdbc.queryForList("""
                select id from producto where tienda_id = :storeId and id in (:ids) order by id
                """, Map.of("storeId", storeId, "ids", productIds), UUID.class);
        if (scopedIds.size() != productIds.stream().distinct().count()) {
            throw new IllegalArgumentException("Producto no encontrado en la tienda");
        }
        captureChunks(scopedIds, offersOnly);
    }

    private void captureChunks(List<UUID> ids, boolean offersOnly) {
        for (int start = 0; start < ids.size(); start += 500) {
            var chunk = ids.subList(start, Math.min(ids.size(), start + 500));
            jdbc.query("""
                    select product_commercial_history_capture(
                        array(select id from producto where id in (:ids) order by id), false, :offersOnly)
                    """, Map.of("ids", chunk, "offersOnly", offersOnly), rs -> { });
        }
    }

    @Transactional(readOnly = true)
    public List<HistoryView> history(UUID productId) {
        var params = Map.of("productId", productId, "storeId", organization.currentStore().getId());
        var exists = jdbc.queryForObject("""
                select exists(select 1 from producto where id = :productId and tienda_id = :storeId)
                """, params, Boolean.class);
        if (!Boolean.TRUE.equals(exists)) throw new IllegalArgumentException("Producto no encontrado en la tienda");
        return jdbc.query("""
                select id, source, snapshot->>'type' as type, snapshot->>'name' as name,
                    (snapshot->>'dateFrom')::date as date_from, (snapshot->>'dateTo')::date as date_to,
                    (snapshot->>'beforePrice')::numeric as before_price,
                    (snapshot->>'finalPrice')::numeric as final_price,
                    (snapshot->>'discountPercent')::numeric as discount_percent,
                    registrado_en, snapshot->>'status' as status, initial_snapshot
                from producto_historial_comercial
                where producto_id = :productId and tienda_id = :storeId
                order by sequence_id desc
                """, params, (rs, row) -> new HistoryView(rs.getObject("id", UUID.class),
                        rs.getString("source"), rs.getString("type"), rs.getString("name"),
                        rs.getObject("date_from", LocalDate.class), rs.getObject("date_to", LocalDate.class),
                        rs.getBigDecimal("before_price"), rs.getBigDecimal("final_price"),
                        rs.getBigDecimal("discount_percent"), rs.getTimestamp("registrado_en").toInstant(),
                        rs.getString("status"), rs.getBoolean("initial_snapshot")));
    }

    public record HistoryView(UUID id, String source, String type, String name,
            LocalDate dateFrom, LocalDate dateTo, BigDecimal beforePrice, BigDecimal finalPrice,
            BigDecimal discountPercent, Instant recordedAt, String status, boolean initialSnapshot) { }
}
