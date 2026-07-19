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
        name = "preferencia_dashboard",
        uniqueConstraints = @UniqueConstraint(
                name = "preferencia_dashboard_usuario_uq",
                columnNames = "usuario_id"))
public class DashboardPreference {

    static final int MAX_WIDGETS = 24;
    static final int MAX_WIDGET_KEY_LENGTH = 64;
    static final String WIDGET_KEY_REGEXP = "[a-z0-9.-]+";

    private static final Pattern WIDGET_KEY_PATTERN = Pattern.compile(WIDGET_KEY_REGEXP);
    private static final Set<Integer> ALLOWED_WIDTHS = Set.of(3, 4, 6, 8, 12);

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private UserAccount user;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<DashboardWidgetLayout> widgets = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected DashboardPreference() {
    }

    public DashboardPreference(
            UserAccount user,
            List<DashboardWidgetLayout> widgets,
            Instant now) {
        this.id = UUID.randomUUID();
        this.user = Objects.requireNonNull(user, "user");
        this.widgets = validateWidgets(widgets);
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void update(List<DashboardWidgetLayout> nextWidgets, Instant now) {
        this.widgets = validateWidgets(nextWidgets);
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UserAccount getUser() {
        return user;
    }

    public List<DashboardWidgetLayout> getWidgets() {
        return List.copyOf(widgets);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    static List<DashboardWidgetLayout> validateWidgets(List<DashboardWidgetLayout> value) {
        if (value == null) {
            throw new IllegalArgumentException("widgets es obligatorio");
        }
        if (value.size() > MAX_WIDGETS) {
            throw new IllegalArgumentException("widgets no puede contener mas de 24 elementos");
        }
        var seen = new HashSet<String>();
        var normalized = value.stream()
                .map(widget -> {
                    if (widget == null) {
                        throw new IllegalArgumentException("widget es obligatorio");
                    }
                    var key = requiredKey(widget.key());
                    if (!seen.add(key)) {
                        throw new IllegalArgumentException("widget.key no puede estar duplicada");
                    }
                    if (!ALLOWED_WIDTHS.contains(widget.width())) {
                        throw new IllegalArgumentException("widget.width no esta permitido");
                    }
                    if (widget.height() < 1 || widget.height() > 3) {
                        throw new IllegalArgumentException("widget.height debe estar entre 1 y 3");
                    }
                    return new DashboardWidgetLayout(key, widget.width(), widget.height());
                })
                .toList();
        return new ArrayList<>(normalized);
    }

    private static String requiredKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("widget.key es obligatoria");
        }
        var normalized = value.trim();
        if (normalized.length() > MAX_WIDGET_KEY_LENGTH) {
            throw new IllegalArgumentException("widget.key no puede superar 64 caracteres");
        }
        if (!WIDGET_KEY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("widget.key contiene caracteres no permitidos");
        }
        return normalized;
    }
}
