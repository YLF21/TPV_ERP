package com.tpverp.saas.reportexport;

import com.tpverp.saas.admin.AdminAuditQuery;
import com.tpverp.saas.admin.AdminAuditService;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuditExportService {
    private final AdminAuditQuery query;
    private final AdminAuditService audit;
    private final ExportFiles files;
    private final Clock clock;

    public AuditExportService(AdminAuditQuery query, AdminAuditService audit, ExportFiles files, Clock clock) {
        this.query = query;
        this.audit = audit;
        this.files = files;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ExportFiles.PreparedReport prepare(AdminAuditQuery.Filters filters, String language) throws IOException {
        filters.validate();
        if (!Set.of("es", "en", "zh").contains(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idioma no válido");
        }
        var layout = new DirectoryExportLayout(language, clock.instant());
        return files.prepare("xlsx", path -> {
            long total = query.count(filters);
            if (total > ReportExportService.LIMIT) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "La exportación supera las 50.000 filas");
            }
            var headers = List.of(layout.tr("Fecha y hora (UTC)", "Date and time (UTC)", "日期和时间 (UTC)"),
                    layout.tr("Usuario", "User", "用户"), layout.tr("Acción", "Action", "操作"),
                    layout.tr("Tipo de entidad", "Target type", "对象类型"), layout.tr("Referencia", "Target ID", "对象编号"),
                    layout.tr("Detalles", "Details", "详情"), layout.tr("ID de auditoría", "Audit ID", "审计编号"));
            var context = new LinkedHashMap<String, String>();
            context.put(layout.label("report"), layout.tr("Auditoría SaaS", "SaaS audit", "SaaS 审计"));
            context.put(layout.label("asOf"), layout.now().toString());
            context.put(layout.label("timezone"), "UTC");
            context.put(layout.label("rows"), Long.toString(total));
            if (filters.from() != null) context.put(layout.tr("Desde", "From", "从"), filters.from().toString());
            if (filters.to() != null) context.put(layout.tr("Hasta", "Through", "至"), filters.to().toString());
            if (filters.username() != null) context.put(headers.get(1), filters.username());
            if (filters.action() != null) context.put(headers.get(2), filters.action());
            if (filters.q() != null) context.put(layout.label("search"), filters.q());
            ReportFile.write(path, "xlsx", headers, context, layout.tr("Datos", "Data", "数据"),
                    layout.tr("Contexto", "Context", "上下文"), layout.label("value"), true, writer -> {
                        for (long offset = 0; offset < total; offset += 500) {
                            for (var row : query.rows(filters, offset, 500)) {
                                if (row.details() != null && row.details().length() > 32767) {
                                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                            "El detalle supera el límite de texto de Excel");
                                }
                                writer.write(Arrays.asList(row.createdAt(), row.username(), row.action(), row.targetType(),
                                        row.targetId(), row.details(), row.id()));
                            }
                        }
                    });
            audit.log("EXPORT_SAAS_AUDIT", "SAAS_AUDIT", "XLSX", "rows=" + total);
        });
    }
}
