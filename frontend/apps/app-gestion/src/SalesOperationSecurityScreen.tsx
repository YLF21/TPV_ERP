import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ApiError,
  apiRequest,
  loadSalesOperationSecurity,
  resetSalesOperationSecurity,
  saveSalesOperationSecurity,
  type SalesOperationSecurityConfiguration,
  type SalesOperationSecurityOperation,
  type UserSession,
} from "@tpverp/app-common";

type Translator = (key: string) => string;

type Props = {
  session: UserSession;
  t: Translator;
  request?: typeof apiRequest;
};

type Feedback = {
  kind: "success" | "error" | "conflict";
  key: string;
};

const categoryOrder = [
  "CASH",
  "TICKET",
  "PRODUCT",
  "DISCOUNT",
  "CREDIT",
  "PAYMENT",
  "PAYMENT_TERMINAL",
] as const;

const localizedShortcutKeys: Record<string, string> = {
  "-1+Pausa": "gestion.salesOperationSecurity.shortcut.NEGATIVE_PAUSE",
  Inicio: "gestion.salesOperationSecurity.shortcut.HOME",
  "Ctrl+RePag": "gestion.salesOperationSecurity.shortcut.CTRL_PAGE_UP",
  RePag: "gestion.salesOperationSecurity.shortcut.PAGE_UP",
};

function cloneConfiguration(
  configuration: SalesOperationSecurityConfiguration,
): SalesOperationSecurityConfiguration {
  return {
    ...configuration,
    operations: configuration.operations.map((operation) => ({
      ...operation,
      shortcuts: [...operation.shortcuts],
      permissions: [...operation.permissions],
    })),
  };
}

function translatedValue(t: Translator, key: string, fallback: string) {
  const translated = t(key);
  return translated === key ? fallback : translated;
}

function operationLabel(t: Translator, code: string) {
  return translatedValue(
    t,
    `gestion.salesOperationSecurity.operation.${code}`,
    code,
  );
}

function categoryLabel(t: Translator, category: string) {
  return translatedValue(
    t,
    `gestion.salesOperationSecurity.category.${category}`,
    category,
  );
}

function shortcutLabel(t: Translator, shortcut: string) {
  const key = localizedShortcutKeys[shortcut];
  return key ? translatedValue(t, key, shortcut) : shortcut;
}

function effectiveProtectionKeys(operation: SalesOperationSecurityOperation) {
  if (operation.requirePermission && operation.requirePassword) {
    return [
      "gestion.salesOperationSecurity.effective.permissionAndPassword",
      "gestion.salesOperationSecurity.effective.delegated",
    ];
  }
  if (operation.requirePermission) {
    return [
      "gestion.salesOperationSecurity.effective.permission",
      "gestion.salesOperationSecurity.effective.delegated",
    ];
  }
  if (operation.requirePassword) {
    return ["gestion.salesOperationSecurity.effective.password"];
  }
  return ["gestion.salesOperationSecurity.effective.direct"];
}

function operationChanged(
  operation: SalesOperationSecurityOperation,
  baseline: SalesOperationSecurityOperation | undefined,
) {
  return !baseline
    || operation.requirePermission !== baseline.requirePermission
    || operation.requirePassword !== baseline.requirePassword;
}

function operationUsesDefaults(operation: SalesOperationSecurityOperation) {
  return operation.requirePermission === operation.defaultRequirePermission
    && operation.requirePassword === operation.defaultRequirePassword;
}

