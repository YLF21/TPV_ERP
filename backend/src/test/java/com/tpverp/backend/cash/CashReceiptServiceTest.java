package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.document.template.OperationalDocumentJasperRenderer;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CashReceiptServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T09:30:00Z");

    @Test
    void withdrawalReceiptIncludesPrintableDataAndSignatureLabels() {
        var fixture = fixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        var movement = CashMovement.sessionMovement(
                fixture.store.getId(), fixture.terminal.getId(), session, CashMovementType.RETIRADA_CIERRE,
                new BigDecimal("20.00"), NOW.plusSeconds(60), fixture.user.getId(),
                fixture.user.getId(), "retirada cierre",
                null, null);
        movement.addDenomination(new BigDecimal("20.00"), 1);
        when(fixture.movements.findById(movement.getId())).thenReturn(Optional.of(movement));
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));

        var receipt = fixture.service.withdrawalReceipt(movement.getId(), salesAuthentication(fixture.user));

        assertThat(receipt.amount()).isEqualByComparingTo("20.00");
        assertThat(receipt.denominations()).containsExactly(
                new CashDenominationCommand(new BigDecimal("20.00"), 1));
        assertThat(receipt.createdAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(receipt.userName()).isEqualTo("SELLER");
        assertThat(receipt.terminalName()).isEqualTo("TPV 1");
        assertThat(receipt.sessionId()).isEqualTo(session.getId());
        assertThat(receipt.authorizerName()).isEqualTo("SELLER");
        assertThat(receipt.comment()).isEqualTo("retirada cierre");
        assertThat(receipt.giverSignatureLabel()).isEmpty();
        assertThat(receipt.receiverSignatureLabel()).isEmpty();
    }

    @Test
    void withdrawalReceiptRejectsNonWithdrawalMovement() {
        var fixture = fixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        var movement = CashMovement.sessionMovement(
                fixture.store.getId(), fixture.terminal.getId(), session, CashMovementType.ENTRADA,
                new BigDecimal("20.00"), NOW.plusSeconds(60), fixture.user.getId(), null, "entrada",
                null, null);
        when(fixture.movements.findById(movement.getId())).thenReturn(Optional.of(movement));

        assertThatThrownBy(() -> fixture.service.withdrawalReceipt(movement.getId(), salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retirada");
    }

    @Test
    void withdrawalReceiptRequiresCashStatusPermissionBeforeLoadingMovement() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.service.withdrawalReceipt(
                UUID.randomUUID(), new UsernamePasswordAuthenticationToken("guest", "token")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("consulta de caja");
        verifyNoInteractions(fixture.movements);
    }

    @Test
    void sellerCloseReceiptDoesNotExposeAccountingTotals() {
        var fixture = fixture();
        var session = closedSession(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId());
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));

        var receipt = fixture.service.closeReceipt(session.getId(), salesAuthentication(fixture.user));

        assertThat(receipt.retainedFund()).isNull();
        assertThat(receipt.discrepancy()).isNull();
        assertThat(receipt.expectedCash()).isNull();
        assertThat(receipt.giverSignatureLabel()).isEmpty();
        assertThat(receipt.receiverSignatureLabel()).isEmpty();
    }

    @Test
    void accountingCloseReceiptIncludesExpectedCash() {
        var fixture = fixture();
        var session = closedSession(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId());
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));

        var receipt = fixture.service.closeReceipt(session.getId(), accountingAuthentication(fixture.user));

        assertThat(receipt.retainedFund()).isEqualByComparingTo("95.00");
        assertThat(receipt.discrepancy()).isEqualByComparingTo("-5.00");
        assertThat(receipt.expectedCash()).isEqualByComparingTo("100.00");
    }

    @Test
    void closeReceiptRejectsOpenSession() {
        var fixture = fixture();
        var session = CashSession.open(
                fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), NOW, new BigDecimal("100.00"));
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> fixture.service.closeReceipt(session.getId(), salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("abierta");
    }

    @ParameterizedTest
    @EnumSource(value = CashMovementType.class, names = {"ENTRADA", "RETIRADA"})
    void receiptUsesOriginalGlobalOperatorAndVisibleAuthorizerNames(CashMovementType type) {
        var fixture = fixture();
        var globalAdmin = new UserAccount(null, "ADMIN", "hash", new Role(fixture.store, "ADMIN"));
        globalAdmin.cambiarUserName("Administrador principal");
        fixture.user.cambiarUserName("María López");
        var movement = receiptMovement(fixture, type, globalAdmin.getId(), fixture.user.getId(), "Banco");
        when(fixture.users.findById(globalAdmin.getId())).thenReturn(Optional.of(globalAdmin));

        var receipt = type == CashMovementType.ENTRADA
                ? fixture.service.entryReceipt(movement.getId(), salesAuthentication(fixture.user))
                : fixture.service.withdrawalReceipt(movement.getId(), salesAuthentication(fixture.user));

        assertThat(receipt.userName()).isEqualTo("Administrador principal");
        assertThat(receipt.authorizerName()).isEqualTo("María López");
    }

    @Test
    void historicalReceiptKeepsDisabledOperatorFromAnotherStoreOfSameCompany() {
        var fixture = fixture();
        var otherStore = new Store(fixture.store.getEmpresa(), "002", "Otra tienda", fixture.store.getDireccion(),
                UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
        var operator = new UserAccount(otherStore, "cajera", "hash", new Role(otherStore, "SELLER"));
        operator.cambiarUserName("Ana Pérez");
        operator.deactivate();
        var movement = receiptMovement(fixture, CashMovementType.RETIRADA, operator.getId(), null, "Banco");
        when(fixture.users.findById(operator.getId())).thenReturn(Optional.of(operator));

        var receipt = fixture.service.withdrawalReceipt(movement.getId(), salesAuthentication(fixture.user));

        assertThat(receipt.userName()).isEqualTo("Ana Pérez");
    }

    @Test
    void receiptDoesNotExposeOperatorFromAnotherCompanyOrTechnicalIdWhenMissing() {
        var fixture = fixture();
        var otherStore = store();
        var foreignUser = new UserAccount(otherStore, "foreign", "hash", new Role(otherStore, "SELLER"));
        var missingAuthorizer = UUID.randomUUID();
        var movement = receiptMovement(fixture, CashMovementType.RETIRADA, foreignUser.getId(), missingAuthorizer, "Banco");
        when(fixture.users.findById(foreignUser.getId())).thenReturn(Optional.of(foreignUser));

        var receipt = fixture.service.withdrawalReceipt(movement.getId(), salesAuthentication(fixture.user));

        assertThat(receipt.userName()).isEmpty();
        assertThat(receipt.authorizerName()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = CashMovementType.class, names = {"ENTRADA", "RETIRADA"})
    void receiptRejectsMovementFromAnotherStoreBeforeReadingItsUsers(CashMovementType type) {
        var fixture = fixture();
        var movement = CashMovement.sessionMovement(UUID.randomUUID(), fixture.terminal.getId(), UUID.randomUUID(),
                type, new BigDecimal("20.00"), NOW, fixture.user.getId(), null, "Banco", null, null);
        when(fixture.movements.findById(movement.getId())).thenReturn(Optional.of(movement));

        assertThatThrownBy(() -> {
            if (type == CashMovementType.ENTRADA) {
                fixture.service.entryReceipt(movement.getId(), salesAuthentication(fixture.user));
            } else {
                fixture.service.withdrawalReceipt(movement.getId(), salesAuthentication(fixture.user));
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessage("Movimiento de caja no encontrado");
        verifyNoInteractions(fixture.users);
    }

    @Test
    void entryReceiptRejectsWithdrawalAndRequiresPermissionBeforeLoadingMovement() {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.service.entryReceipt(
                UUID.randomUUID(), new UsernamePasswordAuthenticationToken("guest", "token")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(fixture.movements);
        var movement = receiptMovement(fixture, CashMovementType.RETIRADA, fixture.user.getId(), null, "Banco");

        assertThatThrownBy(() -> fixture.service.entryReceipt(movement.getId(), salesAuthentication(fixture.user)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entrada");
    }

    @ParameterizedTest
    @EnumSource(value = CashMovementType.class, names = {"ENTRADA", "RETIRADA"})
    void renderedReceiptUsesOwnTemplateAndExactAmountWithStoreDateAndCurrency(CashMovementType type) {
        var fixture = fixture();
        var renderer = mock(OperationalDocumentJasperRenderer.class);
        when(renderer.mapper()).thenReturn(new ObjectMapper());
        fixture.service.setPrinting(renderer);
        fixture.user.cambiarUserName("María López");
        var movement = receiptMovement(fixture, type, fixture.user.getId(), null, "Ingreso en banco");
        movement.addDenomination(new BigDecimal("20.00"), 1);
        var withdrawal = type == CashMovementType.RETIRADA;

        if (withdrawal) {
            fixture.service.withdrawalPrintDocument(movement.getId(), salesAuthentication(fixture.user));
        } else {
            fixture.service.entryPrintDocument(movement.getId(), salesAuthentication(fixture.user));
        }

        var data = ArgumentCaptor.forClass(ObjectNode.class);
        verify(renderer).render(org.mockito.ArgumentMatchers.eq(withdrawal
                        ? DocumentTemplateType.RETIRADA_CAJA : DocumentTemplateType.ENTRADA_CAJA),
                org.mockito.ArgumentMatchers.eq(DocumentTemplateFormat.TICKET_80), data.capture(),
                org.mockito.ArgumentMatchers.eq((withdrawal ? "retirada-caja-" : "entrada-caja-") + movement.getId() + ".pdf"));
        var root = data.getValue();
        assertThat(root.path("movement").path("amount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(root.path("movement").path("amountFormatted").asText().replace('\u00a0', ' ')).isEqualTo("20,00 €");
        assertThat(root.path("movement").path("currency").asText()).isEqualTo("EUR");
        assertThat(root.path("movement").path("createdAtFormatted").asText()).isEqualTo("25/06/2026 10:30");
        assertThat(root.path("movement").path("signatureRequired").asBoolean()).isEqualTo(withdrawal);
        assertThat(root.path("movement").path("title").asText()).isEqualTo(withdrawal
                ? "RETIRADA DE EFECTIVO" : "ENTRADA DE EFECTIVO");
        assertThat(root.path("lines").findValuesAsText("label")).containsExactly(
                "Operador", "Terminal", "Importe", "Motivo", "Unidades de 20,00\u00a0€");
        assertThat(root.path("lines").findValuesAsText("value")).containsExactly(
                "María López", "TPV 1", "20.00", "Ingreso en banco", "1");
    }

    @Test
    void entryPrintingOmitsEmptyOptionalDetails() {
        var fixture = fixture();
        var renderer = mock(OperationalDocumentJasperRenderer.class);
        when(renderer.mapper()).thenReturn(new ObjectMapper());
        fixture.service.setPrinting(renderer);
        var movement = receiptMovement(fixture, CashMovementType.ENTRADA, fixture.user.getId(), null, null);

        fixture.service.entryPrintDocument(movement.getId(), salesAuthentication(fixture.user));

        var data = ArgumentCaptor.forClass(ObjectNode.class);
        verify(renderer).render(org.mockito.ArgumentMatchers.eq(DocumentTemplateType.ENTRADA_CAJA),
                org.mockito.ArgumentMatchers.eq(DocumentTemplateFormat.TICKET_80), data.capture(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(data.getValue().path("lines").findValuesAsText("label")).containsExactly(
                "Operador", "Terminal", "Importe");
    }

    private static CashMovement receiptMovement(Fixture fixture, CashMovementType type,
            UUID operatorId, UUID authorizerId, String comment) {
        var session = CashSession.open(fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(),
                NOW, new BigDecimal("100.00"));
        var movement = CashMovement.sessionMovement(fixture.store.getId(), fixture.terminal.getId(), session,
                type, new BigDecimal("20.00"), NOW, operatorId, authorizerId, comment, null, null);
        when(fixture.movements.findById(movement.getId())).thenReturn(Optional.of(movement));
        when(fixture.sessions.findById(session.getId())).thenReturn(Optional.of(session));
        return movement;
    }

    private static Fixture fixture() {
        var store = store();
        var user = new UserAccount(store, "SELLER", "hash", new Role(store, "SELLER"));
        var terminal = new Terminal(store, "TPV 1", TerminalType.TERMINAL_VENTA, "hash");
        var sessions = mock(CashSessionRepository.class);
        var movements = mock(CashMovementRepository.class);
        var terminals = mock(TerminalRepository.class);
        var users = mock(UserAccountRepository.class);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(store.getEmpresa());
        when(organization.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(user);
        when(terminals.findByIdAndTiendaId(terminal.getId(), store.getId())).thenReturn(Optional.of(terminal));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        var service = new CashReceiptService(
                sessions, movements, terminals, users, organization,
                new CashPermissionService(null, null, organization));
        return new Fixture(service, sessions, movements, users, store, user, terminal);
    }

    private static CashSession closedSession(UUID storeId, UUID terminalId, UUID userId) {
        var session = CashSession.open(storeId, terminalId, userId, NOW, new BigDecimal("100.00"));
        session.close(userId, NOW.plusSeconds(120), new BigDecimal("100.00"),
                new BigDecimal("95.00"), new BigDecimal("-5.00"));
        return session;
    }

    private static UsernamePasswordAuthenticationToken salesAuthentication(UserAccount user) {
        return new UsernamePasswordAuthenticationToken(
                user, "token", List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.VENTA)));
    }

    private static UsernamePasswordAuthenticationToken accountingAuthentication(UserAccount user) {
        return new UsernamePasswordAuthenticationToken(
                user, "token", List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.GESTION_CUENTAS)));
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "001", "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record Fixture(
            CashReceiptService service,
            CashSessionRepository sessions,
            CashMovementRepository movements,
            UserAccountRepository users,
            Store store,
            UserAccount user,
            Terminal terminal) {
    }
}
