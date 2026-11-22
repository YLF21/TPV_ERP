package com.tpverp.saas.stores;

import static com.tpverp.saas.stores.StoreWorkspaceApi.*;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class StoreWorkspaceController {
    private final StoreAdministrationService stores;
    private final LicenseWorkspaceService licenses;

    public StoreWorkspaceController(StoreAdministrationService stores, LicenseWorkspaceService licenses) {
        this.stores = stores;
        this.licenses = licenses;
    }

    @GetMapping("/stores")
    public Page<StoreRow> stores(@RequestParam(required = false) UUID companyId,
            @RequestParam(defaultValue = "") String q, @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String commercialConfiguration,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "companyName") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        return stores.list(companyId, q, active, commercialConfiguration, page, size, sortBy, sortDirection);
    }

    @GetMapping("/stores/{storeId}")
    public StoreRow store(@PathVariable UUID storeId) {
        return stores.get(storeId);
    }

    @PostMapping("/companies/{companyId}/stores")
    public StoreRow createStore(@PathVariable UUID companyId, @Valid @RequestBody CreateStore request) {
        return stores.create(companyId, request);
    }

    @PutMapping("/stores/{storeId}")
    public StoreRow updateStore(@PathVariable UUID storeId, @Valid @RequestBody UpdateStore request) {
        return stores.update(storeId, request, true);
    }

    @PostMapping("/stores/{storeId}/internal-code")
    public StoreRow assignCode(@PathVariable UUID storeId, @Valid @RequestBody AssignCode request) {
        return stores.assignCode(storeId, request);
    }

    @PutMapping("/stores/{storeId}/activity")
    public StoreRow activity(@PathVariable UUID storeId, @Valid @RequestBody UpdateActivity request) {
        return stores.updateActivity(storeId, request.active());
    }

    @GetMapping("/license-workspace")
    public Page<LicenseRow> licenses(@RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "") String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant expiresBefore,
            @RequestParam(required = false) Boolean hasConnections,
            @RequestParam(required = false) String billingStatus,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "validUntil") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        return licenses.list(companyId, storeId, q, status, expiresBefore, hasConnections,
                billingStatus, page, size, sortBy, sortDirection);
    }

    @GetMapping("/license-workspace/activation-codes")
    public ActivationCodePage activationCodes(@RequestParam(required = false) UUID companyId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return licenses.activationCodes(companyId, page, size);
    }

    @DeleteMapping("/license-workspace/activation-codes/{codeId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void revokeActivationCode(@PathVariable UUID codeId) {
        licenses.revokeActivationCode(codeId);
    }

    @GetMapping("/license-workspace/{licenseId}")
    public LicenseRow license(@PathVariable UUID licenseId) {
        return licenses.get(licenseId);
    }

    @PostMapping("/license-workspace")
    public CreatedLicense createLicense(@Valid @RequestBody CreateLicense request) {
        return licenses.create(request, true);
    }
}
