package com.tpverp.backend.party.loyalty.category;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("tpv.sync.central-url")
public class MemberCategoryBootstrapWorker {
    private static final int CHUNK_SIZE = 500;
    private final MemberBalanceCentralContextResolver contexts;
    private final MemberCategoryBootstrapCaptureService capture;
    private final MemberCategoryBootstrapSnapshotRepository snapshots;
    private final MemberCategoryBootstrapUploadRepository uploads;
    private final MemberCategoryBootstrapGateway gateway;
    private final MemberCategoryProjectionCoordinator projection;
    private final MemberCategoryOfficialSnapshotApplicationService officialSnapshots;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MemberCategoryBootstrapWorker(
            MemberBalanceCentralContextResolver contexts,
            MemberCategoryBootstrapCaptureService capture,
            MemberCategoryBootstrapSnapshotRepository snapshots,
            MemberCategoryBootstrapUploadRepository uploads,
            MemberCategoryBootstrapGateway gateway,
            MemberCategoryProjectionCoordinator projection,
            MemberCategoryOfficialSnapshotApplicationService officialSnapshots,
            JdbcTemplate jdbc,
            Clock clock) {
        this.contexts = contexts;
        this.capture = capture;
        this.snapshots = snapshots;
        this.uploads = uploads;
        this.gateway = gateway;
        this.projection = projection;
        this.officialSnapshots = officialSnapshots;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void runOnce() {
        for (var context : contexts.resolveBootstrapContexts()) {
            try {
                synchronize(context);
            } catch (RuntimeException ignored) {
                // Cada tienda se reintenta de forma independiente y nunca bloquea operativa local.
            }
        }
    }

    private void synchronize(MemberBalanceCentralContextResolver.BootstrapContext context) {
        var central = gateway.discover(context.companyId(), context.storeId());
        if (central.bootstrapId() == null || !central.isCollecting()) {
            reconcileExisting(context, central);
            return;
        }
        UUID requestedSnapshotId = UUID.nameUUIDFromBytes(
                (central.bootstrapId() + "|" + context.localStoreId())
                        .getBytes(StandardCharsets.UTF_8));
        var captured = capture.freezeAndCapture(
                context.localCompanyId(),
                context.localStoreId(),
                central.bootstrapId(),
                requestedSnapshotId);
        var snapshot = snapshots.findById(captured.snapshotId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se encontro el snapshot de categorias capturado"));
        var upload = uploads.findBySnapshotId(snapshot.getSnapshotId())
                .orElseGet(() -> uploads.save(MemberCategoryBootstrapUpload.pending(
                        context.companyId(),
                        context.storeId(),
                        snapshot.getSnapshotId(),
                        clock.instant())));
        if (upload.getNextAttemptAt().isAfter(clock.instant())) {
            return;
        }
        if (upload.getStatus() == MemberCategoryBootstrapUpload.Status.WAITING_CENTRAL) {
            finish(context, snapshot, upload, central);
            return;
        }
        if (upload.getStatus() == MemberCategoryBootstrapUpload.Status.APPLIED
                || upload.getStatus() == MemberCategoryBootstrapUpload.Status.CONFLICT) {
            return;
        }
        process(context, snapshot, upload, central.bootstrapId());
    }

    private void reconcileExisting(
            MemberBalanceCentralContextResolver.BootstrapContext context,
            MemberCategoryBootstrapGateway.BootstrapStatus central) {
        if (central.bootstrapId() == null) {
            return;
        }
        snapshots.findByBootstrapIdAndStoreId(
                        central.bootstrapId(), context.localStoreId())
                .flatMap(snapshot -> uploads.findBySnapshotId(snapshot.getSnapshotId())
                        .map(upload -> new Existing(snapshot, upload)))
                .ifPresent(existing -> {
                    var upload = existing.upload();
                    if (upload.getStatus() == MemberCategoryBootstrapUpload.Status.APPLIED
                            || upload.getStatus() == MemberCategoryBootstrapUpload.Status.CONFLICT
                            || upload.getNextAttemptAt().isAfter(clock.instant())) {
                        return;
                    }
                    finish(context, existing.snapshot(), upload, central);
                });
    }

    private void process(
            MemberBalanceCentralContextResolver.BootstrapContext context,
            MemberCategoryBootstrapSnapshot snapshot,
            MemberCategoryBootstrapUpload upload,
            UUID bootstrapId) {
        Instant now = clock.instant();
        try {
            if (upload.getStatus() == MemberCategoryBootstrapUpload.Status.PENDING) {
                upload.start(bootstrapId, now);
                uploads.save(upload);
            }
            int categoryChunks = chunkCount(snapshot.getCategoryCount());
            int assignmentChunks = chunkCount(snapshot.getAssignmentCount());
            gateway.begin(
                    bootstrapId,
                    upload.getCompanyId(),
                    upload.getStoreId(),
                    snapshot,
                    categoryChunks,
                    assignmentChunks);
            while (upload.getCategoryChunksUploaded() < categoryChunks) {
                int index = upload.getCategoryChunksUploaded();
                var values = categoryChunk(snapshot.getSnapshotId(), index);
                gateway.uploadCategories(
                        bootstrapId,
                        snapshot.getSnapshotId(),
                        upload.getCompanyId(),
                        upload.getStoreId(),
                        index,
                        hashCategoryChunk(values),
                        values);
                upload.recordCategoryChunk(clock.instant());
                uploads.save(upload);
            }
            while (upload.getAssignmentChunksUploaded() < assignmentChunks) {
                int index = upload.getAssignmentChunksUploaded();
                var values = assignmentChunk(snapshot.getSnapshotId(), index);
                gateway.uploadAssignments(
                        bootstrapId,
                        snapshot.getSnapshotId(),
                        upload.getCompanyId(),
                        upload.getStoreId(),
                        index,
                        hashAssignmentChunk(values),
                        values);
                upload.recordAssignmentChunk(clock.instant());
                uploads.save(upload);
            }
            var central = gateway.complete(
                    bootstrapId,
                    snapshot.getSnapshotId(),
                    upload.getCompanyId(),
                    upload.getStoreId(),
                    snapshot.getSnapshotChecksum());
            upload.waitForCentralResult(clock.instant());
            uploads.save(upload);
            finish(context, snapshot, upload, central);
        } catch (RuntimeException exception) {
            upload.scheduleRetry(exception.getMessage(), clock.instant());
            uploads.save(upload);
        }
    }

    private void finish(
            MemberBalanceCentralContextResolver.BootstrapContext context,
            MemberCategoryBootstrapSnapshot snapshot,
            MemberCategoryBootstrapUpload upload,
            MemberCategoryBootstrapGateway.BootstrapStatus central) {
        if (central.isCompleted()) {
            try {
                var official = gateway.officialSnapshot(
                        upload.getCompanyId(), upload.getStoreId());
                officialSnapshots.apply(
                        context.localCompanyId(),
                        context.localStoreId(),
                        central.bootstrapId(),
                        snapshot.getSnapshotId(),
                        official);
                upload.markApplied(clock.instant());
                uploads.save(upload);
            } catch (RuntimeException exception) {
                upload.scheduleRetry(exception.getMessage(), clock.instant());
                uploads.save(upload);
            }
            return;
        }
        if (central.isConflict()) {
            upload.markConflict(central.conflictReason(), clock.instant());
            uploads.save(upload);
            projection.markConflict(
                    context.localCompanyId(),
                    context.localStoreId(),
                    central.bootstrapId(),
                    snapshot.getSnapshotId());
            return;
        }
        if (upload.getStatus() == MemberCategoryBootstrapUpload.Status.WAITING_CENTRAL) {
            upload.defer(clock.instant());
            uploads.save(upload);
        }
    }

    private List<MemberCategoryBootstrapGateway.CategoryValue> categoryChunk(
            UUID snapshotId,
            int index) {
        return jdbc.query("""
                select category_id, code, name, min_points, discount_percent,
                       discount_enabled, manual_only, active, sort_order
                from member_category_bootstrap_category
                where snapshot_id = ?
                order by code, category_id
                limit ? offset ?
                """, (rs, row) -> new MemberCategoryBootstrapGateway.CategoryValue(
                        rs.getObject("category_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("min_points"),
                        rs.getObject("discount_percent", BigDecimal.class),
                        rs.getBoolean("discount_enabled"),
                        rs.getBoolean("manual_only"),
                        rs.getBoolean("active"),
                        rs.getInt("sort_order")),
                snapshotId, CHUNK_SIZE, index * CHUNK_SIZE);
    }

    private List<MemberCategoryBootstrapGateway.AssignmentValue> assignmentChunk(
            UUID snapshotId,
            int index) {
        return jdbc.query("""
                select member_id, category_id, lock_automatic, lock_known,
                       assigned_at, assignment_source, assignment_action
                from member_category_bootstrap_assignment
                where snapshot_id = ?
                order by member_id
                limit ? offset ?
                """, (rs, row) -> new MemberCategoryBootstrapGateway.AssignmentValue(
                        rs.getObject("member_id", UUID.class),
                        rs.getObject("category_id", UUID.class),
                        rs.getBoolean("lock_automatic"),
                        rs.getBoolean("lock_known"),
                        rs.getObject("assigned_at", Instant.class),
                        rs.getString("assignment_source"),
                        rs.getString("assignment_action")),
                snapshotId, CHUNK_SIZE, index * CHUNK_SIZE);
    }

    private static String hashCategoryChunk(
            List<MemberCategoryBootstrapGateway.CategoryValue> values) {
        var text = new StringBuilder();
        values.forEach(value -> text.append("C|")
                .append(value.categoryId()).append('|')
                .append(escaped(value.code())).append('|')
                .append(escaped(value.name())).append('|')
                .append(value.minPoints()).append('|')
                .append(decimal(value.discountPercent())).append('|')
                .append(value.discountEnabled()).append('|')
                .append(value.manualOnly()).append('|')
                .append(value.active()).append('|')
                .append(value.sortOrder()).append('\n'));
        return sha256(text.toString());
    }

    private static String hashAssignmentChunk(
            List<MemberCategoryBootstrapGateway.AssignmentValue> values) {
        var text = new StringBuilder();
        values.forEach(value -> text.append("M|")
                .append(value.memberId()).append('|')
                .append(value.assignmentAction()).append('|')
                .append(value.categoryId() == null ? "-" : value.categoryId()).append('|')
                .append(value.lockKnown()
                        ? Boolean.toString(value.lockAutomatic()) : "?").append('|')
                .append(value.assignedAt()).append('|')
                .append(value.assignmentSource()).append('\n'));
        return sha256(text.toString());
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String decimal(BigDecimal value) {
        var normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static int chunkCount(int count) {
        return count == 0 ? 0 : (count + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }

    private record Existing(
            MemberCategoryBootstrapSnapshot snapshot,
            MemberCategoryBootstrapUpload upload) {
    }
}
