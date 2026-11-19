import { useState, type ReactNode } from "react";
import { useI18n } from "../i18n";
import { directoryLink, followDirectoryLink } from "./directory-navigation";
import "./directory-actions.css";

async function copyText(value: string) {
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(value); return; } catch { /* Try the user-initiated LAN fallback. */ }
  }
  const previous = document.activeElement as HTMLElement | null;
  const input = document.createElement("textarea");
  input.value = value; input.readOnly = true; input.style.cssText = "position:fixed;opacity:0;pointer-events:none";
  document.body.append(input);
  try { input.select(); if (!document.execCommand("copy")) throw new Error("Copy unavailable"); }
  finally { input.remove(); previous?.focus({ preventScroll: true }); }
}

export function DirectoryLink({ view, parameters, children, label, disabled = false }: {
  view: "companies" | "stores" | "licenses"; parameters: Record<string, string>; children: ReactNode; label?: string; disabled?: boolean;
}) {
  const href = directoryLink(view, parameters);
  return <a className="directory-link" href={disabled ? undefined : href} aria-label={label} aria-disabled={disabled || undefined}
    onClick={event => { if (disabled) event.preventDefault(); else followDirectoryLink(event, href); }}
    onDoubleClick={event => event.stopPropagation()}>{children}</a>;
}

export function DirectoryReturn() {
  const { language } = useI18n();
  if (!window.history.state?.directoryFrom) return null;
  return <button type="button" className="secondary-button directory-return" onClick={() => window.history.back()}>
    {language === "zh" ? "返回" : language === "en" ? "Back" : "Volver"}
  </button>;
}

export function CopyDirectoryValue({ value, label }: { value: string | null | undefined; label: string }) {
  const { language } = useI18n();
  const [result, setResult] = useState<"copied" | "failed" | null>(null);
  const copyLabel = language === "zh" ? "复制" : language === "en" ? "Copy" : "Copiar";
  const message = result === "copied" ? (language === "zh" ? "已复制" : language === "en" ? "Copied" : "Copiado")
    : result === "failed" ? (language === "zh" ? "无法复制，请选择文本。" : language === "en" ? "Could not copy. Select the text." : "No se pudo copiar. Selecciona el texto.") : "";
  if (!value) return <>—</>;
  return <span className="directory-copy"><span>{value}</span><button type="button" className="directory-copy-button" aria-label={`${copyLabel} ${label}`}
    title={`${copyLabel} ${label}`} onDoubleClick={event => event.stopPropagation()}
    onClick={async () => { try { await copyText(value); setResult("copied"); } catch { setResult("failed"); } }}>
    <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><rect x="7" y="7" width="10" height="11" rx="1"/><path d="M13 5V2H2v12h3"/></svg>
  </button><span className="directory-copy-feedback" role="status">{message}</span></span>;
}
