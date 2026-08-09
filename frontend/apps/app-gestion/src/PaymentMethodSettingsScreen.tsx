import { useEffect, useMemo, useState } from "react";
import {
  ApiError,
  addInvoiceBankAccount,
  apiRequest,
  isReferenceConfigurablePaymentMethod,
  loadPaymentMethods,
  loadInvoicePrintConfiguration,
  managedCheckoutPaymentMethodNames,
  setPaymentMethodActive,
  setPaymentMethodReferenceRequirement,
  saveInvoiceObservations,
  setInvoiceBankAccountActive,
  type InvoiceBankAccountView,
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
  const [returnPolicy, setReturnPolicy] = useState<"REFUND_ALLOWED" | "EXCHANGE_OR_VOUCHER_ONLY">("REFUND_ALLOWED");
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const [observations, setObservations] = useState("");
  const [bankAccounts, setBankAccounts] = useState<InvoiceBankAccountView[]>([]);
  const [bankName, setBankName] = useState("");
  const [iban, setIban] = useState("");
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

  useEffect(() => {
    let active = true;
    void request<{ policy: "REFUND_ALLOWED" | "EXCHANGE_OR_VOUCHER_ONLY" }>(
      "/return-policy",
      { token: session.accessToken },
    ).then((value) => {
      if (active) setReturnPolicy(value.policy);
    }).catch(() => undefined);
    return () => { active = false; };
  }, [request, session.accessToken]);

  useEffect(() => {
    let active = true;
    void loadInvoicePrintConfiguration(session.accessToken, request)
      .then((configuration) => {
        if (!active || !configuration || Array.isArray(configuration)) return;
        setObservations(configuration.observations ?? "");
        setBankAccounts(Array.isArray(configuration.bankAccounts)
          ? configuration.bankAccounts : []);
      })
      .catch(() => undefined);
    return () => { active = false; };
  }, [request, session.accessToken]);

  async function updateReturnPolicy(policy: "REFUND_ALLOWED" | "EXCHANGE_OR_VOUCHER_ONLY") {
    if (!canManage || busyId) return;
    setBusyId("return-policy");
    setMessage(null);
    try {
      const saved = await request<{ policy: "REFUND_ALLOWED" | "EXCHANGE_OR_VOUCHER_ONLY" }>(
        "/return-policy",
        { token: session.accessToken, method: "PUT", body: { policy } },
      );
      setReturnPolicy(saved.policy);
      setMessage({ kind: "success", text: t("gestion.paymentMethods.saved") });
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.paymentMethods.saveError")) });
    } finally {
      setBusyId(null);
    }
  }

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

  async function updateObservations() {
    if (!canManage || busyId) return;
    setBusyId("invoice-observations");
    setMessage(null);
    try {
      const saved = await saveInvoiceObservations(
        observations, session.accessToken, request,
      );
      setObservations(saved.observations ?? "");
      setBankAccounts(saved.bankAccounts);
      setMessage({ kind: "success", text: t("gestion.invoicePrint.saved") });
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.invoicePrint.saveError")) });
    } finally {
      setBusyId(null);
    }
  }

  async function createBankAccount() {
    if (!canManage || busyId || !bankName.trim() || !iban.trim()) return;
    setBusyId("new-bank-account");
    setMessage(null);
    try {
      const saved = await addInvoiceBankAccount(
        bankName, iban, session.accessToken, request,
      );
      setBankAccounts((current) => [...current, saved]);
      setBankName("");
      setIban("");
      setMessage({ kind: "success", text: t("gestion.invoicePrint.saved") });
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.invoicePrint.bankAccountError")) });
    } finally {
      setBusyId(null);
    }
  }

  async function updateBankAccountActive(account: InvoiceBankAccountView, active: boolean) {
    if (!canManage || busyId) return;
    setBusyId(`bank-${account.id}`);
    setMessage(null);
    try {
      const saved = await setInvoiceBankAccountActive(
        account.id, active, session.accessToken, request,
      );
      setBankAccounts((current) => current.map((value) => value.id === saved.id ? saved : value));
      setMessage({ kind: "success", text: t("gestion.invoicePrint.saved") });
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.invoicePrint.saveError")) });
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

      <section className="gestion-return-policy" aria-labelledby="gestion-return-policy-title">
        <div>
          <h3 id="gestion-return-policy-title">{t("gestion.paymentMethods.returnPolicy.title")}</h3>
          <p>{t("gestion.paymentMethods.returnPolicy.description")}</p>
        </div>
        <label>
          <span>{t("gestion.paymentMethods.returnPolicy.label")}</span>
          <select
            value={returnPolicy}
            disabled={!canManage || busyId !== null}
            onChange={(event) => void updateReturnPolicy(event.currentTarget.value as typeof returnPolicy)}
          >
            <option value="REFUND_ALLOWED">{t("gestion.paymentMethods.returnPolicy.refundAllowed")}</option>
            <option value="EXCHANGE_OR_VOUCHER_ONLY">{t("gestion.paymentMethods.returnPolicy.voucherOnly")}</option>
          </select>
        </label>
      </section>

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

      <section className="gestion-invoice-print-panel" aria-labelledby="gestion-invoice-print-title">
        <header>
          <div>
            <h3 id="gestion-invoice-print-title">{t("gestion.invoicePrint.title")}</h3>
            <p>{t("gestion.invoicePrint.description")}</p>
          </div>
        </header>

        <label className="gestion-invoice-observations">
          <span>{t("gestion.invoicePrint.observations")}</span>
          <textarea
            value={observations}
            maxLength={2000}
            rows={4}
            disabled={!canManage || busyId !== null}
            onChange={(event) => setObservations(event.currentTarget.value)}
          />
          <small>{t("gestion.invoicePrint.observationsHelp")}</small>
        </label>
        <button
          type="button"
          className="gestion-primary-button"
          disabled={!canManage || busyId !== null}
          onClick={() => void updateObservations()}
        >
          {t("gestion.invoicePrint.saveObservations")}
        </button>

        <div className="gestion-invoice-bank-form">
          <label>
            <span>{t("gestion.invoicePrint.bankName")}</span>
            <input
              value={bankName}
              maxLength={120}
              disabled={!canManage || busyId !== null}
              onChange={(event) => setBankName(event.currentTarget.value)}
            />
          </label>
          <label>
            <span>{t("gestion.invoicePrint.iban")}</span>
            <input
              value={iban}
              maxLength={42}
              autoCapitalize="characters"
              disabled={!canManage || busyId !== null}
              onChange={(event) => setIban(event.currentTarget.value.toUpperCase())}
            />
          </label>
          <button
            type="button"
            disabled={!canManage || busyId !== null || !bankName.trim() || !iban.trim()}
            onClick={() => void createBankAccount()}
          >
            {t("gestion.invoicePrint.addBankAccount")}
          </button>
        </div>

        <div className="gestion-invoice-bank-list">
          {bankAccounts.length === 0 ? (
            <p>{t("gestion.invoicePrint.noBankAccounts")}</p>
          ) : bankAccounts.map((account) => (
            <article key={account.id}>
              <div>
                <strong>{account.bankName}</strong>
                <span>{account.displayIban}</span>
              </div>
              <label className="gestion-payment-switch">
                <input
                  type="checkbox"
                  role="switch"
                  aria-label={`${t("gestion.invoicePrint.activeOnInvoice")} · ${account.bankName}`}
                  checked={account.active}
                  disabled={!canManage || busyId !== null}
                  onChange={(event) => void updateBankAccountActive(account, event.currentTarget.checked)}
                />
                <i aria-hidden="true" />
                <b>{account.active ? t("common.yes") : t("common.no")}</b>
              </label>
            </article>
          ))}
        </div>
      </section>
    </section>
  );
}
