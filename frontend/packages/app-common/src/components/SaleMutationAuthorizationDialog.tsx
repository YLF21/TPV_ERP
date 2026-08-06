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
    protectedOperation: "Operación protegida",
    cancel: "Cancelar",
    confirm: "Confirmar y continuar",
    close: "Cerrar",
  },
  en: {
    title: "Sale authorization",
    description: "Confirm the protected operations before continuing.",
    protectedOperation: "Protected operation",
    cancel: "Cancel",
    confirm: "Confirm and continue",
    close: "Close",
  },
  zh: {
    title: "销售授权",
    description: "请先确认受保护的操作，然后继续。",
    protectedOperation: "受保护操作",
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
  const submittingRef = useRef(false);
  const requirementKey = requirements
    .map((requirement) => `${requirement.code}:${requirement.authorization.mode}`)
    .join("|");
  const [drafts, setDrafts] = useState<Record<string, Draft>>(
    () => emptyDrafts(requirements),
  );
  const t = copy[locale];

  useEffect(() => {
    if (!open) return;
    submittingRef.current = false;
    setDrafts(emptyDrafts(requirements));
    const deactivateFocusTrap = activateModalFocusTrap(
      dialogRef.current as unknown as ModalFocusRoot,
      document,
    );
    // The close button is the first focusable element in the modal. Move the
    // initial focus to the first credential actually required by the policy:
    // password for the current user, or username for delegated authorization.
    queueMicrotask(() => {
      dialogRef.current
        ?.querySelector<HTMLInputElement>(
          ".sale-operation-authorization-fields input:not([disabled])",
        )
        ?.focus();
    });
    return deactivateFocusTrap;
  }, [open, requirementKey]);

  useEffect(() => {
    if (!busy) submittingRef.current = false;
  }, [busy]);

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
    <div className="sale-action-overlay sale-mutation-authorization-overlay" role="presentation">
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
            if (busy || !complete || submittingRef.current) return;
            submittingRef.current = true;
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
                <section
                  key={requirement.code}
                  className="sale-mutation-authorization-requirement"
                  role="group"
                  aria-label={requirement.label}
                >
                  <div className="sale-mutation-authorization-operation">
                    <small>{t.protectedOperation}</small>
                    <strong>{requirement.label}</strong>
                  </div>
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
                </section>
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
