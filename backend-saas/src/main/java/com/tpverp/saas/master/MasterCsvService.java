package com.tpverp.saas.master;

import com.tpverp.saas.plan.PlanLimitService;
import com.tpverp.saas.plan.PlanResource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MasterCsvService {

    private static final Map<String, Definition> DEFINITIONS = Map.of(
            "customers", new Definition("saas_erp_customer", "code",
                    List.of("code", "name", "tax_id", "email", "phone")),
            "suppliers", new Definition("saas_erp_supplier", "code",
                    List.of("code", "name", "tax_id", "email", "phone")),
            "products", new Definition("saas_erp_product", "sku",
                    List.of("sku", "name", "category", "price", "tax_rate", "min_stock")),
            "warehouses", new Definition("saas_erp_warehouse", "code",
                    List.of("code", "name", "address")));

    private final JdbcTemplate jdbc;
    private final PlanLimitService limits;
    private final Clock clock;

    public MasterCsvService(JdbcTemplate jdbc, PlanLimitService limits, Clock clock) {
        this.jdbc = jdbc;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID companyId, String resource) {
        Definition definition = definition(resource);
        String columns = String.join(", ", definition.columns());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select " + columns + " from " + definition.table()
                        + " where company_id = ? order by " + definition.key(), companyId);
        StringBuilder csv = new StringBuilder(String.join(",", definition.columns())).append('\n');
        for (Map<String, Object> row : rows) {
            for (int index = 0; index < definition.columns().size(); index++) {
                if (index > 0) csv.append(',');
                csv.append(escape(row.get(definition.columns().get(index))));
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    @Transactional
    public MasterImportResult importCsv(UUID companyId, String resource, String csv) {
        Definition definition = definition(resource);
        List<List<String>> rows = parse(csv);
        if (rows.isEmpty() || !rows.get(0).equals(definition.columns())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cabecera CSV esperada: " + String.join(",", definition.columns()));
        }
        int inserted = 0;
        int updated = 0;
        for (int rowNumber = 1; rowNumber < rows.size(); rowNumber++) {
            List<String> row = rows.get(rowNumber);
            if (row.size() != definition.columns().size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Numero de columnas invalido en fila " + (rowNumber + 1));
            }
            validate(definition, row, rowNumber + 1);
            Long exists = jdbc.queryForObject("select count(*) from " + definition.table()
                    + " where company_id = ? and " + definition.key() + " = ?", Long.class,
                    companyId, row.get(0).trim());
            if (exists == null || exists == 0) {
                limits.requireCapacity(companyId, PlanResource.MASTER_RECORDS);
                insert(companyId, definition, row);
                inserted++;
            } else {
                update(companyId, definition, row);
                updated++;
            }
        }
        return new MasterImportResult(rows.size() - 1, inserted, updated);
    }

    @Transactional(readOnly = true)
    public MasterSearchPage search(UUID companyId, String resource, String query, int page, int size) {
        Definition definition = definition(resource);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String term = "%" + (query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
        String filter = "company_id = ? and (lower(" + definition.key() + ") like ? or lower(name) like ?)";
        Long total = jdbc.queryForObject("select count(*) from " + definition.table() + " where " + filter,
                Long.class, companyId, term, term);
        List<Map<String, Object>> raw = jdbc.queryForList("select * from " + definition.table()
                + " where " + filter + " order by " + definition.key() + " limit ? offset ?",
                companyId, term, term, safeSize, safePage * safeSize);
        List<Map<String, Object>> items = raw.stream().map(row -> {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("company_id");
            return java.util.Collections.unmodifiableMap(copy);
        }).toList();
        return new MasterSearchPage(items, safePage, safeSize, total == null ? 0 : total);
    }

    private void insert(UUID companyId, Definition definition, List<String> row) {
        String columns = String.join(", ", definition.columns());
        String placeholders = String.join(", ", definition.columns().stream().map(ignored -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(UUID.randomUUID());
        args.add(companyId);
        args.addAll(row.stream().map(MasterCsvService::blank).toList());
        args.add(true);
        args.add(Timestamp.from(clock.instant()));
        jdbc.update("insert into " + definition.table() + "(id, company_id, " + columns
                + ", active, created_at) values (?, ?, " + placeholders + ", ?, ?)", args.toArray());
    }

    private void update(UUID companyId, Definition definition, List<String> row) {
        String assignments = String.join(", ", definition.columns().subList(1, definition.columns().size())
                .stream().map(column -> column + " = ?").toList());
        List<Object> args = new ArrayList<>(row.subList(1, row.size()).stream()
                .map(MasterCsvService::blank).toList());
        args.add(companyId);
        args.add(row.get(0).trim());
        jdbc.update("update " + definition.table() + " set " + assignments
                + " where company_id = ? and " + definition.key() + " = ?", args.toArray());
    }

    private static void validate(Definition definition, List<String> row, int rowNumber) {
        if (row.get(0).isBlank() || row.get(1).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Codigo/SKU y nombre son obligatorios en fila " + rowNumber);
        }
        if (definition.key().equals("sku")) {
            for (int index : List.of(3, 4, 5)) {
                try {
                    new BigDecimal(row.get(index));
                } catch (NumberFormatException exception) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Valor numerico invalido en fila " + rowNumber);
                }
            }
        }
    }

    private static Definition definition(String resource) {
        Definition result = DEFINITIONS.get(resource == null ? "" : resource.toLowerCase(Locale.ROOT));
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Maestro CSV no soportado");
        }
        return result;
    }

    private static String escape(Object value) {
        String text = value == null ? "" : value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static List<List<String>> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < csv.length(); index++) {
            char value = csv.charAt(index);
            if (value == '"') {
                if (quoted && index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                    field.append('"'); index++;
                } else quoted = !quoted;
            } else if (value == ',' && !quoted) {
                row.add(field.toString()); field.setLength(0);
            } else if ((value == '\n' || value == '\r') && !quoted) {
                if (value == '\r' && index + 1 < csv.length() && csv.charAt(index + 1) == '\n') index++;
                row.add(field.toString()); field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(List.copyOf(row));
                row.clear();
            } else field.append(value);
        }
        if (quoted) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV con comillas sin cerrar");
        if (!field.isEmpty() || !row.isEmpty()) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Definition(String table, String key, List<String> columns) {
    }
}
