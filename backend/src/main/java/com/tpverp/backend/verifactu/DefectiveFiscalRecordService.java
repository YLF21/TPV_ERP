package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import org.springframework.stereotype.Service;
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

    public DefectiveFiscalRecordService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization) {
        this.states = states;
        this.records = records;
        this.organization = organization;
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
                        .map(record -> DefectiveFiscalRecordView.from(record, state)))
                .toList();
    }
}
