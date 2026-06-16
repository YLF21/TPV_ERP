package com.tpverp.backend.document;

import com.tpverp.backend.verifactu.FiscalQrUrlService;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentFiscalQrService {

    private final FiscalRecordRepository records;
    private final FiscalQrUrlService qrUrls;

    public DocumentFiscalQrService(
            FiscalRecordRepository records,
            FiscalQrUrlService qrUrls) {
        this.records = records;
        this.qrUrls = qrUrls;
    }

    // Devuelve la URL QR del alta fiscal si el documento ya tiene registro VERI*FACTU.
    @Transactional(readOnly = true)
    public String qrUrl(UUID documentId) {
        return records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA)
                .map(qrUrls::productionUrl)
                .orElse(null);
    }
}
