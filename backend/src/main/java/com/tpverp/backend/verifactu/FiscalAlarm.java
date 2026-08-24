package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alarma_fiscal")
public class FiscalAlarm {
    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;
    @Column(name = "codigo", nullable = false, length = 64)
    private String code;
    @Column(name = "detalle", nullable = false, columnDefinition = "text")
    private String detail;
    @Column(name = "detectada_en", nullable = false)
    private Instant detectedAt;
    @Column(name = "activa", nullable = false)
    private boolean active = true;

    protected FiscalAlarm() {}

    public FiscalAlarm(UUID companyId, UUID installationId, String code, String detail,
            Instant detectedAt) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.installationId = installationId;
        this.code = code;
        this.detail = detail;
        this.detectedAt = detectedAt;
    }
}
