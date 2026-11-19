import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { ApiError, apiProblemCode } from "../api/client";
import { cancelWebTerminal, loadWebTerminalStatus, requestWebTerminal, type WebTerminalStatus } from "../webTerminalIdentity";
import type { LocaleCode, TerminalContext } from "../types";
import { AppBrand } from "./AppBrand";
import "./BrowserTerminalRegistrationScreen.css";

import { browserTerminalCopy } from "./browserTerminalCopy";
export { browserTerminalCopy } from "./browserTerminalCopy";
export function browserTerminalError(error: unknown, locale: LocaleCode): string {
  const copy = browserTerminalCopy(locale);
  const code = apiProblemCode(error);
  if (code === "WEB_TERMINAL_BROWSER_UNSUPPORTED") return copy.unsupported;
  if (code === "WEB_TERMINAL_DISABLED" || error instanceof ApiError && error.status === 404) return copy.off;
  if (code === "WEB_TERMINAL_ORIGIN_FORBIDDEN" || code === "WEB_TERMINAL_REQUEST_FORBIDDEN") return copy.forbidden;
  if (code === "WEB_TERMINAL_INVALID_RESPONSE") return copy.invalid;
  if (code === "WEB_TERMINAL_PROOF_INVALID" || code === "LINK_PROOF_INVALID" || code === "WEB_TERMINAL_COOKIE_REQUIRED") return copy.proof;
  if (error instanceof ApiError && error.status === 409) return copy.conflict;
  return copy.error;
}

export function BrowserTerminalFrame({ locale, onLocaleChange, children }: { locale: LocaleCode; onLocaleChange: (locale: LocaleCode) => void; children: ReactNode }) {
  const copy = browserTerminalCopy(locale);
  return <div className="web-terminal-page" lang={locale === "zh" ? "zh-CN" : locale}>
    <header className="web-terminal-topbar"><div><AppBrand app="venta" label="esPOS" /><span>{copy.brand}</span></div>
      <label><span>{copy.language}</span><select aria-label={copy.language} value={locale} onChange={event => onLocaleChange(event.target.value as LocaleCode)}>
        <option value="es">Español</option><option value="en">English</option><option value="zh">中文</option>
      </select></label>
    </header>{children}
  </div>;
}

export function BrowserTerminalRegistrationScreen({ locale, onLocaleChange, onReady, initialStatus = null, initialError, onBack }: {
  locale: LocaleCode; onLocaleChange: (locale: LocaleCode) => void; onReady: (identity: TerminalContext) => void;
  initialStatus?: WebTerminalStatus | null; initialError?: unknown; onBack?: () => void;
}) {
  const copy = browserTerminalCopy(locale);
  const [state, setState] = useState(initialStatus);
  const [error, setError] = useState<unknown>(initialError);
  const [busy, setBusy] = useState(false);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const requestRef = useRef<AbortController | null>(null);
  const readyRef = useRef(onReady);
  readyRef.current = onReady;
  const run = useCallback(async (operation: (signal: AbortSignal) => Promise<WebTerminalStatus>) => {
    if (requestRef.current) return;
    const controller = new AbortController();
    requestRef.current = controller;
    setBusy(true);
    try {
      const next = await operation(controller.signal);
      if (controller.signal.aborted) return;
      setState(next); setError(undefined);
      if (next.status === "ACTIVE" && next.context) readyRef.current(next.context);
    } catch (caught) {
      if (!controller.signal.aborted) setError(caught);
    } finally {
      if (requestRef.current === controller) requestRef.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }, []);
  const refresh = useCallback(() => void run(loadWebTerminalStatus), [run]);
  useEffect(() => {
    if (!initialStatus && !initialError) refresh();
    return () => { requestRef.current?.abort(); requestRef.current = null; };
  }, []);
  useEffect(() => {
    if (state?.status !== "PENDING") return;
    const check = () => { if (document.visibilityState !== "hidden") refresh(); };
    const timer = window.setInterval(check, 5000);
    document.addEventListener("visibilitychange", check);
    return () => { window.clearInterval(timer); document.removeEventListener("visibilitychange", check); };
  }, [state?.status, refresh]);
  const freeSlots = state?.slots.filter(slot => slot.status === "FREE") ?? [];
  const pending = state?.status === "PENDING";
  const disabled = state?.status === "DISABLED";
  const active = state?.status === "ACTIVE";
  const retired = state && !["UNREGISTERED", "PENDING", "ACTIVE", "DISABLED"].includes(state.status);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!freeSlots.some(slot => slot.code === code) || !name.trim()) return;
    await run(signal => requestWebTerminal(code, name, signal));
  }
  return <BrowserTerminalFrame locale={locale} onLocaleChange={onLocaleChange}>
    <main className="web-terminal-layout"><section className="web-terminal-intro"><span className="web-terminal-eyebrow">{copy.step}</span>
      <h1>{copy.title}</h1><p>{copy.intro}</p>
      <div className="web-terminal-guidance">{([[copy.approval, copy.approvalDetail], [copy.storage, copy.storageDetail], [copy.hardware, copy.hardwareDetail]]).map(([title, detail], index) =>
        <article key={title}><span aria-hidden="true">0{index + 1}</span><div><h2>{title}</h2><p>{detail}</p></div></article>)}</div>
    </section>
    <section className="web-terminal-card" aria-busy={busy}>
      <h2>{pending ? copy.pending : disabled ? copy.disabled : active ? copy.active : retired ? copy.retired : copy.slot}</h2>
      {(pending || disabled || active || retired) && <p role="status">{pending ? copy.pendingDetail : disabled ? copy.disabledDetail : active ? copy.activeDetail : copy.retiredDetail}</p>}
      {active && state.context && <p className="web-terminal-binding"><strong>{state.context.storeName}</strong><span>{state.context.terminalCode} · {state.context.terminalName}</span></p>}
      {!!error && <p className="web-terminal-error" role="alert">{browserTerminalError(error, locale)}</p>}
      {state?.status === "UNREGISTERED" && <form onSubmit={submit}>
        <label htmlFor="web-terminal-code">{copy.slot}</label>
        <select id="web-terminal-code" value={code} disabled={busy || !freeSlots.length} required onChange={event => setCode(event.target.value)}>
          <option value="">{copy.choose}</option>{freeSlots.map(slot => <option key={slot.code} value={slot.code}>{slot.code}{slot.name ? ` · ${slot.name}` : ""}</option>)}
        </select>
        {!freeSlots.length && <p className="web-terminal-muted" role="status">{copy.empty}</p>}
        <label htmlFor="web-terminal-name">{copy.name}</label><input id="web-terminal-name" value={name} maxLength={80} required autoComplete="off" placeholder={copy.placeholder} disabled={busy} onChange={event => setName(event.target.value)} />
        <button className="web-terminal-primary" disabled={busy || !freeSlots.some(slot => slot.code === code) || !name.trim()}>{busy ? copy.working : copy.submit}</button>
      </form>}
      <div className="web-terminal-actions"><button type="button" disabled={busy} onClick={refresh}>{busy ? copy.working : copy.refresh}</button>
        {pending && <button type="button" disabled={busy} onClick={() => void run(cancelWebTerminal)}>{copy.cancel}</button>}
        {retired && <button type="button" disabled={busy} onClick={() => void run(cancelWebTerminal)}>{copy.reset}</button>}
        {onBack && <button type="button" disabled={busy} onClick={onBack}>{copy.back}</button>}
      </div>
    </section></main>
  </BrowserTerminalFrame>;
}
