package com.tpverp.backend.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "preferencia_columnas_stock",
        uniqueConstraints = @UniqueConstraint(
                name = "preferencia_columnas_stock_tienda_usuario_app_uq",
                columnNames = {"tienda_id", "usuario_id", "app"}))
public class StockColumnPreference {

    private static final int MAX_COLUMNS_PER_VIEW = 64;
    private static final Set<String> SUPPORTED_VIEWS = Set.of(
            "stock.current",
            "stock.topSales",
            "stock.offers",
            "stock.memberPrice",
            "stock.promotions",
            "stock.noDiscount",
            "stock.bulkEdit");

    @Id
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "usuario_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String app;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columnas", nullable = false, columnDefinition = "jsonb")
    private Map<String, List<StockColumnSetting>> columns = new LinkedHashMap<>();

    @Column(name = "creada_en", nullable = false)
    private Instant createdAt;

    @Column(name = "actualizada_en", nullable = false)
    private Instant updatedAt;

    protected StockColumnPreference() {
    }

    public StockColumnPreference(
            UUID companyId,
            UUID storeId,
            UUID userId,
            String app,
            Map<String, List<StockColumnSetting>> columns,
            Instant now) {
        this.id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.app = knownApp(app);
        this.columns = validateAndCopy(columns);
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void update(Map<String, List<StockColumnSetting>> nextColumns, Instant now) {
        this.columns = validateAndCopy(nextColumns);
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getApp() {
        return app;
    }

    public Map<String, List<StockColumnSetting>> getColumns() {
        var copy = new LinkedHashMap<String, List<StockColumnSetting>>();
        columns.forEach((view, settings) -> copy.put(view, List.copyOf(settings)));
        return Collections.unmodifiableMap(copy);
    }

    static String knownApp(String value) {
        String normalized = required(value, "app").toLowerCase(Locale.ROOT);
        if (!normalized.equals("venta") && !normalized.equals("gestion")) {
            throw new IllegalArgumentException("app no soportada");
        }
        return normalized;
    }

    static Map<String, List<StockColumnSetting>> validateAndCopy(
            Map<String, List<StockColumnSetting>> value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("settings es obligatorio");
        }
        if (value.size() > SUPPORTED_VIEWS.size()) {
            throw new IllegalArgumentException("settings contiene demasiadas vistas");
        }

        var normalized = new LinkedHashMap<String, List<StockColumnSetting>>();
        value.forEach((rawView, rawColumns) -> {
            String view = required(rawView, "view");
            if (!SUPPORTED_VIEWS.contains(view)) {
                throw new IllegalArgumentException("vista de stock no soportada: " + view);
            }
            if (rawColumns == null || rawColumns.isEmpty()) {
                throw new IllegalArgumentException("settings[" + view + "] es obligatorio");
            }
            if (rawColumns.size() > MAX_COLUMNS_PER_VIEW) {
                throw new IllegalArgumentException("settings[" + view + "] contiene demasiadas columnas");
            }

            var seen = new HashSet<String>();
            var viewColumns = rawColumns.stream().map(column -> {
                if (column == null) {
                    throw new IllegalArgumentException("column es obligatoria");
                }
                String key = required(column.key(), "column.key");
                if (key.length() > 64) {
                    throw new IllegalArgumentException("column.key no puede superar 64 caracteres");
                }
                if (!seen.add(key)) {
                    throw new IllegalArgumentException("column.key duplicada: " + key);
                }
                if (column.width() < 72 || column.width() > 420) {
                    throw new IllegalArgumentException("column.width debe estar entre 72 y 420");
                }
                return new StockColumnSetting(key, column.width());
            }).toList();
            normalized.put(view, viewColumns);
        });
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
