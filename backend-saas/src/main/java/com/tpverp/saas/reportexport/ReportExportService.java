package com.tpverp.saas.reportexport;

import static com.tpverp.saas.reportexport.ReportExportLayout.label;
import static com.tpverp.saas.reportexport.ReportExportLayout.tr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tpverp.saas.analytics.ArticleReport;
import com.tpverp.saas.analytics.ArticleReportService;
import com.tpverp.saas.analytics.DocumentReport;
import com.tpverp.saas.analytics.DocumentReportService;
import com.tpverp.saas.analytics.ReportStatistics;
import com.tpverp.saas.analytics.ReportStatisticsService;
import com.tpverp.saas.inventory.InventoryComparison;
import com.tpverp.saas.inventory.InventoryComparisonService;
import com.tpverp.saas.inventory.InventoryReport;
import com.tpverp.saas.inventory.InventoryReportService;
import com.tpverp.saas.movement.MovementReport;
import com.tpverp.saas.movement.MovementReportService;
import java.io.IOException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportExportService {
    static final int LIMIT = 50_000;
    static final int ANALYTICS_PAGE_SIZE = 100;
    static final int INVENTORY_PAGE_SIZE = 200;
    static final String TEMP_PREFIX = "espos-report-";

    private static final List<String> FILTERS = List.of("kind", "companyId", "storeId", "province",
            "previousSnapshotId", "currentSnapshotId", "snapshotId", "warehouseId", "productId", "productCode",
            "currency", "search",
            "active", "changedOnly", "type", "from", "to", "comparison", "comparisonFrom", "comparisonTo",
            "groupBy", "sort", "direction");

    private final ReportStatisticsService statistics;
    private final ArticleReportService articles;
    private final DocumentReportService documents;
    private final MovementReportService movements;
    private final InventoryReportService inventory;
    private final InventoryComparisonService comparison;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ExportFiles files;

    public ReportExportService(ReportStatisticsService statistics, ArticleReportService articles,
            DocumentReportService documents,
            MovementReportService movements, InventoryReportService inventory, InventoryComparisonService comparison,
            ObjectMapper mapper, Clock clock, ExportFiles files) {
        this.files = files;
        this.statistics = statistics;
        this.articles = articles;
        this.documents = documents;
        this.movements = movements;
        this.inventory = inventory;
        this.comparison = comparison;
        this.mapper = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportFiles.PreparedReport prepare(String resource, Map<String, String> query, String format, String language)
            throws IOException {
        return files.prepare(format, path -> {
            String kind = query.getOrDefault("kind", defaultKind(resource));
            boolean readable = switch (query.getOrDefault("layout", "complete")) {
                case "complete" -> false;
                case "readable" -> true;
                default -> throw invalid();
            };
            String view = resource.equals("statistics") ? query.getOrDefault("view", "sales") : resource;
            if (resource.equals("statistics") && !ReadableReportLayout.STATISTICS_VIEWS.contains(view)) {
                throw invalid();
            }
            var pages = new ExportPages(pageSource(resource, kind, query));
            if (pages.total > LIMIT) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "El informe supera 50.000 filas. Acota los filtros para exportar todos los resultados.");
            }
            var layout = readable ? ReadableReportLayout.of(resource, kind, view, pages.metadata, language) : null;
            List<String> columns = readable ? layout.columns() : ReportExportLayout.columns(resource, kind, pages.metadata);
            if (format.equals("xlsx") && columns.size() > ReportFile.XLSX_COLUMN_LIMIT) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "El informe supera el límite de columnas de Excel. Acota los filtros o usa CSV.");
            }
            List<String> headers = columns.stream().map(key -> readable ? layout.header(key) : label(key, language)).toList();
            Map<String, String> context = readable
                    ? readableContext(view, kind, query, language, pages, layout)
                    : context(resource, kind, query, language, pages.total, pages.metadata);
            ReportFile.write(path, format, headers, context,
                    tr(language, "Datos", "Data", "数据"), tr(language, "Contexto", "Context", "上下文"),
                    readable ? label("value", language) : "UTC", readable, writer -> pages.writeTo(columns, layout, writer));
        });
    }

    private Map<String, String> readableContext(String view, String kind, Map<String, String> query,
            String language, ExportPages pages, ReadableReportLayout layout) {
        var context = new LinkedHashMap<String, String>();
        context.put(label("report", language), label(view.equals("provinces") ? "provinceSales" : view, language));
        if (!kind.isEmpty()) {
            context.put(label("kind", language), label(kind, language));
        }
        context.put(label("asOf", language), pages.metadata.getOrDefault("asOf", clock.instant()).toString());
        context.put(label("rows", language), Long.toString(pages.total));
        for (String key : List.of("companyId", "storeId", "province")) {
            String value = text(query, key);
            context.put(label(key, language), value == null ? tr(language, "Todas", "All", "全部") : value);
        }
        for (String key : FILTERS) {
            // Period dates below are resolved by the report, including automatic comparisons.
            if (List.of("from", "to", "comparisonFrom", "comparisonTo", "comparison").contains(key)) {
                continue;
            }
            String value = text(query, key);
            if (value != null) {
                context.put(label(key, language), filterLabel(key, value, language));
            }
        }
        var metadata = ReportExportLayout.flatten(mapper.valueToTree(pages.metadata));
        for (var entry : metadata.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (key.startsWith("totals.")) {
                String metric = key.substring("totals.".length());
                if (layout.columns().contains(metric) || view.equals("inventory")) {
                    context.put(label("total", language) + " · " + layout.header(metric), value.toString());
                }
            } else if (List.of("period.from", "period.to", "comparisonPeriod.from", "comparisonPeriod.to",
                    "snapshot.capturedAt", "previous.capturedAt", "current.capturedAt", "groupBy", "sort",
                    "direction", "currentCount", "comparisonCount").contains(key)
                    || key.startsWith("coverage.") && !value.toString().equals("0")) {
                context.put(label(key, language), filterLabel(key, value.toString(), language));
            }
        }
        return context;
    }

    private Map<String, String> context(String resource, String kind, Map<String, String> query, String language,
            long total, Map<String, Object> metadata) {
        var context = new LinkedHashMap<String, String>();
        context.put(label("report", language), label(resource, language));
        context.put(label("asOf", language), clock.instant().toString());
        context.put(label("timezone", language), "UTC");
        context.put(label("rows", language), Long.toString(total));
        String all = tr(language, "Todas", "All", "全部");
        for (String key : List.of("companyId", "storeId", "province")) {
            String value = text(query, key);
            context.put(label(key, language), value == null ? all : value);
        }
        for (String key : FILTERS) {
            String value = text(query, key);
            if (value != null) {
                context.put(label(key, language), filterLabel(key, value, language));
            }
        }
        if (!kind.isEmpty()) {
            context.put(label("kind", language), label(kind, language));
        }
        ReportExportLayout.flatten(mapper.valueToTree(metadata)).forEach((key, value) ->
                context.put(label(key, language), value == null ? "" : filterLabel(key, value.toString(), language)));
        context.put(label("precision", language), tr(language,
                "Los decimales de Excel se guardan como texto exacto. Las celdas vacías indican datos no disponibles.",
                "Excel decimals are stored as exact text. Blank cells indicate unavailable data.",
                "Excel 小数保存为精确文本。空白单元格表示数据不可用。"));
        return context;
    }

    private static String filterLabel(String key, String value, String language) {
        return switch (key) {
            case "kind", "active", "changedOnly", "type", "comparison", "groupBy", "sort", "direction" ->
                label(value, language);
            default -> value;
        };
    }

    private PageSource pageSource(String resource, String kind, Map<String, String> q) {
        try {
            UUID company = uuid(q, "companyId");
            UUID store = uuid(q, "storeId");
            UUID warehouse = uuid(q, "warehouseId");
            UUID product = uuid(q, "productId");
            String province = text(q, "province");
            String search = text(q, "search");

            if (resource.equals("comparison")) {
                return comparisonSource(q, required(company), required(store), warehouse, search);
            }
            if (resource.equals("inventory")) {
                return inventorySource(kind, q, company, store, province, warehouse, product, search);
            }

            var period = ReportStatistics.Period.inclusive(date(q, "from"), date(q, "to"));
            if (resource.equals("documents")) {
                return documentSource(q, period, company, store, province, product);
            }
            var other = period.comparison(choice(q, "comparison", ReportStatistics.Comparison.NONE),
                    date(q, "comparisonFrom"), date(q, "comparisonTo"));
            return switch (resource) {
                case "statistics" -> statisticsSource(q, period, other, company, store, province);
                case "articles" -> articleSource(q, period, other, company, store, province, search);
                case "movements" -> movementSource(kind, q, period, other, company, store, province, warehouse, product);
                default -> throw invalid();
            };
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    private PageSource statisticsSource(Map<String, String> q, ReportStatistics.Period period,
            ReportStatistics.Period other, UUID company, UUID store, String province) {
        var group = choice(q, "groupBy", ReportStatistics.GroupBy.DAY);
        return new PageSource(ANALYTICS_PAGE_SIZE, page -> {
            var result = statistics.report(period, other, group, company, store, province, page, ANALYTICS_PAGE_SIZE);
            var metadata = page == 0 ? metadata("asOf", result.asOf(), "period", result.period(),
                    "comparisonPeriod", result.comparisonPeriod(), "groupBy", result.groupBy(),
                    "coverage", result.coverage(), "totals", result.totals(), "syncByType", result.syncByType(),
                    "syncByStatus", result.syncByStatus()) : Map.<String, Object>of();
            return new ExportPage(result.totalGroups(), result.rows(), metadata);
        });
    }

    private PageSource articleSource(Map<String, String> q, ReportStatistics.Period period,
            ReportStatistics.Period other, UUID company, UUID store, String province, String search) {
        var sort = choice(q, "sort", ArticleReport.Sort.AMOUNT);
        var direction = text(q, "direction") == null ? null : choice(q, "direction", ArticleReport.Direction.DESC);
        return new PageSource(ANALYTICS_PAGE_SIZE, page -> {
            var result = articles.report(period, other, company, store, province, search, sort, direction,
                    page, ANALYTICS_PAGE_SIZE);
            var metadata = page == 0 ? metadata("asOf", result.asOf(), "period", result.period(),
                    "comparisonPeriod", result.comparisonPeriod(), "coverage", result.coverage(),
                    "sort", sort, "direction", direction != null ? direction
                            : sort == ArticleReport.Sort.CODE ? ArticleReport.Direction.ASC : ArticleReport.Direction.DESC)
                    : Map.<String, Object>of();
            return new ExportPage(result.totalRows(), result.rows(), metadata);
        });
    }

    private PageSource movementSource(String kind, Map<String, String> q, ReportStatistics.Period period,
            ReportStatistics.Period other, UUID company, UUID store, String province, UUID warehouse, UUID product) {
        String type = text(q, "type");
        var filter = new MovementReport.Filter(company, store, province, warehouse, product,
                type == null ? null : MovementReport.Type.valueOf(type));
        if (kind.equals("ledger")) {
            var sort = choice(q, "sort", MovementReport.Sort.OCCURRED_AT);
            var direction = choice(q, "direction", MovementReport.Direction.DESC);
            return new PageSource(INVENTORY_PAGE_SIZE, page -> {
                var result = movements.ledger(filter, period, sort, direction, page, INVENTORY_PAGE_SIZE);
                var metadata = page == 0 && result.total() <= LIMIT
                        ? movementMetadata(movements.summary(filter, period, null, MovementReport.GroupBy.TYPE, 0, 1))
                        : Map.<String, Object>of();
                if (page == 0 && result.total() <= LIMIT) {
                    metadata.remove("groupBy");
                    metadata.put("sort", sort);
                    metadata.put("direction", direction);
                }
                return new ExportPage(result.total(), result.rows(), metadata);
            });
        }
        if (!kind.equals("summary")) {
            throw invalid();
        }
        var group = choice(q, "groupBy", MovementReport.GroupBy.TYPE);
        return new PageSource(INVENTORY_PAGE_SIZE, page -> {
            var result = movements.summary(filter, period, other, group, page, INVENTORY_PAGE_SIZE);
            return new ExportPage(result.totalGroups(), result.rows(),
                    page == 0 ? movementMetadata(result) : Map.of());
        });
    }

    private static Map<String, Object> movementMetadata(MovementReport.Summary result) {
        return metadata("period", result.period(), "comparisonPeriod", result.comparisonPeriod(),
                "groupBy", result.groupBy(), "asOf", result.asOf(), "coverage", result.coverage(),
                "currentCount", result.currentCount(), "comparisonCount", result.comparisonCount());
    }

    private PageSource documentSource(Map<String, String> q, ReportStatistics.Period period,
            UUID company, UUID store, String province, UUID product) {
        String productCode = text(q, "productCode");
        String currency = text(q, "currency");
        var sort = choice(q, "sort", DocumentReport.Sort.DATE);
        var direction = text(q, "direction") == null ? null : choice(q, "direction", DocumentReport.Direction.DESC);
        var appliedDirection = direction == null
                ? switch (sort) {
                    case DATE, AMOUNT -> DocumentReport.Direction.DESC;
                    case NUMBER, CURRENCY -> DocumentReport.Direction.ASC;
                }
                : direction;
        return new PageSource(ANALYTICS_PAGE_SIZE, page -> {
            var result = documents.report(period, company, store, province, product, productCode, currency, sort,
                    direction, page, ANALYTICS_PAGE_SIZE);
            var metadata = page == 0 ? metadata("asOf", result.asOf(), "period", result.period(),
                    "coverage", result.coverage(), "sort", sort, "direction", appliedDirection)
                    : Map.<String, Object>of();
            return new ExportPage(result.totalRows(), result.rows(), metadata);
        });
    }

    private PageSource comparisonSource(Map<String, String> q, UUID company, UUID store, UUID warehouse, String search) {
        UUID previous = required(uuid(q, "previousSnapshotId"));
        UUID current = required(uuid(q, "currentSnapshotId"));
        boolean changedOnly = bool(q, "changedOnly", false);
        var sort = choice(q, "sort", InventoryComparison.Sort.PRODUCT);
        var direction = choice(q, "direction", InventoryComparison.Direction.ASC);
        return new PageSource(INVENTORY_PAGE_SIZE, page -> {
            var result = comparison.compare(company, store, previous, current, warehouse, search, changedOnly, sort,
                    direction, page, INVENTORY_PAGE_SIZE);
            var metadata = page == 0 ? metadata("previous", result.previous(), "current", result.current(),
                    "counts", result.counts(), "catalogue", result.catalogue(),
                    "sort", sort, "direction", direction, "changedOnly", changedOnly) : Map.<String, Object>of();
            return new ExportPage(result.total(), result.rows(), metadata);
        });
    }

    private PageSource inventorySource(String kind, Map<String, String> q, UUID company, UUID store,
            String province, UUID warehouse, UUID product, String search) {
        if (kind.equals("provinces")) {
            return new PageSource(INVENTORY_PAGE_SIZE, page -> {
                var rows = inventory.provinces(company, store, province).stream().map(row ->
                        metadata("province", row.province(), "totals", ReportExportLayout.stockTotals(row.totals())))
                        .toList();
                return new ExportPage(rows.size(), rows, Map.of());
            });
        }
        if (!kind.equals("overview")) {
            required(company);
            required(store);
        }
        UUID snapshot = uuid(q, "snapshotId");
        var direction = choice(q, "direction", kind.equals("history")
                ? InventoryReport.Direction.DESC : InventoryReport.Direction.ASC);
        Boolean active = text(q, "active") == null ? null : bool(q, "active", false);
        IntFunction<ExportPage> read = switch (kind) {
            case "overview" -> {
                var sort = choice(q, "sort", InventoryReport.OverviewSort.COMPANY);
                yield page -> {
                    var result = inventory.overview(company, store, province, sort, direction, page, INVENTORY_PAGE_SIZE);
                    return new ExportPage(result.total(), result.rows(), page == 0
                            ? metadata("totals", ReportExportLayout.stockTotals(result.totals()),
                                    "sort", sort, "direction", direction) : Map.of());
                };
            }
            case "history" -> {
                var sort = choice(q, "sort", InventoryReport.HistorySort.CAPTURED_AT);
                yield page -> {
                    var result = inventory.history(company, store, sort, direction, page, INVENTORY_PAGE_SIZE);
                    return new ExportPage(result.total(), result.rows(),
                            page == 0 ? metadata("sort", sort, "direction", direction) : Map.of());
                };
            }
            case "products" -> {
                var sort = choice(q, "sort", InventoryReport.ProductSort.PRODUCT);
                yield page -> detail(inventory.products(company, store, snapshot, search, active, sort, direction,
                        page, INVENTORY_PAGE_SIZE), page, sort, direction);
            }
            case "warehouses" -> {
                var sort = choice(q, "sort", InventoryReport.WarehouseSort.WAREHOUSE);
                yield page -> detail(inventory.warehouses(company, store, snapshot, sort, direction,
                        page, INVENTORY_PAGE_SIZE), page, sort, direction);
            }
            case "balances" -> {
                var sort = choice(q, "sort", InventoryReport.BalanceSort.PRODUCT);
                yield page -> detail(inventory.balances(company, store, snapshot, search, active, product, warehouse,
                        sort, direction, page, INVENTORY_PAGE_SIZE), page, sort, direction);
            }
            default -> throw invalid();
        };
        return new PageSource(INVENTORY_PAGE_SIZE, read);
    }

    private static ExportPage detail(InventoryReport.Detail<?> result, int page, Enum<?> sort,
            InventoryReport.Direction direction) {
        return new ExportPage(result.total(), result.rows(), page == 0
                ? metadata("snapshot", result.snapshot(), "sort", sort, "direction", direction) : Map.of());
    }

    private static Map<String, Object> metadata(Object... pairs) {
        var metadata = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            metadata.put((String) pairs[index], pairs[index + 1]);
        }
        return metadata;
    }

    private static String defaultKind(String resource) {
        return switch (resource) {
            case "inventory" -> "overview";
            case "movements" -> "summary";
            default -> "";
        };
    }

    private static UUID required(UUID value) {
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private static UUID uuid(Map<String, String> q, String key) {
        String value = text(q, key);
        if (value == null) {
            return null;
        }
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw invalid();
        }
        return id;
    }

    private static LocalDate date(Map<String, String> q, String key) {
        String value = text(q, key);
        return value == null ? null : LocalDate.parse(value);
    }

    private static String text(Map<String, String> q, String key) {
        String value = q.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean bool(Map<String, String> q, String key, boolean fallback) {
        String value = text(q, key);
        if (value == null) {
            return fallback;
        }
        if (!value.equals("true") && !value.equals("false")) {
            throw invalid();
        }
        return Boolean.parseBoolean(value);
    }

    private static <T extends Enum<T>> T choice(Map<String, String> q, String key, T fallback) {
        String value = text(q, key);
        return value == null ? fallback : Enum.valueOf(fallback.getDeclaringClass(), value);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtros de exportación no válidos");
    }

    private record PageSource(int size, IntFunction<ExportPage> read) {}
    private record ExportPage(long total, List<?> rows, Map<String, Object> metadata) {}

    private final class ExportPages {
        private final PageSource source;
        private final long total;
        private final Map<String, Object> metadata;
        private ExportPage page;

        private ExportPages(PageSource source) {
            this.source = source;
            this.page = source.read().apply(0);
            this.total = page.total();
            this.metadata = page.metadata();
        }

        private void writeTo(List<String> columns, ReadableReportLayout layout, ReportFile.RowWriter writer) throws IOException {
            for (int index = 0, written = 0; ; index++) {
                int expected = (int) Math.min(source.size(), total - written);
                if (page.total() != total || page.rows().size() != expected) {
                    throw new IllegalStateException("La paginación del informe no coincide con el total");
                }
                for (Object row : page.rows()) {
                    Map<String, Object> cells = ReportExportLayout.flatten(mapper.valueToTree(row));
                    writer.write(columns.stream().map(key -> layout == null ? cells.get(key) : layout.cell(cells, key)).toList());
                }
                page = null;
                written += expected;
                if (written == total) {
                    return;
                }
                page = source.read().apply(index + 1);
            }
        }
    }
}
