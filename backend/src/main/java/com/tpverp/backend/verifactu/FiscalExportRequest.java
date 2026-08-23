package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.NotNull;

public record FiscalExportRequest(@NotNull FiscalExportKind kind) {
}
