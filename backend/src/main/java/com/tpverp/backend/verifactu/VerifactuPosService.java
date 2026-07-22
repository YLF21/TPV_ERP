package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuPosService {

    static final int MAX_QUEUE_LIMIT = 50;

    private static final List<FiscalSubmissionStatus> PENDING = List.of(
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIADO);
    private static final List<FiscalSubmissionStatus> SENDING = List.of(
            FiscalSubmissionStatus.ENVIANDO);
    private static final List<FiscalSubmissionStatus> REVIEW_REQUIRED = List.of(
            FiscalSubmissionStatus.RECHAZADO,
            FiscalSubmissionStatus.DEFECTUOSO,
            FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);
    private static final List<FiscalSubmissionStatus> POS_QUEUE_STATUSES = List.of(
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIANDO,
            FiscalSubmissionStatus.ENVIADO,
            FiscalSubmissionStatus.RECHAZADO,
            FiscalSubmissionStatus.DEFECTUOSO,
            FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);

    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final TerminalRepository terminals;
    private final FiscalSubmissionStateRepository states;
    private final VerifactuConfigurationRepository configurations;
    private final LicenseRepository licenses;
    private final VerifactuActivationService activation;
    private final Clock clock;

    public VerifactuPosService(
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            TerminalRepository terminals,
            FiscalSubmissionStateRepository states,
            VerifactuConfigurationRepository configurations,
            LicenseRepository licenses,
            VerifactuActivationService activation,
            Clock clock) {
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.terminals = terminals;
        this.states = states;
        this.configurations = configurations;
        this.licenses = licenses;
        this.activation = activation;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VerifactuPosStatusView status(Authentication authentication) {
        var scope = scope(authentication);
        var pendingCount = count(scope, PENDING);
        var sendingCount = count(scope, SENDING);
        var reviewRequiredCount = count(scope, REVIEW_REQUIRED);
        var active = isActive(scope.store());
        return new VerifactuPosStatusView(
                active,
                presentationStatus(active, pendingCount, sendingCount, reviewRequiredCount),
                pendingCount,
                sendingCount,
                reviewRequiredCount);
    }

    @Transactional(readOnly = true)
    public List<VerifactuPosQueueItem> queue(int requestedLimit, Authentication authentication) {
        if (requestedLimit < 1) {
            throw new IllegalArgumentException("limit debe ser mayor que cero");
        }
        var scope = scope(authentication);
        var effectiveLimit = Math.min(requestedLimit, MAX_QUEUE_LIMIT);
        return states.findPosQueue(
                        scope.companyId(), scope.store().getId(), scope.terminalId(),
                        POS_QUEUE_STATUSES,
                        PageRequest.of(0, effectiveLimit))
                .stream()
                .map(VerifactuPosQueueItem::from)
                .toList();
    }

    private long count(PosScope scope, List<FiscalSubmissionStatus> statuses) {
        return states.countPosQueueByStatusIn(
                scope.companyId(), scope.store().getId(), scope.terminalId(), statuses);
    }

    private PosScope scope(Authentication authentication) {
        Store store = organization.currentStore();
        UUID terminalId = currentTerminal.terminalId(authentication);
        terminals.findByIdAndTiendaId(terminalId, store.getId())
                .filter(terminal -> terminal.isActiva() && terminal.isAprobada())
                .orElseThrow(() -> new IllegalStateException(
                        "La terminal autenticada no pertenece a la tienda activa"));
        return new PosScope(store.getEmpresa().getId(), store, terminalId);
    }

    private boolean isActive(Store store) {
        var configuration = configurations.findByCompanyId(store.getEmpresa().getId());
        if (configuration.map(VerifactuConfiguration::isVoluntarilyActive).orElse(false)) {
            return true;
        }
        return activeLicense(store.getId())
                .map(license -> activation.isAutomaticallyRequired(
                        license.getTaxpayerType(),
                        license.getVerifactuActivationDate(),
                        Instant.now(clock),
                        ZoneId.of(store.getTimezone())))
                .orElse(false);
    }

    private java.util.Optional<License> activeLicense(UUID storeId) {
        return licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId).stream()
                .filter(License::isActiva)
                .findFirst();
    }

    private static VerifactuPosPresentationStatus presentationStatus(
            boolean active,
            long pendingCount,
            long sendingCount,
            long reviewRequiredCount) {
        if (!active) {
            return VerifactuPosPresentationStatus.INACTIVO;
        }
        if (reviewRequiredCount > 0) {
            return VerifactuPosPresentationStatus.REQUIERE_REVISION;
        }
        if (sendingCount > 0) {
            return VerifactuPosPresentationStatus.ENVIANDO;
        }
        if (pendingCount > 0) {
            return VerifactuPosPresentationStatus.PENDIENTES;
        }
        return VerifactuPosPresentationStatus.OPERATIVO;
    }

    private record PosScope(UUID companyId, Store store, UUID terminalId) {
    }
}
