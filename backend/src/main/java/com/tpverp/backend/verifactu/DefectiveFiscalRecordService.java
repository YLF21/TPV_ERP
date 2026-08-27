package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefectiveFiscalRecordService {

    private static final int MAX_VISIBLE_RESULTS = 100;

    private static final List<FiscalSubmissionStatus> VISIBLE_STATUSES = List.of(
            FiscalSubmissionStatus.RECHAZADO,
            FiscalSubmissionStatus.DEFECTUOSO,
            FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);

    private final FiscalSubmissionStateRepository states;
    private final FiscalRecordRepository records;
    private final CurrentOrganization organization;
    private final FiscalQrUrlService qrUrls;
    private final FiscalPrintSnapshotRecordRepository printSnapshots;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private boolean optimizedReads;

    @Deprecated(forRemoval = true)
    public DefectiveFiscalRecordService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            FiscalQrUrlService qrUrls) {
        this(states, records, organization, qrUrls, null, null, null);
        this.optimizedReads = false;
    }

    @Deprecated(forRemoval = true)
    public DefectiveFiscalRecordService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            FiscalQrUrlService qrUrls,
            FiscalPrintSnapshotRecordRepository printSnapshots) {
        this(states, records, organization, qrUrls, printSnapshots, null, null);
        this.optimizedReads = false;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefectiveFiscalRecordService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            FiscalQrUrlService qrUrls,
            FiscalPrintSnapshotRecordRepository printSnapshots,
            InstallationRepository installations,
            LicenseRepository licenses) {
        this.states = states;
        this.records = records;
        this.organization = organization;
        this.qrUrls = qrUrls;
        this.printSnapshots = printSnapshots;
        this.installations = installations;
        this.licenses = licenses;
        // The Spring bean always uses the bounded scoped projection. The two
        // shorter constructors are retained only for legacy unit fixtures.
        this.optimizedReads = true;
    }

    // Lista registros fiscales con incidencia sin bloquear nuevas ventas.
    @Transactional(readOnly = true)
    public List<DefectiveFiscalRecordView> list() {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        if (optimizedReads) {
            var installationId = FiscalInstallationResolver.resolveCurrent(
                    organization, installations, licenses).getId();
            return states.findDefectiveViews(companyId, storeId, installationId,
                    VISIBLE_STATUSES, PageRequest.of(0, MAX_VISIBLE_RESULTS));
        }
        var installationId = installations == null || licenses == null
                ? null
                : FiscalInstallationResolver.resolveCurrent(organization, installations, licenses).getId();
        return states.findAllByStatusInOrderByUpdatedAtDesc(VISIBLE_STATUSES).stream()
                .flatMap(state -> records.findById(state.getRecordId()).stream()
                        .filter(record -> record.getCompanyId().equals(companyId))
                        .filter(record -> record.getStoreId().equals(storeId))
                        .filter(record -> installationId == null
                                || installationId.equals(record.getInstallationId()))
                        .map(record -> DefectiveFiscalRecordView.from(record, state,
                                frozenQrUrl(record))))
                .toList();
    }

    private String frozenQrUrl(FiscalRecord record) {
        if (printSnapshots != null) {
            var frozen = printSnapshots.findByRecordId(record.getId());
            return frozen.map(FiscalPrintSnapshotRecord::getQrUrl).orElse(null);
        }
        return DefectiveFiscalRecordView.qrUrl(record, qrUrls);
    }
}
