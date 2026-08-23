package com.tpverp.backend.document;

import com.tpverp.backend.verifactu.FiscalQrUrlService;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
import com.tpverp.backend.verifactu.FiscalRecordArtifactRepository;
import com.tpverp.backend.verifactu.FiscalRuntimeProperties;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotRecordRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentFiscalQrService {

    private final FiscalRecordRepository records;
    private final FiscalQrUrlService qrUrls;
    private final FiscalRecordArtifactRepository artifacts;
    private final FiscalRuntimeProperties runtime;
    private final FiscalPrintSnapshotRecordRepository printSnapshots;

    public DocumentFiscalQrService(
            FiscalRecordRepository records,
            FiscalQrUrlService qrUrls) {
        this(records, qrUrls, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentFiscalQrService(
            FiscalRecordRepository records,
            FiscalQrUrlService qrUrls,
            FiscalRecordArtifactRepository artifacts,
            FiscalRuntimeProperties runtime,
            FiscalPrintSnapshotRecordRepository printSnapshots) {
        this.records = records;
        this.qrUrls = qrUrls;
        this.artifacts = artifacts;
        this.runtime = runtime;
        this.printSnapshots = printSnapshots;
    }

    // Returns the fiscal creation QR URL when the document already has a VERI*FACTU record.
    @Transactional(readOnly = true)
    public String qrUrl(UUID documentId) {
        return records.findByDocumentIdAndOperation(documentId, FiscalRecordOperation.ALTA)
                .map(record -> printSnapshots == null ? (artifacts == null
                        ? qrUrls.url(record, record.getFiscalMode(), endpointEnvironment())
                        : artifacts.findByRecordId(record.getId())
                                .map(com.tpverp.backend.verifactu.FiscalRecordArtifact::getQrUrl)
                                .orElseGet(() -> qrUrls.url(record, record.getFiscalMode(),
                                        endpointEnvironment())))
                        : printSnapshots.findByRecordId(record.getId())
                                .map(com.tpverp.backend.verifactu.FiscalPrintSnapshotRecord::getQrUrl)
                                .orElseGet(() -> qrUrls.url(record, record.getFiscalMode(),
                                        endpointEnvironment())))
                .orElse(null);
    }

    private com.tpverp.backend.verifactu.FiscalEndpointEnvironment endpointEnvironment() {
        return runtime == null
                ? com.tpverp.backend.verifactu.FiscalEndpointEnvironment.PRODUCTION
                : runtime.endpointEnvironment();
    }
}
