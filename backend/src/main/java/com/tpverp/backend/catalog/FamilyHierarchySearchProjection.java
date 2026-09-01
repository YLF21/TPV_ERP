package com.tpverp.backend.catalog;

import java.util.UUID;

/** Narrow store-scoped projection for searching unloaded family tree nodes. */
public interface FamilyHierarchySearchProjection {
    String getKind();
    UUID getId();
    UUID getFamilyId();
    UUID getSubfamilyId();
    String getCode();
    String getName();
    String getFamilyCode();
    String getSuffix();
    boolean isDefaultFamily();
}
