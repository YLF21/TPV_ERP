package com.tpverp.backend.party.loyalty.category;

import com.tpverp.backend.party.MemberCategory;
import com.tpverp.backend.party.loyalty.sync.MemberPointsContractCanonicalizer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemberCategoryBootstrapCanonicalizer {
    private MemberCategoryBootstrapCanonicalizer() {
    }

    public static String categoryLine(MemberCategory category) {
        return "C|" + category.getId()
                + "|" + text(category.getCode())
                + "|" + text(category.getName())
                + "|" + category.getMinPoints()
                + "|" + decimal(category.getDiscountPercent())
                + "|" + category.isDiscountEnabled()
                + "|" + category.isManualOnly()
                + "|" + category.isActive()
                + "|" + category.getSortOrder() + "\n";
    }

    public static String assignmentLine(AssignmentValue assignment) {
        return "M|" + assignment.memberId()
                + "|" + assignment.action()
                + "|" + (assignment.categoryId() == null ? "-" : assignment.categoryId())
                + "|" + (assignment.lockKnown()
                        ? Boolean.toString(assignment.lockAutomatic()) : "?")
                + "|" + assignment.assignedAt()
                + "|" + assignment.source() + "\n";
    }

    public static String snapshotChecksum(String categoryHash, String assignmentHash) {
        return MemberPointsContractCanonicalizer.sha256(
                "CATEGORIES|" + categoryHash + "\n"
                        + "ASSIGNMENTS|" + assignmentHash + "\n");
    }

    public static String hash(List<String> lines) {
        return MemberPointsContractCanonicalizer.sha256(String.join("", lines));
    }

    private static String text(String value) {
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String decimal(BigDecimal value) {
        var normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    public record AssignmentValue(
            UUID memberId,
            UUID categoryId,
            boolean lockAutomatic,
            boolean lockKnown,
            Instant assignedAt,
            String source,
            String action) {
    }
}
