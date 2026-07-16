package com.tpverp.backend.ui;

import com.tpverp.backend.security.domain.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "preferencia_diseno_tabla",
        uniqueConstraints = @UniqueConstraint(
                name = "preferencia_diseno_tabla_usuario_app_tabla_uq",
                columnNames = {"usuario_id", "app", "table_key"}))
public class TableLayoutPreference {

    static final int MAX_COLUMNS = 128;
    static final int MAX_TABLE_KEY_LENGTH = 128;
    static final String APP_REGEXP = "venta|gestion";
    static final String TABLE_KEY_REGEXP = "[A-Za-z0-9._-]+";

    private static final Set<String> SUPPORTED_APPS = Set.of("venta", "gestion");
    private static final Pattern TABLE_KEY_PATTERN = Pattern.compile(TABLE_KEY_REGEXP);

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false, length = 16)
    private String app;

    @Column(name = "table_key", nullable = false, length = MAX_TABLE_KEY_LENGTH)
    private String tableKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columnas", nullable = false, columnDefinition = "jsonb")
    private List<TableLayoutColumn> columns = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TableLayoutPreference() {
    }

    public TableLayoutPreference(
            UserAccount user,
            String app,
            String tableKey,
            List<TableLayoutColumn> columns,
            Instant now) {
        this.id = UUID.randomUUID();
        this.user = Objects.requireNonNull(user, "user");
        this.app = knownApp(app);
        this.tableKey = knownTableKey(tableKey);
        this.columns = validateColumns(columns);
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void update(List<TableLayoutColumn> nextColumns, Instant now) {
        this.columns = validateColumns(nextColumns);
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UserAccount getUser() {
        return user;
    }

    public String getApp() {
        return app;
    }

    public String getTableKey() {
        return tableKey;
    }

    public List<TableLayoutColumn> getColumns() {
        return List.copyOf(columns);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    static String knownApp(String value) {
        String normalized = required(value, "app");
        if (!SUPPORTED_APPS.contains(normalized)) {
            throw new IllegalArgumentException("app no soportada");
        }
        return normalized;
    }

    static String knownTableKey(String value) {
        String normalized = required(value, "tableKey");
        if (normalized.length() > MAX_TABLE_KEY_LENGTH) {
            throw new IllegalArgumentException("tableKey no puede superar 128 caracteres");
        }
        if (!TABLE_KEY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("tableKey contiene caracteres no permitidos");
        }
        return normalized;
    }

    static List<TableLayoutColumn> validateColumns(List<TableLayoutColumn> value) {
        if (value == null) {
            throw new IllegalArgumentException("columns es obligatorio");
        }
        if (value.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("columns no puede contener mas de 128 columnas");
        }
        var seen = new HashSet<String>();
        var normalized = value.stream()
                .map(column -> {
                    if (column == null) {
                        throw new IllegalArgumentException("column es obligatoria");
                    }
                    var next = new TableLayoutColumn(column.key(), column.width(), column.visible());
                    if (!seen.add(next.key())) {
                        throw new IllegalArgumentException("column.key no puede estar duplicada");
                    }
                    return next;
                })
                .toList();
        return new ArrayList<>(normalized);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
