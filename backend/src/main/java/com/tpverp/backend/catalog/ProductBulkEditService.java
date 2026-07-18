package com.tpverp.backend.catalog;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.persistence.OptimisticLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductBulkEditService {

    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final CurrentOrganization organization;
    private final UserAccountRepository users;
    private final ProductBulkEditRepository repository;
    private final ProductBulkCodeSequenceRepository codeSequences;
    private final CatalogService catalog;
    private final ProductSupplierService productSuppliers;
    private final ProductBulkEditImageRepository images;
    private final ProductImageService productImages;
    private final ProductRepository products;
    private final Clock clock;

    public ProductBulkEditService(
            CurrentOrganization organization,
            UserAccountRepository users,
            ProductBulkEditRepository repository,
            ProductBulkCodeSequenceRepository codeSequences,
            CatalogService catalog,
            ProductSupplierService productSuppliers,
            ProductBulkEditImageRepository images,
            ProductImageService productImages,
            ProductRepository products,
            Clock clock) {
        this.organization = organization;
        this.users = users;
        this.repository = repository;
        this.codeSequences = codeSequences;
        this.catalog = catalog;
        this.productSuppliers = productSuppliers;
        this.images = images;
        this.productImages = productImages;
        this.products = products;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductBulkEditView> list() {
        UUID storeId = organization.currentStore().getId();
        Map<UUID, UserAccount> userIndex = userIndex(storeId);
        return repository.findByStoreIdOrderByActualizadoEnDesc(storeId).stream()
                .map(edit -> view(edit, userIndex))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductBulkEditView get(UUID id) {
        UUID storeId = organization.currentStore().getId();
        return view(find(id, storeId), userIndex(storeId));
    }

    @Transactional
    public ProductBulkEditView create(ProductBulkCreateRequest request, Authentication authentication) {
        var store = organization.currentStore();
        UserAccount user = organization.currentUser(authentication);
        var now = clock.instant();
        ProductBulkEdit edit = new ProductBulkEdit(
                store.getId(), nextCode(store.getId()), request.name(), request.content(), user.getId(), now);
        repository.saveAndFlush(edit);
        return view(edit, userIndex(store.getId()));
    }

    @Transactional
    public ProductBulkEditView update(
            UUID id,
            ProductBulkUpdateRequest request,
            Authentication authentication) {
        UUID storeId = organization.currentStore().getId();
        UserAccount user = organization.currentUser(authentication);
        ProductBulkEdit edit = find(id, storeId);
        requireVersion(edit, request.version());
        var now = clock.instant();
        if (edit.getEstado() == ProductBulkEditStatus.APPLIED) {
            edit.recordNewVersion(user.getId(), now);
            flushWithOptimisticConflict(edit, request.version());
            int nextVersionNumber = repository.findTopBySerieIdOrderByNumeroVersionDesc(edit.getSerieId())
                    .map(ProductBulkEdit::getNumeroVersion)
                    .orElse(edit.getNumeroVersion()) + 1;
            ProductBulkEdit next = edit.nextVersion(
                    nextCode(storeId), nextVersionNumber, request.name(), request.content(), user.getId(), now);
            flushWithOptimisticConflict(next, 0L);
            return view(next, userIndex(storeId));
        }
        edit.update(request.name(), request.content(), user.getId(), now);
        flushWithOptimisticConflict(edit, request.version());
        return view(edit, userIndex(storeId));
    }

    @Transactional
    public ProductBulkEditView rename(
            UUID id,
            ProductBulkRenameRequest request,
            Authentication authentication) {
        UUID storeId = organization.currentStore().getId();
        UserAccount user = organization.currentUser(authentication);
        ProductBulkEdit edit = find(id, storeId);
        requireVersion(edit, request.version());
        edit.rename(request.name(), user.getId(), clock.instant());
        flushWithOptimisticConflict(edit, request.version());
        return view(edit, userIndex(storeId));
    }

    @Transactional
    public ProductBulkEditView apply(UUID id, ProductBulkApplyRequest request, Authentication authentication) {
        UUID storeId = organization.currentStore().getId();
        UserAccount user = organization.currentUser(authentication);
        ProductBulkEdit edit = find(id, storeId);
        requireVersion(edit, request.version());
        List<ProductBulkEditImage> stagedImages =
                images.findByEdicion_IdOrderByPosicionAsc(edit.getId());
        List<ProductBulkEditContent.Row> content =
                validateApplyRequest(storeId, edit, request, stagedImages);
        if (request.updates().isEmpty()
                && request.supplierAssignments().isEmpty()
                && request.supplierPrincipalAssignments().isEmpty()
                && stagedImages.isEmpty()) {
            throw new IllegalArgumentException("No hay cambios para aplicar");
        }
        Map<UUID, Product> modifiedProducts = new HashMap<>();
        if (!request.updates().isEmpty()) {
            catalog.updateProducts(request.updates()).forEach(
                    product -> modifiedProducts.put(product.getId(), product));
        }
        request.supplierAssignments().forEach(assignment ->
                productSuppliers.linkProducts(assignment.supplierId(), assignment.productIds()));
        request.supplierPrincipalAssignments().forEach(assignment -> {
            if (assignment.supplierId() == null) {
                productSuppliers.clearPrincipal(assignment.productId());
            } else {
                productSuppliers.setPrincipal(assignment.productId(), assignment.supplierId());
            }
        });
        for (ProductBulkEditImage stagedImage : stagedImages) {
            Product product = productImages.upload(
                    stagedImage.getProductId(), stagedImage.getContent());
            modifiedProducts.put(product.getId(), product);
        }
        content = finalizeSupplierState(content);
        if (!modifiedProducts.isEmpty()) {
            products.flush();
            content = refreshPersistenceState(content, modifiedProducts);
        }
        edit.apply(content, user.getId(), clock.instant());
        images.deleteAll(stagedImages);
        flushWithOptimisticConflict(edit, request.version());
        return view(edit, userIndex(storeId));
    }

    @Transactional
    public ProductBulkEditView addComment(UUID id, ProductBulkCommentRequest request, Authentication authentication) {
        UUID storeId = organization.currentStore().getId();
        UserAccount user = organization.currentUser(authentication);
        ProductBulkEdit edit = find(id, storeId);
        edit.addComment(request.text(), user.getId(), clock.instant());
        flushWithOptimisticConflict(edit, edit.getVersion());
        return view(edit, userIndex(storeId));
    }

    @Transactional
    public void delete(UUID id, long version, Authentication authentication) {
        UUID storeId = organization.currentStore().getId();
        UserAccount user = organization.currentUser(authentication);
        ProductBulkEdit edit = find(id, storeId);
        requireVersion(edit, version);
        if (!edit.getCreadoPor().equals(user.getId()) && !isAdmin(authentication)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Solo ADMIN o el creador pueden eliminar la lista");
        }
        try {
            repository.delete(edit);
            repository.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(id, version, null);
        }
    }

    private ProductBulkEdit find(UUID id, UUID storeId) {
        return repository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new IllegalArgumentException("Lista de edicion masiva no encontrada"));
    }

    private String nextCode(UUID storeId) {
        LocalDate date = LocalDate.now(clock);
        String prefix = date.format(CODE_DATE);
        int sequence = codeSequences.next(storeId, date);
        return prefix + String.format("%03d", sequence);
    }

    private List<ProductBulkEditContent.Row> validateApplyRequest(
            UUID storeId,
            ProductBulkEdit edit,
            ProductBulkApplyRequest request,
            List<ProductBulkEditImage> stagedImages) {
        Objects.requireNonNull(request.updates(), "updates");
        Objects.requireNonNull(request.supplierAssignments(), "supplierAssignments");
        Objects.requireNonNull(request.supplierPrincipalAssignments(), "supplierPrincipalAssignments");
        List<ProductBulkEditContent.Row> content = ProductBulkEditContent.validateAndCopy(request.content());
        Map<UUID, ProductBulkEditContent.ProductData> contentProducts = content.stream()
                .map(ProductBulkEditContent.Row::effectiveProduct)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        ProductBulkEditContent.ProductData::productId,
                        Function.identity()));
        Set<UUID> persistedProductIds = edit.getContenido().stream()
                .map(ProductBulkEditContent.Row::effectiveProduct)
                .filter(Objects::nonNull)
                .map(ProductBulkEditContent.ProductData::productId)
                .collect(Collectors.toSet());
        if (!persistedProductIds.equals(contentProducts.keySet())) {
            throw new IllegalArgumentException(
                    "content no coincide con los productos guardados en la lista de edicion masiva");
        }
        Set<UUID> updatedProductIds = new HashSet<>();
        for (int index = 0; index < request.updates().size(); index++) {
            CatalogService.BulkProductUpdate update = request.updates().get(index);
            if (update == null || update.productId() == null || update.product() == null) {
                throw new IllegalArgumentException("updates[" + index + "] no es valido");
            }
            if (!updatedProductIds.add(update.productId())) {
                throw new IllegalArgumentException(
                        "updates contiene el producto duplicado " + update.productId());
            }
            ProductBulkEditContent.ProductData effective = contentProducts.get(update.productId());
            if (effective == null) {
                throw new IllegalArgumentException(
                        "updates[" + index + "] referencia un producto ausente de content: "
                                + update.productId());
            }
            requireUpdateMatchesContent(update, effective, index);
        }
        if (request.supplierAssignments().size() > 100) {
            throw new IllegalArgumentException(
                    "supplierAssignments no puede superar 100 proveedores");
        }
        Set<UUID> assignmentSuppliers = new HashSet<>();
        int totalSupplierProducts = 0;
        for (int index = 0; index < request.supplierAssignments().size(); index++) {
            BulkSupplierAssignment assignment = request.supplierAssignments().get(index);
            if (assignment == null || assignment.supplierId() == null
                    || assignment.productIds() == null || assignment.productIds().isEmpty()) {
                throw new IllegalArgumentException("supplierAssignments[" + index + "] no es valido");
            }
            if (!assignmentSuppliers.add(assignment.supplierId())) {
                throw new IllegalArgumentException(
                        "supplierAssignments contiene el proveedor duplicado " + assignment.supplierId());
            }
            totalSupplierProducts += assignment.productIds().size();
            if (totalSupplierProducts > 20_000) {
                throw new IllegalArgumentException(
                        "supplierAssignments no puede superar 20000 vinculaciones");
            }
            Set<UUID> assignedProducts = new HashSet<>();
            for (UUID productId : assignment.productIds()) {
                if (productId == null || !assignedProducts.add(productId)) {
                    throw new IllegalArgumentException(
                            "supplierAssignments[" + index + "] contiene productos nulos o duplicados");
                }
                requireContentProduct(
                        contentProducts.keySet(), productId, "supplierAssignments[" + index + "]");
            }
        }
        if (request.supplierPrincipalAssignments().size() > 5_000) {
            throw new IllegalArgumentException(
                    "supplierPrincipalAssignments no puede superar 5000 productos");
        }
        Map<UUID, ProductBulkEditContent.Row> contentRows = content.stream()
                .filter(row -> row.product() != null)
                .collect(Collectors.toMap(row -> row.product().productId(), Function.identity()));
        Set<UUID> principalProducts = new HashSet<>();
        for (int index = 0; index < request.supplierPrincipalAssignments().size(); index++) {
            BulkSupplierPrincipalAssignment assignment = request.supplierPrincipalAssignments().get(index);
            if (assignment == null || assignment.productId() == null) {
                throw new IllegalArgumentException(
                        "supplierPrincipalAssignments[" + index + "] no es valido");
            }
            if (!principalProducts.add(assignment.productId())) {
                throw new IllegalArgumentException(
                        "supplierPrincipalAssignments contiene el producto duplicado "
                                + assignment.productId());
            }
            ProductBulkEditContent.Row row = contentRows.get(assignment.productId());
            if (row == null || !row.principalSupplierChanged()
                    || !Objects.equals(row.pendingPrincipalSupplierId(), assignment.supplierId())) {
                throw new IllegalArgumentException(
                        "supplierPrincipalAssignments[" + index
                                + "] no coincide con content");
            }
        }
        for (ProductBulkEditContent.Row row : contentRows.values()) {
            if (row.principalSupplierChanged()
                    && !principalProducts.contains(row.product().productId())) {
                throw new IllegalArgumentException(
                        "content contiene un cambio de proveedor principal sin asignacion para "
                                + row.product().productId());
            }
        }
        validateStagedImages(storeId, stagedImages, contentProducts);
        return content;
    }

    private void validateStagedImages(
            UUID storeId,
            List<ProductBulkEditImage> stagedImages,
            Map<UUID, ProductBulkEditContent.ProductData> contentProducts) {
        Set<UUID> productIds = new HashSet<>();
        for (int index = 0; index < stagedImages.size(); index++) {
            ProductBulkEditImage image = stagedImages.get(index);
            UUID productId = image.getProductId();
            if (productId == null) {
                throw new IllegalArgumentException(
                        "images[" + index + "] no tiene un producto asignado");
            }
            if (!productIds.add(productId)) {
                throw new IllegalArgumentException(
                        "images contiene el producto duplicado " + productId);
            }
            requireContentProduct(contentProducts.keySet(), productId, "images[" + index + "]");
        }
        if (productIds.isEmpty()) {
            return;
        }
        Map<UUID, Product> currentProducts = products.findAllByStoreIdAndIdIn(storeId, productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (currentProducts.size() != productIds.size()) {
            throw new IllegalArgumentException(
                    "Hay imagenes asignadas a productos que no pertenecen a la tienda actual");
        }
        for (UUID productId : productIds) {
            Product current = currentProducts.get(productId);
            Long expectedVersion = contentProducts.get(productId).version();
            if (expectedVersion == null || current.getVersion() != expectedVersion) {
                throw new IllegalStateException(
                        "Conflicto de version en el producto " + productId
                                + ": se esperaba " + expectedVersion
                                + " y tiene version " + current.getVersion());
            }
        }
    }

    private static List<ProductBulkEditContent.Row> refreshPersistenceState(
            List<ProductBulkEditContent.Row> content,
            Map<UUID, Product> modifiedProducts) {
        return content.stream().map(row -> {
            ProductBulkEditContent.ProductData effective = row.effectiveProduct();
            if (effective == null) {
                return row;
            }
            Product product = modifiedProducts.get(effective.productId());
            if (product == null || row.product() == null) {
                return row;
            }
            return new ProductBulkEditContent.Row(
                    row.id(),
                    row.selected(),
                    row.query(),
                    row.product().withPersistenceState(product.getVersion(), product.getImageId()),
                    row.draft().withoutPersistenceState(),
                    row.suppliers(),
                    row.pendingSupplier(),
                    row.principalSupplierChanged(),
                    row.pendingPrincipalSupplierId());
        }).toList();
    }

    private static List<ProductBulkEditContent.Row> finalizeSupplierState(
            List<ProductBulkEditContent.Row> content) {
        return content.stream().map(row -> {
            if (row.pendingSupplier() == null && !row.principalSupplierChanged()) {
                return row;
            }
            var suppliers = new java.util.ArrayList<>(row.suppliers());
            if (row.pendingSupplier() != null) {
                suppliers.removeIf(supplier -> supplier.id().equals(row.pendingSupplier().id()));
                suppliers.add(row.pendingSupplier());
            }
            if (row.principalSupplierChanged()) {
                UUID principalId = row.pendingPrincipalSupplierId();
                suppliers.replaceAll(supplier -> supplier.withPrincipal(
                        principalId != null && principalId.equals(supplier.id())));
            }
            return new ProductBulkEditContent.Row(
                    row.id(),
                    row.selected(),
                    row.query(),
                    row.product(),
                    row.draft(),
                    List.copyOf(suppliers),
                    null,
                    false,
                    null);
        }).toList();
    }

    private static void requireUpdateMatchesContent(
            CatalogService.BulkProductUpdate update,
            ProductBulkEditContent.ProductData content,
            int index) {
        String path = "updates[" + index + "]";
        requireSame(path, "expectedVersion", content.version(), update.expectedVersion());
        CatalogService.ProductRequest product = update.product();
        requireSame(path, "familyId", uuid(content.familyId()), product.familyId());
        requireSame(path, "subfamilyId", uuid(content.subfamilyId()), product.subfamilyId());
        requireSame(path, "taxId", uuid(content.taxId()), product.taxId());
        requireSame(path, "productType", enumValue(content.productType(), ProductType.class), product.productType());
        requireSame(path, "discountType", enumValue(content.backendDiscountType(), DiscountType.class),
                product.discountType());
        requireSame(path, "priceUseMode", enumValue(content.discountType(), PriceUseMode.class),
                product.priceUseMode());
        requireSame(path, "name", text(content.name()), text(product.name()));
        requireSame(path, "description", text(content.description()), text(product.description()));
        requireSame(path, "comments", text(content.comments()), text(product.comments()));
        requireSame(path, "purchasePrice", decimal(content.purchasePrice()), product.purchasePrice());
        requireSame(path, "taxesIncluded", bool(content.taxesIncluded()), product.taxesIncluded());
        requireSame(path, "code", text(content.code()), text(product.code()));
        requireSame(path, "barcode", text(content.barcode()), text(product.barcode()));
        requireSame(path, "barcode2", text(content.barcode2()), text(product.barcode2()));
        requireSame(path, "salePrice", decimal(content.salePrice()), product.salePrice());
        requireSame(path, "memberPrice", decimal(content.memberPrice()), product.memberPrice());
        requireSame(path, "wholesalePrice", decimal(content.wholesalePrice()), product.wholesalePrice());
        requireSame(path, "offerPrice", decimal(content.offerPrice()), product.offerPrice());
        requireSame(path, "offerDiscountPercent", decimal(content.offerDiscountPercent()),
                product.offerDiscountPercent());
        requireSame(path, "purchaseDiscountPercent", decimal(content.purchaseDiscountPercent()),
                product.purchaseDiscountPercent());
        requireSame(path, "offerActive", bool(content.offerActive()), product.offerActive());
        requireSame(path, "offerFrom", date(content.offerFrom()), product.offerFrom());
        requireSame(path, "offerUntil", date(content.offerUntil()), product.offerUntil());
        requireSame(path, "stockMin", decimal(content.stockMin()), product.stockMin());
        requireSame(path, "stockMax", decimal(content.stockMax()), product.stockMax());
        requireSame(path, "packageQuantity", decimal(content.packageQuantity()), product.packageQuantity());
        requireSame(path, "active", content.active() == null ? Boolean.TRUE : bool(content.active()),
                product.active());
    }

    private static void requireSame(String path, String field, Object expected, Object actual) {
        boolean matches = expected instanceof BigDecimal left && actual instanceof BigDecimal right
                ? left.compareTo(right) == 0
                : Objects.equals(expected, actual);
        if (!matches) {
            throw new IllegalArgumentException(
                    path + ".product." + field + " no coincide con content");
        }
    }

    private static UUID uuid(String value) {
        return text(value) == null ? null : UUID.fromString(text(value));
    }

    private static BigDecimal decimal(String value) {
        return text(value) == null ? null : new BigDecimal(text(value).replace(',', '.'));
    }

    private static LocalDate date(String value) {
        return text(value) == null ? null : LocalDate.parse(text(value));
    }

    private static Boolean bool(String value) {
        String normalized = text(value);
        if (normalized == null) {
            return null;
        }
        return switch (normalized.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "si", "1", "common.yes" -> true;
            case "false", "no", "0", "common.no" -> false;
            default -> throw new IllegalArgumentException("Valor booleano no valido: " + value);
        };
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        String normalized = text(value);
        return normalized == null ? null : Enum.valueOf(
                type, normalized.toUpperCase(java.util.Locale.ROOT));
    }

    private static String text(String value) {
        return value == null || value.isBlank() || "-".equals(value.trim())
                ? null
                : value.trim();
    }

    private static void requireContentProduct(Set<UUID> contentProductIds, UUID productId, String field) {
        if (!contentProductIds.contains(productId)) {
            throw new IllegalArgumentException(field + " referencia un producto ausente de content: " + productId);
        }
    }

    private void flushWithOptimisticConflict(ProductBulkEdit edit, long expectedVersion) {
        try {
            repository.saveAndFlush(edit);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(edit.getId(), expectedVersion, null);
        }
    }

    private static void requireVersion(ProductBulkEdit edit, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("version es obligatoria");
        }
        if (edit.getVersion() != expectedVersion) {
            throw staleVersion(edit.getId(), expectedVersion, edit.getVersion());
        }
    }

    private static IllegalStateException staleVersion(UUID id, long expected, Long actual) {
        String detail = actual == null ? "ya fue modificada" : "tiene version " + actual;
        return new IllegalStateException(
                "Conflicto de version en la lista " + id + ": se esperaba " + expected + " y " + detail);
    }

    private Map<UUID, UserAccount> userIndex(UUID storeId) {
        return users.findAllByTiendaIdOrderByNombre(storeId).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private ProductBulkEditView view(ProductBulkEdit edit, Map<UUID, UserAccount> userIndex) {
        Map<UUID, UserAccount> allUsers = new HashMap<>(userIndex);
        return new ProductBulkEditView(
                edit.getId(),
                edit.getCodigo(),
                edit.getSerieId(),
                edit.getNumeroVersion(),
                edit.getVersionAnteriorId(),
                edit.getNombre(),
                edit.getEstado(),
                edit.getContenido(),
                edit.getVersion(),
                edit.getCreadoPor(),
                userName(allUsers, edit.getCreadoPor()),
                edit.getCreadoEn(),
                edit.getActualizadoPor(),
                userName(allUsers, edit.getActualizadoPor()),
                edit.getActualizadoEn(),
                edit.getAplicadoPor(),
                userName(allUsers, edit.getAplicadoPor()),
                edit.getAplicadoEn(),
                edit.getComentarios().stream().map(comment -> new ProductBulkEditView.Comment(
                        comment.getId(),
                        comment.getUsuarioId(),
                        userName(allUsers, comment.getUsuarioId()),
                        comment.getTexto(),
                        comment.getCreadoEn())).toList());
    }

    private static String userName(Map<UUID, UserAccount> users, UUID userId) {
        if (userId == null) {
            return null;
        }
        UserAccount user = users.get(userId);
        return user == null ? userId.toString() : user.getUserName();
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())
                        || "ADMIN".equals(authority.getAuthority()));
    }

    public record ProductBulkCreateRequest(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid
            List<ProductBulkEditContent.Row> content) {
    }

    public record ProductBulkUpdateRequest(
            @jakarta.validation.constraints.NotNull Long version,
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid
            List<ProductBulkEditContent.Row> content) {
    }

    public record ProductBulkRenameRequest(
            @jakarta.validation.constraints.NotNull Long version,
            @jakarta.validation.constraints.NotBlank String name) {
    }

    public record ProductBulkApplyRequest(
            @jakarta.validation.constraints.NotNull Long version,
            @jakarta.validation.constraints.NotNull List<CatalogService.BulkProductUpdate> updates,
            @jakarta.validation.constraints.NotNull
            List<@jakarta.validation.Valid BulkSupplierAssignment> supplierAssignments,
            @jakarta.validation.constraints.NotNull
            List<@jakarta.validation.Valid BulkSupplierPrincipalAssignment> supplierPrincipalAssignments,
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid
            List<ProductBulkEditContent.Row> content) {

        public ProductBulkApplyRequest {
            supplierPrincipalAssignments = supplierPrincipalAssignments == null
                    ? List.of()
                    : List.copyOf(supplierPrincipalAssignments);
        }

        public ProductBulkApplyRequest(
                Long version,
                List<CatalogService.BulkProductUpdate> updates,
                List<BulkSupplierAssignment> supplierAssignments,
                List<ProductBulkEditContent.Row> content) {
            this(version, updates, supplierAssignments, List.of(), content);
        }
    }

    public record BulkSupplierAssignment(
            @jakarta.validation.constraints.NotNull UUID supplierId,
            @jakarta.validation.constraints.NotEmpty List<UUID> productIds) {
    }

    public record BulkSupplierPrincipalAssignment(
            @jakarta.validation.constraints.NotNull UUID productId,
            UUID supplierId) {
    }

    public record ProductBulkCommentRequest(
            @jakarta.validation.constraints.NotBlank String text) {
    }
}
