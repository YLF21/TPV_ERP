package com.tpverp.backend.verifactu;

enum VerifactuSecretDeletionReason {
    ACTIVE_DELETED,
    PREVIOUS_REPLACED,
    RETENTION_PURGE,
    IMPORT_ROLLBACK,
    MIGRATION_RECONCILIATION
}
