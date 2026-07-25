import { useEffect, useRef, useState } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Props = {
  open: boolean;
  busy: boolean;
  error: string;
  t: (key: string) => string;
  translationPrefix?: "sale.cashDrawer" | "sale.productEdit";
  onCancel: () => void;
  onAuthorize: (username: string, password: string) => void;
};

export function SaleCashDrawerAuthorizationDialog({
  open,
  busy,
  error,
  t,
  translationPrefix = "sale.cashDrawer",
  onCancel,
  onAuthorize,
}: Props) {
  const message = (suffix: string) => t(`${translationPrefix}.${suffix}`);
  const dialogRef = useRef<HTMLElement | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  useEffect(() => {
    if (!open) return;
    setUsername("");
    setPassword("");
    return activateModalFocusTrap(dialogRef.current as ModalFocusRoot, document);
  }, [open]);

  if (!open) return null;

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-cash-drawer-authorization"
        role="dialog"
        aria-modal="true"
        aria-label={message("authorizationTitle")}
        onKeyDown={(event) => {
          if (event.key === "Escape" && !busy) onCancel();
        }}
      >
        <header>
          <div>
            <h2>{message("authorizationTitle")}</h2>
            <p>{message("authorizationHint")}</p>
          </div>
          <button type="button" aria-label={t("common.close")} disabled={busy} onClick={onCancel}>×</button>
        </header>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!busy && username.trim() && password) {
              const submittedPassword = password;
              setPassword("");
              onAuthorize(username.trim(), submittedPassword);
            }
          }}
        >
          <label>
            <span>{message("authorizerUsername")}</span>
            <input
              autoFocus
              autoComplete="username"
              value={username}
              disabled={busy}
              onChange={(event) => setUsername(event.currentTarget.value)}
            />
          </label>
          <label>
            <span>{message("authorizerPassword")}</span>
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              disabled={busy}
              onChange={(event) => setPassword(event.currentTarget.value)}
            />
          </label>
          {error && <p className="sale-dialog-error" role="alert">{error}</p>}
          <div className="sale-action-buttons">
            <button type="button" disabled={busy} onClick={onCancel}>{t("common.cancel")}</button>
            <button type="submit" disabled={busy || !username.trim() || !password}>
              {busy ? message("opening") : message("authorize")}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
