package com.tpverp.saas.document;

import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncEventRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommercialDocumentSyncProjector {

    private final CommercialDocumentProjectionRepository documents;

    public CommercialDocumentSyncProjector(CommercialDocumentProjectionRepository documents) {
        this.documents = documents;
    }

    /** Explicit unsupported or malformed versions must reach validation, never fall back to IGNORED. */
    public boolean supports(SyncEventRequest request) {
        return request != null && "DOCUMENTO".equals(request.entityType())
                && !CommercialDocumentSnapshot.isLegacy(request.payload());
    }

    @Transactional
    public void project(SaasSyncEvent event, SyncEventRequest request) {
        CommercialDocumentSnapshot snapshot = CommercialDocumentSnapshot.parse(request);
        if (event == null || event.getCompany() == null || event.getStore() == null
                || event.getInstallation() == null
                || !event.getStore().getCompany().getId().equals(event.getCompany().getId())
                || !event.getInstallation().getCompany().getId().equals(event.getCompany().getId())
                || !event.getInstallation().getStore().getId().equals(event.getStore().getId())
                || !event.getEventId().equals(request.eventId())
                || !event.getEntityId().equals(request.entityId())
                || !event.getEntityType().equals(request.entityType())
                || event.getOperation() != request.operation()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La procedencia documental no coincide");
        }
        documents.project(event, snapshot, CommercialDocumentQueryMetadata.parse(request));
    }
}
