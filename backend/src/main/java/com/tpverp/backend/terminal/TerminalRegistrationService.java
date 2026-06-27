package com.tpverp.backend.terminal;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.security.domain.UserSessionRepository;
import java.time.Clock;
import java.time.Instant;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class TerminalRegistrationService {

    private final TerminalRepository terminalRepository;
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
            StoreRepository tiendaRepository,
            CurrentOrganization organization,
            LicenseRepository licenciaRepository,
            InstallationStatusService installationStatusService,
            PasswordEncoder passwordEncoder,
            UserSessionRepository sesionRepository,
            Clock clock,
            AuditService auditService) {
        this.terminalRepository = terminalRepository;
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
                .orElseThrow(() -> new IllegalArgumentException("Store no encontrada"));
        if (type == null || type == TerminalType.SERVIDOR) {
            throw new IllegalArgumentException("Solo se pueden request terminales Windows o PDA");
        }
        if (terminalRepository.findByTiendaIdAndNombreIgnoreCase(store.getId(), name).isPresent()) {
            throw new IllegalArgumentException("Ya existe una terminal con ese nombre");
        }
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        Terminal terminal = terminalRepository.save(
                Terminal.request(store, name, type, passwordEncoder.encode(credential)));
        auditService.record(
                "TERMINAL_REQUESTED",
                AuditResult.EXITO,
                Map.of("terminalId", terminal.getId(), "type", type.name()));
        return new RegistrationResult(terminal.getId(), credential, "PENDING");
    }

    @Transactional
    public TerminalItem approve(UUID terminalId) {
        if (installationStatusService.status().mode() == OperationalMode.RESTRICTED) {
            throw new IllegalStateException("No se pueden approve terminales sin demo o licencia valida");
        }
        Terminal terminal = currentTerminal(terminalId);
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

    private Terminal currentTerminal(UUID terminalId) {
        return terminalRepository.findByIdAndTiendaId(terminalId, currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Terminal no encontrada"));
    }

    public record RegistrationResult(UUID terminalId, String credential, String status) {
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
