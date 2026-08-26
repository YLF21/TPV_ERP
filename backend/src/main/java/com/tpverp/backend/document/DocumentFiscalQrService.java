package com.tpverp.backend.document;

import com.tpverp.backend.verifactu.FiscalQrUrlService;
import com.tpverp.backend.verifactu.FiscalEndpointEnvironment;
import com.tpverp.backend.verifactu.FiscalMode;
import com.tpverp.backend.verifactu.FiscalRecordOperation;
import com.tpverp.backend.verifactu.FiscalRecordRepository;
import com.tpverp.backend.verifactu.FiscalRecordArtifactRepository;
import com.tpverp.backend.verifactu.FiscalRuntimeProperties;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
                .map(record -> frozenOrCompatibleQrUrl(record))
                .orElse(null);
    }

    /**
     * Resolves the immutable QR payload used by a print or reprint job.
     *
     * <p>An empty result means that the document has no fiscal ALTA and must
     * remain QR-free. A fiscal ALTA without a valid frozen payload is an
     * explicit, recoverable printing failure; it is never recalculated from the
     * current runtime configuration.</p>
     */
    @Transactional(readOnly = true)
    public Optional<FiscalQrPrintData> resolveForPrint(UUID documentId) {
        var record = records.findByDocumentIdAndOperation(
                        Objects.requireNonNull(documentId, "documentId"),
                        FiscalRecordOperation.ALTA)
                .orElse(null);
        if (record == null) {
            return Optional.empty();
        }
        if (printSnapshots != null) {
            var snapshot = printSnapshots.findByRecordId(record.getId()).orElse(null);
            if (snapshot != null) {
                return Optional.of(validated(documentId, record, snapshot));
            }
        }
        throw new FiscalQrUnavailableException(
                documentId,
                FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_MISSING);
    }

    private String frozenOrCompatibleQrUrl(
            com.tpverp.backend.verifactu.FiscalRecord record) {
        // The production wiring is snapshot-only: a legacy record without a
        // frozen artifact must not be silently recalculated with current config.
        if (printSnapshots != null) {
            var snapshot = printSnapshots.findByRecordId(record.getId());
            if (snapshot.isPresent()) {
                return snapshot.get().getQrUrl();
            }
            if (artifacts != null) {
                return artifacts.findByRecordId(record.getId())
                        .map(com.tpverp.backend.verifactu.FiscalRecordArtifact::getQrUrl)
                        .orElse(null);
            }
            return null;
        }
        // Two-argument construction is retained for compatibility adapters and
        // explicitly has no persisted snapshot repository.
        return artifacts == null
                ? qrUrls.url(record, record.getFiscalMode(), endpointEnvironment())
                : artifacts.findByRecordId(record.getId())
                        .map(com.tpverp.backend.verifactu.FiscalRecordArtifact::getQrUrl)
                        .orElseGet(() -> qrUrls.url(record, record.getFiscalMode(),
                                endpointEnvironment()));
    }

    private com.tpverp.backend.verifactu.FiscalEndpointEnvironment endpointEnvironment() {
        return runtime == null
                ? com.tpverp.backend.verifactu.FiscalEndpointEnvironment.PRODUCTION
                : runtime.endpointEnvironment();
    }

    private FiscalQrPrintData validated(
            UUID documentId,
            com.tpverp.backend.verifactu.FiscalRecord record,
            com.tpverp.backend.verifactu.FiscalPrintSnapshotRecord snapshot) {
        String qrUrl = snapshot.getQrUrl();
        String expectedHash = snapshot.getQrHash();
        if (qrUrl == null || qrUrl.isBlank()
                || expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            throw new FiscalQrUnavailableException(
                    documentId,
                    FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_INVALID);
        }
        var normalizedHash = expectedHash.toLowerCase(java.util.Locale.ROOT);
        if (!MessageDigest.isEqual(
                sha256(qrUrl).getBytes(StandardCharsets.US_ASCII),
                normalizedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new FiscalQrUnavailableException(
                    documentId,
                    FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_HASH_MISMATCH);
        }
        var mode = snapshot.getMode();
        var environment = snapshot.getEnvironment();
        var formatVersion = snapshot.getFormatVersion();
        var generatorVersion = snapshot.getGeneratorVersion();
        var prefix = snapshot.getPrefix();
        var legend = snapshot.getLegend();
        var testNotice = snapshot.getTestNotice();
        boolean validMode = mode == FiscalMode.VERIFACTU || mode == FiscalMode.NO_VERIFACTU;
        boolean validLegend = mode == FiscalMode.VERIFACTU
                ? nonBlank(legend)
                : mode == FiscalMode.NO_VERIFACTU && legend == null;
        boolean validTestNotice = environment == FiscalEndpointEnvironment.TEST
                ? nonBlank(testNotice)
                : environment == FiscalEndpointEnvironment.PRODUCTION && testNotice == null;
        if (!validMode || environment == null
                || !nonBlank(formatVersion) || !nonBlank(generatorVersion)
                || !nonBlank(prefix) || !validLegend || !validTestNotice
                || !qrUrls.isOfficialUrlFor(qrUrl, mode, environment)) {
            throw new FiscalQrUnavailableException(
                    documentId,
                    FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_INVALID);
        }
        String issuerName = null;
        String issuerTaxId = null;
        Map<String, String> issuerAddress = null;
        if (artifacts != null) {
            var artifact = artifacts.findByRecordId(record.getId()).orElseThrow(() ->
                    new FiscalQrUnavailableException(documentId,
                            FiscalQrUnavailableException.Reason
                                    .FROZEN_ISSUER_IDENTITY_MISSING));
            issuerName = artifact.getIssuerName();
            issuerTaxId = artifact.getIssuerTaxId();
            issuerAddress = artifact.getIssuerAddress();
            if (!nonBlank(issuerName) || !nonBlank(issuerTaxId)
                    || !validAddress(issuerAddress)) {
                throw new FiscalQrUnavailableException(documentId,
                        FiscalQrUnavailableException.Reason
                                .FROZEN_ISSUER_IDENTITY_MISSING);
            }
            if (!issuerTaxId.equals(record.getIssuerTaxId())
                    || artifact.getFiscalMode() != mode
                    || artifact.getEnvironment() != environment
                    || !Objects.equals(artifact.getQrUrl(), qrUrl)
                    || artifact.getQrHash() == null
                    || !artifact.getQrHash().equalsIgnoreCase(normalizedHash)) {
                throw new FiscalQrUnavailableException(documentId,
                        FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_INVALID);
            }
        }
        return new FiscalQrPrintData(
                qrUrl,
                normalizedHash,
                formatVersion,
                generatorVersion,
                mode,
                environment,
                prefix,
                legend,
                testNotice,
                issuerName,
                issuerTaxId,
                issuerAddress);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean validAddress(Map<String, String> address) {
        if (address == null) {
            return false;
        }
        for (String key : new String[] {
                "linea1", "codigoPostal", "ciudad", "provincia", "pais"}) {
            if (!nonBlank(address.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record FiscalQrPrintData(
            String url,
            String payloadSha256,
            String formatVersion,
            String generatorVersion,
            FiscalMode mode,
            FiscalEndpointEnvironment environment,
            String prefix,
            String legend,
            String testNotice,
            String issuerName,
            String issuerTaxId,
            Map<String, String> issuerAddress) {

        /** Compatibility constructor for snapshots predating frozen issuer metadata. */
        public FiscalQrPrintData(
                String url,
                String payloadSha256,
                String formatVersion,
                String generatorVersion,
                FiscalMode mode,
                FiscalEndpointEnvironment environment,
                String prefix,
                String legend,
                String testNotice) {
            this(url, payloadSha256, formatVersion, generatorVersion, mode,
                    environment, prefix, legend, testNotice, null, null, null);
        }

        /** Compatibility constructor for isolated adapters and older tests. */
        public FiscalQrPrintData(String url, String payloadSha256) {
            this(url, payloadSha256, null, null, null, null, null, null, null,
                    null, null, null);
        }

        public FiscalQrPrintData {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(payloadSha256, "payloadSha256");
        }

        public boolean hasFrozenMetadata() {
            return formatVersion != null && generatorVersion != null
                    && mode != null && environment != null && prefix != null;
        }

        public FiscalPrintView toView() {
            if (!hasFrozenMetadata()) {
                throw new IllegalStateException("fiscal_print_snapshot_metadata_missing");
            }
            return new FiscalPrintView(formatVersion, generatorVersion, mode, environment,
                    url, payloadSha256, prefix, legend, testNotice,
                    issuerName, issuerTaxId, issuerAddress);
        }
    }
}
