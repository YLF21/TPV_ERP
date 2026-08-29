import { useEffect, useMemo, useState } from "react";
import { apiRequest, type UserSession } from "@tpverp/app-common";

export type RegisteredTerminal = {
  id: string;
  name: string;
  type: "SERVIDOR" | "TERMINAL_VENTA" | "PDA";
  approved: boolean;
  active: boolean;
};

export type PdaPairingCode = { code: string; expiresAt: string };

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
  const [selectedId, setSelectedId] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [pairing, setPairing] = useState<PdaPairingCode | null>(null);
  const token = session.accessToken;
  const selected = terminals.find((terminal) => terminal.id === selectedId) ?? null;
  const pendingCount = useMemo(() => terminals.filter((terminal) => !terminal.approved).length, [terminals]);

  async function refresh(preferredId = selectedId) {
    setLoading(true);
    setError("");
    try {
      const values = await apiRequest<RegisteredTerminal[]>("/terminals", { token });
      setTerminals(values);
      setSelectedId(values.some((terminal) => terminal.id === preferredId) ? preferredId : values[0]?.id ?? "");
    } catch {
      setError(t("gestion.terminals.loadError"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(""); }, [token]);

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
        <button type="button" disabled={loading || busy} onClick={() => void refresh()}>{t("common.refresh")}</button>
      </header>

      <div className="gestion-terminal-summary">
        <div><span>{t("gestion.terminals.total")}</span><strong>{terminals.length}</strong></div>
        <div className={pendingCount > 0 ? "attention" : ""}><span>{t("gestion.terminals.pending")}</span><strong>{pendingCount}</strong></div>
      </div>

      <div className="gestion-terminal-layout">
        <section className="gestion-terminal-list" aria-label={t("gestion.terminals.title")}>
          {terminals.map((terminal) => {
            const status = terminalDisplayStatus(terminal);
            return (
              <button type="button" key={terminal.id} className={terminal.id === selectedId ? "selected" : ""}
                onClick={() => { setSelectedId(terminal.id); setPairing(null); }}>
                <span><strong>{terminal.name}</strong><small>{t(`gestion.terminals.type.${terminal.type}`)}</small></span>
                <em className={status}>{t(`gestion.terminals.status.${status}`)}</em>
              </button>
            );
          })}
          {loading && <p>{t("common.loading")}</p>}
          {!loading && terminals.length === 0 && <p>{t("gestion.terminals.empty")}</p>}
        </section>

        <aside className="gestion-terminal-detail">
          {!selected ? <p>{t("gestion.terminals.select")}</p> : (
            <>
              <header><span>{t(`gestion.terminals.type.${selected.type}`)}</span><h3>{selected.name}</h3></header>
              <dl>
                <div><dt>{t("gestion.terminals.id")}</dt><dd>{selected.id}</dd></div>
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
