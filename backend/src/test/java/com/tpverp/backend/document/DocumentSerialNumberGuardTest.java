package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentSerialNumberGuardTest {

    @Mock
    private CommercialDocumentRepository documents;

    @Mock
    private ProductRepository products;

    private DocumentSerialNumberGuard guard;

    @BeforeEach
    void setUp() {
        guard = new DocumentSerialNumberGuard(documents, products);
    }

    @Test
    void locksDistinctNormalizedSerialsInStableOrderBeforeFinalLookup() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, new BigDecimal("2"), List.of(" z-2 ", "a-1")));
        when(documents.usedSerialNumbers(document.getTiendaId(), List.of("A-1", "Z-2")))
                .thenReturn(List.of());

        guard.lockAndValidate(document, true);

        var ordered = inOrder(documents);
        ordered.verify(documents).lockSerialNumber(
                DocumentSerialNumberGuard.lockKey(document, "A-1"));
        ordered.verify(documents).lockSerialNumber(
                DocumentSerialNumberGuard.lockKey(document, "Z-2"));
        ordered.verify(documents).usedSerialNumbers(
                document.getTiendaId(), List.of("A-1", "Z-2"));
    }

    @Test
    void rejectsSerialAlreadyClaimedByAnotherActiveStockOutput() {
        var document = document(CommercialDocumentType.ALBARAN_VENTA);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of("SN-1")));
        when(documents.usedSerialNumbers(document.getTiendaId(), List.of("SN-1")))
                .thenReturn(List.of("SN-1"));

        assertThatThrownBy(() -> guard.lockAndValidate(document, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.serial_number_already_used");

        verify(documents).lockSerialNumber(
                DocumentSerialNumberGuard.lockKey(document, "SN-1"));
    }

    @Test
    void rejectsSameSerialAcrossTwoPositiveLinesBeforeDatabaseAccess() {
        var document = document(CommercialDocumentType.FACTURA_VENTA);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of("SN-X")));
        document.addLine(line(document, 2, BigDecimal.ONE, List.of("sn-x")));

        assertThatThrownBy(() -> guard.lockAndValidate(document, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.serial_number_duplicated");

        verifyNoInteractions(documents);
    }

    @Test
    void ignoresReturnsAndDocumentsThatDoNotApplyStock() {
        var refund = document(CommercialDocumentType.TICKET);
        refund.addLine(line(refund, 1, new BigDecimal("-1"), List.of("SN-RETURN")));
        var derivedInvoice = document(CommercialDocumentType.FACTURA_VENTA);
        derivedInvoice.addLine(line(
                derivedInvoice, 1, BigDecimal.ONE, List.of("SN-DERIVED")));

        guard.lockAndValidate(refund, true);
        guard.lockAndValidate(derivedInvoice, false);

        verify(documents, never()).lockSerialNumber(org.mockito.ArgumentMatchers.anyString());
        verify(documents, never()).usedSerialNumbers(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requiresSerialNumberRejectsMissingSerials() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of()));
        var product = product(document, true, ProductType.UNIT);
        when(products.findAllByStoreIdAndIdIn(document.getTiendaId(), List.of(product.getId())))
                .thenReturn(List.of(product));
        assertThatThrownBy(() -> guard.lockAndValidate(document, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.serial_number_required");
    }

    @Test
    void requiresSerialNumberMatchesEachUnit() {
        var document = document(CommercialDocumentType.TICKET);
        assertThatThrownBy(() -> line(document, 1, new BigDecimal("2"), List.of("SN-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cada unidad con numero de serie debe tener un S/N distinto");
    }

    @Test
    void requiredSerialNumberWithOnePerUnitPasses() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, new BigDecimal("2"), List.of("SN-1", "SN-2")));
        var product = product(document, true, ProductType.UNIT);
        when(products.findAllByStoreIdAndIdIn(document.getTiendaId(), List.of(product.getId())))
                .thenReturn(List.of(product));
        when(documents.usedSerialNumbers(document.getTiendaId(), List.of("SN-1", "SN-2")))
                .thenReturn(List.of());

        guard.lockAndValidate(document, true);
    }

    @Test
    void productWithoutRequirementDoesNotNeedSerialNumber() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of()));
        var product = product(document, false, ProductType.UNIT);
        when(products.findAllByStoreIdAndIdIn(document.getTiendaId(), List.of(product.getId())))
                .thenReturn(List.of(product));

        guard.lockAndValidate(document, true);
        verify(documents, never()).usedSerialNumbers(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void inconsistentNonUnitRequirementFailsSafely() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of("SN-1")));
        var product = product(document, true, ProductType.WEIGHT);
        when(products.findAllByStoreIdAndIdIn(document.getTiendaId(), List.of(product.getId())))
                .thenReturn(List.of(product));

        assertThatThrownBy(() -> guard.lockAndValidate(document, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.serial_number_required");
    }

    @Test
    void snapshotPolicyFalseIsNotRetrofittedWhenCatalogBecomesRequired() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of()));

        guard.lockAndValidateSnapshot(document, true,
                List.of(DocumentLineCommand.from(document.getLineas().getFirst())
                        .withRequiresSerialNumber(false)));
        verifyNoInteractions(products, documents);
    }

    @Test
    void snapshotPolicyTrueStillAppliesWhenCatalogNoLongerRequiresSerials() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of()));

        assertThatThrownBy(() -> guard.lockAndValidateSnapshot(document, true,
                List.of(DocumentLineCommand.from(document.getLineas().getFirst())
                        .withRequiresSerialNumber(true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.serial_number_required");
        verifyNoInteractions(products, documents);
    }

    @Test
    void prepaymentSnapshotValidationUsesFrozenPolicyWithoutTakingDatabaseLock() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of("SN-1")));
        when(documents.usedSerialNumbers(document.getTiendaId(), List.of("SN-1")))
                .thenReturn(List.of());

        guard.validateSnapshot(document, true,
                List.of(DocumentLineCommand.from(document.getLineas().getFirst())
                        .withRequiresSerialNumber(true)));

        verify(documents).usedSerialNumbers(document.getTiendaId(), List.of("SN-1"));
        verify(documents, never()).lockSerialNumber(
                org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(products);
    }

    @Test
    void prepaymentRejectsUsedSerialButAuthorizedSnapshotCanProceedBestEffort() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of("SN-USED")));
        var command = DocumentLineCommand.from(document.getLineas().getFirst())
                .withRequiresSerialNumber(true);
        when(documents.usedSerialNumbers(document.getTiendaId(), List.of("SN-USED")))
                .thenReturn(List.of("SN-USED"));

        assertThatThrownBy(() -> guard.validateSnapshot(document, true, List.of(command)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.serial_number_already_used");

        guard.lockAndValidateSnapshot(document, true, List.of(command), true);
        verify(documents).lockSerialNumber(DocumentSerialNumberGuard.lockKey(document, "SN-USED"));
        verify(documents, times(1)).usedSerialNumbers(document.getTiendaId(), List.of("SN-USED"));
    }

    @Test
    void authorizedSnapshotStillRejectsDuplicateSerialInsideItsOwnTicket() {
        var document = document(CommercialDocumentType.TICKET);
        document.addLine(line(document, 1, BigDecimal.ONE, List.of("SN-DUP")));
        document.addLine(line(document, 2, BigDecimal.ONE, List.of("sn-dup")));
        var commands = document.getLineas().stream()
                .map(line -> DocumentLineCommand.from(line).withRequiresSerialNumber(true))
                .toList();

        assertThatThrownBy(() -> guard.lockAndValidateSnapshot(document, true, commands, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.serial_number_duplicated");
        verifyNoInteractions(documents);
    }

    private Product product(CommercialDocument document, boolean required, ProductType type) {
        var product = org.mockito.Mockito.mock(Product.class);
        var id = document.getLineas().isEmpty()
                ? UUID.randomUUID() : document.getLineas().get(0).getProductoId();
        when(product.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(product.isRequiresSerialNumber()).thenReturn(required);
        org.mockito.Mockito.lenient().when(product.getProductType()).thenReturn(type);
        return product;
    }

    private CommercialDocument document(CommercialDocumentType type) {
        return new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), type,
                LocalDate.of(2026, 8, 7), UUID.randomUUID(), BigDecimal.ZERO);
    }

    private DocumentLine line(
            CommercialDocument document,
            int position,
            BigDecimal quantity,
            List<String> serialNumbers) {
        var line = new DocumentLine(
                document, UUID.randomUUID(), position, quantity,
                "P-" + position, "Producto " + position, "VENTA",
                BigDecimal.TEN, BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
        line.assignSerialNumbers(serialNumbers);
        return line;
    }
}
