package com.tpverp.saas.reportexport;

import com.tpverp.saas.admin.AdminService;
import com.tpverp.saas.admin.CompanySummaryResponse;
import com.tpverp.saas.stores.LicenseWorkspaceService;
import com.tpverp.saas.stores.StoreAdministrationService;
import com.tpverp.saas.stores.StoreWorkspaceApi.Page;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DirectoryExportService {
    static final int PAGE_SIZE = 100;
    private static final List<String> COMPANY_FILTERS = List.of("query", "province", "taxpayerType",
            "dateFrom", "dateTo", "contactName", "contactPhone", "contactEmail", "owner", "city", "postalCode");
    private final StoreAdministrationService stores;
    private final LicenseWorkspaceService licenses;
    private final AdminService admin;
    private final ExportFiles files;
    private final Clock clock;

    DirectoryExportService(StoreAdministrationService stores, LicenseWorkspaceService licenses, AdminService admin,
            ExportFiles files, Clock clock) {
        this.stores = stores;
        this.licenses = licenses;
        this.admin = admin;
        this.files = files;
        this.clock = clock;
    }

    public record CompanySelection(List<UUID> companyIds, Map<String, String> filters, String sortBy, String sortDirection) {}

    public ExportFiles.PreparedReport companies(CompanySelection selection, String language) throws IOException {
        var layout = layout(language);
        if (selection == null || selection.companyIds() == null) throw invalid();
        var ids = selection.companyIds();
        checkLimit(ids.size());
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw invalid();
        if (selection.sortBy() == null || !DirectoryExportLayout.COMPANIES.contains(selection.sortBy())) throw invalid();
        var filters = selection.filters() == null ? Map.<String, String>of() : selection.filters();
        if (!COMPANY_FILTERS.containsAll(filters.keySet())) throw invalid();
        var context = context(layout, "companies", selection.sortBy(), selection.sortDirection());
        for (String key : COMPANY_FILTERS) {
            String value = filters.get(key);
            if (value != null && !value.isBlank()) {
                if (value.length() > 32767) throw invalid();
                context.put(layout.label(key), key.equals("taxpayerType") ? layout.label(value) : value);
            }
        }
        context.put(layout.label("rows"), Integer.toString(ids.size()));
        return files.prepare("xlsx", path -> write(path, layout, DirectoryExportLayout.COMPANIES, context, writer -> {
            for (int offset = 0; offset < ids.size(); offset += PAGE_SIZE) {
                var batch = ids.subList(offset, Math.min(offset + PAGE_SIZE, ids.size()));
                var profiles = admin.companyProfiles(batch).stream()
                        .collect(Collectors.toMap(CompanySummaryResponse::companyId, Function.identity()));
                for (UUID id : batch) {
                    var profile = profiles.get(id);
                    if (profile == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Una empresa ya no está en el directorio. Actualiza la tabla y vuelve a exportar.");
                    writer.write(layout.company(profile));
                }
            }
        }));
    }

    public ExportFiles.PreparedReport stores(UUID companyId, String q, Boolean active, String commercialConfiguration,
            String sortBy, String sortDirection, String language) throws IOException {
        var layout = layout(language);
        return files.prepare("xlsx", path -> {
            var context = context(layout, "stores", sortBy, sortDirection);
            addFilters(layout, context, companyId, q);
            if (active != null) context.put(layout.label("status"), layout.label(active ? "ACTIVE_STORE" : "INACTIVE_STORE"));
            if (commercialConfiguration != null && !commercialConfiguration.isBlank())
                context.put(layout.label("commercialConfiguration"), layout.label(commercialConfiguration));
            writePages(path, layout, DirectoryExportLayout.STORES, context,
                    page -> stores.list(companyId, q, active, commercialConfiguration,
                            page, PAGE_SIZE, sortBy, sortDirection), layout::store);
        });
    }

    public ExportFiles.PreparedReport licenses(UUID companyId, UUID storeId, String q, String status, Instant expiresBefore,
            Boolean hasConnections, String billingStatus, String sortBy, String sortDirection, String language) throws IOException {
        var layout = layout(language);
        return files.prepare("xlsx", path -> {
            var context = context(layout, "licenses", sortBy, sortDirection);
            addFilters(layout, context, companyId, q);
            if (storeId != null) {
                String store = stores.find(storeId)
                        .map(row -> DirectoryExportLayout.storeDisplay(row.code(), row.internalCode(), row.name()))
                        .orElseGet(() -> layout.tr("Tienda no encontrada", "Store not found", "未找到门店"));
                context.put(layout.label("storeName"), store);
            }
            if (status != null && !status.isBlank())
                context.put(layout.label("status"), layout.label(status.trim().toUpperCase(Locale.ROOT)));
            if (expiresBefore != null) context.put(layout.label("expiresBefore"), expiresBefore.toString());
            if (hasConnections != null) context.put(layout.label("hasConnections"), layout.label(hasConnections.toString()));
            if (billingStatus != null && !billingStatus.isBlank())
                context.put(layout.label("billingStatus"), layout.label(billingStatus.trim().toUpperCase(Locale.ROOT)));
            writePages(path, layout, DirectoryExportLayout.LICENSES, context, page ->
                    licenses.listAt(companyId, storeId, q, status, expiresBefore, hasConnections, billingStatus, page,
                            PAGE_SIZE, sortBy, sortDirection, layout.now()), layout::license);
        });
    }

    private DirectoryExportLayout layout(String language) {
        if (!Set.of("es", "en", "zh").contains(language)) throw invalid();
        return new DirectoryExportLayout(language, clock.instant());
    }

    private static Map<String, String> context(DirectoryExportLayout layout, String directory, String sortBy, String direction) {
        if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) throw invalid();
        var context = new LinkedHashMap<String, String>();
        context.put(layout.label("report"), layout.label(directory));
        context.put(layout.label("asOf"), layout.now().toString());
        context.put(layout.label("timezone"), "UTC");
        context.put(layout.label("sort"), layout.label(sortBy));
        context.put(layout.label("direction"), layout.label(direction.toUpperCase(Locale.ROOT)));
        return context;
    }

    private void addFilters(DirectoryExportLayout layout, Map<String, String> context, UUID companyId, String q) {
        if (companyId != null) {
            var companies = admin.companyProfiles(List.of(companyId));
            context.put(layout.label("companyName"), companies.isEmpty()
                    ? layout.tr("Empresa no encontrada", "Company not found", "未找到企业") : companies.getFirst().companyName());
        }
        if (q != null && !q.isBlank()) context.put(layout.label("q"), q);
    }

    private static <T> void writePages(Path path, DirectoryExportLayout layout, List<String> columns,
            Map<String, String> context, IntFunction<Page<T>> read, Function<T, List<?>> cells) throws IOException {
        var first = read.apply(0);
        long total = first.total();
        checkLimit(total);
        context.put(layout.label("rows"), Long.toString(total));
        write(path, layout, columns, context, writer -> {
            var page = first;
            for (int index = 0, written = 0; ; index++) {
                if (page.total() != total || page.items().size() != Math.min(PAGE_SIZE, total - written))
                    throw new IllegalStateException("La paginación del directorio no coincide con el total");
                for (T row : page.items()) writer.write(cells.apply(row));
                written += page.items().size();
                if (written == total) return;
                page = read.apply(index + 1);
            }
        });
    }

    private static void write(Path path, DirectoryExportLayout layout, List<String> columns,
            Map<String, String> context, ReportFile.Rows rows) throws IOException {
        ReportFile.write(path, "xlsx", columns.stream().map(layout::label).toList(), context,
                layout.tr("Datos", "Data", "数据"), layout.tr("Contexto", "Context", "上下文"),
                layout.label("value"), true, writer -> rows.writeTo(values -> {
                    for (Object cell : values) {
                        if (cell instanceof String text && text.length() > 32767)
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "Una celda supera el límite de texto de Excel.");
                    }
                    writer.write(values);
                }));
    }

    private static void checkLimit(long total) {
        if (total > ReportExportService.LIMIT)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "El directorio supera 50.000 filas. Acota los filtros para exportar todos los resultados.");
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selección o filtros de exportación no válidos");
    }
}
