package com.tpverp.backend.document;

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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "preferencia_visualizacion_informe",
        uniqueConstraints = @UniqueConstraint(
                name = "preferencia_visualizacion_informe_usuario_app_report_uq",
                columnNames = {"usuario_id", "app", "report_key"}))
public class ReportVisualizationPreference {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false, length = 32)
    private String app;

    @Column(name = "report_key", nullable = false, length = 128)
    private String reportKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_attributes", nullable = false, columnDefinition = "jsonb")
    private List<String> visibleAttributes = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ReportVisualizationPreference() {
    }

    public ReportVisualizationPreference(
            UserAccount user,
            String app,
            String reportKey,
            List<String> visibleAttributes) {
        this.id = UUID.randomUUID();
        this.user = Objects.requireNonNull(user, "user");
        this.app = required(app, "app");
        this.reportKey = required(reportKey, "reportKey");
        this.visibleAttributes = validateAttributes(visibleAttributes);
    }

    public void updateVisibleAttributes(List<String> nextVisibleAttributes, Instant when) {
        this.visibleAttributes = validateAttributes(nextVisibleAttributes);
        this.updatedAt = Objects.requireNonNull(when, "when");
        if (createdAt == null) {
            createdAt = when;
        }
    }

    public void markCreated(Instant when) {
        this.createdAt = Objects.requireNonNull(when, "when");
        this.updatedAt = when;
    }

    public String getReportKey() {
        return reportKey;
    }

    public List<String> getVisibleAttributes() {
        return List.copyOf(visibleAttributes);
    }

    private static List<String> validateAttributes(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("visibleAttributes es obligatorio");
        }
        var normalized = values.stream()
                .map(value -> required(value, "visibleAttributes"))
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
