import { useEffect, useMemo, useState } from "react";
import { apiRequest, type UserSession } from "@tpverp/app-common";
import "./TerminalServerConnection.css";

export type TerminalServerConnection = {
  addresses: string[];
  httpsPort: number | null;
  backendPort: number;
  publicUrl: string | null;
};

export type RegisteredTerminal = {
  id: string;
  name: string;
  type: "SERVIDOR" | "TERMINAL_VENTA" | "PDA";
  approved: boolean;
  active: boolean;
  lastIp?: string | null;
};

export type PdaPairingCode = { code: string; expiresAt: string };
export type WorkstationSlot = { code: string; status: string; name?: string; deviceName?: string;
  lastSeenAt?: string; lastIp?: string | null; terminalId?: string; bindingId?: string; expiresAt?: string; outOfQuota?: boolean };
export type WorkstationView = { maxWindows: number; slots: WorkstationSlot[];
  legacyTerminals: RegisteredTerminal[] };
export type WorkstationHistory = { bindingId: string; requestId?: string; deviceName?: string;
  name?: string; status: string; createdAt: string; approvedAt?: string; endedAt?: string };

export function workstationActionPath(code: string, action: "approve" | "cancel" | "deactivate" | "release") {
  return `/terminals/workstations/${encodeURIComponent(code)}/${action}`;
}

export function terminalApprovePath(id: string) {
  return `/terminals/${encodeURIComponent(id)}/approve`;
}

export function terminalDeactivatePath(id: string) {
  return `/terminals/${encodeURIComponent(id)}/deactivate`;
}

export function terminalPairingPath(id: string) {
  return `/terminals/${encodeURIComponent(id)}/pairing-code`;
}

export function terminalDisplayStatus(terminal: Pick<RegisteredTerminal, "approved" | "active">) {
  if (!terminal.approved) return "pending";
  return terminal.active ? "approved" : "inactive";
}

