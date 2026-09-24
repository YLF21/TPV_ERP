package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class WarehouseTransferDocumentServiceTest {
    @Test
    void rejectsNotesThatCannotBeExportedBeforePersisting() {
        var fixture = new DraftFixture();
        var command = new WarehouseTransferDocumentService.Command(fixture.source.getId(), fixture.target.getId(),
                "x".repeat(4001), null, List.of(fixture.line("1", "1", "0", true, "Producto")));
        assertThatThrownBy(() -> fixture.service.create(command, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("4000");
        verify(fixture.documents, never()).save(any());
        verifyNoInteractions(fixture.inventory);
    }

    @Test
    void savesValuationAndRepeatedProductSnapshotsWithoutMovingStockOrChangingTariffs() {
        var fixture = new DraftFixture();
        var command = fixture.command(List.of(
                fixture.line("2", "1.105", "10", true, "Primera descripción"),
                fixture.line("3", "999", "0", false, "Segunda descripción")));

        var view = fixture.service.create(command, null);

        assertThat(view.document().getDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(view.document().getExternalNumber()).isEqualTo("EXT-42");
        assertThat(view.document().getPriceSource()).isEqualTo(WarehouseInputPriceSource.SALE);
        assertThat(view.document().getSubtotal()).isEqualByComparingTo("16.99");
        assertThat(view.document().getTotal()).isEqualByComparingTo("16.14");
        assertThat(view.lines()).extracting(WarehouseTransferLine::getProductName)
                .containsExactly("Primera descripción", "Segunda descripción");
        assertThat(view.lines()).extracting(WarehouseTransferLine::getPosition).containsExactly(1, 2);
        assertThat(view.lines().get(0).getUnitPrice()).isEqualByComparingTo("1.105");
        assertThat(view.lines().get(1).getUnitPrice()).isEqualByComparingTo("5.000");
        verify(fixture.lines).saveAll(any());
        verifyNoInteractions(fixture.inventory);
        verify(fixture.products, never()).save(any());
    }

    @Test
    void rejectsInvalidPricesDiscountsAndMissingOverrideBeforePersisting() {
        for (var line : List.of(
                new BigDecimal[] {new BigDecimal("-0.001"), BigDecimal.ZERO},
                new BigDecimal[] {new BigDecimal("1.0001"), BigDecimal.ZERO},
                new BigDecimal[] {BigDecimal.ONE, new BigDecimal("100.001")},
                new BigDecimal[] {BigDecimal.ONE, new BigDecimal("-0.001")},
                new BigDecimal[] {null, BigDecimal.ZERO})) {
            var fixture = new DraftFixture();
            var command = fixture.command(List.of(new WarehouseTransferDocumentService.LineCommand(
                    fixture.productId, BigDecimal.ONE, line[0], line[1], true, null)));
            assertThatThrownBy(() -> fixture.service.create(command, null)).isInstanceOf(IllegalArgumentException.class);
            verify(fixture.documents, never()).save(any());
            verify(fixture.lines, never()).saveAll(any());
        }
    }

    @Test
    void supportsFiveThousandLinesAndRejectsLargerDocuments() {
        var fixture = new DraftFixture();
        var line = fixture.line("1", "1", "0", true, "Producto");
        assertThat(fixture.service.create(fixture.command(java.util.Collections.nCopies(5000, line)), null).lines())
                .hasSize(5000);
        assertThatThrownBy(() -> fixture.service.create(
                fixture.command(java.util.Collections.nCopies(5001, line)), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5000");
    }

    @Test
    void updateChecksVersionAndPersistsHeaderAndLineSnapshots() {
        var fixture = new DraftFixture();
        var original = fixture.service.create(fixture.command(List.of(fixture.line("1", "1", "0", true, "Original"))), null);
        when(fixture.documents.findLockedByIdAndStoreId(original.document().getId(), fixture.storeId))
                .thenReturn(Optional.of(original.document()));
        var stale = new WarehouseTransferDocumentService.Command(fixture.source.getId(), fixture.target.getId(), null,
                99L, List.of(fixture.line("2", "2", "0", true, "Editada")));
        assertThatThrownBy(() -> fixture.service.update(original.document().getId(), stale))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cambió");
        verify(fixture.lines, never()).deleteByTransferId(any());
        var updated = fixture.service.update(original.document().getId(), fixture.command(
                List.of(fixture.line("2", "2", "0", true, "Editada"))));
        assertThat(updated.lines().get(0).getProductName()).isEqualTo("Editada");
        assertThat(updated.document().getSubtotal()).isEqualByComparingTo("4");
        verify(fixture.lines).deleteByTransferId(original.document().getId());
        verifyNoInteractions(fixture.inventory);
    }

    private static class DraftFixture {
        final UUID storeId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final Warehouse source = new Warehouse(storeId, "ORIGEN");
        final Warehouse target = new Warehouse(storeId, "DESTINO");
        final WarehouseTransferDocumentRepository documents = mock(WarehouseTransferDocumentRepository.class);
        final WarehouseTransferLineRepository lines = mock(WarehouseTransferLineRepository.class);
        final InventoryService inventory = mock(InventoryService.class);
        final ProductRepository products = mock(ProductRepository.class);
        final WarehouseTransferDocumentService service;
        List<WarehouseTransferLine> saved = List.of();
        DraftFixture() {
            var product = mock(Product.class);
            when(product.getId()).thenReturn(productId);
            when(product.getStoreId()).thenReturn(storeId);
            when(product.getProductType()).thenReturn(ProductType.UNIT);
            when(product.getName()).thenReturn("Catálogo");
            when(product.getCode()).thenReturn("P1");
            when(product.getSalePrice()).thenReturn(new BigDecimal("5"));
            when(product.getPurchasePrice()).thenReturn(new BigDecimal("2"));
            when(products.findById(productId)).thenReturn(Optional.of(product));
            var warehouses = mock(WarehouseRepository.class);
            when(warehouses.findById(source.getId())).thenReturn(Optional.of(source));
            when(warehouses.findById(target.getId())).thenReturn(Optional.of(target));
            var organization = mock(CurrentOrganization.class);
            var store = mock(Store.class);
            var user = mock(UserAccount.class);
            when(store.getId()).thenReturn(storeId);
            when(user.getId()).thenReturn(UUID.randomUUID());
            when(organization.currentStore()).thenReturn(store);
            when(organization.currentUser(null)).thenReturn(user);
            when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(lines.saveAll(any())).thenAnswer(invocation -> { saved = invocation.getArgument(0); return saved; });
            when(lines.findByTransferIdOrderByCode(any())).thenAnswer(invocation -> saved);
            service = new WarehouseTransferDocumentService(documents, lines, warehouses, products,
                    mock(DocumentCounterRepository.class), inventory, organization,
                    Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC));
        }
        WarehouseTransferDocumentService.LineCommand line(String quantity, String price, String discount,
                boolean overridden, String name) {
            return new WarehouseTransferDocumentService.LineCommand(productId, new BigDecimal(quantity),
                    new BigDecimal(price), new BigDecimal(discount), overridden, name);
        }
        WarehouseTransferDocumentService.Command command(List<WarehouseTransferDocumentService.LineCommand> lines) {
            return new WarehouseTransferDocumentService.Command(source.getId(), target.getId(), "Notas", 0L, lines,
                    LocalDate.of(2026, 8, 15), " EXT-42 ", WarehouseInputPriceSource.SALE, new BigDecimal("5"));
        }
    }
    @Test
    void listsLineCountAndTotalUnitsForEachTransferWithoutLoadingLinesIndividually() {
        var storeId = UUID.randomUUID();
        var document = new WarehouseTransferDocument(storeId, UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), Instant.parse("2026-09-22T10:00:00Z"));
        var documents = mock(WarehouseTransferDocumentRepository.class);
        var lines = mock(WarehouseTransferLineRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(documents.pageFiltered(eq(storeId), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(document)));
        when(lines.totalsForTransfers(List.of(document.getId()))).thenReturn(List.of(
                new WarehouseTransferLineTotals(document.getId(), 2, new BigDecimal("3.500"))));
        var service = new WarehouseTransferDocumentService(documents, lines, mock(WarehouseRepository.class),
                mock(ProductRepository.class), mock(DocumentCounterRepository.class), mock(InventoryService.class),
                organization, Clock.systemUTC());

        var result = service.list(0, 50, null, null, null, null, null, null);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.lineCount()).isEqualTo(2);
            assertThat(item.totalUnits()).isEqualByComparingTo("3.500");
        });
        verify(lines).totalsForTransfers(List.of(document.getId()));
        verify(lines, never()).findByTransferIdOrderByCode(any());
    }

    @Test
    void appliesSearchWarehouseStatusAndDateFiltersBeforePagination() {
        var storeId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var from = Instant.parse("2026-09-01T00:00:00Z");
        var before = Instant.parse("2026-10-01T00:00:00Z");
        var documents = mock(WarehouseTransferDocumentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(documents.pageFiltered(eq(storeId), eq(WarehouseTransferDocument.Status.CONFIRMED),
                eq(sourceId), eq(targetId), eq(from), eq(before), eq("%tra-2026%"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        var service = new WarehouseTransferDocumentService(documents, mock(WarehouseTransferLineRepository.class),
                mock(WarehouseRepository.class), mock(ProductRepository.class), mock(DocumentCounterRepository.class),
                mock(InventoryService.class), organization, Clock.systemUTC());

        var page = service.list(2, 50, WarehouseTransferDocument.Status.CONFIRMED,
                sourceId, targetId, from, before, " TRA-2026 ");

        assertThat(page.items()).isEmpty();
        verify(documents).pageFiltered(eq(storeId), eq(WarehouseTransferDocument.Status.CONFIRMED),
                eq(sourceId), eq(targetId), eq(from), eq(before), eq("%tra-2026%"),
                argThat(request -> request.getPageNumber() == 2 && request.getPageSize() == 50));
    }

    @Test
    void rejectsFractionalUnitQuantityBeforeSavingDraft() {
        var storeId = UUID.randomUUID();
        var source = new Warehouse(storeId, "ORIGEN");
        var target = new Warehouse(storeId, "DESTINO");
        var productId = UUID.randomUUID();
        var product = mock(Product.class);
        when(product.getStoreId()).thenReturn(storeId);
        when(product.getProductType()).thenReturn(ProductType.UNIT);
        var warehouses = mock(WarehouseRepository.class);
        when(warehouses.findById(source.getId())).thenReturn(Optional.of(source));
        when(warehouses.findById(target.getId())).thenReturn(Optional.of(target));
        var products = mock(ProductRepository.class);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        var documents = mock(WarehouseTransferDocumentRepository.class);
        var service = new WarehouseTransferDocumentService(documents,
                mock(WarehouseTransferLineRepository.class), warehouses, products,
                mock(DocumentCounterRepository.class), mock(InventoryService.class), organization,
                Clock.systemUTC());

        assertThatThrownBy(() -> service.create(new WarehouseTransferDocumentService.Command(
                source.getId(), target.getId(), null, null,
                List.of(new WarehouseTransferDocumentService.LineCommand(productId,
                        new BigDecimal("1.500")))), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unit_quantity");
        verify(documents, never()).save(any());
    }

    @Test
    void confirmationTransfersBothSidesOnceAndAssignsDocumentNumber() {
        var storeId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var source = new Warehouse(storeId, "ORIGEN");
        var target = new Warehouse(storeId, "DESTINO");
        var productId = UUID.randomUUID();
        var product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getCode()).thenReturn("P-1");
        when(product.getName()).thenReturn("Producto");
        var document = new WarehouseTransferDocument(storeId, source.getId(), target.getId(), null,
                userId, Instant.parse("2026-09-22T10:00:00Z"));
        var line = new WarehouseTransferLine(document.getId(), product, new BigDecimal("2.000"));
        var documents = mock(WarehouseTransferDocumentRepository.class);
        var lines = mock(WarehouseTransferLineRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var counters = mock(DocumentCounterRepository.class);
        var inventory = mock(InventoryService.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var user = mock(UserAccount.class);
        var auth = new UsernamePasswordAuthenticationToken("admin", "token");
        when(store.getId()).thenReturn(storeId);
        when(user.getId()).thenReturn(userId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(auth)).thenReturn(user);
        when(documents.findLockedByIdAndStoreId(document.getId(), storeId)).thenReturn(Optional.of(document));
        when(documents.saveAndFlush(any())).thenAnswer(value -> value.getArgument(0));
        when(lines.findByTransferIdOrderByCode(document.getId())).thenReturn(List.of(line, line));
        when(warehouses.findById(source.getId())).thenReturn(Optional.of(source));
        when(warehouses.findById(target.getId())).thenReturn(Optional.of(target));
        when(counters.findByTiendaIdAndTipoAndPeriodo(storeId, "TRA", "2026")).thenReturn(Optional.empty());
        var service = new WarehouseTransferDocumentService(documents, lines, warehouses,
                mock(ProductRepository.class), counters, inventory, organization,
                Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC));

        var first = service.confirm(document.getId(), 0L, auth);
        var second = service.confirm(document.getId(), 0L, auth);

        assertThat(first.document().getNumber()).isEqualTo("TRA-2026-000001");
        assertThat(second.document().getStatus()).isEqualTo(WarehouseTransferDocument.Status.CONFIRMED);
        verify(inventory, times(1)).transferBatch(argThat(batch -> batch.size() == 1
                && batch.get(0).quantity().compareTo(new BigDecimal("4.000")) == 0), eq(auth), eq(document.getId()));
        verify(counters, times(1)).saveAndFlush(any());
    }
}
