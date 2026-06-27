# Backend Code Cleanup Audit

## Scope

Branch: `codex/backend-code-cleanup`

Goal: use English for code identifiers, keep persisted attribute/table names unchanged, clarify class names, and remove duplicated helper logic where it is real duplication.

## Findings

1. Spanish class names still exist in active backend code.
   - Audit: `Auditoria`, `ResultadoAuditoria`
   - Backup: `ConfiguracionBackup`, `EjecucionBackup`, `ResultadoBackup`
   - Installation/licensing/organization: `Instalacion`, `Licencia`, `ResultadoImportacion`, `Empresa`, `Tienda`
   - Security: `Usuario`, `Rol`, `Permiso`, `RolPermiso`, `Sesion`
   - Documents: `Documento`, `DocumentoLinea`, `DocumentoPago`, `DocumentoRelacion`, `EstadoDocumento`, `MetodoPago`, `TipoDocumento`, `TipoRelacionDocumento`, `ContadorDocumento`
   - Terminal: `TipoTerminal`

2. Spanish method names are limited and safe to rename later.
   - Examples: `solicitar`, `aprobar`, `desactivar`, `activar`, `validarEliminacion`.

3. Hardcoded Spanish errors remain in services and domain objects.
   - These should move to i18n or stable message codes after class renames.

4. Repeated validation helpers exist.
   - Repeated `required(String value, String field)` appears across many domain classes.
   - Repeated `requiredText` / generic `required` appears in VERI*FACTU records.
   - Do not extract every helper blindly; extract only common null/blank validation first.

5. Backend functionality still pending after cleanup.
   - Full production backup restore flow needs end-to-end validation.
   - Printing templates/receipts are not complete UI-ready outputs.
   - Frontend API contract should be frozen before frontend work.
   - Full VERI*FACTU official submission behavior still needs final real-environment validation.
   - Product/customer/supplier import/export API is not yet a finished frontend-ready workflow.

## Refactor Order

1. Rename Spanish classes to English while preserving entity table names and field names.
2. Rename obvious Spanish public methods where callers are internal.
3. Move user-facing Spanish exception text to i18n/message keys.
4. Extract minimal shared validation helpers.
5. Run focused compile/tests after each step.
