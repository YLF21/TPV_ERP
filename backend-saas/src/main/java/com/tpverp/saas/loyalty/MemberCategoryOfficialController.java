package com.tpverp.saas.loyalty;

import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.SnapshotResponse;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.StoreRequest;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.FeedRequest;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.FeedResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/loyalty/member-categories/official")
public class MemberCategoryOfficialController {
    private static final String TOKEN = "X-TPV-Installation-Token";
    private final MemberCategoryOfficialService service;

    public MemberCategoryOfficialController(MemberCategoryOfficialService service) {
        this.service = service;
    }

    @PostMapping("/snapshot")
    public SnapshotResponse snapshot(
            @RequestHeader(TOKEN) String token,
            @RequestBody StoreRequest request) {
        return service.snapshot(request, token);
    }

    @PostMapping("/feed")
    public FeedResponse feed(
            @RequestHeader(TOKEN) String token,
            @RequestBody FeedRequest request) {
        return service.feed(request, token);
    }
}
