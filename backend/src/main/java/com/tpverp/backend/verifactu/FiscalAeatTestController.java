package com.tpverp.backend.verifactu;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dev/fiscal-aeat-test")
@RequireGestionGroup(GestionGroup.FISCAL)
public class FiscalAeatTestController {

    private final FiscalAeatTestDispatchService dispatch;

    public FiscalAeatTestController(FiscalAeatTestDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @PostMapping("/dispatch-next")
    @PreAuthorize("hasRole('ADMIN')")
    public FiscalAeatTestDispatchView dispatchNext(
            @Valid @RequestBody FiscalAeatTestDispatchRequest request) {
        return dispatch.dispatch(request);
    }
}
