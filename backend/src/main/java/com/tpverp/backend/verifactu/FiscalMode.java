package com.tpverp.backend.verifactu;

/**
 * Fiscal operating mode for one company/installation pair.
 *
 * PRE_SIF is the compatibility state used before the fiscal subsystem has
 * been enabled. It must never be interpreted as NO_VERIFACTU.
 */
public enum FiscalMode {
    PRE_SIF,
    NO_VERIFACTU,
    VERIFACTU
}
