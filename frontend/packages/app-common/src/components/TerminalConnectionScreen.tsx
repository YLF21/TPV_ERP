import { useEffect, useRef, useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import { SaleSettingsShell, type SaleSettingsShellProps } from "./SaleSettingsShell";
import "./TerminalConnectionScreen.css";

type Bridge = NonNullable<NonNullable<Window["tpvDesktop"]>["backendConnection"]>;
type Probe = Extract<Awaited<ReturnType<Bridge["probe"]>>, { ok: true }>;
type Link = Extract<Awaited<ReturnType<Bridge["refreshLink"]>>, { ok: true }>["link"];

export function connectionUrl(hostInput: string, portInput: string): string | null {
  const host = hostInput.trim();
  const port = Number(portInput.trim());
  if (!host || !/^[A-Za-z0-9.\-\[\]:]+$/.test(host) || !Number.isInteger(port) || port < 1 || port > 65535) return null;
  try {
    const parsed = new URL(`https://${host}`);
    if (parsed.port || parsed.pathname !== "/" || parsed.search || parsed.hash || parsed.username || parsed.password) return null;
    const hostname = parsed.hostname;
    const local = ["localhost", "127.0.0.1", "[::1]"].includes(hostname);
    return `${local ? "http" : "https"}://${host}:${port}`;
  } catch { return null; }
}

function splitAddress(value?: string) {
  try {
    const url = new URL(value || "");
    return { host: url.hostname, port: url.port || (url.protocol === "https:" ? "443" : "80") };
  } catch { return { host: "", port: "" }; }
}

export function TerminalConnectionScreen({ locale, identity, onReady, onBack, settingsShell }: {
  locale: LocaleCode; identity: TerminalContext | null;
  onReady: (identity: TerminalContext) => void; onBack?: () => void;
  settingsShell?: Omit<SaleSettingsShellProps, "locale" | "active" | "heading" | "subtitle" | "children" | "navigationDisabled">;
}) {
  const t = createTranslator(locale);
  const bridge = window.tpvDesktop?.backendConnection;
  const [host, setHost] = useState("");
  const [port, setPort] = useState("");
  const [servers, setServers] = useState<Array<{ backendUrl: string; label: string }>>([]);
  const [probe, setProbe] = useState<Probe | null>(null);
  const [link, setLink] = useState<Link | null>(null);
  const [pendingRequest, setPendingRequest] = useState<{ code: string; name: string; backendUrl: string; mode: string } | null>(null);
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [adminRequired, setAdminRequired] = useState(false);
  const [adminUser, setAdminUser] = useState("");
  const [adminPassword, setAdminPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [restartRequired, setRestartRequired] = useState(false);
  const [error, setError] = useState("");
  const [deviceName, setDeviceName] = useState("");
  const [legacyIdentity, setLegacyIdentity] = useState<{ terminalId: string; terminalCode: string; storeName: string } | null>(null);
  const [linkedInstallation, setLinkedInstallation] = useState(identity?.installationId ?? "");
  const [linkedTerminal, setLinkedTerminal] = useState<{ code: string; name: string } | null>(null);
  const refreshRunning = useRef(false);
  const loadRunning = useRef(false);
  const busyRef = useRef(false);
  const mounted = useRef(false);
  const loadGeneration = useRef(0);
  const readyReported = useRef(false);
  const edited = useRef({ host: false, port: false, name: false, code: false });
  const pendingRef = useRef(false);

  function actionBusy(value: boolean) {
    busyRef.current = value;
    if (mounted.current) setBusy(value);
  }

  function reportReady(verified: TerminalContext | null | undefined, restart: boolean) {
    if (!identity && verified && !restart && mounted.current && !readyReported.current) {
      readyReported.current = true;
      onReady(verified);
    }
  }

  function showError(result: { code: string }) {
    const normalized = ({
      INVALID_ADDRESS: "INVALID_URL", INVALID_INPUT: "INVALID_URL",
      INVALID_BOOTSTRAP: "SIGNATURE_INVALID", INSTALLATION_KEY_CHANGED: "SIGNATURE_INVALID",
      OTHER_INSTALLATION: "INSTALLATION_MISMATCH", NOT_LINKED: "INSTALLATION_MISMATCH",
      LINK_PENDING: "PENDING_EXISTS", INSTALLATION_NOT_READY: "INSTALLATION_NOT_READY",
      SERVER_LOCAL_ONLY: "SERVER_LOCAL_ONLY",
      SERVER_SLOT_PROTECTED: "SERVER_LOCAL_ONLY", WORKSTATION_OCCUPIED: "SLOT_UNAVAILABLE",
      WORKSTATION_QUOTA_REACHED: "NO_CAPACITY", WORKSTATION_OUT_OF_QUOTA: "NO_CAPACITY",
      LICENSE_REQUIRED: "INSTALLATION_NOT_READY",
    } as Record<string, string>)[result.code] || result.code;
    const key = `terminalLink.error.${normalized}`;
    const translated = t(key);
    setError(translated === key ? t("terminalLink.error") : translated);
  }
  async function loadShared(initial = false) {
    if (!bridge || loadRunning.current || busyRef.current || refreshRunning.current) return;
    loadRunning.current = true;
    const generation = loadGeneration.current;
    try {
      const result = await bridge.load();
      if (!mounted.current || generation !== loadGeneration.current) return;
      if (!result.ok) { showError(result); return; }
      const address = splitAddress(result.pendingRequest?.backendUrl || result.configuration?.backendUrl);
      if (!edited.current.host) setHost(address.host);
      if (!edited.current.port) setPort(address.port);
      setDeviceName(result.deviceName);
      setLegacyIdentity(result.legacyIdentity ?? null);
      setLinkedInstallation(result.linkedIdentity?.installationId || identity?.installationId || "");
      setLinkedTerminal(result.linkedIdentity ? {
        code: result.linkedIdentity.terminalCode,
        name: result.linkedIdentity.terminalName || result.link?.terminalName || result.deviceName,
      } : null);
      if (!edited.current.name) setName(result.pendingRequest?.name || result.link?.terminalName || result.linkedIdentity?.terminalName || result.deviceName);
      if (initial || result.pendingRequest || (result.link?.status !== "RELEASED" && (result.link || result.linkedIdentity))) {
        setCode(result.pendingRequest?.code || result.link?.terminalCode || result.linkedIdentity?.terminalCode || "");
      }
      setLink(result.link ?? null);
      setPendingRequest(result.pendingRequest ?? null);
      pendingRef.current = result.link?.status === "PENDING" || !!result.pendingRequest;
      setRestartRequired(!!result.restartRequired);
      if (result.configurationError) showError({ code: result.configurationError });
      reportReady(result.link?.status === "ACTIVE" ? result.identity : null, !!result.restartRequired);
      if (initial && !identity && !result.configuration && !result.pendingRequest && !result.linkedIdentity) {
        window.setTimeout(() => { if (mounted.current && generation === loadGeneration.current) void discover(); }, 0);
      }
    } catch { if (mounted.current && generation === loadGeneration.current) setError(t("terminalLink.error")); }
    finally { loadRunning.current = false; }
  }

  useEffect(() => {
    mounted.current = true;
    if (!bridge) { setError(t("terminalLink.error")); return; }
    void loadShared(true);
    return () => { mounted.current = false; loadGeneration.current++; };
  }, [bridge, identity]);

  useEffect(() => {
    if (!bridge || identity || readyReported.current) return;
    const update = () => {
      if (readyReported.current) return;
      if (pendingRef.current && !restartRequired) void refresh(); else void loadShared();
    };
    const timer = window.setInterval(update, 5000);
    const onFocus = () => { if (!readyReported.current) void loadShared(); };
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onFocus);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onFocus);
    };
  }, [bridge, identity, restartRequired]);

  async function discover() {
    if (!bridge || busyRef.current || loadRunning.current || refreshRunning.current) return;
    actionBusy(true); setError("");
    try {
      const result = await bridge.discover();
      if (!mounted.current) return;
      if (result.ok) setServers(result.servers);
      else showError(result);
    } catch { if (mounted.current) setError(t("terminalLink.error")); }
    finally { actionBusy(false); }
  }
  async function check() {
    if (!bridge || busyRef.current || loadRunning.current || refreshRunning.current) return;
    const backendUrl = connectionUrl(host, port);
    if (!backendUrl) { showError({ code: "INVALID_URL" }); return; }
    actionBusy(true); setError(""); setProbe(null); edited.current.code = false;
    try {
      const result = await bridge.probe({ backendUrl });
      if (!mounted.current) return;
      if (!result.ok) { showError(result); return; }
      setProbe(result);
      const legacySlot = result.server.slots.find(slot => slot.terminalId && slot.terminalId === legacyIdentity?.terminalId);
      if (legacySlot && legacyIdentity?.terminalCode !== "SERVIDOR") {
        setCode(legacySlot.code);
        setName(legacySlot.name || deviceName);
        return;
      }
      const free = result.server.slots.filter(slot => !slot.outOfQuota
        && (result.localServer ? slot.code === "001" : slot.status === "FREE")
        && (slot.code !== "001" || result.localServer));
      setCode(current => free.some(slot => slot.code === current) ? current : free[0]?.code ?? "");
    } catch { if (mounted.current) setError(t("terminalLink.error")); }
    finally { actionBusy(false); }
  }
  async function requestLink() {
    if (!bridge || (!probe && !pendingRequest) || !code || !name.trim() || busyRef.current || loadRunning.current || refreshRunning.current) return;
    const backendUrl = connectionUrl(host, port);
    if (!backendUrl) { showError({ code: "INVALID_URL" }); return; }
    actionBusy(true); setError("");
    let activeLinked = false;
    try {
      const result = await bridge.requestLink({ backendUrl,
        code: pendingRequest?.code || (link?.status === "PENDING" ? link.terminalCode : code),
        name: pendingRequest?.name || (link?.status === "PENDING" ? link.terminalName : name.trim()),
        ...(adminRequired && code === "001" && (probe?.localServer || pendingRequest?.mode === "SERVER_ADMIN")
          ? { administrator: { username: adminUser.trim(), password: adminPassword } } : {}) });
      if (!mounted.current) return;
      setAdminPassword("");
      if (!result.ok) {
        if (result.code === "SERVER_AUTH_REQUIRED" && code === "001") setAdminRequired(true);
        showError(result); return;
      }
      setLink(result.link);
      setPendingRequest(null);
      pendingRef.current = result.link.status === "PENDING";
      setRestartRequired(!!result.restartRequired);
      reportReady(result.link.status === "ACTIVE" ? result.identity : null, !!result.restartRequired);
      activeLinked = result.link.status === "ACTIVE";
    } catch { if (mounted.current) setError(t("terminalLink.error")); }
    finally { actionBusy(false); if (activeLinked && mounted.current) void loadShared(); }
  }
  async function refresh() {
    if (!bridge || identity || refreshRunning.current || loadRunning.current || busyRef.current) return;
    refreshRunning.current = true;
    try {
      const result = await bridge.refreshLink();
      if (!mounted.current) return;
      if (!result.ok) { if (result.code === "SERVER_AUTH_REQUIRED") setAdminRequired(true); showError(result); return; }
      setLink(result.link);
      setPendingRequest(null);
      pendingRef.current = result.link.status === "PENDING";
      setRestartRequired(!!result.restartRequired);
      reportReady(result.link.status === "ACTIVE" ? result.identity : null, !!result.restartRequired);
    } catch { if (mounted.current) setError(t("terminalLink.error")); }
    finally { refreshRunning.current = false; }
  }
  async function cancel() {
    if (!bridge || busyRef.current || loadRunning.current || refreshRunning.current) return;
    actionBusy(true); setError("");
    try {
      const result = await bridge.cancelLink();
      if (!mounted.current) return;
      if (result.ok) { setLink(null); setPendingRequest(null); pendingRef.current = false; setProbe(null); }
      else showError(result);
    } catch { if (mounted.current) setError(t("terminalLink.error")); }
    finally { actionBusy(false); }
  }
  async function saveAddress() {
    if (!bridge || busyRef.current || loadRunning.current || refreshRunning.current) return;
    const backendUrl = connectionUrl(host, port);
    if (!backendUrl) { showError({ code: "INVALID_URL" }); return; }
    actionBusy(true); setError("");
    try {
      if (!probe?.sameInstallation) { showError({ code: "INSTALLATION_MISMATCH" }); return; }
      const result = await bridge.saveAddress({ backendUrl });
      if (!mounted.current) return;
      if (result.ok) setRestartRequired(result.restartRequired);
      else showError(result);
    } catch { if (mounted.current) setError(t("terminalLink.error")); }
    finally { actionBusy(false); }
  }
  async function restart() {
    if (!bridge || busyRef.current || loadRunning.current || refreshRunning.current) return;
    actionBusy(true); setError("");
    try { const result = await bridge.restart(); if (mounted.current && !result.ok) showError(result); }
    catch { if (mounted.current) setError(t("terminalLink.error")); }
    finally { actionBusy(false); }
  }
  const available = probe?.server.slots.filter(slot => !slot.outOfQuota
    && (probe.localServer ? slot.code === "001" : slot.status === "FREE")
    && (slot.code !== "001" || probe.localServer)) ?? [];
  const serverReady = !!probe?.server.installationId && !!probe.server.storeId && probe.server.maxWindows > 0;
  const legacySlot = legacyIdentity && legacyIdentity.terminalCode !== "SERVIDOR"
    ? probe?.server.slots.find(slot => slot.terminalId === legacyIdentity.terminalId && !slot.outOfQuota) : undefined;
  const linkedNeedsRestart = !identity && !!linkedTerminal && restartRequired && link?.status !== "RELEASED";
  const panel = <section className={`settings-card terminal-link-panel${settingsShell ? " terminal-link-panel--embedded" : ""}`}>
    {!settingsShell && <header><h1>{t("terminalLink.title")}</h1><p>{identity || linkedInstallation ? t("terminalLink.addressHelp") : t("terminalLink.setup")}</p></header>}
    <div className="terminal-link-toolbar"><button type="button" disabled={busy} onClick={() => void discover()}>{t("terminalLink.search")}</button>
      {onBack && !settingsShell && <button type="button" disabled={busy || restartRequired} onClick={onBack}>{t("common.back")}</button>}</div>
    {servers.length > 0 && <label>{t("terminalLink.backend")}<select value="" onChange={event => {
      const address = splitAddress(event.target.value); edited.current.host = true; edited.current.port = true;
      setHost(address.host); setPort(address.port); setProbe(null);
    }}><option value="">{t("terminalLink.backend")}</option>{servers.map(server =>
      <option key={server.backendUrl} value={server.backendUrl}>{server.label}</option>)}</select></label>}
    <div className="terminal-link-address"><label>{t("terminalLink.host")}<input value={host} onChange={event => { edited.current.host = true; setHost(event.target.value); setProbe(null); }} autoComplete="off" /></label>
      <label>{t("terminalLink.port")}<input value={port} onChange={event => { edited.current.port = true; setPort(event.target.value); setProbe(null); }} inputMode="numeric" autoComplete="off" /></label></div>
    <div className="terminal-link-toolbar"><button type="button" disabled={busy} onClick={() => void check()}>{t("terminalLink.connect")}</button></div>
    {probe && <div className="terminal-link-server"><div><strong>{t("terminalLink.store")}</strong><span>{probe.server.storeName || "—"}</span></div>
      <div><strong>{t("terminalLink.installation")}</strong><span>{probe.server.installationReference || "—"}</span></div>
      <div><strong>{t("terminalLink.capacity")}</strong><span>{serverReady ? probe.server.maxWindows : "—"}</span></div></div>}
    {probe && !serverReady && <p role="status">{t("terminalLink.serverSetup")}</p>}
    {!identity && !linkedNeedsRestart && (link?.status === "PENDING" || pendingRequest) && <div className="terminal-link-pending" role="status"><p>{t("terminalLink.pending")}</p>
      <strong>{pendingRequest?.code || link?.terminalCode} · {pendingRequest?.name || link?.terminalName}</strong><div className="terminal-link-toolbar">
        <button type="button" disabled={busy} onClick={() => void refresh()}>{t("terminalLink.retry")}</button>
        <button type="button" disabled={busy} onClick={() => void cancel()}>{t("terminalLink.cancel")}</button>
      </div></div>}
    {!identity && !linkedNeedsRestart && (link?.status === "PENDING" || pendingRequest) && adminRequired && (pendingRequest?.code || link?.terminalCode) === "001" && <fieldset><legend>{t("terminalLink.localAdmin")}</legend>
      <label>{t("terminalLink.adminUser")}<input value={adminUser} onChange={event => setAdminUser(event.target.value)} autoComplete="username" /></label>
      <label>{t("terminalLink.adminPassword")}<input type="password" value={adminPassword} onChange={event => setAdminPassword(event.target.value)} autoComplete="current-password" /></label>
      <button type="button" disabled={busy || !adminUser.trim() || !adminPassword} onClick={() => void requestLink()}>{t("terminalLink.retry")}</button>
    </fieldset>}
    {linkedNeedsRestart && <div className="terminal-link-pending" role="status"><p>{t("terminalLink.sharedLinked")}</p>
      <strong>{linkedTerminal.code} · {linkedTerminal.name}</strong></div>}
    {!identity && link?.status === "ACTIVE" && !linkedNeedsRestart && <div className="terminal-link-pending" role="status"><p>{t(restartRequired ? "terminalLink.restartHelp" : "terminalLink.approved")}</p>
      <button type="button" disabled={busy} onClick={() => void refresh()}>{t("terminalLink.retry")}</button></div>}
    {!identity && (!linkedInstallation || link?.status === "RELEASED") && !linkedNeedsRestart && !pendingRequest && (!link || !["PENDING", "ACTIVE"].includes(link.status)) && probe && serverReady && <>
      {legacyIdentity && legacyIdentity.terminalCode !== "SERVIDOR" ? legacySlot ? <div className="terminal-link-pending">
        <p>{t("terminalLink.legacyHelp")}</p><strong>{legacySlot.code} · {legacySlot.name || name}</strong>
        <button type="button" disabled={busy} onClick={() => void requestLink()}>{t("terminalLink.legacyAdopt")}</button>
      </div> : <p role="status">{t("terminalLink.legacyAssign")}</p>
      : available.length ? <><label>{t("terminalLink.code")}<select value={code} onChange={event => { edited.current.code = true; setCode(event.target.value); setAdminRequired(false); }}>
        {available.map(slot => <option key={slot.code} value={slot.code}>{slot.code}</option>)}</select></label>
        <label>{t("terminalLink.name")}<input value={name} maxLength={80} onChange={event => { edited.current.name = true; setName(event.target.value); }} placeholder={deviceName} /></label>
        {adminRequired && code === "001" && probe.localServer && <fieldset><legend>{t("terminalLink.localAdmin")}</legend>
          <label>{t("terminalLink.adminUser")}<input value={adminUser} onChange={event => setAdminUser(event.target.value)} autoComplete="username" /></label>
          <label>{t("terminalLink.adminPassword")}<input type="password" value={adminPassword} onChange={event => setAdminPassword(event.target.value)} autoComplete="current-password" /></label>
        </fieldset>}
        <button type="button" disabled={busy || !code || !name.trim() || (adminRequired && (!adminUser.trim() || !adminPassword))} onClick={() => void requestLink()}>{t("terminalLink.request")}</button></>
        : <p role="status">{t("terminalLink.noSlots")}</p>}</>}
    {(identity || linkedInstallation || pendingRequest) && probe?.sameInstallation && <div className="terminal-link-toolbar"><button type="button" disabled={busy || restartRequired} onClick={() => void saveAddress()}>{t("terminalLink.saveAddress")}</button></div>}
    {identity && restartRequired && <button type="button" disabled={busy} onClick={() => void restart()}>{t("terminalLink.restart")}</button>}
    {!identity && restartRequired && <button type="button" disabled={busy} onClick={() => void restart()}>{t("terminalLink.restart")}</button>}
    {error && <p role="alert" className="terminal-link-error">{error}</p>}
  </section>;
  return settingsShell ? <SaleSettingsShell {...settingsShell} locale={locale} active="connection"
    heading={t("terminalLink.title")}
    subtitle={t(identity || linkedInstallation ? "terminalLink.addressHelp" : "terminalLink.setup")}
    navigationDisabled={busy || restartRequired}>
    {panel}
  </SaleSettingsShell> : <main className="settings-screen terminal-link-screen">{panel}</main>;
}