export function SalesOperationSecurityScreen({
  session,
  t,
  request = apiRequest,
}: Props) {
  const canManage = session.permissions.includes("ADMIN");
  const [configuration, setConfiguration] = useState<SalesOperationSecurityConfiguration | null>(null);
  const [draft, setDraft] = useState<SalesOperationSecurityOperation[]>([]);
  const [loading, setLoading] = useState(canManage);
  const [busy, setBusy] = useState<"save" | "reset" | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [resetConfirmationOpen, setResetConfirmationOpen] = useState(false);

  const applyConfiguration = useCallback((next: SalesOperationSecurityConfiguration) => {
    const cloned = cloneConfiguration(next);
    setConfiguration(cloned);
    setDraft(cloneConfiguration(cloned).operations);
  }, []);

  const loadCurrent = useCallback(async () => {
    if (!canManage) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setFeedback(null);
    try {
      applyConfiguration(await loadSalesOperationSecurity(session.accessToken, request));
    } catch {
      setConfiguration(null);
      setDraft([]);
      setFeedback({
        kind: "error",
        key: "gestion.salesOperationSecurity.loadError",
      });
    } finally {
      setLoading(false);
    }
  }, [applyConfiguration, canManage, request, session.accessToken]);

  useEffect(() => {
    void loadCurrent();
  }, [loadCurrent]);

  const baselineByCode = useMemo(() => new Map(
    configuration?.operations.map((operation) => [operation.code, operation]) ?? [],
  ), [configuration]);

  const changedCount = useMemo(() => draft.filter((operation) => (
    operationChanged(operation, baselineByCode.get(operation.code))
  )).length, [baselineByCode, draft]);

  const groups = useMemo(() => {
    const grouped = new Map<string, SalesOperationSecurityOperation[]>();
    draft.forEach((operation) => {
      const operations = grouped.get(operation.category) ?? [];
      operations.push(operation);
      grouped.set(operation.category, operations);
    });
    const known = categoryOrder.filter((category) => grouped.has(category));
    const unknown = [...grouped.keys()]
      .filter((category) => !categoryOrder.includes(category as (typeof categoryOrder)[number]))
      .sort((left, right) => left.localeCompare(right));
    return [...known, ...unknown].map((category) => ({
      category,
      operations: grouped.get(category) ?? [],
    }));
  }, [draft]);

  function updateOperation(
    code: string,
    field: "requirePermission" | "requirePassword",
    value: boolean,
  ) {
    if (!canManage || busy) return;
    setFeedback(null);
    setDraft((current) => current.map((operation) => {
      if (operation.code !== code) return operation;
      const updated = { ...operation, [field]: value };
      return {
        ...updated,
        customized: !operationUsesDefaults(updated),
      };
    }));
  }

  function discardChanges() {
    if (!configuration || busy) return;
    setDraft(cloneConfiguration(configuration).operations);
    setFeedback(null);
  }

  async function saveChanges() {
    if (!configuration || changedCount === 0 || busy || !canManage) return;
    setBusy("save");
    setFeedback(null);
    try {
      const saved = await saveSalesOperationSecurity(
        configuration.version,
        draft.map((operation) => ({
          code: operation.code,
          requirePermission: operation.requirePermission,
          requirePassword: operation.requirePassword,
        })),
        session.accessToken,
        request,
      );
      applyConfiguration(saved);
      setFeedback({
        kind: "success",
        key: "gestion.salesOperationSecurity.saved",
      });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setFeedback({
          kind: "conflict",
          key: "gestion.salesOperationSecurity.conflict",
        });
      } else {
        setFeedback({
          kind: "error",
          key: "gestion.salesOperationSecurity.saveError",
        });
      }
    } finally {
      setBusy(null);
    }
  }

  async function confirmReset() {
    if (!configuration || busy || !canManage) return;
    setBusy("reset");
    setFeedback(null);
    try {
      const restored = await resetSalesOperationSecurity(
        configuration.version,
        session.accessToken,
        request,
      );
      applyConfiguration(restored);
      setResetConfirmationOpen(false);
      setFeedback({
        kind: "success",
        key: "gestion.salesOperationSecurity.resetSuccess",
      });
    } catch (error) {
      setResetConfirmationOpen(false);
      if (error instanceof ApiError && error.status === 409) {
        setFeedback({
          kind: "conflict",
          key: "gestion.salesOperationSecurity.conflict",
        });
      } else {
        setFeedback({
          kind: "error",
          key: "gestion.salesOperationSecurity.resetError",
        });
      }
    } finally {
      setBusy(null);
    }
  }

  if (!canManage) {
    return (
      <section className="gestion-workspace gestion-operation-security-workspace">
        <p className="gestion-operation-security-message error" role="alert">
          {t("gestion.salesOperationSecurity.accessDenied")}
        </p>
      </section>
    );
  }

  return (
    <section className="gestion-workspace gestion-operation-security-workspace">
      <header className="gestion-operation-security-header">
        <div>
          <span>{t("gestion.salesOperationSecurity.eyebrow")}</span>
          <h2>{t("gestion.salesOperationSecurity.title")}</h2>
          <p>{t("gestion.salesOperationSecurity.description")}</p>
        </div>
        <aside>
          <strong>{t("gestion.salesOperationSecurity.storeScope")}</strong>
          {configuration && (
            <>
              <small>
                {t("gestion.salesOperationSecurity.store")}: <code>{configuration.storeId}</code>
              </small>
              <small>
                {t("gestion.salesOperationSecurity.version")}: <code>{configuration.version}</code>
              </small>
            </>
          )}
        </aside>
      </header>

      {feedback && (
        <div
          className={`gestion-operation-security-message ${feedback.kind}`}
          role={feedback.kind === "success" ? "status" : "alert"}
        >
          <div>
            <strong>{t(feedback.key)}</strong>
            {feedback.kind === "conflict" && (
              <small>{t("gestion.salesOperationSecurity.conflictHint")}</small>
            )}
          </div>
          {feedback.kind === "conflict" && (
            <button type="button" disabled={Boolean(busy)} onClick={() => void loadCurrent()}>
              {t("gestion.salesOperationSecurity.reload")}
            </button>
          )}
        </div>
      )}

      <div className="gestion-operation-security-toolbar">
        <div>
          <strong>
            {changedCount > 0
              ? t("gestion.salesOperationSecurity.changes").replace("{count}", String(changedCount))
              : t("gestion.salesOperationSecurity.noChanges")}
          </strong>
          <small>{t("gestion.salesOperationSecurity.delegatedHint")}</small>
        </div>
        <div>
          <button
            type="button"
            className="secondary"
            disabled={loading || Boolean(busy) || !configuration}
            onClick={() => setResetConfirmationOpen(true)}
          >
            {t("gestion.salesOperationSecurity.reset")}
          </button>
          <button
            type="button"
            className="secondary"
            disabled={changedCount === 0 || Boolean(busy)}
            onClick={discardChanges}
          >
            {t("gestion.salesOperationSecurity.discard")}
          </button>
          <button
            type="button"
            className="primary"
            disabled={changedCount === 0 || Boolean(busy)}
            onClick={() => void saveChanges()}
          >
            {busy === "save"
              ? t("gestion.salesOperationSecurity.saving")
              : t("gestion.salesOperationSecurity.save")}
          </button>
        </div>
      </div>

      <section
        className="gestion-operation-security-panel"
        aria-label={t("gestion.salesOperationSecurity.title")}
      >
        {loading ? (
          <p className="gestion-operation-security-state" role="status">
            {t("gestion.salesOperationSecurity.loading")}
          </p>
        ) : !configuration ? (
          <div className="gestion-operation-security-state">
            <button type="button" onClick={() => void loadCurrent()}>
              {t("gestion.salesOperationSecurity.retry")}
            </button>
          </div>
        ) : draft.length === 0 ? (
          <p className="gestion-operation-security-state">
            {t("gestion.salesOperationSecurity.empty")}
          </p>
        ) : (
          <>
            <header className="gestion-operation-security-row head">
              <span>{t("gestion.salesOperationSecurity.column.function")}</span>
              <span>{t("gestion.salesOperationSecurity.column.shortcut")}</span>
              <span>{t("gestion.salesOperationSecurity.column.permission")}</span>
              <span>{t("gestion.salesOperationSecurity.column.requirePermission")}</span>
              <span>{t("gestion.salesOperationSecurity.column.requirePassword")}</span>
              <span>{t("gestion.salesOperationSecurity.column.effective")}</span>
            </header>

            {groups.map((group) => (
              <section className="gestion-operation-security-group" key={group.category}>
                <h3>{categoryLabel(t, group.category)}</h3>
                {group.operations.map((operation) => {
                  const label = operationLabel(t, operation.code);
                  const usesDefaults = !operation.customized;
                  return (
                    <article className="gestion-operation-security-row" key={operation.code}>
                      <span className="gestion-operation-security-function">
                        <strong>{label}</strong>
                        <small className={usesDefaults ? "default" : "customized"}>
                          {usesDefaults
                            ? t("gestion.salesOperationSecurity.default")
                            : t("gestion.salesOperationSecurity.customized")}
                        </small>
                      </span>
                      <span className="gestion-operation-security-shortcuts">
                        {operation.shortcuts.length > 0
                          ? operation.shortcuts.map((shortcut) => (
                              <kbd key={shortcut}>{shortcutLabel(t, shortcut)}</kbd>
                            ))
                          : <small>{t("gestion.salesOperationSecurity.noShortcut")}</small>}
                      </span>
                      <span className="gestion-operation-security-permissions">
                        {operation.permissions.length > 0
                          ? operation.permissions.map((permission, index) => (
                              <span key={permission}>
                                {index > 0 && <i>{t("gestion.salesOperationSecurity.permissionSeparator")}</i>}
                                <code>{permission}</code>
                              </span>
                            ))
                          : <small>{t("gestion.salesOperationSecurity.noPermission")}</small>}
                      </span>
                      <span>
                        <label className="gestion-operation-security-switch">
                          <input
                            type="checkbox"
                            role="switch"
                            aria-label={`${t("gestion.salesOperationSecurity.column.requirePermission")}: ${label}`}
                            checked={operation.requirePermission}
                            disabled={Boolean(busy)}
                            onChange={(event) => updateOperation(
                              operation.code,
                              "requirePermission",
                              event.currentTarget.checked,
                            )}
                          />
                          <i aria-hidden="true" />
                          <b>{operation.requirePermission ? t("common.yes") : t("common.no")}</b>
                        </label>
                      </span>
                      <span>
                        <label className="gestion-operation-security-switch">
                          <input
                            type="checkbox"
                            role="switch"
                            aria-label={`${t("gestion.salesOperationSecurity.column.requirePassword")}: ${label}`}
                            checked={operation.requirePassword}
                            disabled={Boolean(busy)}
                            onChange={(event) => updateOperation(
                              operation.code,
                              "requirePassword",
                              event.currentTarget.checked,
                            )}
                          />
                          <i aria-hidden="true" />
                          <b>{operation.requirePassword ? t("common.yes") : t("common.no")}</b>
                        </label>
                      </span>
                      <span className="gestion-operation-security-effective" role="list">
                        {effectiveProtectionKeys(operation).map((key) => (
                          <span role="listitem" key={key}>{t(key)}</span>
                        ))}
                      </span>
                    </article>
                  );
                })}
              </section>
            ))}
          </>
        )}
      </section>

      {resetConfirmationOpen && configuration && (
        <div className="gestion-operation-security-overlay" role="presentation">
          <section
            className="gestion-operation-security-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sales-operation-security-reset-title"
          >
            <header>
              <h3 id="sales-operation-security-reset-title">
                {t("gestion.salesOperationSecurity.resetTitle")}
              </h3>
            </header>
            <p>{t("gestion.salesOperationSecurity.resetDescription")}</p>
            <footer>
              <button
                type="button"
                disabled={Boolean(busy)}
                onClick={() => setResetConfirmationOpen(false)}
              >
                {t("gestion.salesOperationSecurity.resetCancel")}
              </button>
              <button
                type="button"
                className="danger"
                disabled={Boolean(busy)}
                onClick={() => void confirmReset()}
              >
                {busy === "reset"
                  ? t("gestion.salesOperationSecurity.resetting")
                  : t("gestion.salesOperationSecurity.resetConfirm")}
              </button>
            </footer>
          </section>
        </div>
      )}
    </section>
  );
}
