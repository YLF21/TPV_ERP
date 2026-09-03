import { useEffect, useRef, useState } from "react";
import { ApiError, apiProblemCode, apiRequest, type UserSession } from "@tpverp/app-common";
import {
  activateModalFocusTrap,
  type ModalFocusRoot,
} from "../../../packages/app-common/src/components/modalFocusTrap";
import type { GestionGroupLock } from "./gestionNavigation";

type Translator = (key: string) => string;

type Props = {
  group: GestionGroupLock;
  session: UserSession;
  t: Translator;
  onUnlocked: () => void;
  onLocked: () => void;
  onCancel: () => void;
};

export function GestionGroupUnlockDialog({ group, session, t, onUnlocked, onLocked, onCancel }: Props) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => { inputRef.current?.focus(); }, []);
  useEffect(() => {
    if (!dialogRef.current) return undefined;
    return activateModalFocusTrap(dialogRef.current as ModalFocusRoot, document);
  }, []);

  async function submit() {
    if (!password.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await apiRequest(`/auth/gestion-groups/${group}/unlock`, {
        method: "POST",
        token: session.accessToken,
        body: { password },
      });
      setPassword("");
      onUnlocked();
    } catch (reason) {
      const code = apiProblemCode(reason);
      if (reason instanceof ApiError && reason.status === 403 && code === "GESTION_GROUP_LOCKED") {
        onLocked();
      } else if (reason instanceof ApiError && reason.status === 429) {
        setError(t("gestion.groupUnlock.throttled"));
      } else if (reason instanceof ApiError && reason.status === 403
        && code === "GESTION_GROUP_INVALID_PASSWORD") {
        setError(t("gestion.groupUnlock.invalid"));
      } else {
        setError(t("gestion.groupUnlock.error"));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="gestion-group-unlock-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.currentTarget === event.target && !busy) onCancel();
    }}>
      <section
        ref={dialogRef}
        className="gestion-group-unlock-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="gestion-group-unlock-title"
        aria-describedby="gestion-group-unlock-description"
        onKeyDown={(event) => {
          if (event.key === "Escape" && !busy) {
            event.preventDefault();
            onCancel();
            return;
          }
        }}
      >
        <header>
          <h2 id="gestion-group-unlock-title">{t("gestion.groupUnlock.title")}</h2>
          <button type="button" onClick={onCancel} disabled={busy} aria-label={t("gestion.groupUnlock.cancel")}>×</button>
        </header>
        <p id="gestion-group-unlock-description">{t(`gestion.groupUnlock.description.${group}`)}</p>
        <label>
          <span>{t("gestion.groupUnlock.password")}</span>
          <input
            ref={inputRef}
            type="password"
            inputMode="numeric"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            onKeyDown={(event) => { if (event.key === "Enter") void submit(); }}
          />
        </label>
        {error && <p className="gestion-group-unlock-error" role="alert">{error}</p>}
        <footer>
          <button type="button" onClick={onCancel} disabled={busy}>{t("gestion.groupUnlock.cancel")}</button>
          <button type="button" className="primary" onClick={() => void submit()} disabled={busy || !password.trim()}>
            {busy ? t("gestion.groupUnlock.checking") : t("gestion.groupUnlock.submit")}
          </button>
        </footer>
      </section>
    </div>
  );
}
