import { useEffect, useRef, useState } from "react";
import { GestionTableSearch, matchesGestionTableSearch } from "./GestionTableSearch";
import { GestionModuleHeading } from "./GestionModuleHeading";
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
  setInvoiceBankAccountActive,
  type InvoiceBankAccountView,
  type PaymentMethodView,
  type UserSession,
} from "@tpverp/app-common";

type Translator = (key: string) => string;
type ReturnPolicy = "REFUND_ALLOWED" | "EXCHANGE_OR_VOUCHER_ONLY";
type Message = { kind: "success" | "error"; text: string };
type Configuration = {
  policy: ReturnPolicy;
  methods: PaymentMethodView[];
  bankAccounts: InvoiceBankAccountView[];
};
type LocalBankAccount = {
  localId: string;
  bankName: string;
  iban: string;
  active: boolean;
};
type Editor = {
  context: string;
  baseline: Configuration;
  draft: Configuration;
  newAccounts: LocalBankAccount[];
  bankName: string;
  iban: string;
};

type Props = {
  session: UserSession;
  t: Translator;
  request?: typeof apiRequest;
};

function failureMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError) return error.message || fallback;
  return error instanceof Error && error.message ? error.message : fallback;
}

function normalizeIban(value: string | undefined) {
  return (value ?? "").replace(/\s+/g, "").toUpperCase();
}

function compatibleBankName(left: string, right: string) {
  return left.trim().replace(/\s+/g, " ").toLocaleLowerCase() === right.trim().replace(/\s+/g, " ").toLocaleLowerCase();
}

function hasChanges(editor: Editor) {
  if (editor.baseline.policy !== editor.draft.policy || editor.newAccounts.length > 0
    || editor.bankName.trim() !== "" || editor.iban.trim() !== "") return true;
  if (editor.draft.methods.some((method) => {
    const confirmed = editor.baseline.methods.find((value) => value.id === method.id);
    return confirmed && (confirmed.active !== method.active
      || (isReferenceConfigurablePaymentMethod(method.name)
        && confirmed.requiresReference !== method.requiresReference));
  })) return true;
  return editor.draft.bankAccounts.some((account) =>
    editor.baseline.bankAccounts.find((value) => value.id === account.id)?.active !== account.active);
}

function hasDuplicateIban(editor: Editor, accounts: LocalBankAccount[]) {
  const ibans = [
    ...editor.baseline.bankAccounts.map((account) => normalizeIban(account.iban)),
    ...accounts.map((account) => normalizeIban(account.iban)),
  ].filter(Boolean);
  return new Set(ibans).size !== ibans.length;
}

