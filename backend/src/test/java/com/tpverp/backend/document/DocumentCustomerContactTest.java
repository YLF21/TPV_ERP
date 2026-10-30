package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerAddressEntry;
import com.tpverp.backend.party.FiscalAddress;
import com.tpverp.backend.verifactu.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class DocumentCustomerContactTest {
    private static final UUID CUSTOMER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ADDRESS = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private static DocumentCustomerContact contact() {
        return new DocumentCustomerContact(ADDRESS, new CustomerAddressEntry.Address(
                "Calle Elegida 2", "35002", "Las Palmas", "Las Palmas", "ES"), "222", "elegida@example.invalid");
    }
    private static CommercialDocument document() {
        var d = new CommercialDocument(UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 10, 29), UUID.randomUUID(), BigDecimal.ZERO);
        d.setParties(CUSTOMER, null, null);
        d.setCustomerContact(contact());
        d.addLine(new DocumentLine(d, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P", "Producto", null, new BigDecimal("12.10"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21")));
        return d;
    }
    @Test void selectsOnlyOwnedContactWithoutChangingMaster() {
        var customer = mock(Customer.class);
        var selected = contact();
        var primary = new CustomerAddressEntry(SECOND, new CustomerAddressEntry.Address(
                "Predeterminada", "35001", "Las Palmas", "Las Palmas", "ES"), "111", null, null);
        var addresses = List.of(primary, new CustomerAddressEntry(ADDRESS, selected.address(), selected.phone(), selected.email(), "Notas"));
        when(customer.getAddresses()).thenReturn(addresses);
        assertThat(DocumentCustomerContact.resolve(customer, ADDRESS)).isEqualTo(selected);
        assertThat(customer.getAddresses()).containsExactlyElementsOf(addresses);
        assertThatThrownBy(() -> DocumentCustomerContact.resolve(customer, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("customer_address_not_found");
        assertThatThrownBy(() -> DocumentCustomerContact.resolve(null, ADDRESS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("customer_address_customer_required");
        assertThat(DocumentCustomerContact.resolve(customer, null)).isNull();
        verify(customer, times(3)).getAddresses();
        verifyNoMoreInteractions(customer);
    }
    @Test void cardAndRecoverySnapshotsKeepFrozenContactAndReadPreContactVersions() throws Exception {
        var d = document();
        var base = ApprovedCardTicketSnapshot.from(d, UUID.randomUUID(), List.of(), null);
        var frozen = new ApprovedCardTicketSnapshot(base.storeId(), base.warehouseId(), base.date(), base.customerId(),
                base.wholesaleMode(), base.paymentMethodId(), base.globalDiscount(), base.baseTotal(), base.taxTotal(),
                base.total(), base.lines().stream().map(l -> l.withRequiresSerialNumber(false).withDiscountEligible(true)).toList(),
                base.internalComment(), base.historicalReplay(), base.adjustments(), base.manualControl(), base.customerContact());
        var codec = new PosCardDocumentSnapshot();
        var json = codec.serialize(frozen);
        assertThat(codec.deserialize(json).customerContact()).isEqualTo(contact());
        var old = new ObjectMapper().readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) old).put("schemaVersion", 5);
        ((com.fasterxml.jackson.databind.node.ObjectNode) old.get("ticket")).remove("customerContact");
        assertThat(codec.deserialize(old.toString()).customerContact()).isNull();
    }
    @Test void frozenContactFeedsFiscalAndFallbackReprintAfterMasterChanges() {
        var d = document();
        var customer = mock(Customer.class);
        when(customer.getId()).thenReturn(CUSTOMER);
        when(customer.getDocumentType()).thenReturn(com.tpverp.backend.party.DocumentType.NIF);
        when(customer.getDocumentNumber()).thenReturn("12345678Z");
        when(customer.getFiscalName()).thenReturn("Cliente");
        when(customer.getFiscalAddress()).thenReturn(new FiscalAddress("Nueva predeterminada", "28001", "Madrid", "Madrid", "ES"));
        when(customer.getPhone()).thenReturn("999");
        d.confirm("T-1", UUID.randomUUID(), Instant.now(), false);
        var fiscal = new FiscalSnapshotFactory().create(d, "B12345674", FiscalRecordOperation.ALTA, FiscalDocumentType.F2, customer);
        var fiscalCustomer = (Map<?, ?>) fiscal.get("cliente");
        assertThat(((Map<?, ?>) fiscalCustomer.get("direccion")).get("calle")).isEqualTo("Calle Elegida 2");
        var printed = CustomerReceivablePrintService.FiscalParty.from(d, customer);
        assertThat(printed.address().line1()).isEqualTo("Calle Elegida 2");
        assertThat(printed.phone()).isEqualTo("222");
        assertThat(customer.getFiscalAddress().getAddress()).isEqualTo("Nueva predeterminada");
        assertThatThrownBy(() -> d.setCustomerContact(null)).isInstanceOf(IllegalStateException.class);
    }
    private static PosCashController.SaleRequest sale(UUID address) {
        return new PosCashController.SaleRequest(CUSTOMER, List.of(), null, null, null, null, Map.of(), null, null,
                null, null, false, address);
    }
    @Test void checkoutHashesDistinguishAddressButPreserveLegacyShapeWhenAbsent() {
        var one = sale(ADDRESS); var two = sale(SECOND); var legacy = new PosCashController.SaleRequest(CUSTOMER, List.of());
        assertThat(PosCardService.hash(one, BigDecimal.ONE)).isNotEqualTo(PosCardService.hash(two, BigDecimal.ONE));
        assertThat(SalePaymentSessionService.hash(one, BigDecimal.ONE)).isNotEqualTo(SalePaymentSessionService.hash(two, BigDecimal.ONE));
        UUID checkout = UUID.randomUUID();
        assertThat(PosCashService.requestHash(new PosCashController.CashRequest(checkout, one, BigDecimal.ONE, BigDecimal.ONE)))
                .isNotEqualTo(PosCashService.requestHash(new PosCashController.CashRequest(checkout, two, BigDecimal.ONE, BigDecimal.ONE)));
        assertThat(PosCardService.hash(sale(null), BigDecimal.ONE)).isEqualTo(PosCardService.hash(legacy, BigDecimal.ONE));
        assertThat(SalePaymentSessionService.hash(sale(null), BigDecimal.ONE)).isEqualTo(SalePaymentSessionService.hash(legacy, BigDecimal.ONE));
        assertThat(PosCashService.canonicalCustomerAddress(null)).isEmpty();
    }
    @Test void parkedSaleRoundTripKeepsSelectedId() {
        var command = new DocumentCommand(UUID.randomUUID(), CommercialDocumentType.TICKET, LocalDate.now(), CUSTOMER,
                null, null, BigDecimal.ZERO, false, List.of(), null, null, false, ADDRESS);
        var parked = new ParkedSale(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), command, null);
        assertThat(parked.documentCommand().customerAddressId()).isEqualTo(ADDRESS);
        var legacy = parked.documentSnapshot(); legacy.remove("customerAddressId");
        assertThat(ParkedSale.documentCommand(legacy).customerAddressId()).isNull();
    }

    @Test void editingDraftFromLegacyClientKeepsContactButChangingCustomerDoesNot() {
        var draft = new CommercialDocument(UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.ALBARAN_VENTA,
                LocalDate.now(), UUID.randomUUID(), BigDecimal.ZERO);
        draft.setParties(CUSTOMER, null, null);
        draft.setCustomerContact(contact());
        var replacement = new CommercialDocument(draft.getTiendaId(), draft.getAlmacenId(), CommercialDocumentType.ALBARAN_VENTA,
                draft.getFecha(), UUID.randomUUID(), BigDecimal.ZERO);
        replacement.setParties(CUSTOMER, null, null);
        var command = new DocumentCommand(draft.getAlmacenId(), draft.getTipo(), draft.getFecha(), CUSTOMER,
                null, null, BigDecimal.ZERO, false, List.of());
        assertThat(DocumentCustomerContact.retainedFor(draft, command)).isEqualTo(contact());
        draft.replacePendingSaleDraft(replacement);
        assertThat(draft.getCustomerContact()).isEqualTo(contact());
        replacement.setParties(UUID.randomUUID(), null, null);
        draft.replacePendingSaleDraft(replacement);
        assertThat(draft.getCustomerContact()).isNull();
    }

    @Test void pendingCheckoutHashIncludesSelectedAddressAndOldRequestsStillDecode() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.createObjectNode();
        json.put("checkoutId", UUID.randomUUID().toString());
        json.put("warehouseId", UUID.randomUUID().toString());
        json.put("customerId", CUSTOMER.toString());
        json.put("type", "ALBARAN_VENTA");
        json.put("date", "2026-10-29");
        json.put("dueDate", "2026-11-29");
        json.put("globalDiscount", 0);
        json.put("quotedTotal", 1);
        json.putArray("lines");
        var legacy = mapper.treeToValue(json, CustomerPendingSaleController.CreateRequest.class);
        assertThat(legacy.customerAddressId()).isNull();
        var legacyHash = CustomerPendingSaleRequestHasher.hash(legacy, BigDecimal.ONE);
        json.put("customerAddressId", ADDRESS.toString());
        var selected = mapper.treeToValue(json, CustomerPendingSaleController.CreateRequest.class);
        assertThat(selected.toCommand().customerAddressId()).isEqualTo(ADDRESS);
        var first = CustomerPendingSaleRequestHasher.hash(selected, BigDecimal.ONE);
        assertThat(first).isNotEqualTo(legacyHash);
        json.put("customerAddressId", SECOND.toString());
        assertThat(CustomerPendingSaleRequestHasher.hash(mapper.treeToValue(json,
                CustomerPendingSaleController.CreateRequest.class), BigDecimal.ONE)).isNotEqualTo(first);
        json.putNull("customerAddressId");
        assertThat(CustomerPendingSaleRequestHasher.hash(mapper.treeToValue(json,
                CustomerPendingSaleController.CreateRequest.class), BigDecimal.ONE)).isEqualTo(legacyHash);
    }

    @Test void contactErrorsHaveActionableTranslationsInAllSupportedLanguages() {
        var source = new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        var messages = new com.tpverp.backend.shared.i18n.LocalizedMessages(source);
        for (var language : com.tpverp.backend.shared.i18n.SupportedLanguage.values()) {
            assertThat(messages.legacy("customer_address_not_found", language)).isPresent().get()
                    .asString().doesNotContain("customer_address_not_found");
        }
    }

    @Test void deserializationCannotInjectTheInternalTrustedContact() throws Exception {
        var command = new DocumentCommand(UUID.randomUUID(), CommercialDocumentType.TICKET, LocalDate.now(), CUSTOMER,
                null, null, BigDecimal.ZERO, true, List.of(), null, null, false, ADDRESS)
                .withFrozenCustomerContact(contact());
        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.writeValueAsString(command);
        assertThat(json).contains("customerContactSnapshot", "Calle Elegida 2");
        var deserialized = mapper.readValue(json, DocumentCommand.class);
        assertThat(deserialized.customerAddressId()).isEqualTo(ADDRESS);
        assertThat(deserialized.customerContactSnapshot()).isNull();
        // The durable gateway snapshot uses a different type and must still deserialize.
        assertThat(mapper.readValue(mapper.writeValueAsString(contact()), DocumentCustomerContact.class)).isEqualTo(contact());
    }
}
