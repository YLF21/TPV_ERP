package com.tpverp.backend.verifactu;

import java.util.UUID;

/** Immutable relation to another fiscal record. */
public record FiscalRecordRelationView(
        UUID relatedRecordId,
        FiscalRelationType type) {
}
