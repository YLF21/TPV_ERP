package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class ProductBulkEditImageServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private ProductBulkEditRepository edits;
    @Mock private ProductBulkEditImageRepository images;
    @Mock private ProductRepository products;
    @Mock private ProductImageService productImages;
    @Mock private Store store;
    @Mock private UserAccount user;

    private ProductBulkEditImageService service;
    private ProductBulkEdit edit;
    private UUID storeId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        org.mockito.Mockito.lenient().when(organization.currentUser(
                org.mockito.ArgumentMatchers.any())).thenReturn(user);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(userId);
        edit = new ProductBulkEdit(
                storeId,
                "20260711001",
                "Imagenes",
                List.of(),
                userId,
                Instant.parse("2026-07-11T09:00:00Z"));
        service = new ProductBulkEditImageService(
                organization,
                edits,
                images,
                products,
                productImages,
                Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void synchronizesTheCompleteListKeepingReplacingAndDeletingRows() {
        UUID productId = UUID.randomUUID();
        Product product = org.mockito.Mockito.mock(Product.class);
        ProductBulkEditImage kept = image(productId, 0, "anterior.png", new byte[] {1});
        ProductBulkEditImage removed = image(null, 1, "eliminar.png", new byte[] {2});
        when(edits.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByEdicion_IdOrderByPosicionAsc(edit.getId()))
                .thenReturn(List.of(kept, removed));
        when(products.findAllByStoreIdAndIdIn(eq(storeId), anyCollection()))
                .thenReturn(List.of(product));
        when(product.getId()).thenReturn(productId);

        ProductBulkEditImageSyncView result = service.sync(
                edit.getId(),
                new ProductBulkEditImageService.ProductBulkEditImageSyncRequest(
                        edit.getVersion(),
                        List.of(
                                new ProductBulkEditImageService.ProductBulkEditImageSyncItem(
                                        kept.getId(), productId, null),
                                new ProductBulkEditImageService.ProductBulkEditImageSyncItem(
                                        null, null, 0))),
                List.of(new ProductBulkEditImageService.ProductBulkEditImageUpload(
                        "C:\\fotos\\nueva.png", "image/png", new byte[] {3, 4})),
                authentication());

        assertThat(result.images()).hasSize(2);
        assertThat(result.images().get(0).id()).isEqualTo(kept.getId());
        assertThat(result.images().get(1).fileName()).isEqualTo("nueva.png");
        assertThat(result.images().get(1).productId()).isNull();
        verify(images).deleteAll(List.of(removed));
        verify(productImages).validateUpload(new byte[] {3, 4});
        verify(edits).saveAndFlush(edit);
    }

    @Test
    void rejectsAProductOutsideTheCurrentStore() {
        UUID foreignProduct = UUID.randomUUID();
        when(edits.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByEdicion_IdOrderByPosicionAsc(edit.getId())).thenReturn(List.of());
        when(products.findAllByStoreIdAndIdIn(eq(storeId), anyCollection()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.sync(
                edit.getId(),
                new ProductBulkEditImageService.ProductBulkEditImageSyncRequest(
                        edit.getVersion(),
                        List.of(new ProductBulkEditImageService.ProductBulkEditImageSyncItem(
                                null, foreignProduct, 0))),
                List.of(new ProductBulkEditImageService.ProductBulkEditImageUpload(
                        "foto.png", "image/png", new byte[] {1})),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pertenecen a la tienda actual");

        verify(images, never()).saveAll(anyCollection());
    }

    @Test
    void rejectsUnreferencedUploadsAndOversizedFiles() {
        when(edits.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByEdicion_IdOrderByPosicionAsc(edit.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.sync(
                edit.getId(),
                new ProductBulkEditImageService.ProductBulkEditImageSyncRequest(
                        edit.getVersion(), List.of()),
                List.of(new ProductBulkEditImageService.ProductBulkEditImageUpload(
                        "foto.png", "image/png", new byte[] {1})),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("referenciados");

        byte[] oversized = new byte[ProductBulkEditImageService.MAX_FILE_BYTES + 1];
        assertThatThrownBy(() -> service.sync(
                edit.getId(),
                new ProductBulkEditImageService.ProductBulkEditImageSyncRequest(
                        edit.getVersion(),
                        List.of(new ProductBulkEditImageService.ProductBulkEditImageSyncItem(
                                null, null, 0))),
                List.of(new ProductBulkEditImageService.ProductBulkEditImageUpload(
                        "foto.png", "image/png", oversized)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 MB");
    }

    @Test
    void readChecksDraftOwnershipBeforeReturningBytes() {
        ProductBulkEditImage image = image(null, 0, "foto.png", new byte[] {7, 8});
        when(edits.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByIdAndEdicion_Id(image.getId(), edit.getId()))
                .thenReturn(Optional.of(image));

        ProductBulkEditImageService.ProductBulkEditImageContent result =
                service.read(edit.getId(), image.getId());

        assertThat(result.fileName()).isEqualTo("foto.png");
        assertThat(result.content()).containsExactly(7, 8);
    }

    @Test
    void listsMetadataWithoutReadingContentThroughTheEntityQuery() {
        ProductBulkEditImageView metadata = new ProductBulkEditImageView(
                UUID.randomUUID(), null, 0, "foto.png", "image/png", 2, "a".repeat(64));
        when(edits.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findViewsByEditId(edit.getId())).thenReturn(List.of(metadata));

        assertThat(service.list(edit.getId())).containsExactly(metadata);

        verify(images, never()).findByEdicion_IdOrderByPosicionAsc(edit.getId());
    }

    @SuppressWarnings("unchecked")
    @Test
    void savesNewImagesWithStableMetadataAndHash() {
        when(edits.findByIdAndStoreId(edit.getId(), storeId)).thenReturn(Optional.of(edit));
        when(images.findByEdicion_IdOrderByPosicionAsc(edit.getId())).thenReturn(List.of());

        service.sync(
                edit.getId(),
                new ProductBulkEditImageService.ProductBulkEditImageSyncRequest(
                        edit.getVersion(),
                        List.of(new ProductBulkEditImageService.ProductBulkEditImageSyncItem(
                                null, null, 0))),
                List.of(new ProductBulkEditImageService.ProductBulkEditImageUpload(
                        "foto.png", "image/png", new byte[] {1, 2, 3})),
                authentication());

        ArgumentCaptor<List<ProductBulkEditImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(images).saveAll(captor.capture());
        ProductBulkEditImage saved = captor.getValue().getFirst();
        assertThat(saved.getSize()).isEqualTo(3);
        assertThat(saved.getSha256()).hasSize(64);
        assertThat(saved.getContent()).containsExactly(1, 2, 3);
    }

    private ProductBulkEditImage image(
            UUID productId, int position, String fileName, byte[] content) {
        return new ProductBulkEditImage(
                edit, productId, position, fileName, "image/png", "a".repeat(64), content);
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("manager", "");
    }
}
