package com.tpverp.backend.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

@Component
public class PosCardDocumentSnapshot {

    private static final int CURRENT_SCHEMA_VERSION = 4;

    private final ObjectMapper mapper;

    public PosCardDocumentSnapshot() {
        this(JsonMapper.builder().findAndAddModules().build());
    }

    PosCardDocumentSnapshot(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String serialize(ApprovedCardTicketSnapshot ticket) {
        validate(ticket);
        try {
            return mapper.writeValueAsString(new Snapshot(CURRENT_SCHEMA_VERSION, ticket));
        } catch (JsonProcessingException exception) {
            throw new ApprovedCardSnapshotException(
                    "No se pudo guardar la instantanea de venta", exception);
        }
    }

    public ApprovedCardTicketSnapshot deserialize(String json) {
        try {
            var snapshot = mapper.readValue(json, Snapshot.class);
            if (snapshot.schemaVersion() < 1
                    || snapshot.schemaVersion() > CURRENT_SCHEMA_VERSION) {
                throw new ApprovedCardSnapshotException(
                        "Version de instantanea no soportada");
            }
            var legacy = snapshot.schemaVersion() < CURRENT_SCHEMA_VERSION;
            var ticket = legacy ? withLegacyPolicies(snapshot.ticket()) : snapshot.ticket();
            validate(ticket, !legacy);
            return ticket;
        } catch (ApprovedCardSnapshotException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ApprovedCardSnapshotException(
                    "Instantanea de venta corrupta", exception);
        }
    }

    private static ApprovedCardTicketSnapshot withLegacyPolicies(
            ApprovedCardTicketSnapshot value) {
        if (value == null || value.lines() == null) {
            return value;
        }
        return new ApprovedCardTicketSnapshot(
                value.storeId(), value.warehouseId(), value.date(), value.customerId(),
                value.wholesaleMode(), value.paymentMethodId(), value.globalDiscount(), value.baseTotal(),
                value.taxTotal(), value.total(),
                value.lines().stream()
                        .map(line -> {
                            var normalized = isPositiveProduct(line)
                                    && line.requiresSerialNumber() == null
                                    ? line.withRequiresSerialNumber(false) : line;
                            // Legacy snapshots did not freeze catalogue discount policy.
                            // Preserve null so accrual can resolve it against the catalogue.
                            return normalized;
                        })
                        .toList(),
                value.internalComment(), value.historicalReplay(), value.adjustments());
    }

    private static void validate(ApprovedCardTicketSnapshot value) {
        validate(value, true);
    }

    private static void validate(
            ApprovedCardTicketSnapshot value, boolean requireDiscountEligibility) {
        if (value == null
                || value.storeId() == null
                || value.warehouseId() == null
                || value.date() == null
                || value.paymentMethodId() == null
                || value.lines() == null
                || value.lines().isEmpty()
                || value.baseTotal() == null
                || value.taxTotal() == null
                || value.total() == null) {
            throw new ApprovedCardSnapshotException(
                    "Instantanea de venta incompleta");
        }
        if (value.lines().stream()
                .anyMatch(line -> isPositiveProduct(line)
                        && line.requiresSerialNumber() == null)) {
            throw new ApprovedCardSnapshotException(
                    "La instantanea no conserva la politica de numeros de serie");
        }
        if (requireDiscountEligibility && value.lines().stream()
                .anyMatch(line -> isPositiveProduct(line)
                        && line.discountEligible() == null)) {
            throw new ApprovedCardSnapshotException(
                    "La instantanea no conserva la elegibilidad de descuentos");
        }
        try {
            var ticket = new CommercialDocument(
                    value.storeId(), value.warehouseId(),
                    CommercialDocumentType.TICKET, value.date(),
                    java.util.UUID.randomUUID(), value.globalDiscount());
            ticket.setParties(value.customerId(), null, null);
            value.lines().forEach(line -> ticket.addLine(line.toEntity(ticket)));
            value.restoreAdjustments(ticket);
            if (ticket.getBaseTotal().compareTo(Money.euros(value.baseTotal())) != 0
                    || ticket.getImpuestoTotal().compareTo(
                            Money.euros(value.taxTotal())) != 0
                    || ticket.getTotal().compareTo(Money.euros(value.total())) != 0) {
                throw new ApprovedCardSnapshotException(
                        "Los totales fiscales de la instantanea no cuadran");
            }
        } catch (ApprovedCardSnapshotException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApprovedCardSnapshotException(
                    "Lineas fiscales invalidas", exception);
        }
    }

    private static boolean isPositiveProduct(DocumentLineCommand line) {
        return line != null
                && line.productoId() != null
                && (line.lineType() == null
                        || line.lineType() == DocumentLineType.PRODUCT)
                && line.cantidad() != null
                && line.cantidad().signum() > 0;
    }

    public record Snapshot(int schemaVersion, ApprovedCardTicketSnapshot ticket) {
    }
}
