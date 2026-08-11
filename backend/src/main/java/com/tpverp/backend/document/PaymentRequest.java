package com.tpverp.backend.document;

import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PaymentRequest(
        @NotEmpty List<Item> pagos,
        @Size(max = 32)
        @Valid Map<@NotNull SaleOperationCode, @NotNull @Valid OperationAuthorizationRequest>
                operationAuthorizations) {

    public PaymentRequest {
        operationAuthorizations = OperationAuthorizationRequest.immutableCopy(
                operationAuthorizations);
    }

    public PaymentRequest(List<Item> pagos) {
        this(pagos, Map.of());
    }

    // Maps every payment while preserving order and principal flag.
    public List<PaymentCommand> toCommands() {
        return pagos.stream().map(Item::toCommand).toList();
    }

    public record Item(
            @NotNull UUID metodoPagoId,
            @NotNull BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            String voucherCode,
            String reference,
            PaymentCardMode cardMode,
            PaymentTerminalProvider paymentTerminalProvider,
            PaymentTerminalOperationStatus paymentTerminalStatus,
            String cardAuthorizationCode,
            UUID paymentTerminalId,
            UUID requestId,
            UUID paymentTerminalOperationId,
            LocalDate transferDate) {

        public Item(
                UUID metodoPagoId,
                BigDecimal importe,
                boolean principal,
                BigDecimal entregado,
                BigDecimal cambio,
                String voucherCode,
                String reference,
                PaymentCardMode cardMode,
                PaymentTerminalProvider paymentTerminalProvider,
                PaymentTerminalOperationStatus paymentTerminalStatus,
                String cardAuthorizationCode,
                UUID paymentTerminalId,
                UUID requestId,
                UUID paymentTerminalOperationId) {
            this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                    cardMode, paymentTerminalProvider, paymentTerminalStatus,
                    cardAuthorizationCode, paymentTerminalId, requestId,
                    paymentTerminalOperationId, null);
        }

        public Item(
                UUID metodoPagoId,
                BigDecimal importe,
                boolean principal,
                BigDecimal entregado,
                BigDecimal cambio,
                String voucherCode) {
            this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, null,
                    null, null, null, null, null, null, null, null);
        }

        public Item(
                UUID metodoPagoId,
                BigDecimal importe,
                boolean principal,
                BigDecimal entregado,
                BigDecimal cambio,
                String voucherCode,
                String reference) {
            this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                    null, null, null, null, null, null, null, null);
        }

        public Item(
                UUID metodoPagoId, BigDecimal importe, boolean principal,
                BigDecimal entregado, BigDecimal cambio, String voucherCode, String reference,
                PaymentCardMode cardMode, PaymentTerminalProvider paymentTerminalProvider,
                PaymentTerminalOperationStatus paymentTerminalStatus,
                String cardAuthorizationCode, UUID paymentTerminalId) {
            this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                    cardMode, paymentTerminalProvider, paymentTerminalStatus,
                    cardAuthorizationCode, paymentTerminalId, null, null, null);
        }

        PaymentCommand toCommand() {
            return new PaymentCommand(
                    metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                    cardMode, paymentTerminalProvider, paymentTerminalStatus,
                    cardAuthorizationCode, paymentTerminalId, requestId, null, transferDate);
        }
    }
}
