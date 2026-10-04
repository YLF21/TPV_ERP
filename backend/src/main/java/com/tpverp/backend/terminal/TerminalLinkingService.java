package com.tpverp.backend.terminal;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TerminalLinkingService {
    private final TerminalRepository terminals;
    private final TerminalPhysicalBindingRepository bindings;
    private final StoreRepository stores;
    private final CurrentOrganization organization;
    private final LicenseRepository licenses;
    private final InstallationRepository installations;
    private final InstallationStatusService installationStatus;
    private final InstallationIdentityStore identity;
    private final PasswordEncoder encoder;
    private final UserSessionRepository sessions;
    private final AuditService audit;
    private final Clock clock;
    private final int developmentMaxWindows;
    private final Duration reservationDuration;

    public TerminalLinkingService(TerminalRepository terminals, TerminalPhysicalBindingRepository bindings,
            StoreRepository stores, CurrentOrganization organization, LicenseRepository licenses,
            InstallationRepository installations, InstallationStatusService installationStatus,
            InstallationIdentityStore identity, PasswordEncoder encoder, UserSessionRepository sessions,
            AuditService audit, Clock clock,
            @Value("${tpv.terminal-linking.development-max-windows:1}") int developmentMaxWindows,
            @Value("${tpv.terminal-linking.reservation-minutes:15}") int reservationMinutes) {
        this.terminals = terminals; this.bindings = bindings; this.stores = stores; this.organization = organization;
        this.licenses = licenses; this.installations = installations; this.installationStatus = installationStatus;
        this.identity = identity; this.encoder = encoder; this.sessions = sessions; this.audit = audit; this.clock = clock;
        this.developmentMaxWindows = Math.max(1, developmentMaxWindows);
        if (reservationMinutes < 1 || reservationMinutes > 1440) throw new IllegalArgumentException("Invalid reservation duration");
        reservationDuration = Duration.ofMinutes(reservationMinutes);
    }

    public Bootstrap bootstrap(String challenge) {
        if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{32,128}")) throw error("INVALID_CHALLENGE");
        var store = lock(singleStore());
        expire(store);
        var installation = installations.findAll().stream().findFirst().orElseThrow(() -> error("INSTALLATION_NOT_READY"));
        var key = identity.loadOrCreate().publicKey().getEncoded();
        if (!MessageDigest.isEqual(key, Base64.getDecoder().decode(installation.getPublicKey())))
            throw error("INSTALLATION_IDENTITY_MISMATCH");
        var signed = identity.sign(("TPV-TERMINAL-LINKING-V1\n" + challenge + "\n" + installation.getId())
                .getBytes(StandardCharsets.UTF_8));
        return new Bootstrap(1, installation.getId(), installation.getReferencia(), store.getId(), store.getNombreEfectivo(),
                Base64.getEncoder().encodeToString(key), challenge, Base64.getEncoder().encodeToString(signed),
                capacity(store), slotViews(store, false));
    }

    public LinkState request(LinkRequest request) {
        validateRequest(request);
        var store = lock(singleStore());
        expire(store);
        var existing = bindings.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            var value = proof(existing.get(), request.credential());
            if (!value.getDeviceId().equals(request.deviceId())
                    || !value.getTerminal().getWorkstationCode().equals(request.code())
                    || !value.getName().equals(request.name().trim()) || !value.getDeviceName().equals(request.deviceName().trim()))
                throw error("LINK_REQUEST_CONFLICT");
            return state(value);
        }
        requireOperational(store);
        int number = codeNumber(request.code());
        if (number == 1) throw error("SERVER_SLOT_PROTECTED");
        if (number > capacity(store)) throw error("WORKSTATION_OUT_OF_QUOTA");
        var terminal = terminals.findByTiendaIdAndWorkstationCode(store.getId(), request.code()).orElse(null);
        if (terminal != null && terminal.getCurrentBindingId() != null) throw error("WORKSTATION_OCCUPIED");
        // Unassigned historical Windows machines still consume their existing license seats.
        requireCapacity(store, null);
        if (bindings.findByTerminalTiendaIdOrderByCreatedAt(store.getId()).stream()
                .anyMatch(value -> value.occupies() && value.getDeviceId().equals(request.deviceId())))
            throw error("DEVICE_ALREADY_BOUND");
        if (terminal == null) {
            var name = request.name().trim();
            if (terminals.findByTiendaIdAndNombreIgnoreCase(store.getId(), name).isPresent())
                throw error("TERMINAL_NAME_EXISTS");
            terminal = Terminal.request(store, name, TerminalType.TERMINAL_VENTA, unusableCredential());
            terminal.assignWorkstationCode(request.code());
            terminal = terminals.saveAndFlush(terminal);
        } else {
            terminal = lockTerminal(terminal);
        }
        var now = now();
        var binding = bindings.saveAndFlush(new TerminalPhysicalBinding(terminal, request.requestId(), request.deviceId(),
                encoder.encode(request.credential()), request.name().trim(), request.deviceName().trim(), now,
                now.plus(reservationDuration)));
        terminal.bind(binding.getId());
        audit(store, "WORKSTATION_REQUESTED", binding);
        return state(binding);
    }

    public LinkState status(Proof request) {
        var store = lock(singleStore());
        expire(store);
        var binding = findProof(request);
        binding.seen(now());
        return state(binding);
    }

    public LinkState cancel(Proof request) {
        var store = lock(singleStore());
        expire(store);
        var binding = findProof(request);
        if (binding.getStatus().equals("PENDING")) end(binding, "CANCELLED");
        else if (binding.occupies()) throw error("LINK_REQUEST_NOT_PENDING");
        return state(binding);
    }

    public ManagementView management() {
        var store = lock(organization.currentStore());
        expire(store);
        return management(store);
    }

    public ManagementView action(String code, UUID expectedBinding, String action) {
        codeNumber(code);
        var store = lock(organization.currentStore());
        expire(store);
        var terminal = terminals.findByTiendaIdAndWorkstationCode(store.getId(), code)
                .orElseThrow(() -> error("WORKSTATION_NOT_FOUND"));
        terminal = lockTerminal(terminal);
        if (code.equals("001")) throw error("SERVER_SLOT_PROTECTED");
        if (expectedBinding == null) throw error("BINDING_REQUIRED");
        var terminalId = terminal.getId();
        var binding = bindings.findById(expectedBinding).filter(value -> value.getTerminal().getId().equals(terminalId))
                .orElseThrow(() -> error("STALE_TERMINAL_BINDING"));
        if (!expectedBinding.equals(terminal.getCurrentBindingId())) {
            if (action.equals("release") && binding.getStatus().equals("RELEASED")
                    || action.equals("cancel") && Set.of("CANCELLED", "EXPIRED").contains(binding.getStatus()))
                return management(store); // Idempotent stale retry cannot touch a replacement.
            throw error("STALE_TERMINAL_BINDING");
        }
        switch (action) {
            case "approve" -> {
                requireOperational(store);
                if (codeNumber(code) > capacity(store)) throw error("WORKSTATION_OUT_OF_QUOTA");
                requireCapacity(store, terminal.getId());
                if (!binding.occupies()) throw error("TERMINAL_BINDING_RETIRED");
                binding.approve(now());
                terminal.rotateCredential(binding.getCredentialHash());
                terminal.approve();
                audit(store, "WORKSTATION_APPROVED", binding);
            }
            case "deactivate" -> {
                if (!Set.of("ACTIVE", "DISABLED").contains(binding.getStatus())) throw error("LINK_REQUEST_NOT_ACTIVE");
                terminal.deactivate(); binding.disable(); revoke(terminal, "WORKSTATION_DISABLED");
                audit(store, "WORKSTATION_DISABLED", binding);
            }
            case "release" -> end(binding, "RELEASED");
            case "cancel" -> {
                if (!binding.getStatus().equals("PENDING")) throw error("LINK_REQUEST_NOT_PENDING");
                end(binding, "CANCELLED");
            }
            default -> throw error("INVALID_WORKSTATION_ACTION");
        }
        return management(store);
    }

    public List<HistoryItem> history(String code) {
        var store = organization.currentStore();
        var terminal = terminals.findByTiendaIdAndWorkstationCode(store.getId(), code)
                .orElseThrow(() -> error("WORKSTATION_NOT_FOUND"));
        return bindings.findByTerminalIdOrderByCreatedAtDesc(terminal.getId()).stream()
                .map(value -> new HistoryItem(value.getId(), value.getRequestId(), value.getDeviceName(), value.getName(),
                        value.getStatus(), value.getCreatedAt(), value.getApprovedAt(), value.getEndedAt())).toList();
    }

    public ManagementView assignLegacy(UUID id, String code) {
        var store = lock(organization.currentStore());
        expire(store); requireOperational(store);
        int number = codeNumber(code);
        if (number <= 1 || number > capacity(store)) throw error("WORKSTATION_OUT_OF_QUOTA");
        var terminal = terminals.findForCashSessionPreparation(id, store.getId())
                .orElseThrow(() -> error("WORKSTATION_NOT_FOUND"));
        if (terminal.getTipo() != TerminalType.TERMINAL_VENTA) throw error("INVALID_WORKSTATION_TYPE");
        if (code.equals(terminal.getWorkstationCode())) return management(store);
        if (terminal.getWorkstationCode() != null) throw error("WORKSTATION_ALREADY_ASSIGNED");
        if (terminals.findByTiendaIdAndWorkstationCode(store.getId(), code).isPresent()) throw error("WORKSTATION_OCCUPIED");
        requireCapacity(store, terminal.getId());
        terminal.assignWorkstationCode(code);
        var now = now();
        var binding = new TerminalPhysicalBinding(terminal, UUID.randomUUID(), UUID.randomUUID(), terminal.getCredentialHash(),
                terminal.getNombre(), terminal.getNombre(), now, now.plus(reservationDuration));
        if (terminal.isAprobada()) {
            binding.approve(now);
            if (!terminal.isActiva()) binding.disable();
        }
        binding.awaitLegacyAdoption();
        bindings.saveAndFlush(binding);
        terminal.bind(binding.getId());
        revoke(terminal, "WORKSTATION_LEGACY_ASSIGNED");
        audit(store, "WORKSTATION_LEGACY_ASSIGNED", binding);
        return management(store);
    }

    /** Existing local identity proves possession; no secret rotation on adoption/restart. */
    public LinkState adoptServer(ServerAdoption request, boolean local, UserAccount administrator) {
        if (!local) throw forbidden("SERVER_ADOPTION_REQUIRES_LOOPBACK");
        validateAdoption(request);
        var store = lock(singleStore());
        var terminal = terminals.findByTiendaIdAndTipo(store.getId(), TerminalType.SERVIDOR).orElse(null);
        boolean admin = administrator != null && administrator.isActivo() && administrator.isProtegido()
                && administrator.getTienda() == null;
        if (!admin && (terminal == null || !terminal.getId().equals(request.terminalId())
                || !encoder.matches(request.credential(), terminal.getCredentialHash())))
            throw forbidden("SERVER_IDENTITY_REQUIRED");
        var existing = bindings.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            var binding = proof(existing.get(), request.credential());
            if (!binding.getDeviceId().equals(request.deviceId()) || !"001".equals(binding.getTerminal().getWorkstationCode()))
                throw error("LINK_REQUEST_CONFLICT");
            return state(binding);
        }
        if (bindings.findByTerminalTiendaIdOrderByCreatedAt(store.getId()).stream()
                .anyMatch(value -> value.occupies() && value.getDeviceId().equals(request.deviceId())
                        && !"001".equals(value.getTerminal().getWorkstationCode())))
            throw error("DEVICE_ALREADY_BOUND");
        if (terminal == null) terminal = terminals.saveAndFlush(new Terminal(store, "001", TerminalType.SERVIDOR,
                encoder.encode(request.credential())));
        terminal = lockTerminal(terminal);
        if (terminal.getCurrentBindingId() != null) {
            var binding = bindings.findById(terminal.getCurrentBindingId()).orElseThrow();
            if (!admin) {
                if (binding.getDeviceId().equals(request.deviceId())) return state(binding);
                throw error("DEVICE_ALREADY_BOUND");
            }
            binding.end("RELEASED", now());
            bindings.flush();
        }
        if (admin) terminal.rotateCredential(encoder.encode(request.credential()));
        terminal.assignWorkstationCode("001");
        var binding = new TerminalPhysicalBinding(terminal, request.requestId(), request.deviceId(),
                terminal.getCredentialHash(), request.name() == null ? terminal.getNombre() : request.name().trim(), request.deviceName().trim(), now(), null);
        binding.approve(now());
        bindings.saveAndFlush(binding);
        terminal.bind(binding.getId()); terminal.approve();
        revoke(terminal, "SERVER_BINDING_ADOPTED");
        audit(store, "SERVER_BINDING_ADOPTED", binding);
        return state(binding);
    }

    public LinkState adoptLegacy(ServerAdoption request) {
        validateAdoption(request);
        var store = lock(singleStore());
        expire(store);
        var terminal = terminals.findForCashSessionPreparation(request.terminalId(), store.getId())
                .orElseThrow(() -> forbidden("LINK_PROOF_INVALID"));
        if (terminal.getTipo() != TerminalType.TERMINAL_VENTA || terminal.getWorkstationCode() == null
                || terminal.getCurrentBindingId() == null || !encoder.matches(request.credential(), terminal.getCredentialHash()))
            throw forbidden("LINK_PROOF_INVALID");
        var binding = bindings.findById(terminal.getCurrentBindingId()).orElseThrow();
        proof(binding, request.credential());
        if (!binding.occupies()) throw forbidden("LINK_PROOF_INVALID");
        if (!binding.isLegacyAdoptionPending()) {
            if (binding.getRequestId().equals(request.requestId()) && binding.getDeviceId().equals(request.deviceId())) return state(binding);
            throw error("DEVICE_ALREADY_BOUND");
        }
        if (bindings.findByRequestId(request.requestId()).isPresent()) throw error("LINK_REQUEST_CONFLICT");
        if (bindings.findByTerminalTiendaIdOrderByCreatedAt(store.getId()).stream()
                .anyMatch(value -> value.occupies() && value.getDeviceId().equals(request.deviceId())))
            throw error("DEVICE_ALREADY_BOUND");
        binding.adoptLegacy(request.requestId(), request.deviceId(), request.deviceName().trim());
        audit(store, "WORKSTATION_LEGACY_ADOPTED", binding);
        return state(binding);
    }

    private void expire(Store store) {
        for (var value : bindings.findByTerminalTiendaIdOrderByCreatedAt(store.getId()))
            if (value.getStatus().equals("PENDING") && !now().isBefore(value.getExpiresAt())) end(value, "EXPIRED");
        bindings.flush();
    }
    private void end(TerminalPhysicalBinding binding, String status) {
        var terminal = lockTerminal(binding.getTerminal());
        if (terminal.getWorkstationCode().equals("001")) throw error("SERVER_SLOT_PROTECTED");
        binding.end(status, now());
        if (binding.getId().equals(terminal.getCurrentBindingId())) {
            terminal.endBinding(unusableCredential());
            revoke(terminal, "WORKSTATION_" + status);
        }
        audit(terminal.getTienda(), "WORKSTATION_" + status, binding);
    }
    private void revoke(Terminal terminal, String reason) {
        sessions.findByTerminalIdAndRevocadaEnIsNull(terminal.getId())
                .forEach(value -> value.revocar(value.getUsuario(), reason, now()));
    }
    private Terminal lockTerminal(Terminal terminal) {
        return terminals.findForCashSessionPreparation(terminal.getId(), terminal.getTienda().getId()).orElseThrow();
    }
    private TerminalPhysicalBinding findProof(Proof request) {
        if (request.requestId() == null || request.credential() == null || request.credential().length() > 256)
            throw forbidden("LINK_PROOF_INVALID");
        return proof(bindings.findByRequestId(request.requestId()).orElseThrow(() -> forbidden("LINK_PROOF_INVALID")), request.credential());
    }
    private TerminalPhysicalBinding proof(TerminalPhysicalBinding binding, String credential) {
        if (!encoder.matches(credential, binding.getCredentialHash())) throw forbidden("LINK_PROOF_INVALID");
        return binding;
    }
    private ManagementView management(Store store) {
        return new ManagementView(capacity(store), slotViews(store, true), terminals.findAllByTiendaIdOrderByNombre(store.getId())
                .stream().filter(value -> value.getTipo() == TerminalType.TERMINAL_VENTA && value.getWorkstationCode() == null)
                .map(value -> new LegacyTerminal(value.getId(), value.getNombre(), value.getTipo(), value.isAprobada(), value.isActiva())).toList());
    }
    private List<Slot> slotViews(Store store, boolean management) {
        int capacity = capacity(store);
        var indexed = new TreeMap<Integer, Terminal>();
        terminals.findAllByTiendaIdOrderByNombre(store.getId()).stream().filter(value -> value.getWorkstationCode() != null)
                .forEach(value -> indexed.put(codeNumber(value.getWorkstationCode()), value));
        var current = new HashMap<UUID, TerminalPhysicalBinding>();
        bindings.findByTerminalTiendaIdOrderByCreatedAt(store.getId()).stream().filter(TerminalPhysicalBinding::occupies)
                .forEach(value -> current.put(value.getTerminal().getId(), value));
        var numbers = new TreeSet<Integer>(indexed.keySet());
        for (int i = 1; i <= capacity; i++) numbers.add(i);
        var result = new ArrayList<Slot>();
        for (int number : numbers) {
            var terminal = indexed.get(number);
            var binding = terminal == null ? null : current.get(terminal.getId());
            String status = binding == null ? (number == 1 ? "RESERVED" : "FREE") : binding.getStatus();
            result.add(new Slot(formatCode(number), status, binding != null ? binding.getName() : terminal == null ? null : terminal.getNombre(),
                    terminal == null ? null : terminal.getId(), binding == null ? null : binding.getId(),
                    binding == null ? null : binding.getExpiresAt(), number > capacity,
                    !management || binding == null ? null : binding.getDeviceName(),
                    !management || binding == null ? null : binding.getLastSeenAt()));
        }
        return result;
    }
    private LinkState state(TerminalPhysicalBinding binding) {
        var terminal = binding.getTerminal();
        return new LinkState(binding.getRequestId(), binding.getId(), terminal.getId(), terminal.getWorkstationCode(), binding.getName(),
                terminal.getTienda().getId(), terminal.getTienda().getNombreEfectivo(), installationStatus.status().id(),
                binding.getStatus(), binding.getExpiresAt());
    }
    private int capacity(Store store) {
        if (installationStatus.statusForStore(store.getId()).mode() == OperationalMode.DEVELOPMENT) return developmentMaxWindows;
        return licenses.findFirstByTienda_IdAndActivaTrueOrderByValidaDesdeDesc(store.getId()).map(License::getMaxWindows).orElse(1);
    }
    private void requireOperational(Store store) {
        var mode = installationStatus.statusForStore(store.getId()).mode();
        if (mode != OperationalMode.LICENSED && mode != OperationalMode.OFFLINE && mode != OperationalMode.DEVELOPMENT)
            throw new TerminalLinkingException(HttpStatus.LOCKED, "LICENSE_REQUIRED");
    }
    private void requireCapacity(Store store, UUID exclude) {
        long used = 1; // The backend PC always occupies slot 001, including before adoption.
        for (var terminal : terminals.findAllByTiendaIdOrderByNombre(store.getId())) {
            if (terminal.getTipo() != TerminalType.TERMINAL_VENTA || terminal.getId().equals(exclude)) continue;
            if (terminal.getCurrentBindingId() != null || terminal.getWorkstationCode() == null && terminal.isActiva()) used++;
        }
        if (used >= capacity(store)) throw error("WORKSTATION_QUOTA_REACHED");
    }
    private Store singleStore() {
        var values = stores.findAll();
        if (values.size() != 1) throw error("LINKING_REQUIRES_SINGLE_STORE");
        return values.getFirst();
    }
    private Store lock(Store store) { return stores.findByIdForUpdate(store.getId()).orElseThrow(); }
    private void audit(Store store, String event, TerminalPhysicalBinding binding) {
        audit.recordForStore(store, event, AuditResult.EXITO, Map.of("terminalId", binding.getTerminal().getId(),
                "bindingId", binding.getId(), "code", binding.getTerminal().getWorkstationCode(), "requestId", binding.getRequestId()));
    }
    static String formatCode(int number) { return String.format(Locale.ROOT, "%03d", number); }
    static int codeNumber(String code) {
        if (code == null || !code.matches("[0-9]{3,9}")) throw error("INVALID_WORKSTATION_CODE");
        int value = Integer.parseInt(code);
        if (value < 1 || !formatCode(value).equals(code)) throw error("INVALID_WORKSTATION_CODE");
        return value;
    }
    private static void validateRequest(LinkRequest request) {
        validateIdentity(request.requestId(), request.deviceId(), request.credential(), request.deviceName());
        codeNumber(request.code()); text(request.name());
    }
    private static void validateIdentity(UUID requestId, UUID deviceId, String credential, String deviceName) {
        if (requestId == null || deviceId == null || credential == null || credential.length() < 32 || credential.length() > 72)
            throw error("INVALID_LINK_REQUEST");
        text(deviceName);
    }
    private static void validateAdoption(ServerAdoption request) {
        if (request.requestId() == null || request.deviceId() == null || request.credential() == null
                || request.credential().isBlank() || request.credential().length() > 72) throw error("INVALID_LINK_REQUEST");
        text(request.deviceName());
        if (request.name() != null) text(request.name());
    }
    private static void text(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 128 || value.chars().anyMatch(Character::isISOControl))
            throw error("INVALID_LINK_REQUEST");
    }
    private String unusableCredential() { return encoder.encode(UUID.randomUUID().toString() + UUID.randomUUID()); }
    private Instant now() { return Instant.now(clock); }
    private static TerminalLinkingException error(String code) { return new TerminalLinkingException(HttpStatus.CONFLICT, code); }
    private static TerminalLinkingException forbidden(String code) { return new TerminalLinkingException(HttpStatus.FORBIDDEN, code); }

    public record LinkRequest(UUID requestId, UUID deviceId, String credential, String code, String name, String deviceName) { }
    public record Proof(UUID requestId, String credential) { }
    public record ServerAdoption(UUID requestId, UUID deviceId, String credential, String deviceName, UUID terminalId, String name) {
        public ServerAdoption(UUID requestId, UUID deviceId, String credential, String deviceName, UUID terminalId) {
            this(requestId, deviceId, credential, deviceName, terminalId, null);
        }
    }
    public record LinkState(UUID requestId, UUID bindingId, UUID terminalId, String terminalCode, String terminalName,
            UUID storeId, String storeName, UUID installationId, String status, Instant expiresAt) { }
    public record Slot(String code, String status, String name, UUID terminalId, UUID bindingId, Instant expiresAt,
            boolean outOfQuota, String deviceName, Instant lastSeenAt) { }
    public record Bootstrap(int protocolVersion, UUID installationId, String installationReference, UUID storeId, String storeName,
            String publicKey, String challenge, String signature, int maxWindows, List<Slot> slots) { }
    public record ManagementView(int maxWindows, List<Slot> slots, List<LegacyTerminal> legacyTerminals) { }
    public record LegacyTerminal(UUID id, String name, TerminalType type, boolean approved, boolean active) { }
    public record HistoryItem(UUID bindingId, UUID requestId, String deviceName, String name, String status,
            Instant createdAt, Instant approvedAt, Instant endedAt) { }
}
