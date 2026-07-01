package com.tpverp.saas.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.TokenHasher;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncEventService {

    private final SaasInstallationRepository installations;
    private final SaasSyncEventRepository events;
    private final InstallationAuthenticator authenticator;
    private final TokenHasher tokens;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SyncEventService(
            SaasInstallationRepository installations,
            SaasSyncEventRepository events,
            InstallationAuthenticator authenticator,
            TokenHasher tokens,
            ObjectMapper mapper,
            Clock clock) {
        this.installations = installations;
        this.events = events;
        this.authenticator = authenticator;
        this.tokens = tokens;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public SyncEventReceipt receive(SyncEventRequest request, String token) {
        if (events.existsById(request.eventId())) {
            return new SyncEventReceipt(request.eventId(), true);
        }
        String tokenHash = token == null ? "" : tokens.hash(token);
        SaasInstallation installation = installations.findByCompany_Id(request.companyId()).stream()
                .filter(candidate -> request.storeId() == null || candidate.getStore().getId().equals(request.storeId()))
                .filter(candidate -> candidate.hasTokenHash(tokenHash))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Instalacion no autorizada"));
        authenticator.requireToken(installation, token);
        try {
            events.save(new SaasSyncEvent(
                    request.eventId(),
                    installation.getCompany(),
                    installation.getStore(),
                    installation,
                    request.entityType(),
                    request.entityId(),
                    request.operation(),
                    mapper.writeValueAsString(request.payload()),
                    clock.instant()));
            return new SyncEventReceipt(request.eventId(), true);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo guardar evento sync", exception);
        }
    }
}
