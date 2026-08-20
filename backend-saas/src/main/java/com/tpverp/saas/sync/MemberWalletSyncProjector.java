package com.tpverp.saas.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.loyalty.MemberBalanceType;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccount;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccountRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceLotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberWalletSyncProjector {

    static final String ENTITY_TYPE = "MEMBER_WALLET_LOT";

    private final SaasMemberBalanceAccountRepository accounts;
    private final SaasMemberBalanceLotRepository lots;
    private final ObjectMapper mapper;

    public MemberWalletSyncProjector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            ObjectMapper mapper) {
        this.accounts = accounts;
        this.lots = lots;
        this.mapper = mapper;
    }

    public boolean supports(String entityType, SyncOperation operation) {
        return ENTITY_TYPE.equals(entityType) && operation == SyncOperation.CREAR;
    }

    public void project(SaasSyncEvent event, Map<String, Object> payload, Instant now) {
        WalletLotCreation creation = parse(payload);
        UUID companyId = event.getCompany().getId();
        UUID lotId = event.getEntityId();

        lockCreationKeys(companyId, creation.memberId(), lotId, creation.sourceMovementId());

        SaasMemberBalanceLot existingLot = lots.findById(lotId).orElse(null);
        if (existingLot != null) {
            if (sameImmutableLot(existingLot, companyId, creation)) {
                return;
            }
            throw conflict("El lotId ya existe con datos diferentes: " + lotId);
        }

        SaasMemberBalanceLot existingMovement = lots
                .findByCompanyIdAndSourceMovementId(companyId, creation.sourceMovementId())
                .orElse(null);
        if (existingMovement != null) {
            throw conflict("El sourceMovementId ya pertenece a otro lote: "
                    + creation.sourceMovementId());
        }

        SaasMemberBalanceAccount account = accounts
                .findForUpdate(companyId, creation.memberId())
                .orElseGet(() -> accounts.save(new SaasMemberBalanceAccount(
                        UUID.randomUUID(),
                        companyId,
                        creation.memberId(),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(4),
                        now)));

        account.credit(creation.balanceType(), creation.amount(), now);
        accounts.save(account);
        lots.save(new SaasMemberBalanceLot(
                lotId,
                account,
                creation.balanceType(),
                creation.amount(),
                creation.createdAt(),
                creation.expiresAt(),
                creation.sourceMovementId(),
                creation.documentId()));
    }

    private void lockCreationKeys(
            UUID companyId,
            UUID memberId,
            UUID lotId,
            UUID sourceMovementId) {
        List.of(
                        "COMPANY:" + companyId,
                        "ACCOUNT:" + companyId + ":" + memberId,
                        "LOT:" + lotId,
                        "SOURCE:" + companyId + ":" + sourceMovementId)
                .stream()
                .sorted()
                .forEach(lockKey -> {
                    accounts.ensureProjectionLock(lockKey);
                    accounts.lockProjectionKey(lockKey);
                });
    }

    private static boolean sameImmutableLot(
            SaasMemberBalanceLot lot,
            UUID companyId,
            WalletLotCreation creation) {
        return lot.getCompanyId().equals(companyId)
                && lot.getMemberId().equals(creation.memberId())
                && lot.getBalanceType() == creation.balanceType()
                && lot.getOriginalAmount().compareTo(creation.amount()) == 0
                && lot.getCreatedAt().equals(creation.createdAt())
                && Objects.equals(lot.getExpiresAt(), creation.expiresAt())
                && Objects.equals(lot.getSourceMovementId(), creation.sourceMovementId())
                && Objects.equals(lot.getDocumentId(), creation.documentId());
    }

    private WalletLotCreation parse(Map<String, Object> payload) {
        requireSchemaVersion(payload);
        UUID memberId = requiredUuid(payload, "memberId");
        MemberBalanceType balanceType = requiredBalanceType(payload);
        BigDecimal amount = requiredAmount(payload);
        Instant createdAt = requiredInstant(payload, "createdAt");
        Instant expiresAt = optionalInstant(payload, "expiresAt");
        UUID sourceMovementId = requiredUuid(payload, "sourceMovementId");
        UUID documentId = optionalUuid(payload, "documentId");
        return new WalletLotCreation(
                memberId,
                balanceType,
                amount,
                createdAt,
                expiresAt,
                sourceMovementId,
                documentId);
    }

    private static void requireSchemaVersion(Map<String, Object> payload) {
        Object raw = payload.get("schemaVersion");
        if (!(raw instanceof Number number)) {
            throw badRequest("schemaVersion debe ser el numero 2");
        }
        try {
            if (new BigDecimal(number.toString()).intValueExact() != 2) {
                throw badRequest("schemaVersion no soportado");
            }
        } catch (ArithmeticException exception) {
            throw badRequest("schemaVersion debe ser el numero 2");
        }
    }

    private static MemberBalanceType requiredBalanceType(Map<String, Object> payload) {
        String value = requiredText(payload, "balanceType");
        try {
            return MemberBalanceType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest("balanceType debe ser LOYALTY o RETURN_CREDIT");
        }
    }

    private BigDecimal requiredAmount(Map<String, Object> payload) {
        Object raw = payload.get("amount");
        JsonNode node = mapper.valueToTree(raw);
        if (node == null || node.isNull() || (!node.isNumber() && !node.isTextual())) {
            throw badRequest("amount debe ser un numero JSON o un decimal textual");
        }
        String text = node.asText();
        if (text.isBlank()) {
            throw badRequest("amount no puede estar vacio");
        }
        try {
            BigDecimal amount = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0) {
                throw badRequest("amount debe ser mayor que cero");
            }
            return amount;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw badRequest("amount debe ser un decimal monetario valido con maximo 2 decimales");
        }
    }

    private static UUID requiredUuid(Map<String, Object> payload, String field) {
        String value = requiredText(payload, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " debe ser un UUID valido");
        }
    }

    private static UUID optionalUuid(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            throw badRequest(field + " debe ser null o un UUID valido");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " debe ser null o un UUID valido");
        }
    }

    private static Instant requiredInstant(Map<String, Object> payload, String field) {
        String value = requiredText(payload, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw badRequest(field + " debe ser un instante ISO-8601 valido");
        }
    }

    private static Instant optionalInstant(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            throw badRequest(field + " debe ser null o un instante ISO-8601 valido");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw badRequest(field + " debe ser null o un instante ISO-8601 valido");
        }
    }

    private static String requiredText(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw badRequest(field + " es obligatorio");
        }
        return value;
    }

    static ProjectionException conflict(String reason) {
        return new ProjectionException(HttpStatus.CONFLICT, reason);
    }

    private static ProjectionException badRequest(String reason) {
        return new ProjectionException(HttpStatus.BAD_REQUEST, reason);
    }

    private record WalletLotCreation(
            UUID memberId,
            MemberBalanceType balanceType,
            BigDecimal amount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId,
            UUID documentId) {
    }

    public static final class ProjectionException extends ResponseStatusException {

        private ProjectionException(HttpStatus status, String reason) {
            super(status, reason);
        }
    }
}
