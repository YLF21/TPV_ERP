package com.tpverp.backend.party;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.loyalty.category.MemberCategoryAuthorityGuard;
import com.tpverp.backend.party.loyalty.category.MemberCategoryBootstrapGateway;
import com.tpverp.backend.party.loyalty.category.MemberCategoryOfficialSnapshotApplicationService;
import com.tpverp.backend.party.loyalty.category.MemberCategoryProjectionStateRepository;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class MemberCategoryAuthorityRoutingService {
    private static final int PAGE_SIZE = 500;
    private static final int MAX_SYNC_PAGES = 100;
    private final MemberCategoryAuthorityGuard authority;
    private final CurrentOrganization organization;
    private final MemberBalanceCentralContextResolver centralContexts;
    private final MemberCategoryProjectionStateRepository states;
    private final ObjectProvider<MemberCategoryBootstrapGateway> gateways;
    private final MemberCategoryOfficialSnapshotApplicationService official;
    private final MemberLoyaltyService loyalty;

    public MemberCategoryAuthorityRoutingService(
            MemberCategoryAuthorityGuard authority,
            CurrentOrganization organization,
            MemberBalanceCentralContextResolver centralContexts,
            MemberCategoryProjectionStateRepository states,
            ObjectProvider<MemberCategoryBootstrapGateway> gateways,
            MemberCategoryOfficialSnapshotApplicationService official,
            MemberLoyaltyService loyalty) {
        this.authority = authority;
        this.organization = organization;
        this.centralContexts = centralContexts;
        this.states = states;
        this.gateways = gateways;
        this.official = official;
        this.loyalty = loyalty;
    }

    public boolean centralizedOrThrow() {
        return authority.centralizedOrThrow();
    }

    public MemberLoyaltyService.MemberCategoryView createCategory(
            MemberLoyaltyService.MemberCategoryCommand command) {
        UUID categoryId = UUID.randomUUID();
        var scope = scope();
        var result = gateway().adminCategory(categoryCommand(
                scope, categoryId, normalizeCode(command.name()), command,
                true, "creacion categoria"));
        catchUp(scope, result);
        return category(categoryId);
    }

    public MemberLoyaltyService.MemberCategoryView updateCategory(
            UUID categoryId,
            MemberLoyaltyService.MemberCategoryCommand command) {
        var current = category(categoryId);
        var scope = scope();
        var result = gateway().adminCategory(categoryCommand(
                scope, categoryId, current.code(), command,
                current.active(), "modificacion categoria"));
        catchUp(scope, result);
        return category(categoryId);
    }

    public void deactivateCategory(UUID categoryId) {
        mutateActive(categoryId, false, "desactivacion categoria");
    }

    public MemberLoyaltyService.MemberCategoryView activateCategory(UUID categoryId) {
        mutateActive(categoryId, true, "activacion categoria");
        return category(categoryId);
    }

    public MemberLoyaltyService.MemberView setCategory(
            UUID memberId,
            UUID categoryId,
            boolean lockAutomatic,
            String reason) {
        var scope = scope();
        var result = gateway().adminAssignment(
                new MemberCategoryBootstrapGateway.AdminAssignmentCommand(
                        UUID.randomUUID(), scope.centralCompanyId(), scope.centralStoreId(),
                        scope.actorId(), scope.actorName(), "ADMIN", memberId, categoryId,
                        categoryId != null && lockAutomatic,
                        categoryId == null ? "CLEAR" : "SET", requiredReason(reason)));
        catchUp(scope, result);
        return loyalty.get(memberId);
    }

    private void mutateActive(UUID categoryId, boolean active, String reason) {
        var current = category(categoryId);
        if (current.active() == active) {
            return;
        }
        var command = new MemberLoyaltyService.MemberCategoryCommand(
                current.name(), current.minPoints(), current.discountPercent(),
                current.discountEnabled(), current.manualOnly(), current.sortOrder());
        var scope = scope();
        var result = gateway().adminCategory(categoryCommand(
                scope, categoryId, current.code(), command, active, reason));
        catchUp(scope, result);
    }

    private MemberCategoryBootstrapGateway.AdminCategoryCommand categoryCommand(
            Scope scope,
            UUID categoryId,
            String code,
            MemberLoyaltyService.MemberCategoryCommand command,
            boolean active,
            String reason) {
        return new MemberCategoryBootstrapGateway.AdminCategoryCommand(
                UUID.randomUUID(), scope.centralCompanyId(), scope.centralStoreId(),
                scope.actorId(), scope.actorName(), "ADMIN", categoryId, code,
                command.name(), command.minPoints(), command.discountPercent(),
                command.discountEnabled(), command.manualOnly(), active,
                command.sortOrder(), reason);
    }

    private void catchUp(Scope scope, MemberCategoryBootstrapGateway.AdminResult target) {
        for (int page = 0; page < MAX_SYNC_PAGES; page++) {
            var state = states.findById(scope.localStoreId())
                    .orElseThrow(() -> new IllegalStateException(
                            "No existe la proyeccion local de categorias"));
            boolean configReached = target.configRevision() == null
                    || state.getConfigRevision() >= target.configRevision();
            boolean assignmentReached = target.assignmentRevision() == null
                    || state.getAssignmentRevision() >= target.assignmentRevision();
            if (configReached && assignmentReached) {
                return;
            }
            var feed = gateway().officialFeed(
                    scope.centralCompanyId(), scope.centralStoreId(),
                    state.getConfigRevision(), state.getConfigCursorId(),
                    state.getAssignmentRevision(), state.getAssignmentCursorId(), PAGE_SIZE);
            if (feed.isEmpty()) {
                throw new IllegalStateException(
                        "SaaS acepto el cambio pero aun no lo publico en el feed");
            }
            official.applyFeed(scope.localCompanyId(), scope.localStoreId(), feed);
        }
        throw new IllegalStateException(
                "El cambio central supera el limite de paginas de sincronizacion inmediata");
    }

    private Scope scope() {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var user = organization.currentUser(
                SecurityContextHolder.getContext().getAuthentication());
        if (!user.isProtegido()) {
            throw new IllegalStateException("Se requiere el usuario ADMIN");
        }
        var central = centralContexts.resolve(store.getId());
        return new Scope(
                company.getId(), store.getId(), central.companyId(), central.storeId(),
                user.getId(), user.getNombre());
    }

    private MemberLoyaltyService.MemberCategoryView category(UUID categoryId) {
        return loyalty.categories().stream()
                .filter(value -> value.id().equals(categoryId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "message.member_category.not_found"));
    }

    private MemberCategoryBootstrapGateway gateway() {
        var gateway = gateways.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("tpv.sync.central-url no configurado");
        }
        return gateway;
    }

    private static String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String requiredReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El motivo es obligatorio");
        }
        return value.trim();
    }

    private record Scope(
            UUID localCompanyId,
            UUID localStoreId,
            UUID centralCompanyId,
            UUID centralStoreId,
            UUID actorId,
            String actorName) {
    }
}
