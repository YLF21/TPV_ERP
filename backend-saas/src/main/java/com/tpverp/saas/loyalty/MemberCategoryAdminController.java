package com.tpverp.saas.loyalty;

import com.tpverp.saas.loyalty.MemberCategoryAdminApiModels.AssignmentCommand;
import com.tpverp.saas.loyalty.MemberCategoryAdminApiModels.CategoryCommand;
import com.tpverp.saas.loyalty.MemberCategoryAdminApiModels.CommandResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/loyalty/member-categories/admin")
public class MemberCategoryAdminController {
    private static final String TOKEN = "X-TPV-Installation-Token";
    private final MemberCategoryAdminService service;

    public MemberCategoryAdminController(MemberCategoryAdminService service) {
        this.service = service;
    }

    @PostMapping("/categories")
    public CommandResult category(
            @RequestHeader(TOKEN) String token,
            @RequestBody CategoryCommand command) {
        return service.category(command, token);
    }

    @PostMapping("/assignments")
    public CommandResult assignment(
            @RequestHeader(TOKEN) String token,
            @RequestBody AssignmentCommand command) {
        return service.assignment(command, token);
    }
}
