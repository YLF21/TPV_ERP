package com.tpverp.backend.terminal;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class TerminalRegistrationService {

    private final TerminalRepository terminalRepository;
    private final PdaPairingGrantRepository pairingRepository;
    private final StoreRepository tiendaRepository;
    private final CurrentOrganization organization;
    private final LicenseRepository licenciaRepository;
    private final InstallationStatusService installationStatusService;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionRepository sesionRepository;
    private final Clock clock;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public TerminalRegistrationService(
            TerminalRepository terminalRepository,
            PdaPairingGrantRepository pairingRepository,
            StoreRepository tiendaRepository,
            CurrentOrganization organization,
            LicenseRepository licenciaRepository,
            InstallationStatusService installationStatusService,
            PasswordEncoder passwordEncoder,
            UserSessionRepository sesionRepository,
            Clock clock,
            AuditService auditService) {
        this.terminalRepository = terminalRepository;
        this.pairingRepository = pairingRepository;
        this.tiendaRepository = tiendaRepository;
        this.organization = organization;
        this.licenciaRepository = licenciaRepository;
        this.installationStatusService = installationStatusService;
        this.passwordEncoder = passwordEncoder;
        this.sesionRepository = sesionRepository;
        this.clock = clock;
        this.auditService = auditService;
    }

    @Transactional
    public RegistrationResult request(UUID storeId, String name, TerminalType type) {
        Store store = tiendaRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("message.organization.store_not_found"));
        if (type == null || type == TerminalType.SERVIDOR) {
            throw new IllegalArgumentException("message.terminal.only_windows_or_pda_can_request");
        }
        if (terminalRepository.findByTiendaIdAndNombreIgnoreCase(store.getId(), name).isPresent()) {
            throw new IllegalArgumentException("Ya existe una terminal con ese nombre");
        }
        String credential = newCredential();
        Terminal terminal = terminalRepository.save(
                Terminal.request(store, name, type, passwordEncoder.encode(credential)));
        auditService.record(
                "TERMINAL_REQUESTED",
                AuditResult.EXITO,
                Map.of("terminalId", terminal.getId(), "type", type.name()));
        return new RegistrationResult(terminal.getId(), credential, "PENDING");
    }

    @Transactional
    public PdaRegistrationResult requestPda(String name) {
        var stores = tiendaRepository.findAll();
        if (stores.size() != 1) {
            throw new IllegalStateException("message.terminal.pda_request_requires_single_store");
        }
        var store = stores.getFirst();
        var terminalName = name.trim();
        var registration = request(store.getId(), terminalName, TerminalType.PDA);
        return new PdaRegistrationResult(
                registration.terminalId(), terminalName, store.getNombreEfectivo(),
                registration.credential(), registration.status());
    }

    @Transactional
    public PairingCodeResult createPdaPairingCode(UUID terminalId) {
        var terminal = currentTerminal(terminalId);
        if (terminal.getTipo() != TerminalType.PDA || !terminal.isAprobada() || !terminal.isActiva()) {
            throw new IllegalStateException("message.terminal.pda_pairing_requires_active_pda");
        }
        var now = Instant.now(clock);
        pairingRepository.findActiveForUpdate(terminalId).forEach(grant -> grant.cancel(now));
        var rawCode = newPairingCode();
        var expiresAt = now.plus(Duration.ofMinutes(10));
        pairingRepository.save(new PdaPairingGrant(terminal, hashPairingCode(rawCode), now, expiresAt));
        auditService.record(
                "PDA_PAIRING_CODE_CREATED", AuditResult.EXITO,
                Map.of("terminalId", terminal.getId(), "expiresAt", expiresAt));
        return new PairingCodeResult(displayPairingCode(rawCode), expiresAt);
    }

    @Transactional
    public PdaRegistrationResult linkPda(String code) {
        var normalizedCode = normalizePairingCode(code);
        var grant = pairingRepository.findForUpdateByCodeHash(hashPairingCode(normalizedCode))
                .orElseThrow(() -> new IllegalArgumentException("message.terminal.pda_pairing_invalid"));
        var now = Instant.now(clock);
        var terminal = grant.consume(now);
        if (terminal.getTipo() != TerminalType.PDA || !terminal.isAprobada() || !terminal.isActiva()) {
            throw new IllegalStateException("message.terminal.pda_pairing_requires_active_pda");
        }
        var credential = newCredential();
        terminal.rotateCredential(passwordEncoder.encode(credential));
        sesionRepository.findByTerminalIdAndRevocadaEnIsNull(terminal.getId())
                .forEach(session -> session.revocar(session.getUsuario(), "PDA_RELINKED", now));
        auditService.recordForStore(
                terminal.getTienda(), "PDA_RELINKED", AuditResult.EXITO,
                Map.of("terminalId", terminal.getId()));
        return new PdaRegistrationResult(
                terminal.getId(), terminal.getNombre(), terminal.getTienda().getNombreEfectivo(),
                credential, "APPROVED");
    }

    @Transactional
    public ServerProvisioningResult provisionServer(UserAccount administrator) {
        if (administrator == null || !administrator.isProtegido() || administrator.getTienda() != null) {
            throw new IllegalStateException("message.terminal.server_provision_requires_installation_admin");
        }
        var stores = tiendaRepository.findAll();
        if (stores.size() != 1) {
            throw new IllegalStateException("message.terminal.server_provision_requires_single_store");
        }
        var store = stores.getFirst();
        String credential = newCredential();
        var terminal = terminalRepository.findByTiendaIdAndTipo(store.getId(), TerminalType.SERVIDOR)
                .map(existing -> {
                    existing.rotateCredential(passwordEncoder.encode(credential));
                    existing.approve();
                    return existing;
                })
                .orElseGet(() -> terminalRepository.save(new Terminal(
                        store,
                        "SERVIDOR",
                        TerminalType.SERVIDOR,
                        passwordEncoder.encode(credential))));
        auditService.recordForStore(
                store,
                "SERVER_TERMINAL_PROVISIONED",
                AuditResult.EXITO,
                Map.of("terminalId", terminal.getId(), "storeId", store.getId()));
        return new ServerProvisioningResult(
                terminal.getId(), terminal.getNombre(), store.getNombreEfectivo(), credential);
    }

    @Transactional
    public TerminalItem approve(UUID terminalId) {
        if (installationStatusService.status().mode() == OperationalMode.RESTRICTED) {
            throw new IllegalStateException("message.terminal.approval_requires_demo_or_license");
        }
        UUID storeId = currentStore().getId();
        // Serializa las aprobaciones de una misma tienda para que dos altas
        // simultaneas no puedan superar el cupo Windows/PDA observado.
        tiendaRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "message.organization.store_not_found"));
        Terminal terminal = terminalRepository.findByIdAndTiendaId(terminalId, storeId)
                .orElseThrow(() -> new IllegalArgumentException("message.terminal.not_found"));
        if (!terminal.isAprobada()) {
            validateQuota(terminal);
            terminal.approve();
            auditService.record(
                    "TERMINAL_APPROVED",
                    AuditResult.EXITO,
                    Map.of("terminalId", terminal.getId(), "type", terminal.getTipo().name()));
        }
        return TerminalItem.from(terminal);
    }

    @Transactional
    public void deactivate(UUID terminalId) {
        Terminal terminal = currentTerminal(terminalId);
        terminal.deactivate();
        Instant now = Instant.now(clock);
        sesionRepository.findByTerminalIdAndRevocadaEnIsNull(terminalId)
                .forEach(session -> session.revocar(session.getUsuario(), "TERMINAL_DISABLED", now));
        auditService.record(
                "TERMINAL_DISABLED",
                AuditResult.EXITO,
                Map.of("terminalId", terminalId));
    }

    @Transactional(readOnly = true)
    public List<TerminalItem> list() {
        return terminalRepository.findAllByTiendaIdOrderByNombre(currentStore().getId())
                .stream().map(TerminalItem::from).toList();
    }

    private void validateQuota(Terminal candidate) {
        if (installationStatusService.status().mode() != OperationalMode.LICENSED) {
            return;
        }
        var store = currentStore();
        License license = licenciaRepository.findByTiendaIdOrderByValidaDesdeDesc(store.getId())
                .stream()
                .filter(License::isActiva)
                .findFirst()
                .orElseThrow();
        List<Terminal> active = terminalRepository.findByTiendaIdAndActivaTrue(currentStore().getId());
        if (candidate.getTipo() == TerminalType.PDA) {
            long pda = active.stream().filter(value -> value.getTipo() == TerminalType.PDA).count();
            if (pda >= license.getMaxPda()) {
                throw new IllegalStateException("Se ha alcanzado el cupo de PDA");
            }
        } else {
            long windows = active.stream().filter(value -> value.getTipo() != TerminalType.PDA).count();
            if (windows >= license.getMaxWindows()) {
                throw new IllegalStateException("Se ha alcanzado el cupo de equipos Windows");
            }
        }
    }

    private Store currentStore() {
        return organization.currentStore();
    }

    private String newCredential() {
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    }

    private String newPairingCode() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var code = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private String normalizePairingCode(String code) {
        var normalized = code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalized.length() != 8) {
            throw new IllegalArgumentException("message.terminal.pda_pairing_invalid");
        }
        return normalized;
    }

    private String hashPairingCode(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String displayPairingCode(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    private Terminal currentTerminal(UUID terminalId) {
        return terminalRepository.findByIdAndTiendaId(terminalId, currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.terminal.not_found"));
    }

    public record RegistrationResult(UUID terminalId, String credential, String status) {
    }

    public record PdaRegistrationResult(
            UUID terminalId,
            String terminalCode,
            String storeName,
            String terminalCredential,
            String status) {
    }

    public record PairingCodeResult(String code, Instant expiresAt) {
    }

    public record ServerProvisioningResult(
            UUID terminalId,
            String terminalCode,
            String storeName,
            String terminalCredential) {
    }

    public record TerminalItem(UUID id, String name, TerminalType type, boolean approved, boolean active) {
        static TerminalItem from(Terminal terminal) {
            return new TerminalItem(
                    terminal.getId(),
                    terminal.getNombre(),
                    terminal.getTipo(),
                    terminal.isAprobada(),
                    terminal.isActiva());
        }
    }
}