export function PaymentMethodSettingsScreen({
  session,
  t,
  request = apiRequest,
}: Props) {
  const context = String(session.accessToken ?? "") + "\u0000" + String(session.userId ?? session.username);
  const contextRef = useRef(context);
  contextRef.current = context;
  const translatorRef = useRef(t);
  translatorRef.current = t;
  const requestRef = useRef(request);
  requestRef.current = request;
  const generationRef = useRef(0);
  const saveOperationRef = useRef<symbol | null>(null);
  const savingRef = useRef(false);
  const nextLocalId = useRef(0);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [query, setQuery] = useState("");
  const [loadState, setLoadState] = useState<"loading" | "ready" | "error">("loading");
  const [reloadIndex, setReloadIndex] = useState(0);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const canManage = session.permissions.includes("ADMIN");
  const canManageRef = useRef(canManage);
  canManageRef.current = canManage;
  const current = editor?.context === context ? editor : null;
  const ready = loadState === "ready" && current !== null;
  const dirty = current ? hasChanges(current) : false;

  useEffect(() => {
    let active = true;
    generationRef.current += 1;
    saveOperationRef.current = null;
    savingRef.current = false;
    setSaving(false);
    setLoadState("loading");
    setEditor(null);
    setMessage(null);
    void Promise.all([
      loadPaymentMethods(session.accessToken, request),
      request<{ policy: ReturnPolicy }>("/return-policy", { token: session.accessToken }),
      loadInvoicePrintConfiguration(session.accessToken, request),
    ]).then(([methods, policy, invoice]) => {
      if (!active) return;
      if (!Array.isArray(methods)
        || (policy.policy !== "REFUND_ALLOWED" && policy.policy !== "EXCHANGE_OR_VOUCHER_ONLY")
        || !invoice || !Array.isArray(invoice.bankAccounts)) {
        throw new Error(translatorRef.current("gestion.paymentMethods.configurationLoadError"));
      }
      const loaded: Configuration = {
        methods,
        policy: policy.policy,
        bankAccounts: invoice.bankAccounts,
      };
      setEditor({
        context,
        baseline: loaded,
        draft: loaded,
        newAccounts: [],
        bankName: "",
        iban: "",
      });
      setLoadState("ready");
    }).catch((error) => {
      if (!active) return;
      setLoadState("error");
      setMessage({
        kind: "error",
        text: translatorRef.current("gestion.paymentMethods.configurationLoadError") + " "
          + failureMessage(error, translatorRef.current("gestion.paymentMethods.loadError")),
      });
    });
    return () => {
      active = false;
      generationRef.current += 1;
      saveOperationRef.current = null;
    };
  }, [request, session.accessToken, session.userId, session.username, reloadIndex]);

  const managedMethods = (current?.draft.methods ?? [])
    .filter((method) => managedCheckoutPaymentMethodNames
      .some((name) => method.name.trim().toUpperCase() === name));
  const visibleMethods = managedMethods.filter((method) => {
    const confirmed = current?.baseline.methods.find((value) => value.id === method.id);
    return matchesGestionTableSearch(query, [
      method.name,
      t("gestion.paymentMethods.method." + method.name.trim().toUpperCase()),
      t((confirmed?.active ?? method.active) ? "gestion.paymentMethods.enabled" : "gestion.paymentMethods.disabled"),
    ]);
  });

  function edit(update: (value: Editor) => Editor) {
    if (!canManage || !ready || savingRef.current) return;
    setEditor((value) => value?.context === context ? update(value) : value);
    setMessage(null);
  }

  function addLocalBankAccount() {
    if (!canManage || !ready || savingRef.current
      || !current || !current.bankName.trim() || !current.iban.trim()) return;
    const added: LocalBankAccount = {
      localId: String(++nextLocalId.current),
      bankName: current.bankName.trim(),
      iban: normalizeIban(current.iban),
      active: true,
    };
    if (hasDuplicateIban(current, [...current.newAccounts, added])) {
      setMessage({ kind: "error", text: t("gestion.invoicePrint.bankAccountError") });
      return;
    }
    edit((value) => ({
      ...value,
      newAccounts: [...value.newAccounts, added],
      bankName: "",
      iban: "",
    }));
  }

  async function save() {
    if (!canManage || !ready || !current || !dirty || savingRef.current) return;
    if (Boolean(current.bankName.trim()) !== Boolean(current.iban.trim())) {
      setMessage({ kind: "error", text: t("gestion.paymentMethods.bankFieldsRequired") });
      return;
    }
    const inlineAccount = current.bankName.trim() && current.iban.trim()
      ? [{
        localId: String(++nextLocalId.current),
        bankName: current.bankName.trim(),
        iban: normalizeIban(current.iban),
        active: true,
      }]
      : [];
    if (hasDuplicateIban(current, [...current.newAccounts, ...inlineAccount])) {
      setMessage({ kind: "error", text: t("gestion.invoicePrint.bankAccountError") });
      return;
    }

    savingRef.current = true;
    const saveOperation = Symbol("payment-settings-save");
    saveOperationRef.current = saveOperation;
    const saveGeneration = generationRef.current;
    setSaving(true);
    setMessage(null);
    let working: Editor = inlineAccount.length
      ? { ...current, newAccounts: [...current.newAccounts, ...inlineAccount], bankName: "", iban: "" }
      : current;
    if (inlineAccount.length) setEditor(working);
    const saveContext = context;
    const stillCurrent = () => contextRef.current === saveContext
      && requestRef.current === request && canManageRef.current
      && generationRef.current === saveGeneration
      && saveOperationRef.current === saveOperation;

    function commit(update: (value: Editor) => Editor) {
      if (!stillCurrent()) return;
      working = update(working);
      setEditor(working);
    }

    try {
      // Create accounts first, so invalid bank data cannot leave payment settings half saved.
      for (const local of [...working.newAccounts]) {
        if (!stillCurrent()) return;
        let saved: InvoiceBankAccountView;
        try {
          saved = await addInvoiceBankAccount(local.bankName, local.iban, session.accessToken, request);
        } catch (postError) {
          // A lost POST response may still mean the account was created. Only an
          // account absent from the pre-POST baseline can be adopted.
          let recovered: InvoiceBankAccountView | undefined;
          try {
            const refreshed = await loadInvoicePrintConfiguration(session.accessToken, request);
            const previousIds = new Set(working.baseline.bankAccounts.map((account) => account.id));
            const candidates = refreshed.bankAccounts.filter((account) =>
              !previousIds.has(account.id)
              && normalizeIban(account.iban) === normalizeIban(local.iban)
              && compatibleBankName(account.bankName, local.bankName));
            if (candidates.length === 1) recovered = candidates[0];
          } catch {
            // Keep the original POST error if confirmation could not be obtained.
          }
          if (!recovered) throw postError;
          saved = recovered;
        }
        commit((value) => ({
          ...value,
          baseline: {
            ...value.baseline,
            bankAccounts: [...value.baseline.bankAccounts, saved],
          },
          draft: {
            ...value.draft,
            bankAccounts: [...value.draft.bankAccounts, { ...saved, active: local.active }],
          },
          newAccounts: value.newAccounts.filter((account) => account.localId !== local.localId),
        }));
      }

      for (const initial of [...working.draft.methods]) {
        if (!stillCurrent()) return;
        const confirmed = working.baseline.methods.find((method) => method.id === initial.id);
        const draftMethod = working.draft.methods.find((method) => method.id === initial.id);
        if (!confirmed || !draftMethod || confirmed.active === draftMethod.active) continue;
        const saved = await setPaymentMethodActive(
          confirmed, draftMethod.active, session.accessToken, request,
        );
        commit((value) => {
          const before = value.baseline.methods.find((method) => method.id === saved.id);
          const pending = value.draft.methods.find((method) => method.id === saved.id);
          const keepReference = before && pending && before.requiresReference !== pending.requiresReference;
          return {
            ...value,
            baseline: {
              ...value.baseline,
              methods: value.baseline.methods.map((method) => method.id === saved.id ? saved : method),
            },
            draft: {
              ...value.draft,
              methods: value.draft.methods.map((method) => method.id === saved.id
                ? { ...saved, requiresReference: keepReference ? pending!.requiresReference : saved.requiresReference }
                : method),
            },
          };
        });
      }

      for (const initial of [...working.draft.methods]) {
        if (!stillCurrent()) return;
        const confirmed = working.baseline.methods.find((method) => method.id === initial.id);
        const draftMethod = working.draft.methods.find((method) => method.id === initial.id);
        if (!confirmed || !draftMethod || !isReferenceConfigurablePaymentMethod(draftMethod.name)
          || confirmed.requiresReference === draftMethod.requiresReference) continue;
        const refreshed = await loadPaymentMethods(session.accessToken, request);
        const latest = refreshed.find((method) => method.id === initial.id);
        if (!latest) throw new Error(t("gestion.paymentMethods.configurationLoadError"));
        if (!stillCurrent()) return;
        const saved = await setPaymentMethodReferenceRequirement(
          latest, draftMethod.requiresReference, session.accessToken, request,
        );
        commit((value) => {
          const before = value.baseline.methods.find((method) => method.id === saved.id);
          const pending = value.draft.methods.find((method) => method.id === saved.id);
          const keepActive = before && pending && before.active !== pending.active;
          return {
            ...value,
            baseline: {
              ...value.baseline,
              methods: value.baseline.methods.map((method) => method.id === saved.id ? saved : method),
            },
            draft: {
              ...value.draft,
              methods: value.draft.methods.map((method) => method.id === saved.id
                ? { ...saved, active: keepActive ? pending!.active : saved.active }
                : method),
            },
          };
        });
      }

      if (stillCurrent() && working.baseline.policy !== working.draft.policy) {
        const saved = await request<{ policy: ReturnPolicy }>("/return-policy", {
          token: session.accessToken,
          method: "PUT",
          body: { policy: working.draft.policy },
        });
        commit((value) => ({
          ...value,
          baseline: { ...value.baseline, policy: saved.policy },
          draft: { ...value.draft, policy: saved.policy },
        }));
      }

      for (const initial of [...working.draft.bankAccounts]) {
        if (!stillCurrent()) return;
        const confirmed = working.baseline.bankAccounts.find((account) => account.id === initial.id);
        const draftAccount = working.draft.bankAccounts.find((account) => account.id === initial.id);
        if (!confirmed || !draftAccount || confirmed.active === draftAccount.active) continue;
        const saved = await setInvoiceBankAccountActive(
          initial.id, draftAccount.active, session.accessToken, request,
        );
        commit((value) => ({
          ...value,
          baseline: {
            ...value.baseline,
            bankAccounts: value.baseline.bankAccounts.map((account) => account.id === saved.id ? saved : account),
          },
          draft: {
            ...value.draft,
            bankAccounts: value.draft.bankAccounts.map((account) => account.id === saved.id ? saved : account),
          },
        }));
      }

      if (stillCurrent()) setMessage({ kind: "success", text: t("gestion.paymentMethods.saved") });
    } catch (error) {
      if (stillCurrent()) {
        setMessage({
          kind: "error",
          text: t("gestion.paymentMethods.partialSaveError") + " "
            + failureMessage(error, t("gestion.paymentMethods.saveError")),
        });
      }
    } finally {
      if (saveOperationRef.current === saveOperation) {
        saveOperationRef.current = null;
        savingRef.current = false;
        if (generationRef.current === saveGeneration) setSaving(false);
      }
    }
  }

  return (
    <section className="gestion-workspace gestion-payment-methods-workspace gestion-module-screen" aria-busy={saving}>
      <GestionModuleHeading className="gestion-payment-methods-header" group={t("gestion.navigation.group.configuration")} title={t("gestion.paymentMethods.title")} description={t("gestion.paymentMethods.description")}>
        <aside>
          <strong>{t("gestion.paymentMethods.companyScope")}</strong>
          <small>{t("gestion.paymentMethods.drawerScope")}</small>
        </aside>
        {dirty && <small role="status">{t("gestion.paymentMethods.pending")}</small>}
        <button type="button" className="primary" disabled={!canManage || !ready || !dirty || saving} onClick={() => void save()}>
          {t("common.save")}
        </button>
      </GestionModuleHeading>

      <p className={"gestion-payment-methods-message " + (message?.kind ?? "")}
        role={message?.kind === "error" ? "alert" : "status"} aria-hidden={!message}>
        {message?.text}
      </p>
      {loadState === "error" && (
        <button type="button" className="gestion-module-action" onClick={() => setReloadIndex((value) => value + 1)}>
          {t("connectionRecovery.retry")}
        </button>
      )}

      <section className="gestion-return-policy gestion-module-panel gestion-module-fields" aria-labelledby="gestion-return-policy-title">
        <div>
          <h3 id="gestion-return-policy-title">{t("gestion.paymentMethods.returnPolicy.title")}</h3>
          <p>{t("gestion.paymentMethods.returnPolicy.description")}</p>
        </div>
        <label>
          <span>{t("gestion.paymentMethods.returnPolicy.label")}</span>
          <select value={current?.draft.policy ?? "REFUND_ALLOWED"} disabled={!canManage || !ready || saving}
            onChange={(event) => {
              const policy = event.currentTarget.value as ReturnPolicy;
              edit((value) => ({
                ...value,
                draft: { ...value.draft, policy },
              }));
            }}>
            <option value="REFUND_ALLOWED">{t("gestion.paymentMethods.returnPolicy.refundAllowed")}</option>
            <option value="EXCHANGE_OR_VOUCHER_ONLY">{t("gestion.paymentMethods.returnPolicy.voucherOnly")}</option>
          </select>
        </label>
      </section>

      <section className="gestion-payment-methods-panel gestion-module-panel gestion-module-table" aria-label={t("gestion.paymentMethods.title")}>
        <GestionTableSearch value={query} onChange={setQuery} t={t} />
        <header className="gestion-payment-methods-row head">
          <span>{t("gestion.paymentMethods.method")}</span>
          <span>{t("gestion.paymentMethods.active")}</span>
          <span>{t("gestion.paymentMethods.requiresDocument")}</span>
          <span>{t("gestion.paymentMethods.type")}</span>
        </header>
        {!ready && loadState === "loading" ? (
          <p className="gestion-payment-methods-state" role="status">{t("gestion.paymentMethods.loading")}</p>
        ) : visibleMethods.length === 0 ? (
          <p className="gestion-payment-methods-state">
            {t(query.trim() ? "stock.status.noResults" : "gestion.paymentMethods.empty")}
          </p>
        ) : visibleMethods.map((method) => {
          const referenceConfigurable = isReferenceConfigurablePaymentMethod(method.name);
          const voucherReferenceLocked = method.name.trim().toUpperCase() === "VALE";
          const methodLabel = t("gestion.paymentMethods.method." + method.name.trim().toUpperCase());
          return (
            <article className="gestion-payment-methods-row" key={method.id}>
              <span className="gestion-payment-method-name">
                <strong>{methodLabel}</strong>
                <small>{method.name}</small>
              </span>
              <span>
                <label className="gestion-payment-switch">
                  <input type="checkbox" role="switch"
                    aria-label={t("gestion.paymentMethods.active") + " · " + methodLabel}
                    checked={method.active} disabled={!canManage || !ready || saving}
                    data-saving={canManage && ready && saving || undefined}
                    onChange={(event) => {
                      const active = event.currentTarget.checked;
                      edit((value) => ({
                        ...value,
                        draft: {
                          ...value.draft,
                          methods: value.draft.methods.map((item) =>
                            item.id === method.id ? { ...item, active } : item),
                        },
                      }));
                    }} />
                  <b>{t(method.active ? "gestion.paymentMethods.enabled" : "gestion.paymentMethods.disabled")}</b>
                </label>
              </span>
              <span>
                {referenceConfigurable ? (
                  <label className="gestion-payment-switch">
                    <input type="checkbox" role="switch"
                      aria-label={t("gestion.paymentMethods.requiresDocument") + " · " + methodLabel}
                      checked={method.requiresReference} disabled={!canManage || !ready || saving}
                      data-saving={canManage && ready && saving || undefined}
                      onChange={(event) => {
                        const requiresReference = event.currentTarget.checked;
                        edit((value) => ({
                          ...value,
                          draft: {
                            ...value.draft,
                            methods: value.draft.methods.map((item) =>
                              item.id === method.id ? { ...item, requiresReference } : item),
                          },
                        }));
                      }} />
                    <b>{t(method.requiresReference ? "common.yes" : "common.no")}</b>
                  </label>
                ) : voucherReferenceLocked ? (
                  <label className="gestion-payment-switch is-locked">
                    <input type="checkbox" role="switch"
                      aria-label={t("gestion.paymentMethods.requiresDocument") + " · " + methodLabel}
                      checked={false} disabled readOnly />
                    <b>{t("common.no")}</b>
                  </label>
                ) : (
                  <small className="gestion-payment-not-applicable">{t("gestion.paymentMethods.notApplicable")}</small>
                )}
              </span>
              <span>
                <em className={method.protectedMethod ? "system" : "custom"}>
                  {t(method.protectedMethod ? "gestion.paymentMethods.system" : "gestion.paymentMethods.custom")}
                </em>
              </span>
            </article>
          );
        })}
      </section>

      <section className="gestion-invoice-print-panel gestion-module-panel" aria-labelledby="gestion-invoice-print-title">
        <header>
          <div>
            <h3 id="gestion-invoice-print-title">{t("gestion.invoicePrint.title")}</h3>
            <p>{t("gestion.invoicePrint.description")}</p>
          </div>
        </header>
        <div className="gestion-invoice-bank-form gestion-module-fields">
          <label>
            <span>{t("gestion.invoicePrint.bankName")}</span>
            <input value={current?.bankName ?? ""} maxLength={120} disabled={!canManage || !ready || saving}
              onChange={(event) => {
                const bankName = event.currentTarget.value;
                edit((value) => ({ ...value, bankName }));
              }} />
          </label>
          <label>
            <span>{t("gestion.invoicePrint.iban")}</span>
            <input value={current?.iban ?? ""} maxLength={42} autoCapitalize="characters"
              disabled={!canManage || !ready || saving}
              onChange={(event) => {
                const iban = event.currentTarget.value.toUpperCase();
                edit((value) => ({ ...value, iban }));
              }} />
          </label>
          <div className="gestion-toolbar-actions"><button type="button"
            disabled={!canManage || !ready || saving || !current?.bankName.trim() || !current?.iban.trim()}
            onClick={addLocalBankAccount}>
            {t("gestion.invoicePrint.addBankAccount")}
          </button></div>
        </div>
        <div className="gestion-invoice-bank-list">
          {(current?.draft.bankAccounts.length ?? 0) + (current?.newAccounts.length ?? 0) === 0 ? (
            <p>{t("gestion.invoicePrint.noBankAccounts")}</p>
          ) : (
            <>
              {current?.draft.bankAccounts.map((account) => (
                <article key={account.id}>
                  <div><strong>{account.bankName}</strong><span>{account.displayIban}</span></div>
                  <label className="gestion-payment-switch">
                    <input type="checkbox" role="switch"
                      aria-label={t("gestion.invoicePrint.activeOnInvoice") + " · " + account.bankName}
                      checked={account.active} disabled={!canManage || !ready || saving}
                      data-saving={canManage && ready && saving || undefined}
                      onChange={(event) => {
                        const active = event.currentTarget.checked;
                        edit((value) => ({
                          ...value,
                          draft: {
                            ...value.draft,
                            bankAccounts: value.draft.bankAccounts.map((item) =>
                              item.id === account.id ? { ...item, active } : item),
                          },
                        }));
                      }} />
                    <b>{t(account.active ? "common.yes" : "common.no")}</b>
                  </label>
                </article>
              ))}
              {current?.newAccounts.map((account) => (
                <article key={account.localId}>
                  <div><strong>{account.bankName}</strong><span>{account.iban}</span></div>
                  <label className="gestion-payment-switch">
                    <input type="checkbox" role="switch"
                      aria-label={t("gestion.invoicePrint.activeOnInvoice") + " · " + account.bankName}
                      checked={account.active} disabled={!canManage || !ready || saving}
                      data-saving={canManage && ready && saving || undefined}
                      onChange={(event) => {
                        const active = event.currentTarget.checked;
                        edit((value) => ({
                          ...value,
                          newAccounts: value.newAccounts.map((item) =>
                            item.localId === account.localId ? { ...item, active } : item),
                        }));
                      }} />
                    <b>{t(account.active ? "common.yes" : "common.no")}</b>
                  </label>
                  <button type="button" className="gestion-module-action" disabled={!canManage || !ready || saving}
                    aria-label={t("common.delete") + " · " + account.bankName}
                    onClick={() => edit((value) => ({
                      ...value,
                      newAccounts: value.newAccounts.filter((item) => item.localId !== account.localId),
                    }))}>
                    {t("common.delete")}
                  </button>
                </article>
              ))}
            </>
          )}
        </div>
      </section>
    </section>
  );
}
