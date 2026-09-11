package com.tpverp.backend.catalog;

/**
 * A catalog mutation was rejected after the authoritative lock/revalidation.
 * Import orchestration maps this expected race to a re-preview conflict; it
 * must not be confused with an arbitrary validation or database failure.
 */
public final class ProductImportConflictException extends IllegalArgumentException {
    public ProductImportConflictException(String message) {
        super(message);
    }
}
