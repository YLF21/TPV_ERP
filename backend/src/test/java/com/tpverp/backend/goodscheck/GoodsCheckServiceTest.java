package com.tpverp.backend.goodscheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.inventory.WarehouseInput;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoodsCheckServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-02T10:00:00Z");

    @Mock
    private GoodsCheckRepository checks;
    @Mock
    private WarehouseInputRepository inputs;
    @Mock
    private ProductRepository products;
    @Mock
    private ProductIdentifierRepository identifiers;
    @Mock
    private CurrentOrganization organization;
    @Mock
    private CurrentTerminal terminal;
    @Mock
    private SyncOutboxService syncOutbox;

    private GoodsCheckService service;
    private Store store;
    private UserAccount user;
    private UUID terminalId;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Store(
                new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        terminalId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(store.getEmpresa());
        service = new GoodsCheckService(
                checks,
                inputs,
                products,
                identifiers,
                organization,
                terminal,
                syncOutbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startsFromConfirmedPurchaseDocumentAndGroupsProducts() {
        var productId = UUID.randomUUID();
        var document = confirmed(WarehouseInputDocumentType.ALBARAN_ENTRADA,
                line(productId, 6), line(productId, 6));
        when(inputs.findByIdAndStoreId(document.getId(), store.getId())).thenReturn(Optional.of(document));
        when(checks.existsByDocumentoIdAndEstado(document.getId(), GoodsCheckStatus.ABIERTA))
                .thenReturn(false);
        when(organization.currentUser(any())).thenReturn(user);
        when(checks.save(any())).thenAnswer(call -> call.getArgument(0));

        var view = service.start(document.getId(), authentication());

        assertThat(view.todos()).singleElement()
                .extracting(GoodsCheckView.Item::expectedQuantity)
                .isEqualTo(new BigDecimal("12.000"));
    }

    @Test
    void rejectsDraftOrSalesDocuments() {
        var sales = confirmed(WarehouseInputDocumentType.ENTRADA_ALMACEN, line(UUID.randomUUID(), 1));
        when(inputs.findByIdAndStoreId(sales.getId(), store.getId())).thenReturn(Optional.of(sales));

        assertThatThrownBy(() -> service.start(sales.getId(), authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.goods_check.document_not_found");

        verify(checks, never()).save(any());
    }

    @Test
    void rejectsSecondOpenCheckForSameDocument() {
        var document = confirmed(WarehouseInputDocumentType.FACTURA_ENTRADA, line(UUID.randomUUID(), 1));
        when(inputs.findByIdAndStoreId(document.getId(), store.getId())).thenReturn(Optional.of(document));
        when(checks.existsByDocumentoIdAndEstado(document.getId(), GoodsCheckStatus.ABIERTA))
                .thenReturn(true);

        assertThatThrownBy(() -> service.start(document.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message.goods_check.open_exists");
    }

    @Test
    void importResumesTheOpenCheckForThePurchaseDocument() {
        var productId = UUID.randomUUID();
        var document = confirmed(WarehouseInputDocumentType.FACTURA_ENTRADA, line(productId, 4));
        var openCheck = new GoodsCheck(document.getId(), store.getId(), user.getId(), NOW);
        openCheck.addLine(productId, new BigDecimal("4"));
        when(inputs.findByIdAndStoreId(document.getId(), store.getId())).thenReturn(Optional.of(document));
        when(checks.findByDocumentoIdAndEstadoAndTiendaId(
                document.getId(), GoodsCheckStatus.ABIERTA, store.getId()))
                .thenReturn(Optional.of(openCheck));

        var view = service.importDocument(document.getId(), authentication());

        assertThat(view.id()).isEqualTo(openCheck.getId());
        assertThat(view.status()).isEqualTo(GoodsCheckStatus.ABIERTA);
        assertThat(view.todos()).singleElement()
                .extracting(GoodsCheckView.Item::expectedQuantity)
                .isEqualTo(new BigDecimal("4.000"));
        verify(checks, never()).save(any());
    }

    @Test
    void scanAddsAndSubtractsWithoutGoingNegative() {
        var productId = UUID.randomUUID();
        var document = confirmed(WarehouseInputDocumentType.ALBARAN_ENTRADA, line(productId, 12));
        var check = new GoodsCheck(document.getId(), store.getId(), user.getId(), NOW);
        check.addLine(productId, new BigDecimal("12"));
        when(checks.findByIdAndTiendaId(check.getId(), store.getId())).thenReturn(Optional.of(check));
        when(inputs.findByIdAndStoreId(document.getId(), store.getId())).thenReturn(Optional.of(document));
        when(organization.currentUser(any())).thenReturn(user);
        when(terminal.terminalId(any())).thenReturn(terminalId);
        when(checks.save(check)).thenReturn(check);

        var first = service.scan(check.getId(), new GoodsCheckScanRequest(productId, null, new BigDecimal("22")), authentication());
        var fixed = service.scan(check.getId(), new GoodsCheckScanRequest(productId, null, new BigDecimal("-10")), authentication());

        assertThat(first.registrados()).singleElement()
                .extracting(GoodsCheckView.Item::extraQuantity)
                .isEqualTo(new BigDecimal("10.000"));
        assertThat(fixed.todos()).singleElement()
                .extracting(GoodsCheckView.Item::registeredQuantity)
                .isEqualTo(new BigDecimal("12.000"));
        assertThatThrownBy(() -> service.scan(
                check.getId(), new GoodsCheckScanRequest(productId, null, new BigDecimal("-13")), authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.goods_check.registered_quantity_negative");
    }

    @Test
    void closesAsCompleteOrWithDifferences() {
        var complete = new GoodsCheck(UUID.randomUUID(), store.getId(), user.getId(), NOW);
        var productId = UUID.randomUUID();
        complete.addLine(productId, new BigDecimal("2"));
        complete.register(productId, new BigDecimal("2"), user.getId(), terminalId, NOW);
        complete.close(user.getId(), NOW);

        var different = new GoodsCheck(UUID.randomUUID(), store.getId(), user.getId(), NOW);
        different.addLine(UUID.randomUUID(), new BigDecimal("2"));
        different.close(user.getId(), NOW);

        assertThat(complete.getEstado()).isEqualTo(GoodsCheckStatus.COMPLETA);
        assertThat(different.getEstado()).isEqualTo(GoodsCheckStatus.CON_DIFERENCIAS);
    }

    private WarehouseInput confirmed(WarehouseInputDocumentType type, LineSpec... lines) {
        var document = new WarehouseInput(
                store.getId(), UUID.randomUUID(), java.time.LocalDate.of(2026, 7, 2), user.getId(), type);
        for (var line : lines) {
            document.addLine(line.productId(), line.quantity());
        }
        document.confirm(type == WarehouseInputDocumentType.FACTURA_ENTRADA
                ? "FE-001" : "AE-001", user.getId(), NOW);
        return document;
    }

    private LineSpec line(UUID productId, int quantity) {
        return new LineSpec(productId, quantity);
    }

    private record LineSpec(UUID productId, int quantity) { }

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "n/a");
    }
}
