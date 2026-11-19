import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useI18n } from "../../i18n";
import { requestBinary } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { errorMessage } from "../lib";
import { reportExportFilename } from "./export-filename.mjs";
import "./analytics.css";

type Filters = Record<string, string>;
type Saved = { name: string; filters: Filters };
type Settings = { hiddenColumns: string[]; savedFilters: Saved[] };
function readSettings(key: string, defaults: string[]): Settings {
  const empty: Settings = { hiddenColumns: defaults, savedFilters: [] };
  try {
    const data = JSON.parse(localStorage.getItem(key) ?? "null");
    if (!data || !Array.isArray(data.hiddenColumns) || !Array.isArray(data.savedFilters)) return empty;
    return {
      hiddenColumns: data.hiddenColumns.filter((id: unknown) => typeof id === "string").slice(0, 100),
      savedFilters: data.savedFilters.filter((item: Saved) => item && typeof item.name === "string"
        && item.filters && Object.values(item.filters).every(value => typeof value === "string"))
        .slice(0, 20),
    };
  } catch { return empty; }
}

/** Preferences contain filters and column IDs only, never access tokens or report data. */
export function useReportPreferences(credentials: Credentials, scopeKey: string, defaultHidden: string[] = []) {
  const key = `espos.analytics.v1.${encodeURIComponent(credentials.username)}.${credentials.mode}.${scopeKey}`;
  const defaultsKey = defaultHidden.join("|");
  const initial = useMemo(() => readSettings(key, defaultHidden), [key, defaultsKey]);
  const [saved, setSaved] = useState({ key, value: initial });
  const value = saved.key === key ? saved.value : initial;
  function update(change: (current: Settings) => Settings) {
    setSaved(current => {
      const next = change(current.key === key ? current.value : initial);
      try { localStorage.setItem(key, JSON.stringify(next)); } catch { /* Session-only when storage is unavailable. */ }
      return { key, value: next };
    });
  }
  return {
    ...value,
    isVisible: (id: string) => !value.hiddenColumns.includes(id),
    setHiddenColumns: (ids: string[]) => update(current => ({ ...current, hiddenColumns: ids })),
    save: (name: string, filters: Filters) => update(current => ({ ...current, savedFilters: [
      ...current.savedFilters.filter(item => item.name !== name.trim()),
      { name: name.trim().slice(0, 80), filters: { ...filters } },
    ].slice(-20) })),
    remove: (name: string) => update(current => ({ ...current, savedFilters: current.savedFilters.filter(item => item.name !== name) })),
  };
}

export function ReportTools({ credentials, preferences, filters = {}, columns = [], exportPath, leadingAction, exportName }: {
  credentials: Credentials;
  preferences: ReturnType<typeof useReportPreferences>;
  filters?: Filters;
  onRestoreFilters?: (filters: Filters) => void;
  columns?: { id: string; label: string; required?: boolean; defaultHidden?: boolean }[];
  exportPath?: string;
  exportName?: string;
  leadingAction?: ReactNode;
}) {
  const { language } = useI18n();
  const l = (es: string, en: string, zh: string) => language === "zh" ? zh : language === "en" ? en : es;
  const [busy, setBusy] = useState(false);
  const [layout, setLayout] = useState("readable");
  const [error, setError] = useState<string | null>(null);
  const operation = useRef(0);
  useEffect(() => { operation.current++; setBusy(false); setError(null); return () => { operation.current++; }; }, [credentials.accessToken, exportPath]);

  async function downloadExcel() {
    if (!exportPath || busy) return;
    const current = ++operation.current;
    setBusy(true); setError(null);
    try {
      const blob = await requestBinary(credentials, `${exportPath}${exportPath.includes("?") ? "&" : "?"}format=xlsx&language=${language}&layout=${layout}`);
      if (operation.current !== current) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url; link.download = reportExportFilename(exportPath, filters, "xlsx", language, exportName);
      document.body.append(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (reason) { if (operation.current === current) setError(errorMessage(reason)); }
    finally { if (operation.current === current) setBusy(false); }
  }

  return <div className="analytics-tools">
    {leadingAction}
    {columns.some(column => !column.required) && <details className="analytics-preferences">
      <summary>{l("Columnas", "Columns", "列")}</summary>
      <div className="analytics-preferences-body">
        <p>{l("Preferencias de este usuario en este navegador.", "Preferences for this user in this browser.", "此用户在当前浏览器中的偏好设置。")}</p>
        {columns.length > 0 && <fieldset className="analytics-columns"><legend>{l("Columnas visibles", "Visible columns", "显示的列")}</legend>{columns.map(column => <label key={column.id}>
          <input type="checkbox" checked={column.required || preferences.isVisible(column.id)} disabled={column.required} onChange={event => preferences.setHiddenColumns(event.target.checked ? preferences.hiddenColumns.filter(id => id !== column.id) : [...preferences.hiddenColumns, column.id])} />{column.label}
        </label>)}</fieldset>}
        <button type="button" className="small-button" onClick={() => preferences.setHiddenColumns(columns.filter(column => column.defaultHidden && !column.required).map(column => column.id))}>{l("Restablecer columnas", "Reset columns", "重置列")}</button>
      </div>
    </details>}
    {exportPath && <div className="analytics-exports" aria-busy={busy}>
      <label className="analytics-export-layout">{l("Contenido", "Content", "内容")}
        <select className="control-input" value={layout} disabled={busy} onChange={event => setLayout(event.target.value)}>
          <option value="readable">{l("Informe legible", "Readable report", "易读报表")}</option>
          <option value="complete">{l("Detalle completo", "Complete detail", "完整明细")}</option>
        </select>
      </label>
      <span className="analytics-export-note">{busy ? l("Exportando…", "Exporting…", "正在导出…") : l("Todos los resultados filtrados", "All filtered results", "全部筛选结果")}</span>
      <button type="button" className="small-button" disabled={busy} onClick={() => void downloadExcel()}>{l("Exportar Excel", "Export Excel", "导出 Excel")}</button>
    </div>}
    {error && <p role="alert" className="error-message">{error}</p>}
  </div>;
}
