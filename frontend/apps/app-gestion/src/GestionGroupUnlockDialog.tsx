import { WindowCloseButton } from "../../../packages/app-common/src/components/WindowCloseButton";
import { DialogDismissButton } from "../../../packages/app-common/src/components/DialogDismissButton";
import { useEffect, useId, useRef, useState } from "react";
import { ApiError, apiProblemCode, apiRequest, type LocaleCode, type UserSession } from "@tpverp/app-common";
import { TouchAlphaKeyboard } from "../../../packages/app-common/src/components/TouchAlphaKeyboard";
import { TouchKeyboardToggle } from "../../../packages/app-common/src/components/TouchKeyboardToggle";
import { TouchKeyboardClearButton } from "../../../packages/app-common/src/components/TouchKeyboardClearButton";
import {
  activateModalFocusTrap,
  type ModalFocusRoot,
} from "../../../packages/app-common/src/components/modalFocusTrap";
import type { GestionGroupLock } from "./gestionNavigation";
import "./GestionGroupUnlockDialog.css";

type Translator = (key: string) => string;

type Props = {
  group: GestionGroupLock;
  session: UserSession;
  locale: LocaleCode;
  t: Translator;
  onUnlocked: () => void;
  onLocked: () => void;
  onCancel: () => void;
};

export function GestionGroupUnlockDialog({ group, session, locale, t, onUnlocked, onLocked, onCancel }: Props) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const keyboardId = useId();
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
        className={`gestion-group-unlock-dialog${keyboardOpen ? " gestion-group-unlock-dialog--keyboard" : ""}`}
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
          <WindowCloseButton type="button" onClick={onCancel} disabled={busy} aria-label={t("gestion.groupUnlock.cancel")} >×</WindowCloseButton>
        </header>
        <p id="gestion-group-unlock-description">{t(`gestion.groupUnlock.description.${group}`)}</p>
        <div className="gestion-group-unlock-credentials">
          <label>
            <span>{t("gestion.groupUnlock.password")}</span>
            <input
              ref={inputRef}
              type="password"
              inputMode="numeric"
              autoComplete="current-password"
              disabled={busy}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter") void submit(); }}
            />
          </label>
          <div className="gestion-group-unlock-keyboard-actions" onPointerDown={(event) => event.preventDefault()}>
            {keyboardOpen && <TouchKeyboardClearButton locale={locale} disabled={busy} onClick={() => {
              setPassword("");
              inputRef.current?.focus({ preventScroll: true });
            }} />}
            <TouchKeyboardToggle
              expanded={keyboardOpen}
              controls={keyboardId}
              disabled={busy}
              openLabel={t("sale.touch.keyboard.open")}
              closeLabel={t("sale.touch.keyboard.close")}
              onClick={() => {
                setKeyboardOpen((open) => !open);
                inputRef.current?.focus({ preventScroll: true });
              }}
            />
          </div>
        </div>
        <div id={keyboardId} className="gestion-group-unlock-keyboard" hidden={!keyboardOpen}>
          {keyboardOpen && <TouchAlphaKeyboard
            locale={locale}
            value={password}
            onChange={setPassword}
            inputRef={inputRef}
            disabled={busy}
            collapsible={false}
            hideClearButton
          />}
        </div>
        {error && <p className="gestion-group-unlock-error" role="alert">{error}</p>}
        <footer className="erp-dialog-actions-row">
          <DialogDismissButton className="erp-dialog-action-cancel erp-dialog-dismiss" type="button" onClick={onCancel} disabled={busy}>{t("gestion.groupUnlock.cancel")}</DialogDismissButton>
          <button type="button" className="primary erp-dialog-action-confirm" onClick={() => void submit()} disabled={busy || !password.trim()}>
            {busy ? t("gestion.groupUnlock.checking") : t("gestion.groupUnlock.submit")}
          </button>
        </footer>
      </section>
    </div>
  );
}
