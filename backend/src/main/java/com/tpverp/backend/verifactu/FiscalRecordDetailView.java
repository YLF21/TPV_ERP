package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Sanitized fiscal record detail for APP GESTION. */
public record FiscalRecordDetailView(
        UUID recordId,
        UUID chainId,
        UUID companyId,
        UUID installationId,
        UUID storeId,
        UUID documentId,
        long sequence,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        String number,
        LocalDate issueDate,
        Instant generatedAt,
        String timezone,
        String issuerTaxId,
        BigDecimal totalTax,
        BigDecimal totalAmount,
        String previousHash,
        String hash,
        String snapshotHash,
        String formatVersion,
        String algorithmVersion,
        String applicationVersion,
        FiscalMode fiscalMode,
        UUID previousRecordId,
        UUID nextRecordId,
        FiscalRecordDocumentView document,
        FiscalRecordArtifactView artifact,
        List<FiscalRecordRelationView> relations,
        FiscalRecordSubmissionView submission,
        String adjacentChainStatus) {

    public FiscalRecordDetailView {
        relations = List.copyOf(relations);
    }

    /** Compatibility constructor for clients before the adjacent-link status was exposed. */
    public FiscalRecordDetailView(
            UUID recordId, UUID chainId, UUID companyId, UUID installationId, UUID storeId,
            UUID documentId, long sequence, FiscalRecordOperation operation,
            FiscalDocumentType documentType, String number, LocalDate issueDate, Instant generatedAt,
            String timezone, String issuerTaxId, BigDecimal totalTax, BigDecimal totalAmount,
            String previousHash, String hash, String snapshotHash, String formatVersion,
            String algorithmVersion, String applicationVersion, FiscalMode fiscalMode,
            UUID previousRecordId, UUID nextRecordId, FiscalRecordDocumentView document,
            FiscalRecordArtifactView artifact, List<FiscalRecordRelationView> relations,
            FiscalRecordSubmissionView submission) {
        this(recordId, chainId, companyId, installationId, storeId, documentId, sequence, operation,
                documentType, number, issueDate, generatedAt, timezone, issuerTaxId, totalTax,
                totalAmount, previousHash, hash, snapshotHash, formatVersion, algorithmVersion,
                applicationVersion, fiscalMode, previousRecordId, nextRecordId, document, artifact,
                relations, submission, null);
    }
}
