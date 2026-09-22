package com.tpverp.saas.access;

/** Company-wide data access is granted separately from access to individual stores. */
public enum TenantCompanyPrivilege {
    READ_COMPANY, READ_BILLING, READ_MASTERS, WRITE_MASTERS, SUPPORT
}
