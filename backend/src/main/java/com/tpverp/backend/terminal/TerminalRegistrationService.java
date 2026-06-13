package com.tpverp.backend.terminal;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.Licencia;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.shared.access.OperationalMode;
import com.tpverp.backend.security.domain.SesionRepository;
import java.time.Clock;
import java.time.Instant;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.ResultadoAuditoria;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class TerminalRegistrationService {

    private final TerminalRepository terminalRepository;
    private final TiendaRepository tiendaRepository;
    private final LicenciaRepository licenciaRepository;
    private final InstallationStatusService installationStatusService;
    private final PasswordEncoder passwordEncoder;
    private final SesionRepository sesionRepository;
    private final Clock clock;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public TerminalRegistrationService(
            TerminalRepository terminalRepository,
            TiendaRepository tiendaRepository,
            LicenciaRepository licenciaRepository,
            InstallationStatusService installationStatusService,
            PasswordEncoder passwordEncoder,
            SesionRepository sesionRepository,
            Clock clock,
            AuditService auditService) {
        this.terminalRepository = terminalRepository;
        this.tiendaRepository = tiendaRepository;
        this.licenciaRepository = licenciaRepository;
        this.installationStatusService = installationStatusService;
        this.passwordEncoder = passwordEncoder;
        this.sesionRepository = sesionRepository;
        this.clock = clock;
        this.auditService = auditService;
    }

    @Transactional
    public RegistrationResult request(String name, TipoTerminal type) {
        Tienda store = currentStore();
        if (type == null || type == TipoTerminal.SERVIDOR) {
            throw new IllegalArgumentException("Solo se pueden solicitar terminales Windows o PDA");
        }
        if (terminalRepository.findByTiendaIdAndNombreIgnoreCase(store.getId(), name).isPresent()) {
            throw new IllegalArgumentException("Ya existe una terminal con ese nombre");
        }
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        Terminal terminal = terminalRepository.save(
                Terminal.solicitar(store, name, type, passwordEncoder.encode(credential)));
        auditService.record(
                "TERMINAL_REQUESTED",
                ResultadoAuditoria.EXITO,
                Map.of("terminalId", terminal.getId(), "type", type.name()));
        return new RegistrationResult(terminal.getId(), credential, "PENDING");
    }

    @Transactional
    public TerminalItem approve(UUID terminalId) {
        if (installationStatusService.status().mode() == OperationalMode.RESTRICTED) {
            throw new IllegalStateException("No se pueden aprobar terminales sin demo o licencia valida");
        }
        Terminal terminal = terminalRepository.findById(terminalId)
                .orElseThrow(() -> new IllegalArgumentException("Terminal no encontrada"));
        if (!terminal.isAprobada()) {
            validateQuota(terminal);
            terminal.aprobar();
            auditService.record(
                    "TERMINAL_APPROVED",
                    ResultadoAuditoria.EXITO,
                    Map.of("terminalId", terminal.getId(), "type", terminal.getTipo().name()));
        }
        return TerminalItem.from(terminal);
    }

    @Transactional
    public void deactivate(UUID terminalId) {
        Terminal terminal = terminalRepository.findById(terminalId)
                .orElseThrow(() -> new IllegalArgumentException("Terminal no encontrada"));
        terminal.desactivar();
        Instant now = Instant.now(clock);
        sesionRepository.findByTerminalIdAndRevocadaEnIsNull(terminalId)
                .forEach(session -> session.revocar(session.getUsuario(), "TERMINAL_DISABLED", now));
        auditService.record(
                "TERMINAL_DISABLED",
                ResultadoAuditoria.EXITO,
                Map.of("terminalId", terminalId));
    }

    @Transactional(readOnly = true)
    public List<TerminalItem> list() {
        return terminalRepository.findAll().stream().map(TerminalItem::from).toList();
    }

    private void validateQuota(Terminal candidate) {
        if (installationStatusService.status().mode() != OperationalMode.LICENSED) {
            return;
        }
        Licencia license = licenciaRepository.findAll().stream()
                .filter(Licencia::isActiva)
                .findFirst()
                .orElseThrow();
        List<Terminal> active = terminalRepository.findByTiendaIdAndActivaTrue(currentStore().getId());
        if (candidate.getTipo() == TipoTerminal.PDA) {
            long pda = active.stream().filter(value -> value.getTipo() == TipoTerminal.PDA).count();
            if (pda >= license.getMaxPda()) {
                throw new IllegalStateException("Se ha alcanzado el cupo de PDA");
            }
        } else {
            long windows = active.stream().filter(value -> value.getTipo() != TipoTerminal.PDA).count();
            if (windows >= license.getMaxWindows()) {
                throw new IllegalStateException("Se ha alcanzado el cupo de equipos Windows");
            }
        }
    }

    private Tienda currentStore() {
        return tiendaRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La tienda no esta inicializada"));
    }

    public record RegistrationResult(UUID terminalId, String credential, String status) {
    }

    public record TerminalItem(UUID id, String name, TipoTerminal type, boolean approved, boolean active) {
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
