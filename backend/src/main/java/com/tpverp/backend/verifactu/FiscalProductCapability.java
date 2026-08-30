package com.tpverp.backend.verifactu;

/**
 * Fiscal capabilities shipped by one product build.
 *
 * <p>The capability is a property of the release, not a tenant preference.
 * A REAL installation must therefore obtain it from the immutable release
 * manifest. SANDBOX may use the separate DUAL laboratory contract.</p>
 */
public enum FiscalProductCapability {
    VERIFACTU_ONLY,
    DUAL
}
