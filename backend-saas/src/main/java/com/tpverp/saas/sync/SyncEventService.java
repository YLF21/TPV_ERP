package com.tpverp.saas.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.TokenHasher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncEventService {

    private MemberPointsSyncProjector memberPointsSyncProjector;

    @org.springframework.beans.factory.annotation.Autowired
    void setMemberPointsSyncProjector(MemberPointsSyncProjector memberPointsSyncProjector) {
        this.memberPointsSyncProjector = memberPointsSyncProjector;
    }

    private final SaasInstallationRepository installations;
    private final SaasSyncEventRepository events;
    private final InstallationAuthenticator authenticator;
    private final TokenHasher tokens;
    private final ObjectWriter canonicalPayloadWriter;
    private final MemberWalletSyncProjector walletProjector;
    private final Clock clock;

    public SyncEventService(
            SaasInstallationRepository installations,
            SaasSyncEventRepository events,
            InstallationAuthenticator authenticator,
            TokenHasher tokens,
            ObjectMapper mapper,
            MemberWalletSyncProjector walletProjector,
            Clock clock) {
        this.installations = installations;
        this.events = events;
        this.authenticator = authenticator;
        this.tokens = tokens;
        this.canonicalPayloadWriter = mapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.walletProjector = walletProjector;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = {
            MemberWalletSyncProjector.ProjectionException.class,
            MemberPointsSyncProjector.ProjectionException.class
    })
    public SyncEventReceipt receive(SyncEventRequest request, String token) {
        SaasInstallation installation = authenticate(request, token);
        String payload = canonicalPayload(request);
        String payloadHash = sha256(payload);
        int schemaVersion = schemaVersion(request);

        events.ensureEventLock(request.eventId());
        events.lockEvent(request.eventId());

        Optional<SaasSyncEvent> duplicate = events.findById(request.eventId());
        if (duplicate.isPresent()) {
            SaasSyncEvent existing = duplicate.get();
            if (sameEvent(existing, installation, request, payloadHash)) {
                return new SyncEventReceipt(request.eventId(), true);
            }
            String error = "El eventId ya existe con contenido o procedencia diferente. hashRecibido="
                    + payloadHash;
            existing.recordConflict(error);
            throw MemberWalletSyncProjector.conflict(error);
        }

        InstantHolder received = new InstantHolder(clock.instant());
        SaasSyncEvent event = new SaasSyncEvent(
                request.eventId(),
                installation.getCompany(),
                installation.getStore(),
                installation,
                request.storeSequence(),
                request.entityType(),
                request.entityId(),
                request.operation(),
                payload,
                payloadHash,
                schemaVersion,
                received.value());
        events.save(event);

        if (!(memberPointsSyncProjector.supports(request.entityType(), request.operation()) || walletProjector.supports(request.entityType(), request.operation()))) {
            event.markIgnored(received.value());
            return new SyncEventReceipt(request.eventId(), true);
        }

        try {
            if (memberPointsSyncProjector.supports(request.entityType(), request.operation())) {
                memberPointsSyncProjector.project(event, request.payload(), received.value());
            } else {
                walletProjector.project(event, request.payload(), received.value());
            }
            event.markProjected(received.value());
            return new SyncEventReceipt(request.eventId(), true);
        } catch (MemberWalletSyncProjector.ProjectionException exception) {
            event.markFailed(exception.getReason());
            throw exception;
        } catch (MemberPointsSyncProjector.ProjectionException exception) {
            event.markFailed(exception.getReason());
            throw exception;
        }
    }

    private SaasInstallation authenticate(SyncEventRequest request, String token) {
        String tokenHash = token == null ? "" : tokens.hash(token);
        SaasInstallation installation = installations.findByCompany_Id(request.companyId()).stream()
                .filter(candidate -> request.storeId() == null || candidate.getStore().getId().equals(request.storeId()))
                .filter(candidate -> candidate.hasTokenHash(tokenHash))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Instalacion no autorizada"));
        authenticator.requireToken(installation, token);
        return installation;
    }

    private String canonicalPayload(SyncEventRequest request) {
        try {
            return canonicalPayloadWriter.writeValueAsString(request.payload());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payload sync no serializable", exception);
        }
    }

    private static int schemaVersion(SyncEventRequest request) {
        Object raw = request.payload().get("schemaVersion");
        if (!(raw instanceof Number number)) {
            return 1;
        }
        try {
            int parsed = new BigDecimal(number.toString()).intValueExact();
            return parsed > 0 ? parsed : 1;
        } catch (ArithmeticException exception) {
            return 1;
        }
    }

    private static String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static boolean sameEvent(
            SaasSyncEvent existing,
            SaasInstallation installation,
            SyncEventRequest request,
            String payloadHash) {
        return existing.getCompany().getId().equals(installation.getCompany().getId())
                && Objects.equals(
                        existing.getStore() == null ? null : existing.getStore().getId(),
                        installation.getStore() == null ? null : installation.getStore().getId())
                && Objects.equals(
                        existing.getInstallation() == null ? null : existing.getInstallation().getId(),
                        installation.getId())
                && Objects.equals(existing.getStoreSequence(), request.storeSequence())
                && existing.getEntityType().equals(request.entityType())
                && existing.getEntityId().equals(request.entityId())
                && existing.getOperation() == request.operation()
                && existing.getPayloadHash().equals(payloadHash);
    }

    private record InstantHolder(java.time.Instant value) {
    }
}