export function TerminalManagementScreen({ session, t }: {
  session: UserSession;
  t: (key: string) => string;
}) {
  const [terminals, setTerminals] = useState<RegisteredTerminal[]>([]);
  const [workstations, setWorkstations] = useState<WorkstationView | null>(null);
  const [selectedSlotCode, setSelectedSlotCode] = useState("");
  const [selectedLegacyId, setSelectedLegacyId] = useState("");
  const [assignCode, setAssignCode] = useState("");
  const [history, setHistory] = useState<WorkstationHistory[] | null>(null);
  const [historyRevision, setHistoryRevision] = useState(0);
  const [historyError, setHistoryError] = useState(false);
  const [selectedId, setSelectedId] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [pairing, setPairing] = useState<PdaPairingCode | null>(null);
  const [serverConnection, setServerConnection] = useState<TerminalServerConnection | null>(null);
  const [connectionRevision, setConnectionRevision] = useState(0);
  const [connectionLoading, setConnectionLoading] = useState(true);
  const [connectionError, setConnectionError] = useState(false);
  const token = session.accessToken;
  const selected = terminals.find((terminal) => terminal.id === selectedId) ?? null;
  const selectedSlot = workstations?.slots.find(slot => slot.code === selectedSlotCode);
  const selectedLegacy = workstations?.legacyTerminals.find(terminal => terminal.id === selectedLegacyId);
  const pendingCount = useMemo(() => terminals.filter((terminal) => !terminal.approved).length
    + (workstations?.slots.filter(slot => slot.status === "PENDING").length ?? 0), [terminals, workstations]);
  const freeCodes = workstations?.slots.filter(slot => slot.status === "FREE" && !slot.outOfQuota).map(slot => slot.code) ?? [];
  const legacyCodes = workstations?.slots.filter(slot => slot.status === "FREE" && !slot.outOfQuota && !slot.terminalId).map(slot => slot.code) ?? [];
  const usedCount = workstations?.slots.filter(slot => slot.status !== "FREE" && !slot.outOfQuota).length ?? 0;

  async function refresh(preferredId = selectedId) {
    setLoading(true);
    setError("");
    try {
      const [values, workstationsView] = await Promise.all([
        apiRequest<RegisteredTerminal[]>("/terminals", { token }),
        apiRequest<WorkstationView>("/terminals/workstations", { token })
      ]);
      const pda = values.filter(terminal => terminal.type === "PDA");
      setTerminals(pda);
      setWorkstations(workstationsView);
      setHistoryRevision(value => value + 1);
      setSelectedId(pda.some(terminal => terminal.id === preferredId) ? preferredId : "");
      if (!selectedSlotCode && !selectedLegacyId && !preferredId) setSelectedSlotCode(workstationsView.slots[0]?.code ?? "");
    } catch {
      setError(t("gestion.terminals.loadError"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(""); }, [token]);

  useEffect(() => {
    let active = true;
    setServerConnection(null);
    setConnectionLoading(true);
    setConnectionError(false);
    void apiRequest<TerminalServerConnection>("/terminals/server-connection", { token })
      .then(value => { if (active) setServerConnection(value); })
      .catch(() => { if (active) { setServerConnection(null); setConnectionError(true); } })
      .finally(() => { if (active) setConnectionLoading(false); });
    return () => { active = false; };
  }, [token, connectionRevision]);

  useEffect(() => {
    setHistory(null);
    setHistoryError(false);
    if (!selectedSlotCode) return;
    let active = true;
    void apiRequest<WorkstationHistory[]>(`/terminals/workstations/${encodeURIComponent(selectedSlotCode)}/history`, { token })
      .then(rows => { if (active) setHistory(rows); })
      .catch(() => { if (active) { setHistory([]); setHistoryError(true); } });
    return () => { active = false; };
  }, [selectedSlotCode, token, historyRevision]);

  async function actOnSlot(action: "approve" | "cancel" | "deactivate" | "release") {
    if (!selectedSlot?.bindingId || busy) return;
    if (action !== "approve" && !window.confirm(`${t(`gestion.terminals.workstations.confirm.${action}`)}\n\n${selectedSlot.code} · ${selectedSlot.name || ""}\n${selectedSlot.deviceName || ""}`)) return;
    setBusy(true); setError("");
    try {
      await apiRequest(workstationActionPath(selectedSlot.code, action), {
        token, method: "POST", body: { bindingId: selectedSlot.bindingId }
      });
      await refresh();
    } catch { setError(t("gestion.terminals.workstations.actionError")); }
    finally { setBusy(false); }
  }

  async function assignLegacy() {
    if (!selectedLegacy || !assignCode || busy) return;
    setBusy(true); setError("");
    try {
      await apiRequest(`/terminals/workstations/legacy/${encodeURIComponent(selectedLegacy.id)}/assign-code`, {
        token, method: "POST", body: { code: assignCode }
      });
      setSelectedSlotCode(assignCode); setSelectedLegacyId(""); setAssignCode("");
      await refresh();
    } catch { setError(t("gestion.terminals.workstations.assignError")); }
    finally { setBusy(false); }
  }

  async function approve() {
    if (!selected) return;
    setBusy(true);
    setError("");
    try {
      const updated = await apiRequest<RegisteredTerminal>(terminalApprovePath(selected.id), { token, method: "POST" });
      setTerminals((current) => current.map((terminal) => terminal.id === updated.id ? updated : terminal));
    } catch {
      setError(t("gestion.terminals.approveError"));
    } finally {
      setBusy(false);
    }
  }

  async function deactivate() {
    if (!selected || !window.confirm(t("gestion.terminals.deactivateConfirm"))) return;
    setBusy(true);
    setError("");
    try {
      await apiRequest(terminalDeactivatePath(selected.id), { token, method: "POST" });
      setPairing(null);
      await refresh(selected.id);
    } catch {
      setError(t("gestion.terminals.deactivateError"));
    } finally {
      setBusy(false);
    }
  }

  async function createPairingCode() {
    if (!selected || selected.type !== "PDA") return;
    setBusy(true);
    setError("");
    setPairing(null);
    try {
      setPairing(await apiRequest<PdaPairingCode>(terminalPairingPath(selected.id), { token, method: "POST" }));
    } catch {
      setError(t("gestion.terminals.pairingError"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="gestion-workspace gestion-terminal-workspace">
      <header className="gestion-terminal-heading">
        <div>
          <span>{t("gestion.security.eyebrow")}</span>
          <h2>{t("gestion.terminals.title")}</h2>
          <p>{t("gestion.terminals.subtitle")}</p>
        </div>
        <button type="button" disabled={loading || busy} onClick={() => {
          setConnectionRevision(value => value + 1);
          void refresh();
        }}>{t("common.refresh")}</button>
      </header>

      <section className="gestion-terminal-server-connection" aria-label={t("gestion.terminals.serverConnection.title")} aria-busy={connectionLoading}>
        <h3>{t("gestion.terminals.serverConnection.title")}</h3>
        {connectionLoading ? <p role="status">{t("common.loading")}</p> : connectionError ?
          <p className="gestion-inline-error" role="alert">{t("gestion.terminals.serverConnection.loadError")}</p> : serverConnection &&
          <dl>
            <div><dt>{t("gestion.terminals.serverConnection.addresses")}</dt><dd>{serverConnection.addresses.length ?
              serverConnection.addresses.map(address => <span key={address}>{address}</span>) : t("gestion.terminals.serverConnection.noAddresses")}</dd></div>
            <div><dt>{t("gestion.terminals.serverConnection.httpsPort")}</dt><dd>{serverConnection.httpsPort ?? t("gestion.terminals.serverConnection.notConfigured")}</dd></div>
            <div><dt>{t("gestion.terminals.serverConnection.backendPort")}</dt><dd>{serverConnection.backendPort}</dd></div>
            <div><dt>{t("gestion.terminals.serverConnection.publicUrl")}</dt><dd>{serverConnection.publicUrl || t("gestion.terminals.serverConnection.notConfigured")}</dd></div>
          </dl>}
      </section>

      <div className="gestion-terminal-summary">
        <div><span>{t("gestion.terminals.workstations.capacity")}</span><strong>{workstations?.maxWindows ?? "—"}</strong></div>
        <div><span>{t("gestion.terminals.workstations.free")}</span><strong>{freeCodes.length}</strong></div>
        <div><span>{t("gestion.terminals.workstations.used")}</span><strong>{usedCount}</strong></div>
        <div className={pendingCount > 0 ? "attention" : ""}><span>{t("gestion.terminals.pending")}</span><strong>{pendingCount}</strong></div>
      </div>

      <div className="gestion-terminal-layout">
        <section className="gestion-terminal-list" aria-label={t("gestion.terminals.title")}>
          {workstations?.slots.map(slot => <button type="button" key={slot.code}
            className={slot.code === selectedSlotCode ? "selected" : ""}
            onClick={() => { setSelectedSlotCode(slot.code); setSelectedLegacyId(""); setSelectedId(""); setPairing(null); }}>
            <span><strong>{slot.code} · {slot.name || t("gestion.terminals.workstations.unnamed")}</strong>
              <small>{slot.deviceName || t("gestion.terminals.workstations.noDevice")}</small></span>
            <em className={slot.status.toLowerCase()}>{slot.outOfQuota ? t("gestion.terminals.workstations.outOfQuota") : t(`gestion.terminals.workstations.status.${slot.status}`)}</em>
          </button>)}
          {workstations?.legacyTerminals.map(terminal => <button type="button" key={terminal.id}
            className={terminal.id === selectedLegacyId ? "selected" : ""}
            onClick={() => { setSelectedLegacyId(terminal.id); setSelectedSlotCode(""); setSelectedId(""); }}>
            <span><strong>{terminal.name}</strong><small>{t("gestion.terminals.workstations.legacy")}</small></span>
            <em>{t("gestion.terminals.workstations.unassigned")}</em>
          </button>)}
          {terminals.map((terminal) => {
            const status = terminalDisplayStatus(terminal);
            return (
              <button type="button" key={terminal.id} className={terminal.id === selectedId ? "selected" : ""}
                onClick={() => { setSelectedId(terminal.id); setSelectedSlotCode(""); setSelectedLegacyId(""); setPairing(null); }}>
                <span><strong>{terminal.name}</strong><small>{t(`gestion.terminals.type.${terminal.type}`)}</small></span>
                <em className={status}>{t(`gestion.terminals.status.${status}`)}</em>
              </button>
            );
          })}
          {loading && <p>{t("common.loading")}</p>}
          {!loading && terminals.length === 0 && !workstations?.slots.length && !workstations?.legacyTerminals.length && <p>{t("gestion.terminals.empty")}</p>}
        </section>

        <aside className="gestion-terminal-detail">
          {selectedSlot ? <>
            <header><span>{t("gestion.terminals.workstations.code")}</span><h3>{selectedSlot.code} · {selectedSlot.name || t("gestion.terminals.workstations.unnamed")}</h3></header>
            <dl>
              <div><dt>{t("gestion.terminals.status")}</dt><dd>{selectedSlot.outOfQuota ? t("gestion.terminals.workstations.outOfQuota") : t(`gestion.terminals.workstations.status.${selectedSlot.status}`)}</dd></div>
              <div><dt>{t("gestion.terminals.workstations.device")}</dt><dd>{selectedSlot.deviceName || "—"}</dd></div>
              <div><dt>{t("gestion.terminals.lastIp")}</dt><dd>{selectedSlot.lastIp || "—"}</dd></div>
              <div><dt>{t("gestion.terminals.workstations.lastSeen")}</dt><dd>{selectedSlot.lastSeenAt ? new Date(selectedSlot.lastSeenAt).toLocaleString() : "—"}</dd></div>
              <div><dt>{t("gestion.terminals.id")}</dt><dd>{selectedSlot.terminalId || "—"}</dd></div>
              {selectedSlot.expiresAt && <div><dt>{t("gestion.terminals.pairingExpires")}</dt><dd>{new Date(selectedSlot.expiresAt).toLocaleString()}</dd></div>}
            </dl>
            <div className="gestion-terminal-actions">
              {["PENDING", "DISABLED"].includes(selectedSlot.status) && selectedSlot.code !== "001" && <button className="primary" type="button" disabled={busy || !selectedSlot.bindingId || selectedSlot.outOfQuota} onClick={() => void actOnSlot("approve")}>{t(selectedSlot.status === "DISABLED" ? "gestion.terminals.workstations.reactivate" : "gestion.terminals.workstations.approve")}</button>}
              {selectedSlot.status === "PENDING" && <>
                <button type="button" disabled={busy || !selectedSlot.bindingId} onClick={() => void actOnSlot("cancel")}>{t("gestion.terminals.workstations.cancel")}</button>
              </>}
              {selectedSlot.status === "ACTIVE" && selectedSlot.code !== "001" && <button className="danger" type="button" disabled={busy || !selectedSlot.bindingId} onClick={() => void actOnSlot("deactivate")}>{t("gestion.terminals.deactivate")}</button>}
              {["ACTIVE", "DISABLED"].includes(selectedSlot.status) && selectedSlot.code !== "001" && <button className="danger" type="button" disabled={busy || !selectedSlot.bindingId} onClick={() => void actOnSlot("release")}>{t("gestion.terminals.workstations.release")}</button>}
            </div>
            <h4>{t("gestion.terminals.workstations.history")}</h4>
            {historyError ? <p role="alert">{t("gestion.terminals.loadError")}</p> : history === null ? <p>{t("common.loading")}</p> : history.length === 0 ? <p>{t("gestion.terminals.workstations.noHistory")}</p> :
              <table className="gestion-terminal-history"><thead><tr>
                <th>{t("gestion.terminals.workstations.device")}</th><th>{t("gestion.terminals.status")}</th>
                <th>{t("gestion.terminals.workstations.created")}</th><th>{t("gestion.terminals.workstations.ended")}</th>
              </tr></thead><tbody>{history.map(item => <tr key={item.bindingId}>
                <td>{item.deviceName || item.name || "—"}</td><td>{t(`gestion.terminals.workstations.status.${item.status}`)}</td>
                <td>{new Date(item.createdAt).toLocaleString()}</td><td>{item.endedAt ? new Date(item.endedAt).toLocaleString() : "—"}</td>
              </tr>)}</tbody></table>}
          </> : selectedLegacy ? <>
            <header><span>{t("gestion.terminals.workstations.legacy")}</span><h3>{selectedLegacy.name}</h3></header>
            <dl>
              <div><dt>{t("gestion.terminals.lastIp")}</dt><dd>{selectedLegacy.lastIp || "—"}</dd></div>
            </dl>
            <p>{t("gestion.terminals.workstations.assignHelp")}</p>
            <label className="gestion-terminal-assign">{t("gestion.terminals.workstations.code")}
              <select value={assignCode} onChange={event => setAssignCode(event.target.value)}>
                <option value="">{t("gestion.terminals.workstations.chooseCode")}</option>
                {legacyCodes.map(code => <option key={code} value={code}>{code}</option>)}
              </select>
            </label>
            <div className="gestion-terminal-actions"><button className="primary" type="button" disabled={busy || !assignCode} onClick={() => void assignLegacy()}>{t("gestion.terminals.workstations.assign")}</button></div>
          </> : !selected ? <p>{t("gestion.terminals.select")}</p> : (
            <>
              <header><span>{t(`gestion.terminals.type.${selected.type}`)}</span><h3>{selected.name}</h3></header>
              <dl>
                <div><dt>{t("gestion.terminals.id")}</dt><dd>{selected.id}</dd></div>
                <div><dt>{t("gestion.terminals.lastIp")}</dt><dd>{selected.lastIp || "—"}</dd></div>
                <div><dt>{t("gestion.terminals.status")}</dt><dd>{t(`gestion.terminals.status.${terminalDisplayStatus(selected)}`)}</dd></div>
              </dl>
              <div className="gestion-terminal-actions">
                {!selected.approved && <button className="primary" type="button" disabled={busy} onClick={() => void approve()}>{t("gestion.terminals.approve")}</button>}
                {selected.approved && selected.active && selected.type === "PDA" && <button className="primary" type="button" disabled={busy} onClick={() => void createPairingCode()}>{t("gestion.terminals.pairingCreate")}</button>}
                {selected.approved && selected.active && selected.type !== "SERVIDOR" && <button className="danger" type="button" disabled={busy} onClick={() => void deactivate()}>{t("gestion.terminals.deactivate")}</button>}
              </div>
              {pairing && <section className="gestion-terminal-pairing" aria-live="polite">
                <span>{t("gestion.terminals.pairingCode")}</span>
                <strong>{pairing.code}</strong>
                <p>{t("gestion.terminals.pairingWarning")}</p>
                <small>{t("gestion.terminals.pairingExpires")}: {new Date(pairing.expiresAt).toLocaleTimeString()}</small>
              </section>}
            </>
          )}
          {error && <p className="gestion-inline-error" role="alert">{error}</p>}
        </aside>
      </div>
    </section>
  );
}
