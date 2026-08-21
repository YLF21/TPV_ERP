package com.tpverp.saas.loyalty;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/loyalty/member-points/bootstrap")
public class MemberPointsBootstrapIngestionController {
    private static final String TOKEN="X-TPV-Installation-Token";
    private final MemberPointsBootstrapIngestionService service;
    public MemberPointsBootstrapIngestionController(MemberPointsBootstrapIngestionService service){this.service=service;}
    @PostMapping("/discover") public LoyaltyApiModels.PointsBootstrapStatus discover(@RequestHeader(TOKEN)String token,@RequestBody LoyaltyApiModels.PointsBootstrapStoreRequest request){return service.discover(request,token);}
    @PostMapping("/{bootstrapId}/status") public LoyaltyApiModels.PointsBootstrapStatus status(@PathVariable UUID bootstrapId,@RequestHeader(TOKEN)String token,@RequestBody LoyaltyApiModels.PointsBootstrapStoreRequest request){return service.status(bootstrapId,request,token);}
    @PostMapping("/{bootstrapId}/snapshots") public LoyaltyApiModels.PointsBootstrapStatus begin(@PathVariable UUID bootstrapId,@RequestHeader(TOKEN)String token,@RequestBody LoyaltyApiModels.PointsBootstrapBeginRequest request){return service.begin(bootstrapId,request,token);}
    @PostMapping("/{bootstrapId}/snapshots/{snapshotId}/chunks/{kind}/{index}")
    public LoyaltyApiModels.PointsBootstrapStatus chunk(@PathVariable UUID bootstrapId,@PathVariable UUID snapshotId,@PathVariable String kind,@PathVariable int index,@RequestHeader(TOKEN)String token,@RequestBody LoyaltyApiModels.PointsBootstrapChunkRequest request){return service.chunk(bootstrapId,snapshotId,kind,index,request,token);}
    @PostMapping("/{bootstrapId}/snapshots/{snapshotId}/complete")
    public LoyaltyApiModels.PointsBootstrapStatus complete(@PathVariable UUID bootstrapId,@PathVariable UUID snapshotId,@RequestHeader(TOKEN)String token,@RequestBody LoyaltyApiModels.PointsBootstrapCompleteRequest request){return service.complete(bootstrapId,snapshotId,request,token);}
    @PostMapping("/{bootstrapId}/official-state/chunks/{index}")
    public LoyaltyApiModels.PointsOfficialStateChunk official(@PathVariable UUID bootstrapId,@PathVariable int index,@RequestHeader(TOKEN)String token,@RequestBody LoyaltyApiModels.PointsBootstrapStoreRequest request){return service.officialState(bootstrapId,index,request,token);}
}
