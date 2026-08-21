package com.tpverp.saas.loyalty;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/loyalty/member-wallet/bootstrap")
public class MemberWalletBootstrapIngestionController {

    private static final String INSTALLATION_TOKEN_HEADER = "X-TPV-Installation-Token";

    private final MemberWalletBootstrapIngestionService service;

    public MemberWalletBootstrapIngestionController(MemberWalletBootstrapIngestionService service) {
        this.service = service;
    }

    @PostMapping("/discover")
    public LoyaltyApiModels.WalletBootstrapStatus discover(
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.WalletBootstrapDiscoverRequest request) {
        return service.discover(request, token);
    }

    @PostMapping("/{bootstrapId}/status")
    public LoyaltyApiModels.WalletBootstrapStatus status(
            @PathVariable UUID bootstrapId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.BootstrapStoreRequest request) {
        return service.status(bootstrapId, request, token);
    }

    @PostMapping("/{bootstrapId}/snapshots")
    public LoyaltyApiModels.WalletBootstrapStatus begin(
            @PathVariable UUID bootstrapId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.WalletBootstrapBeginRequest request) {
        return service.begin(bootstrapId, request, token);
    }

    @PostMapping("/{bootstrapId}/snapshots/{snapshotId}/chunks/{kind}/{index}")
    public LoyaltyApiModels.WalletBootstrapStatus chunk(
            @PathVariable UUID bootstrapId,
            @PathVariable UUID snapshotId,
            @PathVariable String kind,
            @PathVariable int index,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.WalletBootstrapChunkRequest request) {
        return service.chunk(bootstrapId, snapshotId, kind, index, request, token);
    }

    @PostMapping("/{bootstrapId}/snapshots/{snapshotId}/complete")
    public LoyaltyApiModels.WalletBootstrapStatus complete(
            @PathVariable UUID bootstrapId,
            @PathVariable UUID snapshotId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.WalletBootstrapCompleteRequest request) {
        return service.complete(bootstrapId, snapshotId, request, token);
    }
}
