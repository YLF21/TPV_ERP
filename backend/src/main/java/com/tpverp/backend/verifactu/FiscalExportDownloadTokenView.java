package com.tpverp.backend.verifactu;

/** Plaintext is returned only once, at issuance; it is never persisted. */
public record FiscalExportDownloadTokenView(String token) {}
