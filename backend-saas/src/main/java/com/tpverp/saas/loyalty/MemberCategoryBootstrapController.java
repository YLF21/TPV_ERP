package com.tpverp.saas.loyalty;

import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.BeginRequest;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.ChunkRequest;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.CompleteRequest;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.Status;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.StoreRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/loyalty/member-categories/bootstrap")
public class MemberCategoryBootstrapController {
    private static final String TOKEN = "X-TPV-Installation-Token";
    private final MemberCategoryBootstrapService service;

    public MemberCategoryBootstrapController(MemberCategoryBootstrapService service) {
        this.service = service;
    }

    @PostMapping("/discover")
    public Status discover(
            @RequestHeader(TOKEN) String token,
            @RequestBody StoreRequest request) {
        return service.discover(request, token);
    }

    @PostMapping("/{bootstrapId}/status")
    public Status status(
            @PathVariable UUID bootstrapId,
            @RequestHeader(TOKEN) String token,
            @RequestBody StoreRequest request) {
        return service.status(bootstrapId, request, token);
    }

    @PostMapping("/{bootstrapId}/snapshots")
    public Status begin(
            @PathVariable UUID bootstrapId,
            @RequestHeader(TOKEN) String token,
            @RequestBody BeginRequest request) {
        return service.begin(bootstrapId, request, token);
    }

    @PostMapping("/{bootstrapId}/snapshots/{snapshotId}/chunks/{kind}/{index}")
    public Status chunk(
            @PathVariable UUID bootstrapId,
            @PathVariable UUID snapshotId,
            @PathVariable String kind,
            @PathVariable int index,
            @RequestHeader(TOKEN) String token,
            @RequestBody ChunkRequest request) {
        return service.chunk(bootstrapId, snapshotId, kind, index, request, token);
    }

    @PostMapping("/{bootstrapId}/snapshots/{snapshotId}/complete")
    public Status complete(
            @PathVariable UUID bootstrapId,
            @PathVariable UUID snapshotId,
            @RequestHeader(TOKEN) String token,
            @RequestBody CompleteRequest request) {
        return service.complete(bootstrapId, snapshotId, request, token);
    }
}
