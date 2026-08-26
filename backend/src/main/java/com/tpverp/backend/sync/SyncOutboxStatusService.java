package com.tpverp.backend.sync;

import com.tpverp.backend.organization.CurrentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncOutboxStatusService {

    private final SyncOutboxEventRepository repository;
    private final CurrentOrganization organization;

    public SyncOutboxStatusService(
            SyncOutboxEventRepository repository,
            CurrentOrganization organization) {
        this.repository = repository;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public SyncOutboxStatusView status() {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        return new SyncOutboxStatusView(
                repository.countForStore(companyId, storeId, SyncOutboxStatus.PENDIENTE),
                repository.countForStore(companyId, storeId, SyncOutboxStatus.ENVIANDO),
                repository.countForStore(companyId, storeId, SyncOutboxStatus.ENVIADO),
                repository.countForStore(companyId, storeId, SyncOutboxStatus.ERROR),
                repository.countForStore(companyId, storeId, SyncOutboxStatus.DEAD_LETTER));
    }
}
