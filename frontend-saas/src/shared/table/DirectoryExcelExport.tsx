import { useEffect, useRef, useState } from "react";
import { useI18n } from "../../i18n";
import { requestBinary } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { query } from "../../lib/workspace-api";
import { errorMessage } from "../lib";
import "./directory-export.css";

const names = {
  companies: ["Empresas", "Companies", "企业"],
  stores: ["Tiendas", "Stores", "门店"],
  licenses: ["Licencias", "Licenses", "许可证"],
} as const;

export function DirectoryExcelExport({ credentials, resource, filters, sort, companyIds, disabled = false }: {
  credentials: Credentials;
  resource: keyof typeof names;
  filters: Record<string, string>;
  sort: { key: string; direction: "asc" | "desc" };
  companyIds?: readonly string[];
  disabled?: boolean;
}) {
  const { language } = useI18n();
  const l = (es: string, en: string, zh: string) => language === "zh" ? zh : language === "en" ? en : es;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const active = useRef<AbortController | null>(null);
  const scope = JSON.stringify([credentials.accessToken, credentials.mode, credentials.companyId,
    credentials.username, language, resource, filters, sort, companyIds]);
  const currentScope = useRef(scope);
  currentScope.current = scope;
  useEffect(() => {
    setBusy(false); setError(null);
    return () => { active.current?.abort(); active.current = null; };
  }, [scope]);

  async function download() {
    if (disabled || active.current) return;
    const controller = new AbortController();
    active.current = controller;
    const startedScope = scope;
    const filename = `espos-${names[resource][language === "zh" ? 2 : language === "en" ? 1 : 0]}-${new Date().toISOString().slice(0, 10)}.xlsx`;
    const current = () => active.current === controller && currentScope.current === startedScope;
    setBusy(true); setError(null);
    try {
      const sortParameters = { sortBy: sort.key, sortDirection: sort.direction.toUpperCase() };
      const parameters = resource === "companies" ? { language } : { ...filters, ...sortParameters, language };
      const path = `/api/v1/admin/directories/export/${resource}${query(parameters)}`;
      const blob = await requestBinary(credentials, path, {
        signal: controller.signal,
        ...(resource === "companies" ? { method: "POST" as const,
          body: { companyIds: companyIds ?? [], filters, ...sortParameters } } : {}),
      });
      if (!current()) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      try {
        link.href = url; link.download = filename;
        document.body.append(link); link.click();
      } finally {
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
    } catch (reason) {
      if (current() && !controller.signal.aborted) setError(errorMessage(reason));
    } finally {
      if (current()) { active.current = null; setBusy(false); }
    }
  }

  return <div className="directory-excel-export" aria-busy={busy}>
    <button type="button" className="secondary-button" disabled={disabled || busy}
      title={l("Exporta todos los resultados filtrados y todas las columnas de la tabla.",
        "Exports all filtered results and all table columns.", "导出全部筛选结果及表格的所有列。")}
      onClick={() => void download()}>
      {busy ? l("Exportando…", "Exporting…", "正在导出…") : l("Exportar Excel", "Export Excel", "导出 Excel")}
    </button>
    {error && <p className="error-message" role="alert">{error}</p>}
  </div>;
}
