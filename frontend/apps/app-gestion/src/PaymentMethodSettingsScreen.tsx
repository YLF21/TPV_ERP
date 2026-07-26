import { useEffect, useMemo, useState } from "react";
import {
  ApiError,
  apiRequest,
  isReferenceConfigurablePaymentMethod,
  loadPaymentMethods,
  managedCheckoutPaymentMethodNames,
  setPaymentMethodActive,
  setPaymentMethodReferenceRequirement,
  type PaymentMethodView,
  type UserSession,
} from "@tpverp/app-common";

type Translator = (key: string) => string;

type Props = {
  session: UserSession;
  t: Translator;
  request?: typeof apiRequest;
};

function failureMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError) return error.message || fallback;
  return error instanceof Error && error.message ? error.message : fallback;
}

export function PaymentMethodSettingsScreen({
  session,
  t,
  request = apiRequest,
}: Props) {
  const [methods, setMethods] = useState<PaymentMethodView[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const canManage = session.permissions.includes("ADMIN");

  const managedMethods = useMemo(() => managedCheckoutPaymentMethodNames
    .map((name) => methods.find((method) => method.name.trim().toUpperCase() === name))
    .filter((method): method is PaymentMethodView => Boolean(method)), [methods]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setMessage(null);
    void loadPaymentMethods(session.accessToken, request)
      .then((loaded) => {
        if (active) setMethods(loaded);
      })
      .catch((error) => {
        if (active) {
          setMethods([]);
          setMessage({
            kind: "error",
            text: failureMessage(error, t("gestion.paymentMethods.loadError")),
          });
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [request, session.accessToken, t]);

  function replaceMethod(saved: PaymentMethodView) {
    setMethods((current) => current.map((method) => method.id === saved.id ? saved : method));
  }

  async function updateActive(method: PaymentMethodView, active: boolean) {
    if (!canManage || busyId) return;
    setBusyId(method.id);
    setMessage(null);
    try {
      replaceMethod(await setPaymentMethodActive(method, active, session.accessToken, request));
      setMessage({ kind: "success", text: t("gestion.paymentMethods.saved") });
    } catch (error) {
      setMessage({
        kind: "error",
        text: failureMessage(error, t("gestion.paymentMethods.saveError")),
      });
    } finally {
      setBusyId(null);
    }
  }

  async function updateReference(method: PaymentMethodView, requiresReference: boolean) {
    if (!canManage || busyId || !isReferenceConfigurablePaymentMethod(method.name)) return;
    setBusyId(method.id);
    setMessage(null);
    try {
      replaceMethod(await setPaymentMethodReferenceRequirement(
        method,
        requiresReference,
        session.accessToken,
        request,
      ));
      setMessage({ kind: "success", text: t("gestion.paymentMethods.saved") });
    } catch (error) {
      setMessage({
        kind: "error",
        text: failureMessage(error, t("gestion.paymentMethods.saveError")),
      });
    } finally {
      setBusyId(null);
    }
  }

  return (
    <section className="gestion-workspace gestion-payment-methods-workspace">
      <header className="gestion-payment-methods-header">
        <div>
          <span>{t("gestion.configuration.navigation")}</span>
          <h2>{t("gestion.paymentMethods.title")}</h2>
          <p>{t("gestion.paymentMethods.description")}</p>
        </div>
        <aside>
          <strong>{t("gestion.paymentMethods.companyScope")}</strong>
          <small>{t("gestion.paymentMethods.drawerScope")}</small>
        </aside>
      </header>

      {message && (
        <p
          className={`gestion-payment-methods-message ${message.kind}`}
          role={message.kind === "error" ? "alert" : "status"}
        >
          {message.text}
        </p>
      )}

      <section className="gestion-payment-methods-panel" aria-label={t("gestion.paymentMethods.title")}>
        <header className="gestion-payment-methods-row head">
          <span>{t("gestion.paymentMethods.method")}</span>
          <span>{t("gestion.paymentMethods.active")}</span>
          <span>{t("gestion.paymentMethods.requiresDocument")}</span>
          <span>{t("gestion.paymentMethods.type")}</span>
        </header>

        {loading ? (
          <p className="gestion-payment-methods-state" role="status">
            {t("gestion.paymentMethods.loading")}
          </p>
        ) : managedMethods.length === 0 ? (
          <p className="gestion-payment-methods-state">
            {t("gestion.paymentMethods.empty")}
          </p>
        ) : managedMethods.map((method) => {
          const referenceConfigurable = isReferenceConfigurablePaymentMethod(method.name);
          const voucherReferenceLocked = method.name.trim().toUpperCase() === "VALE";
          const working = busyId === method.id;
          const methodLabel = t(`gestion.paymentMethods.method.${method.name.trim().toUpperCase()}`);
          return (
            <article className="gestion-payment-methods-row" key={method.id}>
              <span className="gestion-payment-method-name">
                <strong>{methodLabel}</strong>
                <small>{method.name}</small>
              </span>
              <span>
                <label className="gestion-payment-switch">
                  <input
                    type="checkbox"
                    role="switch"
                    aria-label={`${t("gestion.paymentMethods.active")} · ${methodLabel}`}
                    checked={method.active}
                    disabled={!canManage || Boolean(busyId)}
                    onChange={(event) => void updateActive(method, event.currentTarget.checked)}
                  />
                  <i aria-hidden="true" />
                  <b>{method.active
                    ? t("gestion.paymentMethods.enabled")
                    : t("gestion.paymentMethods.disabled")}</b>
                </label>
              </span>
              <span>
                {referenceConfigurable ? (
                  <label className="gestion-payment-switch">
                    <input
                      type="checkbox"
                      role="switch"
                      aria-label={`${t("gestion.paymentMethods.requiresDocument")} · ${methodLabel}`}
                      checked={method.requiresReference}
                      disabled={!canManage || Boolean(busyId)}
                      onChange={(event) => void updateReference(method, event.currentTarget.checked)}
                    />
                    <i aria-hidden="true" />
                    <b>{method.requiresReference
                      ? t("common.yes")
                      : t("common.no")}</b>
                  </label>
                ) : voucherReferenceLocked ? (
                  <label className="gestion-payment-switch is-locked">
                    <input
                      type="checkbox"
                      role="switch"
                      aria-label={`${t("gestion.paymentMethods.requiresDocument")} · ${methodLabel}`}
                      checked={false}
                      disabled
                      readOnly
                    />
                    <i aria-hidden="true" />
                    <b>{t("common.no")}</b>
                  </label>
                ) : (
                  <small className="gestion-payment-not-applicable">
                    {t("gestion.paymentMethods.notApplicable")}
                  </small>
                )}
              </span>
              <span>
                <em className={method.protectedMethod ? "system" : "custom"}>
                  {method.protectedMethod
                    ? t("gestion.paymentMethods.system")
                    : t("gestion.paymentMethods.custom")}
                </em>
                {working && <small>{t("gestion.paymentMethods.saving")}</small>}
              </span>
            </article>
          );
        })}
      </section>
    </section>
  );
}
