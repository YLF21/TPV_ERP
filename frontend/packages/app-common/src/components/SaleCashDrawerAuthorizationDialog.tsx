import { useEffect, useRef, useState } from "react";
import {
  saleOperationAuthorizationComplete,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";

type Props = {
  open: boolean;
  busy: boolean;
  error: string;
  t: (key: string) => string;
  locale?: LocaleCode;
  currentUsername?: string;
  authorization?: SaleOperationAuthorization;
  translationPrefix?: "sale.cashDrawer" | "sale.productEdit";
  onCancel: () => void;
  onAuthorize: (username: string, password: string) => void;
};

export function SaleCashDrawerAuthorizationDialog({
  open,
  busy,
  error,
  t,
  locale = "es",
  currentUsername = "",
  authorization = {
    mode: "DELEGATED",
    requireUsername: true,
    requirePassword: true,
  },
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
          </div>
          <button type="button" aria-label={t("common.close")} disabled={busy} onClick={onCancel}>×</button>
        </header>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!busy && saleOperationAuthorizationComplete(
              authorization,
              username,
              password,
            )) {
              const submittedPassword = password;
              setPassword("");
              onAuthorize(username.trim(), submittedPassword);
            }
          }}
        >
          <SaleOperationAuthorizationFields
            locale={locale}
            currentUsername={currentUsername}
            authorization={authorization}
            username={username}
            password={password}
            disabled={busy}
            autoFocus
            onUsernameChange={setUsername}
            onPasswordChange={setPassword}
          />
          {error && <p className="sale-dialog-error" role="alert">{error}</p>}
          <div className="sale-action-buttons">
            <button type="button" disabled={busy} onClick={onCancel}>{t("common.cancel")}</button>
            <button
              type="submit"
              disabled={busy || !saleOperationAuthorizationComplete(
                authorization,
                username,
                password,
              )}
            >
              {busy ? message("opening") : message("authorize")}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
