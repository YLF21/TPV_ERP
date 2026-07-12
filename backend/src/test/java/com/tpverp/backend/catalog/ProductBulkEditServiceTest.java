package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class ProductBulkEditServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private UserAccountRepository users;
    @Mock private ProductBulkEditRepository repository;
    @Mock private ProductBulkCodeSequenceRepository codeSequences;
    @Mock private CatalogService catalog;
    @Mock private ProductSupplierService productSuppliers;
    @Mock private ProductBulkEditImageRepository images;
    @Mock private ProductImageService productImages;
    @Mock private ProductRepository products;
    @Mock private Store store;
    @Mock private UserAccount user;

    private ProductBulkEditService service;
    private UUID storeId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        org.mockito.Mockito.lenient()
                .when(organization.currentUser(org.mockito.ArgumentMatchers.any()))
                .thenReturn(user);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(userId);
        service = new ProductBulkEditService(
                organization,
                users,
                repository,
                codeSequences,
                catalog,
                productSuppliers,
                images,
                productImages,
                products,
                Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void creatorCanDeleteOwnDraft() {
        ProductBulkEdit edit = editCreatedBy(userId);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        service.delete(edit.getId(), edit.getVersion(), managerAuthentication());

        verify(repository).delete(edit);
        verify(repository).flush();
    }

    @Test
    void createUsesTheAtomicDailySequenceForItsCode() {
        UUID productId = UUID.randomUUID();
        when(codeSequences.next(storeId, java.time.LocalDate.of(2026, 7, 11)))
                .thenReturn(42);

        ProductBulkEditView created = service.create(
                new ProductBulkEditService.ProductBulkCreateRequest(
                        "Revision", List.of(row("row-1", productId))),
                managerAuthentication());

        assertThat(created.code()).isEqualTo("20260711042");
        assertThat(created.version()).isZero();
    }

    @Test
    void renamesAppliedListInPlaceAndUpdatesAuditFields() {
        UUID appliedBy = UUID.randomUUID();
        Instant appliedAt = Instant.parse("2026-07-11T09:30:00Z");
        ProductBulkEdit edit = editCreatedBy(UUID.randomUUID());
        edit.apply(edit.getContenido(), appliedBy, appliedAt);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        ProductBulkEditView renamed = service.rename(
                edit.getId(),
                new ProductBulkEditService.ProductBulkRenameRequest(
                        edit.getVersion(), "  Revision aplicada  "),
                managerAuthentication());

        assertThat(renamed.id()).isEqualTo(edit.getId());
        assertThat(renamed.code()).isEqualTo(edit.getCodigo());
        assertThat(renamed.versionNumber()).isEqualTo(1);
        assertThat(renamed.previousVersionId()).isNull();
        assertThat(renamed.name()).isEqualTo("Revision aplicada");
        assertThat(renamed.status()).isEqualTo(ProductBulkEditStatus.APPLIED);
        assertThat(renamed.appliedById()).isEqualTo(appliedBy);
        assertThat(renamed.appliedAt()).isEqualTo(appliedAt);
        assertThat(renamed.updatedById()).isEqualTo(userId);
        assertThat(renamed.updatedAt()).isEqualTo(Instant.parse("2026-07-11T10:00:00Z"));
        verify(repository).saveAndFlush(edit);
        verify(codeSequences, never()).next(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsStaleVersionBeforeRenaming() {
        ProductBulkEdit edit = editCreatedBy(userId);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        assertThatThrownBy(() -> service.rename(
                edit.getId(),
                new ProductBulkEditService.ProductBulkRenameRequest(7L, "Otro nombre"),
                managerAuthentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicto de version")
                .hasMessageContaining("se esperaba 7");

        verify(repository, never()).saveAndFlush(edit);
    }

    @Test
    void rejectsBlankNameWhenRenaming() {
        ProductBulkEdit edit = editCreatedBy(userId);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        assertThatThrownBy(() -> service.rename(
                edit.getId(),
                new ProductBulkEditService.ProductBulkRenameRequest(edit.getVersion(), "   "),
                managerAuthentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name es obligatorio");

        verify(repository, never()).saveAndFlush(edit);
    }

    @Test
    void managerCannotDeleteAnotherUsersDraft() {
        ProductBulkEdit edit = editCreatedBy(UUID.randomUUID());
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        assertThatThrownBy(() -> service.delete(
                edit.getId(), edit.getVersion(), managerAuthentication()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ADMIN o el creador");

        verify(repository, never()).delete(edit);
        verify(repository, never()).flush();
    }

    @Test
    void adminCanDeleteAnotherUsersDraft() {
        ProductBulkEdit edit = editCreatedBy(UUID.randomUUID());
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        service.delete(edit.getId(), edit.getVersion(), adminAuthentication());

        verify(repository).delete(edit);
        verify(repository).flush();
    }

    @Test
    void openingAnAppliedListCreatesANewVersionWithCopiedComments() {
        UUID commentAuthorId = UUID.randomUUID();
        Instant commentAt = Instant.parse("2026-07-11T09:15:00Z");
        ProductBulkEdit applied = editCreatedBy(UUID.randomUUID());
        ProductBulkEditComment originalComment = applied.addComment(
                "Revisar margen", commentAuthorId, commentAt);
        applied.apply(
                applied.getContenido(),
                UUID.randomUUID(),
                Instant.parse("2026-07-11T09:30:00Z"));
        when(repository.findByIdAndStoreId(applied.getId(), storeId))
                .thenReturn(Optional.of(applied));
        when(repository.findTopBySerieIdOrderByNumeroVersionDesc(applied.getSerieId()))
                .thenReturn(Optional.of(applied));
        when(codeSequences.next(storeId, LocalDate.of(2026, 7, 11))).thenReturn(2);

        ProductBulkEditView opened = service.update(
                applied.getId(),
                new ProductBulkEditService.ProductBulkUpdateRequest(
                        applied.getVersion(), "Revision reabierta", applied.getContenido()),
                managerAuthentication());

        assertThat(opened.id()).isNotEqualTo(applied.getId());
        assertThat(opened.seriesId()).isEqualTo(applied.getSerieId());
        assertThat(opened.previousVersionId()).isEqualTo(applied.getId());
        assertThat(opened.versionNumber()).isEqualTo(2);
        assertThat(opened.status()).isEqualTo(ProductBulkEditStatus.PENDING);
        assertThat(opened.comments()).singleElement().satisfies(comment -> {
            assertThat(comment.id()).isNotEqualTo(originalComment.getId());
            assertThat(comment.userId()).isEqualTo(commentAuthorId);
            assertThat(comment.text()).isEqualTo("Revisar margen");
            assertThat(comment.createdAt()).isEqualTo(commentAt);
        });

        ArgumentCaptor<ProductBulkEdit> saved = ArgumentCaptor.forClass(ProductBulkEdit.class);
        verify(repository, times(2)).saveAndFlush(saved.capture());
        List<ProductBulkEdit> savedEdits = saved.getAllValues();
        assertThat(savedEdits).hasSize(2);
        assertThat(savedEdits.getFirst()).isSameAs(applied);
        assertThat(savedEdits.getLast().getId()).isEqualTo(opened.id());
    }

    @Test
    void appliesSupplierAssignmentsEvenWithoutProductFieldChanges() {
        ProductBulkEdit edit = editCreatedBy(userId);
        UUID supplierId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        service.apply(
                edit.getId(),
                new ProductBulkEditService.ProductBulkApplyRequest(
                        edit.getVersion(),
                        List.of(),
                        List.of(new ProductBulkEditService.BulkSupplierAssignment(
                                supplierId, List.of(productId))),
                        List.of(row("row-1", productId))),
                managerAuthentication());

        verify(productSuppliers).linkProducts(supplierId, List.of(productId));
    }

    @Test
    void rejectsApplyWithoutProductOrSupplierChanges() {
        ProductBulkEdit edit = editCreatedBy(userId);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        assertThatThrownBy(() -> service.apply(
                edit.getId(),
                new ProductBulkEditService.ProductBulkApplyRequest(
                        edit.getVersion(), List.of(), List.of(), List.of(row(
                                "row-1", UUID.randomUUID()))),
                managerAuthentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No hay cambios");
    }

    @Test
    void appliesImageOnlyFromPersistedStagingAndRefreshesContentState() {
        UUID productId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        ProductBulkEdit edit = new ProductBulkEdit(
                storeId,
                "20260711001",
                "Revision imagen",
                List.of(row("row-1", productId)),
                userId,
                Instant.parse("2026-07-11T09:00:00Z"));
        ProductBulkEditImage staged = stagedImage(edit, productId, 0);
        Product product = org.mockito.Mockito.mock(Product.class);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByEdicion_IdOrderByPosicionAsc(edit.getId())).thenReturn(List.of(staged));
        when(products.findAllByStoreIdAndIdIn(
                org.mockito.ArgumentMatchers.eq(storeId),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(product));
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(0L, 3L);
        when(product.getImageId()).thenReturn(imageId);
        when(productImages.upload(productId, staged.getContent())).thenReturn(product);

        service.apply(
                edit.getId(),
                new ProductBulkEditService.ProductBulkApplyRequest(
                        edit.getVersion(), List.of(), List.of(), edit.getContenido()),
                managerAuthentication());

        assertThat(edit.getEstado()).isEqualTo(ProductBulkEditStatus.APPLIED);
        ProductBulkEditContent.ProductData applied = edit.getContenido().getFirst().effectiveProduct();
        assertThat(applied.version()).isEqualTo(3L);
        assertThat(applied.imageId()).isEqualTo(imageId.toString());
        verify(productImages).upload(productId, staged.getContent());
        verify(images).deleteAll(List.of(staged));
        verify(products).flush();
    }

    @Test
    void refreshesVersionAndImageIdAfterAProductUpdateFlush() {
        UUID productId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        ProductBulkEditContent.Row row = row("row-1", productId);
        ProductBulkEdit edit = new ProductBulkEdit(
                storeId,
                "20260711001",
                "Revision producto",
                List.of(row),
                userId,
                Instant.parse("2026-07-11T09:00:00Z"));
        Product product = org.mockito.Mockito.mock(Product.class);
        ProductBulkEditContent.ProductData value = row.effectiveProduct();
        CatalogService.BulkProductUpdate update = new CatalogService.BulkProductUpdate(
                productId, 0L, request(value, value.name()));
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(catalog.updateProducts(List.of(update))).thenReturn(List.of(product));
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(5L);
        when(product.getImageId()).thenReturn(imageId);

        service.apply(
                edit.getId(),
                new ProductBulkEditService.ProductBulkApplyRequest(
                        edit.getVersion(), List.of(update), List.of(), List.of(row)),
                managerAuthentication());

        ProductBulkEditContent.ProductData applied = edit.getContenido().getFirst().effectiveProduct();
        assertThat(applied.version()).isEqualTo(5L);
        assertThat(applied.imageId()).isEqualTo(imageId.toString());
        verify(products).flush();
    }

    @Test
    void rejectsAnImageOnlyApplyWhenTheProductVersionIsStale() {
        UUID productId = UUID.randomUUID();
        ProductBulkEdit edit = new ProductBulkEdit(
                storeId,
                "20260711001",
                "Revision imagen",
                List.of(row("row-1", productId)),
                userId,
                Instant.parse("2026-07-11T09:00:00Z"));
        ProductBulkEditImage staged = stagedImage(edit, productId, 0);
        Product product = org.mockito.Mockito.mock(Product.class);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByEdicion_IdOrderByPosicionAsc(edit.getId())).thenReturn(List.of(staged));
        when(products.findAllByStoreIdAndIdIn(
                org.mockito.ArgumentMatchers.eq(storeId),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(product));
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(2L);

        assertThatThrownBy(() -> service.apply(
                edit.getId(),
                new ProductBulkEditService.ProductBulkApplyRequest(
                        edit.getVersion(), List.of(), List.of(), edit.getContenido()),
                managerAuthentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicto de version en el producto")
                .hasMessageContaining("tiene version 2");

        verify(productImages, never()).upload(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsPersistedImageWithoutProductBeforeApplyingAnything() {
        ProductBulkEdit edit = editCreatedBy(userId);
        ProductBulkEditImage staged = stagedImage(edit, null, 0);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByEdicion_IdOrderByPosicionAsc(edit.getId())).thenReturn(List.of(staged));

        assertThatThrownBy(() -> service.apply(
                edit.getId(),
                new ProductBulkEditService.ProductBulkApplyRequest(
                        edit.getVersion(), List.of(), List.of(), edit.getContenido()),
                managerAuthentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no tiene un producto asignado");

        verify(productImages, never()).upload(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(images, never()).deleteAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void imageFailureKeepsStagingAndDraftPending() {
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        ProductBulkEdit edit = new ProductBulkEdit(
                storeId,
                "20260711001",
                "Revision imagenes",
                List.of(row("row-1", firstProductId), row("row-2", secondProductId)),
                userId,
                Instant.parse("2026-07-11T09:00:00Z"));
        ProductBulkEditImage first = stagedImage(edit, firstProductId, 0);
        ProductBulkEditImage second = stagedImage(edit, secondProductId, 1);
        Product firstProduct = org.mockito.Mockito.mock(Product.class);
        Product secondProduct = org.mockito.Mockito.mock(Product.class);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByEdicion_IdOrderByPosicionAsc(edit.getId()))
                .thenReturn(List.of(first, second));
        when(products.findAllByStoreIdAndIdIn(
                org.mockito.ArgumentMatchers.eq(storeId),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(firstProduct, secondProduct));
        when(firstProduct.getId()).thenReturn(firstProductId);
        when(firstProduct.getVersion()).thenReturn(0L);
        when(secondProduct.getId()).thenReturn(secondProductId);
        when(secondProduct.getVersion()).thenReturn(0L);
        when(productImages.upload(firstProductId, first.getContent())).thenReturn(firstProduct);
        when(productImages.upload(secondProductId, second.getContent()))
                .thenThrow(new IllegalArgumentException("imagen corrupta"));

        assertThatThrownBy(() -> service.apply(
                edit.getId(),
                new ProductBulkEditService.ProductBulkApplyRequest(
                        edit.getVersion(), List.of(), List.of(), edit.getContenido()),
                managerAuthentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imagen corrupta");

        assertThat(edit.getEstado()).isEqualTo(ProductBulkEditStatus.PENDING);
        verify(images, never()).deleteAll(org.mockito.ArgumentMatchers.any());
        verify(products, never()).flush();
    }

    private ProductBulkEdit editCreatedBy(UUID creatorId) {
        return new ProductBulkEdit(
                storeId,
                "20260711001",
                "Revision precios",
                List.of(row("row-1", UUID.randomUUID())),
                creatorId,
                Instant.parse("2026-07-11T09:00:00Z"));
    }

    @Test
    void rejectsStaleVersionBeforeDeleting() {
        ProductBulkEdit edit = editCreatedBy(userId);
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));

        assertThatThrownBy(() -> service.delete(edit.getId(), 7L, managerAuthentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicto de version")
                .hasMessageContaining("se esperaba 7");
    }

    @Test
    void rejectsProductRequestThatDiffersFromEffectiveContent() {
        UUID productId = UUID.randomUUID();
        ProductBulkEditContent.Row row = row("row-1", productId);
        ProductBulkEdit edit = new ProductBulkEdit(
                storeId, "20260711001", "Revision", List.of(row), userId,
                Instant.parse("2026-07-11T09:00:00Z"));
        when(repository.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        ProductBulkEditContent.ProductData value = row.effectiveProduct();

        assertThatThrownBy(() -> service.apply(
                edit.getId(),
                new ProductBulkEditService.ProductBulkApplyRequest(
                        edit.getVersion(),
                        List.of(new CatalogService.BulkProductUpdate(
                                productId, 0L, request(value, "Nombre manipulado"))),
                        List.of(), List.of(row)),
                managerAuthentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.name")
                .hasMessageContaining("no coincide");

        verify(catalog, never()).updateProducts(org.mockito.ArgumentMatchers.any());
    }

    private static CatalogService.ProductRequest request(
            ProductBulkEditContent.ProductData value, String name) {
        return new CatalogService.ProductRequest(
                UUID.fromString(value.familyId()),
                value.subfamilyId() == null ? null : UUID.fromString(value.subfamilyId()),
                UUID.fromString(value.taxId()),
                ProductType.valueOf(value.productType()),
                DiscountType.valueOf(value.backendDiscountType()),
                PriceUseMode.valueOf(value.discountType()),
                name, value.description(), value.comments(),
                new BigDecimal(value.purchasePrice()),
                Boolean.parseBoolean(value.taxesIncluded()),
                value.code(), value.barcode(), value.barcode2(),
                new BigDecimal(value.salePrice()),
                value.memberPrice() == null ? null : new BigDecimal(value.memberPrice()),
                value.wholesalePrice() == null ? null : new BigDecimal(value.wholesalePrice()),
                value.offerPrice() == null ? null : new BigDecimal(value.offerPrice()),
                value.offerDiscountPercent() == null
                        ? null : new BigDecimal(value.offerDiscountPercent()),
                new BigDecimal(value.purchaseDiscountPercent()),
                Boolean.parseBoolean(value.offerActive()),
                value.offerFrom() == null ? null : LocalDate.parse(value.offerFrom()),
                value.offerUntil() == null ? null : LocalDate.parse(value.offerUntil()));
    }

    private static ProductBulkEditContent.Row row(String id, UUID productId) {
        ProductBulkEditContent.ProductData product = new ProductBulkEditContent.ProductData(
                productId,
                0L,
                null,
                null,
                "P-1",
                null,
                null,
                "Producto",
                null,
                null,
                "10.00",
                "0.00",
                "12.00",
                null,
                null,
                null,
                null,
                ProductType.UNIT.name(),
                "NORMAL",
                DiscountType.NORMAL.name(),
                UUID.randomUUID().toString(),
                "General",
                null,
                null,
                UUID.randomUUID().toString(),
                "IGIC",
                "true",
                "false",
                null,
                null,
                null,
                "0",
                "0");
        return new ProductBulkEditContent.Row(
                id, false, "P-1", product, ProductBulkEditContent.ProductData.empty(), List.of(), null);
    }

    private static ProductBulkEditImage stagedImage(
            ProductBulkEdit edit, UUID productId, int position) {
        return new ProductBulkEditImage(
                edit,
                productId,
                position,
                "producto-" + position + ".png",
                "image/png",
                "a".repeat(64),
                new byte[] {(byte) (position + 1)});
    }

    private UsernamePasswordAuthenticationToken managerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "manager", "", List.of(new SimpleGrantedAuthority("GESTION_PRODUCTO")));
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
