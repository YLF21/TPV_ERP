package com.tpverp.saas.admin;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum AdminPermission {
    ADD_COMPANY,
    RENEW_LICENSE,
    BLOCK_LICENSE,
    UNBLOCK_LICENSE,
    EDIT_COMPANY_DATA,
    VIEW_ADMIN_DATA,
    REGENERATE_PAIRING_CODE,
    REVOKE_INSTALLATION,
    MANAGE_ADMIN_USERS,
    MANAGE_SUPPORT_TICKETS,
    MANAGE_BILLING,
    MANAGE_TENANT_USERS,
    MANAGE_ERP_MASTERS,
    MANAGE_FISCAL_POLICY,
    MANAGE_OPERATIONAL_INCIDENTS,
    MANAGE_OPERATIONS,
    MANAGE_INTEGRATIONS,
    VIEW_REPORTS;

    static final Set<String> ALL_CODES = Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
}
