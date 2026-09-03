package com.tpverp.backend.management;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.FiscalAddress;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.party.SalesRepresentative;
import com.tpverp.backend.party.Supplier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single server-side implementation of the safe retirement invariant.
 *
 * The impact check is deliberately based on the database's foreign-key
 * metadata instead of a hand-maintained list. New references therefore fail
 * safe (deactivation) until they are explicitly understood.
 */
@Service
public class SafeManagementRetirementService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CURSOR_LENGTH = 512;

    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final CurrentOrganization organization;
    private final AuditService audit;
    private final MemberLoyaltyService memberLoyalty;

    public SafeManagementRetirementService(
            EntityManager entityManager,
            JdbcTemplate jdbc,
            CurrentOrganization organization,
            AuditService audit,
            MemberLoyaltyService memberLoyalty) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.organization = organization;
        this.audit = audit;
        this.memberLoyalty = memberLoyalty;
    }

    @Transactional(readOnly = true)
    public ManagementPage<ManagementItem> page(EntityType type, int size, String cursor, String search,
            Boolean activeFilter, String sort, String direction) {
        int requestedSize = validateSize(size);
        if (search != null && search.length() > 120) {
            throw new IllegalArgumentException("El texto de busqueda no puede superar 120 caracteres");
        }
        PageOrder pageOrder = pageOrder(type, sort, direction);
        Cursor anchor = decodeCursor(cursor, pageOrder);
        String normalizedSearch = search == null || search.isBlank()
                ? null : likePattern(search);
        StringBuilder sql = new StringBuilder("select ");
        if (type == EntityType.PRODUCT) {
            sql.append("p.id, p.version, coalesce((select pi.valor from producto_identificador pi "
                    + "where pi.producto_id = p.id and pi.tipo = 'CODIGO' limit 1), '') as code, ")
                    .append("p.nombre as name, null as client_id, null as fiscal_name, null as supplier_id, "
                    + "null as legal_name, null as trade_name, null as commercial_id, null as document_type, "
                    + "null as document_number, null as direccion, null as codigo_postal, null as poblacion, "
                    + "null as provincia, null as pais, null as telefono, null as email, null as notes, "
                    + "p.activo, false as is_member, null as num_member, null as other_contact, "
                    + "null as discount, null as member_uuid, null as member_since, null as birthday, "
                    + "null as gender, false as commercial_consent, null as preferred_channel_id, "
                    + "false as credit_enabled, null as credit_limit, null as payment_term_days, "
                    + "false as credit_blocked, false as block_on_overdue "
                    + "from producto p where p.tienda_id = ?");
        } else if (type == EntityType.CUSTOMER) {
            sql.append("c.id, c.version, c.client_id as code, c.nombre_fiscal as name, "
                    + "c.client_id, c.nombre_fiscal as fiscal_name, null as supplier_id, null as legal_name, "
                    + "null as trade_name, null as commercial_id, c.tipo_documento as document_type, "
                    + "c.numero_documento as document_number, c.direccion, c.codigo_postal, c.poblacion, "
                    + "c.provincia, c.pais, c.telefono, c.email, c.observaciones as notes, c.activo, "
                    + "exists(select 1 from miembro m where m.cliente_id = c.id and m.active = true) as is_member, "
                    + "(select m.num_member from miembro m where m.cliente_id = c.id limit 1) as num_member, null as other_contact, "
                    + "c.descuento as discount, (select m.id from miembro m where m.cliente_id = c.id limit 1) as member_uuid, "
                    + "(select m.member_since from miembro m where m.cliente_id = c.id limit 1) as member_since, "
                    + "c.birthday, c.gender, c.commercial_consent, "
                    + "c.preferred_commercial_channel_id as preferred_channel_id, c.credit_enabled, "
                    + "c.credit_limit, c.payment_term_days, c.credit_blocked, c.block_on_overdue "
                    + "from cliente c where c.empresa_id = ?");
        } else if (type == EntityType.SUPPLIER) {
            sql.append("s.id, s.version, s.supplier_id as code, s.razon_social as name, null as client_id, "
                    + "null as fiscal_name, s.supplier_id, s.razon_social as legal_name, s.nombre_comercial as trade_name, "
                    + "null as commercial_id, s.tipo_documento as document_type, s.numero_documento as document_number, "
                    + "s.direccion, s.codigo_postal, s.poblacion, s.provincia, s.pais, s.telefono, s.email, "
                    + "s.observaciones as notes, null as other_contact, s.activo, false as is_member, null as num_member, "
                    + "null as discount, null as member_uuid, null as member_since, null as birthday, "
                    + "null as gender, false as commercial_consent, null as preferred_channel_id, "
                    + "false as credit_enabled, null as credit_limit, null as payment_term_days, "
                    + "false as credit_blocked, false as block_on_overdue "
                    + "from proveedor s where s.empresa_id = ?");
        } else {
            sql.append("r.id, r.version, r.commercial_id as code, r.nombre as name, null as client_id, "
                    + "null as fiscal_name, null as supplier_id, null as legal_name, null as trade_name, "
                    + "r.commercial_id, null as document_type, null as document_number, null as direccion, "
                    + "null as codigo_postal, null as poblacion, null as provincia, null as pais, r.telefono, "
                    + "r.email, r.otro_contacto as notes, r.otro_contacto as other_contact, r.activo, false as is_member, null as num_member "
                    + ", null as discount, null as member_uuid, null as member_since, null as birthday, "
                    + "null as gender, false as commercial_consent, null as preferred_channel_id, "
                    + "false as credit_enabled, null as credit_limit, null as payment_term_days, "
                    + "false as credit_blocked, false as block_on_overdue "
                    + "from comercial r where r.empresa_id = ?");
        }
        List<Object> args = new ArrayList<>();
        args.add(type == EntityType.PRODUCT
                ? organization.currentStore().getId() : organization.currentCompany().getId());
        if (normalizedSearch != null) {
            appendSearch(sql, args, type, normalizedSearch);
        }
        if (activeFilter != null) {
            sql.append(" and activo = ?");
            args.add(activeFilter);
        }
        String idExpression = idExpression(type);
        String comparison = pageOrder.descending() ? " < " : " > ";
        if (anchor != null) {
            sql.append(" and (").append(pageOrder.expression()).append(comparison).append("? ")
                    .append("or (").append(pageOrder.expression()).append(" = ? and ")
                    .append(idExpression).append(comparison).append("?))");
            args.add(anchor.value());
            args.add(anchor.value());
            args.add(anchor.id());
        }
        String orderDirection = pageOrder.descending() ? " desc" : " asc";
        sql.append(" order by ").append(pageOrder.expression()).append(orderDirection).append(", ")
                .append(idExpression).append(orderDirection)
                .append(" limit ?");
        args.add(requestedSize + 1);
        List<ManagementItem> rows = jdbc.query(sql.toString(), args.toArray(), (rs, rowNum) -> {
            UUID rowId = UUID.fromString(rs.getString("id"));
            return new ManagementItem(rowId, rs.getLong("version"), rs.getString("code"),
                    rs.getString("name"), rs.getString("client_id"), rs.getString("fiscal_name"),
                    rs.getString("supplier_id"), rs.getString("legal_name"), rs.getString("trade_name"),
                    rs.getString("commercial_id"), rs.getString("document_type"), rs.getString("document_number"),
                    address(rs), rs.getString("telefono"), rs.getString("email"), rs.getString("notes"),
                    rs.getString("other_contact"),
                    rs.getBoolean("activo"), rs.getBoolean("is_member"), rs.getString("num_member"),
                    rs.getBigDecimal("discount"), uuid(rs.getString("member_uuid")),
                    rs.getObject("member_since", java.time.LocalDate.class),
                    rs.getObject("birthday", java.time.LocalDate.class), rs.getString("gender"),
                    rs.getBoolean("commercial_consent"), uuid(rs.getString("preferred_channel_id")),
                    rs.getBoolean("credit_enabled"), rs.getBigDecimal("credit_limit"),
                    (Integer) rs.getObject("payment_term_days"), rs.getBoolean("credit_blocked"),
                    rs.getBoolean("block_on_overdue"),
                    List.of(), List.of());
        });
        if (type == EntityType.SALES_REPRESENTATIVE && !rows.isEmpty()) {
            rows = attachRepresentativeSuppliers(rows);
        }
        boolean hasNext = rows.size() > requestedSize;
        if (hasNext) {
            rows = new ArrayList<>(rows.subList(0, requestedSize));
        }
        String nextCursor = hasNext
                ? encodeCursor(pageOrder, pageOrder.value(rows.getLast()), rows.getLast().id()) : null;
        return new ManagementPage<>(rows, requestedSize, nextCursor, hasNext);
    }

    public ManagementPage<ManagementItem> page(EntityType type, int size, String cursor, String search,
            Boolean activeFilter) {
        return page(type, size, cursor, search, activeFilter, "name", "asc");
    }

    @Transactional(readOnly = true)
    public ManagementItem representative(UUID id) {
        SalesRepresentative representative = (SalesRepresentative) findScoped(
                EntityType.SALES_REPRESENTATIVE, id, false);
        List<ManagementItem.SupplierLink> suppliers = representativeSuppliers(id);
        return new ManagementItem(id, representative.getVersion(), representative.getCommercialId(),
                representative.getName(), null, null, null, null, null,
                representative.getCommercialId(), null, null, null,
                representative.getPhone(), representative.getEmail(), null,
                representative.getOtherContact(), representative.isActive(), false, null,
                null, null, null, null, null, false, null,
                false, null, null, false, false,
                List.of(), suppliers);
    }

    @Transactional(readOnly = true)
    public SafeRetirementImpact impact(EntityType type, UUID id) {
        Object entity = findScoped(type, id, false);
        boolean active = active(type, entity);
        List<String> reasons = references(type, id, entity);
        boolean protectedProduct = type == EntityType.PRODUCT && "0".equals(code(type, entity));
        if (protectedProduct) {
            reasons = withReason(reasons, "PROTECTED_SYSTEM_PRODUCT");
        }
        RetirementOutcome outcome = !active
                ? RetirementOutcome.ALREADY_INACTIVE
                : reasons.isEmpty() ? RetirementOutcome.HARD_DELETED : RetirementOutcome.DEACTIVATED;
        return new SafeRetirementImpact(id, version(type, entity),
                active ? "ACTIVE" : "INACTIVE", outcome, reasons, !protectedProduct);
    }

    @Transactional
    public SafeRetirementResult retire(EntityType type, UUID id, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("La version esperada no es valida");
        }
        Object entity = findScoped(type, id, true);
        long currentVersion = version(type, entity);
        if (currentVersion != expectedVersion) {
            throw new SafeRetirementStaleStateException();
        }
        boolean wasActive = active(type, entity);
        List<String> reasons = references(type, id, entity);
        if (type == EntityType.PRODUCT && "0".equals(code(type, entity))) {
            throw new ProtectedSystemProductException();
        }
        RetirementOutcome outcome;
        if (!wasActive) {
            outcome = RetirementOutcome.ALREADY_INACTIVE;
        } else if (!reasons.isEmpty()) {
            if (type == EntityType.CUSTOMER) {
                deactivateLinkedMembers(id);
            }
            deactivate(type, entity);
            outcome = RetirementOutcome.DEACTIVATED;
        } else {
            entityManager.remove(entity);
            outcome = RetirementOutcome.HARD_DELETED;
        }
        entityManager.flush();
        audit.record("SAFE_MANAGEMENT_RETIREMENT", AuditResult.EXITO,
                Map.of("entity", type.path, "id", id.toString(), "outcome", outcome.name(),
                        "reasonCodes", reasons, "expectedVersion", expectedVersion));
        return new SafeRetirementResult(id, outcome, reasons);
    }

    private void deactivateLinkedMembers(UUID customerId) {
        entityManager.createQuery(
                        "select member from Member member where member.customer.id = :customerId "
                                + "and member.active = true", Member.class)
                .setParameter("customerId", customerId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .forEach(memberLoyalty::deactivateMember);
    }

    private List<ManagementItem> attachRepresentativeSuppliers(List<ManagementItem> rows) {
        String placeholders = String.join(",", rows.stream().map(row -> "?").toList());
        Map<UUID, List<ManagementItem.SupplierLink>> links = new HashMap<>();
        Object[] ids = rows.stream().map(ManagementItem::id).toArray();
        jdbc.query("""
                select pc.comercial_id, s.id, s.supplier_id, s.razon_social, pc.principal
                from proveedor_comercial pc
                join proveedor s on s.id = pc.proveedor_id
                where pc.comercial_id in (PLACEHOLDERS)
                order by lower(s.razon_social), s.id
                """.replace("PLACEHOLDERS", placeholders), ids, (rs, rowNum) -> {
                    UUID representativeId = UUID.fromString(rs.getString("comercial_id"));
                    links.computeIfAbsent(representativeId, ignored -> new ArrayList<>())
                            .add(new ManagementItem.SupplierLink(
                                    UUID.fromString(rs.getString("id")), rs.getString("supplier_id"),
                                    rs.getString("razon_social"), rs.getBoolean("principal")));
                    return null;
                });
        return rows.stream().map(row -> row.withSuppliers(links.getOrDefault(row.id(), List.of()))).toList();
    }

    private List<ManagementItem.SupplierLink> representativeSuppliers(UUID representativeId) {
        return jdbc.query("""
                select s.id, s.supplier_id, s.razon_social, pc.principal
                from proveedor_comercial pc
                join proveedor s on s.id = pc.proveedor_id
                where pc.comercial_id = ?
                order by lower(s.razon_social), s.id
                """, (rs, rowNum) -> new ManagementItem.SupplierLink(
                        UUID.fromString(rs.getString("id")), rs.getString("supplier_id"),
                        rs.getString("razon_social"), rs.getBoolean("principal")), representativeId);
    }

    private static FiscalAddress address(java.sql.ResultSet rs) throws java.sql.SQLException {
        String street = rs.getString("direccion");
        String postalCode = rs.getString("codigo_postal");
        String city = rs.getString("poblacion");
        String province = rs.getString("provincia");
        String country = rs.getString("pais");
        return street == null && postalCode == null && city == null
                && province == null && country == null
                ? null : new FiscalAddress(street, postalCode, city, province, country);
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private Object findScoped(EntityType type, UUID id, boolean lock) {
        String queryText = switch (type) {
            case PRODUCT -> "select entity from Product entity where entity.id = :id and entity.storeId = :scopeId";
            case CUSTOMER -> "select entity from Customer entity where entity.id = :id and entity.company.id = :scopeId";
            case SUPPLIER -> "select entity from Supplier entity where entity.id = :id and entity.company.id = :scopeId";
            case SALES_REPRESENTATIVE -> "select entity from SalesRepresentative entity "
                    + "where entity.id = :id and entity.company.id = :scopeId";
        };
        UUID scopeId = type == EntityType.PRODUCT
                ? organization.currentStore().getId() : organization.currentCompany().getId();
        TypedQuery<?> query = entityManager.createQuery(queryText, type.entityClass)
                .setParameter("id", id)
                .setParameter("scopeId", scopeId)
                .setLockMode(lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
        return query.getResultStream().findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException(type.path + " no encontrado"));
    }

    private boolean belongsToCurrentScope(EntityType type, Object entity) {
        if (type == EntityType.PRODUCT) {
            return ((Product) entity).getStoreId().equals(organization.currentStore().getId());
        }
        UUID companyId = organization.currentCompany().getId();
        return switch (type) {
            case CUSTOMER -> ((Customer) entity).getCompany().getId().equals(companyId);
            case SUPPLIER -> ((Supplier) entity).getCompany().getId().equals(companyId);
            case SALES_REPRESENTATIVE -> ((SalesRepresentative) entity).getCompany().getId().equals(companyId);
            default -> false;
        };
    }

    private List<String> references(EntityType type, UUID id, Object entity) {
        Set<String> reasons = new TreeSet<>();
        String targetTable = type.table;
        List<ForeignKey> keys = jdbc.query("""
                select distinct kcu.table_schema, kcu.table_name, kcu.column_name,
                       rc.delete_rule
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_name = kcu.constraint_name
                 and tc.table_schema = kcu.table_schema
                join information_schema.constraint_column_usage ccu
                  on tc.constraint_name = ccu.constraint_name
                 and tc.table_schema = ccu.table_schema
                join information_schema.referential_constraints rc
                  on tc.constraint_name = rc.constraint_name
                 and tc.table_schema = rc.constraint_schema
                where tc.constraint_type = 'FOREIGN KEY'
                  and tc.table_schema = current_schema()
                  and ccu.table_schema = current_schema()
                  and ccu.table_name = ?
                """, ps -> ps.setString(1, targetTable),
                (rs, rowNum) -> new ForeignKey(
                        rs.getString("table_schema"), rs.getString("table_name"),
                        rs.getString("column_name"), rs.getString("delete_rule")));
        for (ForeignKey key : keys) {
            // These are owned rows only when the live schema confirms CASCADE.
            if (type == EntityType.PRODUCT
                    && (key.table.equals("producto_identificador") || key.table.equals("producto_precio"))
                    && "CASCADE".equalsIgnoreCase(key.deleteRule)) {
                continue;
            }
            String schema = identifier(key.schema);
            String table = identifier(key.table);
            String column = identifier(key.column);
            Boolean exists = jdbc.queryForObject(
                    "select exists(select 1 from \"" + schema + "\".\"" + table
                            + "\" where \"" + column + "\" = ?)",
                    Boolean.class, id);
            if (Boolean.TRUE.equals(exists)) {
                reasons.add(reasonFor(type, key.table));
            }
        }
        if (type == EntityType.PRODUCT) {
            Product product = (Product) entity;
            if (product != null && product.getImageId() != null) {
                reasons.add("HAS_IMAGE");
            }
        }
        return List.copyOf(reasons);
    }

    private static String reasonFor(EntityType type, String table) {
        return switch (type) {
            case PRODUCT -> switch (table) {
                case "existencia", "stock" -> "HAS_STOCK";
                case "movimiento_stock" -> "HAS_STOCK_MOVEMENTS";
                case "documento_linea", "linea_venta_eliminada" -> "HAS_DOCUMENTS";
                case "salida_almacen_linea", "entrada_almacen_linea", "transferencia_almacen_linea" -> "HAS_WAREHOUSE_MOVEMENTS";
                case "recuento_stock_linea", "recuento_linea" -> "HAS_PHYSICAL_COUNTS";
                case "asignacion_ean_interno" -> "HAS_EAN_CODES";
                case "autorizacion_cambio_precio", "precio_autorizacion", "producto_precio_regla" -> "HAS_PRICE_AUTHORIZATIONS";
                case "producto_proveedor" -> "HAS_SUPPLIER_LINKS";
                default -> table.startsWith("promoc") ? "HAS_PROMOTIONS"
                        : table.startsWith("pda_") ? "HAS_PDA_REFERENCES"
                        : table.contains("import") ? "HAS_IMPORT_REFERENCES"
                        : table.contains("edicion_masiva") ? "HAS_BULK_EDIT_REFERENCES"
                        : "HAS_REFERENCES";
            };
            case CUSTOMER -> switch (table) {
                case "documento" -> "HAS_DOCUMENTS";
                case "miembro" -> "HAS_MEMBER_HISTORY";
                case "movimiento_saldo_socio" -> "HAS_BALANCE_HISTORY";
                case "vale", "vale_cliente" -> "HAS_VOUCHERS";
                default -> table.contains("aparc") ? "HAS_PARKED_SALES" : "HAS_REFERENCES";
            };
            case SUPPLIER -> switch (table) {
                case "documento" -> "HAS_PURCHASE_DOCUMENTS";
                case "entrada_almacen", "entrada_almacen_linea" -> "HAS_WAREHOUSE_INPUTS";
                case "producto_proveedor" -> "HAS_PRODUCT_LINKS";
                default -> "HAS_REFERENCES";
            };
            case SALES_REPRESENTATIVE -> "proveedor_comercial".equals(table)
                    ? "HAS_REPRESENTATIVE_LINKS" : "HAS_REFERENCES";
        };
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalStateException("Identificador SQL no permitido");
        }
        return value;
    }

    private static List<String> withReason(List<String> reasons, String reason) {
        Set<String> merged = new TreeSet<>(reasons);
        merged.add(reason);
        return List.copyOf(merged);
    }

    private static boolean active(EntityType type, Object entity) {
        return switch (type) {
            case PRODUCT -> ((Product) entity).isActive();
            case CUSTOMER -> ((Customer) entity).isActive();
            case SUPPLIER -> ((Supplier) entity).isActive();
            case SALES_REPRESENTATIVE -> ((SalesRepresentative) entity).isActive();
        };
    }

    private static void deactivate(EntityType type, Object entity) {
        switch (type) {
            case PRODUCT -> ((Product) entity).deactivate();
            case CUSTOMER -> ((Customer) entity).deactivate();
            case SUPPLIER -> ((Supplier) entity).deactivate();
            case SALES_REPRESENTATIVE -> ((SalesRepresentative) entity).deactivate();
        }
    }

    private static long version(EntityType type, Object entity) {
        return switch (type) {
            case PRODUCT -> ((Product) entity).getVersion();
            case CUSTOMER -> ((Customer) entity).getVersion();
            case SUPPLIER -> ((Supplier) entity).getVersion();
            case SALES_REPRESENTATIVE -> ((SalesRepresentative) entity).getVersion();
        };
    }

    private static String code(EntityType type, Object entity) {
        return switch (type) {
            case PRODUCT -> ((Product) entity).getCode();
            case CUSTOMER -> ((Customer) entity).getClientId();
            case SUPPLIER -> ((Supplier) entity).getSupplierId();
            case SALES_REPRESENTATIVE -> ((SalesRepresentative) entity).getCommercialId();
        };
    }

    private static void appendSearch(
            StringBuilder sql,
            List<Object> args,
            EntityType type,
            String normalizedSearch) {
        switch (type) {
            case PRODUCT -> {
                sql.append(" and (lower(p.nombre) like ? or exists (select 1 "
                        + "from producto_identificador pi where pi.producto_id = p.id "
                        + "and lower(pi.valor) like ?))");
                args.add(normalizedSearch);
                args.add(normalizedSearch);
            }
            case CUSTOMER -> {
                sql.append(" and lower(concat_ws(' ', c.client_id, c.nombre_fiscal, "
                        + "c.numero_documento, c.telefono, c.email, c.poblacion, c.provincia)) like ?");
                args.add(normalizedSearch);
            }
            case SUPPLIER -> {
                sql.append(" and lower(concat_ws(' ', s.supplier_id, s.razon_social, s.nombre_comercial, "
                        + "s.numero_documento, s.telefono, s.email, s.poblacion, s.provincia)) like ?");
                args.add(normalizedSearch);
            }
            case SALES_REPRESENTATIVE -> {
                sql.append(" and lower(concat_ws(' ', r.commercial_id, r.nombre, r.telefono, "
                        + "r.email, r.otro_contacto)) like ?");
                args.add(normalizedSearch);
            }
        }
    }

    private static String idExpression(EntityType type) {
        return switch (type) {
            case PRODUCT -> "p.id";
            case CUSTOMER -> "c.id";
            case SUPPLIER -> "s.id";
            case SALES_REPRESENTATIVE -> "r.id";
        };
    }

    private static PageOrder pageOrder(EntityType type, String requestedSort, String requestedDirection) {
        SortField field;
        try {
            field = SortField.valueOf((requestedSort == null ? "name" : requestedSort)
                    .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Orden de gestion no valido", exception);
        }
        boolean descending;
        if (requestedDirection == null || requestedDirection.isBlank()
                || "asc".equalsIgnoreCase(requestedDirection)) {
            descending = false;
        } else if ("desc".equalsIgnoreCase(requestedDirection)) {
            descending = true;
        } else {
            throw new IllegalArgumentException("Direccion de orden no valida");
        }
        String expression = switch (field) {
            case NAME -> switch (type) {
                case PRODUCT -> "lower(p.nombre)";
                case CUSTOMER -> "lower(c.nombre_fiscal)";
                case SUPPLIER -> "lower(s.razon_social)";
                case SALES_REPRESENTATIVE -> "lower(r.nombre)";
            };
            case CODE -> switch (type) {
                case PRODUCT -> "coalesce((select lower(pi.valor) from producto_identificador pi "
                        + "where pi.producto_id = p.id and pi.tipo = 'CODIGO' limit 1), '')";
                case CUSTOMER -> "lower(coalesce(c.client_id, ''))";
                case SUPPLIER -> "lower(coalesce(s.supplier_id, ''))";
                case SALES_REPRESENTATIVE -> "lower(coalesce(r.commercial_id, ''))";
            };
            case DOCUMENT -> switch (type) {
                case CUSTOMER -> "lower(coalesce(c.numero_documento, ''))";
                case SUPPLIER -> "lower(coalesce(s.numero_documento, ''))";
                default -> throw new IllegalArgumentException("Orden por documento no disponible");
            };
            case PHONE -> switch (type) {
                case CUSTOMER -> "lower(coalesce(c.telefono, ''))";
                case SUPPLIER -> "lower(coalesce(s.telefono, ''))";
                case SALES_REPRESENTATIVE -> "lower(coalesce(r.telefono, ''))";
                default -> throw new IllegalArgumentException("Orden por telefono no disponible");
            };
            case EMAIL -> switch (type) {
                case CUSTOMER -> "lower(coalesce(c.email, ''))";
                case SUPPLIER -> "lower(coalesce(s.email, ''))";
                case SALES_REPRESENTATIVE -> "lower(coalesce(r.email, ''))";
                default -> throw new IllegalArgumentException("Orden por email no disponible");
            };
            case LOCATION -> switch (type) {
                case CUSTOMER -> "lower(concat_ws(' ', c.poblacion, c.provincia))";
                case SUPPLIER -> "lower(concat_ws(' ', s.poblacion, s.provincia))";
                default -> throw new IllegalArgumentException("Orden por ubicacion no disponible");
            };
            case STATUS -> switch (type) {
                case PRODUCT -> "case when p.activo then '1' else '0' end";
                case CUSTOMER -> "case when c.activo then '1' else '0' end";
                case SUPPLIER -> "case when s.activo then '1' else '0' end";
                case SALES_REPRESENTATIVE -> "case when r.activo then '1' else '0' end";
            };
        };
        return new PageOrder(field, expression, descending);
    }

    private static String likePattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static int validateSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("El tamano de pagina debe estar entre 1 y 100");
        }
        return size;
    }

    private static String encodeCursor(PageOrder order, String value, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (order.cursorKey() + "\u0000" + value + "\u0000" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded, PageOrder order) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        if (encoded.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("Cursor no valido");
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int firstSeparator = value.indexOf('\u0000');
            int secondSeparator = value.indexOf('\u0000', firstSeparator + 1);
            if (firstSeparator <= 0 || secondSeparator <= firstSeparator
                    || secondSeparator == value.length() - 1
                    || value.indexOf('\u0000', secondSeparator + 1) >= 0
                    || !value.substring(0, firstSeparator).equals(order.cursorKey())) {
                throw new IllegalArgumentException("Cursor no valido");
            }
            return new Cursor(value.substring(firstSeparator + 1, secondSeparator),
                    UUID.fromString(value.substring(secondSeparator + 1)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cursor no valido", exception);
        }
    }

    public enum EntityType {
        PRODUCT("products", "producto", Product.class),
        CUSTOMER("customers", "cliente", Customer.class),
        SUPPLIER("suppliers", "proveedor", Supplier.class),
        SALES_REPRESENTATIVE("sales-representatives", "comercial", SalesRepresentative.class);

        private final String path;
        private final String table;
        private final Class<?> entityClass;

        EntityType(String path, String table, Class<?> entityClass) {
            this.path = path;
            this.table = table;
            this.entityClass = entityClass;
        }
    }

    private record Cursor(String value, UUID id) {
    }

    private enum SortField {
        CODE,
        NAME,
        DOCUMENT,
        PHONE,
        EMAIL,
        LOCATION,
        STATUS
    }

    private record PageOrder(SortField field, String expression, boolean descending) {

        private String cursorKey() {
            return field.name() + ":" + (descending ? "DESC" : "ASC");
        }

        private String value(ManagementItem item) {
            String value = switch (field) {
                case CODE -> item.code();
                case NAME -> item.name();
                case DOCUMENT -> item.documentNumber();
                case PHONE -> item.phone();
                case EMAIL -> item.email();
                case LOCATION -> location(item.address());
                case STATUS -> item.active() ? "1" : "0";
            };
            return value == null ? "" : value.toLowerCase(Locale.ROOT);
        }

        private static String location(Object address) {
            if (!(address instanceof FiscalAddress fiscalAddress)) {
                return "";
            }
            String city = fiscalAddress.getCity() == null ? "" : fiscalAddress.getCity();
            String province = fiscalAddress.getProvince() == null ? "" : fiscalAddress.getProvince();
            return city + (city.isEmpty() || province.isEmpty() ? "" : " ") + province;
        }
    }

    private record ForeignKey(String schema, String table, String column, String deleteRule) {
    }

    public static class SafeRetirementStaleStateException extends RuntimeException {
    }

    public static class ProtectedSystemProductException extends RuntimeException {
    }
}
