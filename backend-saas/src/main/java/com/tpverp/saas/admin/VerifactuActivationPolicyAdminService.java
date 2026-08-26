package com.tpverp.saas.admin;

import com.tpverp.saas.license.LicenseSaasStatus;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasLicenseRepository;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.license.VerifactuActivationPolicy;
import com.tpverp.saas.license.VerifactuActivationPolicyRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VerifactuActivationPolicyAdminService {

    private final VerifactuActivationPolicyRepository policies;
    private final SaasLicenseRepository licenses;
    private final SaasInstallationRepository installations;
    private final AdminAuditService audit;
    private final Clock clock;

    public VerifactuActivationPolicyAdminService(
            VerifactuActivationPolicyRepository policies,
            SaasLicenseRepository licenses,
            SaasInstallationRepository installations,
            AdminAuditService audit,
            Clock clock) {
        this.policies = policies;
        this.licenses = licenses;
        this.installations = installations;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<VerifactuActivationPolicyResponse> list() {
        return Arrays.stream(TaxpayerType.values())
                .map(this::required)
                .map(this::response)
                .toList();
    }

    @Transactional
    public VerifactuActivationPolicyResponse update(
            TaxpayerType taxpayerType,
            UpdateVerifactuActivationPolicyRequest request) {
        VerifactuActivationPolicy policy = required(taxpayerType);
        var previousDate = policy.getActivationDate();
        String actor = audit.currentUsername();
        try {
            VerifactuActivationPolicy.validateActivationDate(taxpayerType, request.activationDate());
            policy.update(request.activationDate(), clock.instant(), actor, request.reason());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    exception.getMessage(), exception);
        }
        VerifactuActivationPolicy saved = policies.saveAndFlush(policy);
        audit.log(
                "UPDATE_VERIFACTU_ACTIVATION_POLICY",
                "VERIFACTU_POLICY",
                taxpayerType.name(),
                "previousDate=" + previousDate
                        + ";newDate=" + saved.getActivationDate()
                        + ";version=" + saved.getVersion()
                        + ";reason=" + saved.getReason());
        return response(saved);
    }

    private VerifactuActivationPolicy required(TaxpayerType taxpayerType) {
        return policies.findById(taxpayerType)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe politica VERI*FACTU para " + taxpayerType));
    }

    private VerifactuActivationPolicyResponse response(VerifactuActivationPolicy policy) {
        TaxpayerType type = policy.getTaxpayerType();
        return new VerifactuActivationPolicyResponse(
                type,
                policy.getActivationDate(),
                policy.getVersion(),
                policy.getUpdatedAt(),
                policy.getUpdatedBy(),
                policy.getReason(),
                licenses.countByCompany_TaxpayerTypeAndStatus(type, LicenseSaasStatus.VALIDA),
                installations.countByCompany_TaxpayerTypeAndActiveTrue(type));
    }
}
