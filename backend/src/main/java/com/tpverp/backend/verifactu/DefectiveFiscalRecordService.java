package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefectiveFiscalRecordService {

    private static final List<FiscalSubmissionStatus> VISIBLE_STATUSES = List.of(
            FiscalSubmissionStatus.RECHAZADO,
            FiscalSubmissionStatus.DEFECTUOSO,
            FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);

    private final FiscalSubmissionStateRepository states;
    private final FiscalRecordRepository records;
    private final CurrentOrganization organization;
    private final FiscalQrUrlService qrUrls;
    private final FiscalPrintSnapshotRecordRepository printSnapshots;

    public DefectiveFiscalRecordService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            FiscalQrUrlService qrUrls) {
        this(states, records, organization, qrUrls, null);
    }

    @Autowired
    public DefectiveFiscalRecordService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            FiscalQrUrlService qrUrls,
            FiscalPrintSnapshotRecordRepository printSnapshots) {
        this.states = states;
        this.records = records;
        this.organization = organization;
        this.qrUrls = qrUrls;
        this.printSnapshots = printSnapshots;
    }

    // Lista registros fiscales con incidencia sin bloquear nuevas ventas.
    @Transactional(readOnly = true)
    public List<DefectiveFiscalRecordView> list() {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        return states.findAllByStatusInOrderByUpdatedAtDesc(VISIBLE_STATUSES).stream()
                .flatMap(state -> records.findById(state.getRecordId()).stream()
                        .filter(record -> record.getCompanyId().equals(companyId))
                        .filter(record -> record.getStoreId().equals(storeId))
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
