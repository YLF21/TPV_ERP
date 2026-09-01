package com.tpverp.backend.catalog;

import java.util.UUID;

/** Stable API row for family/subfamily lookup used by explore and move flows. */
public record FamilyHierarchySearchView(
        String kind,
        UUID id,
        UUID familyId,
        UUID subfamilyId,
        String code,
        String name,
        String familyCode,
        String suffix,
        boolean defaultFamily) {
}
