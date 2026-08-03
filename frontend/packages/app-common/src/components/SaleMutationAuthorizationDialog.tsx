import { useEffect, useMemo, useRef, useState } from "react";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
} from "../sale/operationSecurity";
import type {
  SaleMutationAuthorizationRequirement,
  SaleMutationOperationAuthorizations,
} from "../sale/saleMutationAuthorizations";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";

type Draft = {
  username: string;
  password: string;
};

type Props = {
  open: boolean;
  locale: LocaleCode;
  currentUsername?: string;
  requirements: readonly SaleMutationAuthorizationRequirement[];
  busy?: boolean;
  error?: string;
  onCancel: () => void;
  onConfirm: (authorizations: SaleMutationOperationAuthorizations) => void;
};

const copy = {
  es: {
    title: "Autorización de la venta",
    description: "Confirma las operaciones protegidas antes de continuar.",
    cancel: "Cancelar",
    confirm: "Confirmar y continuar",
    close: "Cerrar",
  },
  en: {
    title: "Sale authorization",
    description: "Confirm the protected operations before continuing.",
    cancel: "Cancel",
    confirm: "Confirm and continue",
    close: "Close",
  },
  zh: {
    title: "销售授权",
    description: "请先确认受保护的操作，然后继续。",
    cancel: "取消",
    confirm: "确认并继续",
    close: "关闭",
  },
} as const;

function emptyDrafts(
  requirements: readonly SaleMutationAuthorizationRequirement[],
) {
  return Object.fromEntries(requirements.map((requirement) => [
    requirement.code,
    { username: "", password: "" },
  ])) as Record<string, Draft>;
}

export function SaleMutationAuthorizationDialog({
  open,
  locale,
  currentUsername = "",
  requirements,
  busy = false,
  error = "",
  onCancel,
  onConfirm,
}: Props) {
  const dialogRef = useRef<HTMLElement>(null);
  const requirementKey = requirements
    .map((requirement) => `${requirement.code}:${requirement.authorization.mode}`)
    .join("|");
  const [drafts, setDrafts] = useState<Record<string, Draft>>(
    () => emptyDrafts(requirements),
  );
  const t = copy[locale];

  useEffect(() => {
    if (!open) return;
    setDrafts(emptyDrafts(requirements));
    return activateModalFocusTrap(
      dialogRef.current as unknown as ModalFocusRoot,
      document,
    );
  }, [open, requirementKey]);

  const complete = useMemo(
    () => requirements.every((requirement) => {
      const draft = drafts[requirement.code] ?? { username: "", password: "" };
      return saleOperationAuthorizationComplete(
        requirement.authorization,
        draft.username,
        draft.password,
      );
    }),
    [drafts, requirementKey],
  );

  if (!open || requirements.length === 0) return null;

  function clearAndCancel() {
    setDrafts(emptyDrafts(requirements));
    onCancel();
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-mutation-authorization-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-mutation-authorization-title"
        onKeyDown={(event) => {
          if (event.key === "Escape" && !busy) clearAndCancel();
        }}
      >
        <header>
          <div>
            <h2 id="sale-mutation-authorization-title">{t.title}</h2>
            <p>{t.description}</p>
          </div>
          <button
            type="button"
            aria-label={t.close}
            disabled={busy}
            onClick={clearAndCancel}
          >
            ×
          </button>
        </header>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (busy || !complete) return;
            const authorizations = Object.fromEntries(requirements.map((requirement) => {
              const draft = drafts[requirement.code] ?? {
                username: "",
                password: "",
              };
              return [
                requirement.code,
                saleOperationCredentials(
                  requirement.authorization,
                  draft.username,
                  draft.password,
                ),
              ];
            }));
            // Commit the cleared form before control reaches the network caller.
            setDrafts(emptyDrafts(requirements));
            queueMicrotask(() => onConfirm(authorizations));
          }}
        >
          <div className="sale-mutation-authorization-list">
            {requirements.map((requirement, index) => {
              const draft = drafts[requirement.code] ?? {
                username: "",
                password: "",
              };
              return (
                <fieldset key={requirement.code}>
                  <legend>{requirement.label}</legend>
                  <SaleOperationAuthorizationFields
                    locale={locale}
                    currentUsername={currentUsername}
                    authorization={requirement.authorization}
                    username={draft.username}
                    password={draft.password}
                    disabled={busy}
                    autoFocus={index === 0}
                    onUsernameChange={(username) => setDrafts((current) => ({
                      ...current,
                      [requirement.code]: { ...draft, username },
                    }))}
                    onPasswordChange={(password) => setDrafts((current) => ({
                      ...current,
                      [requirement.code]: { ...draft, password },
                    }))}
                  />
                </fieldset>
              );
            })}
          </div>
          {error && <p className="sale-action-error" role="alert">{error}</p>}
          <div className="sale-action-buttons">
            <button type="button" disabled={busy} onClick={clearAndCancel}>
              {t.cancel}
            </button>
            <button type="submit" className="primary" disabled={busy || !complete}>
              {t.confirm}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
