import { useRefreshVersion } from "../../app/RefreshContext";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../lib/api";
import { canInvoiceBePaid, isCurrentSelection, outstandingAmount, settleWithConcurrency, validateFiscalDecision } from "../../lib/frontend-runtime.mjs";
import type { BillingInvoice, BillingSummary, Credentials, InvoiceFiscalDetail, LicenseSummary, PaymentReconciliation, PlanUsage, TaxRegime } from "../../lib/types";
import { useI18n } from "../../i18n/index";
import { normalizeSearch, formatDate, formatCurrency, billingStatusLabel, toLocalInput, addDays, parseAmount, errorMessage, isPositiveAmount, formatMoney, formatQuantity } from "../../shared/lib";
import { usePagination, EmptyState, Input, StatusPill, PaginationControls, Metric, SectionHeader, RetryError } from "../../shared/ui";
import { Notice } from "../../shared/types";

export function InvoiceTable({ invoices }: { invoices: BillingInvoice[] }) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const filtered = invoices.filter((invoice) => [invoice.number, invoice.concept, invoice.status, invoice.companyName].some((value) => normalizeSearch(value).includes(normalizeSearch(query))));
  const paging = usePagination(filtered);
  if (invoices.length === 0) return <EmptyState text={t("noBillingData")} />;
  return (
    <>
      <div className="toolbar table-filter"><Input label={t("filterRecords")} value={query} onChange={setQuery} /></div>
      <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("invoiceNumber")}</th>
            <th>{t("concept")}</th>
            <th>{t("amount")}</th>
            <th>{t("paidAmount")}</th>
            <th>{t("status")}</th>
            <th>{t("dueAt")}</th>
          </tr>
        </thead>
        <tbody>
          {paging.rows.map((invoice) => (
            <tr key={invoice.id}>
              <td>
                <strong>{invoice.number}</strong>
                <small>{formatDate(invoice.issuedAt)}</small>
              </td>
              <td>{invoice.concept}</td>
              <td>{formatCurrency(invoice.amount, invoice.currency)}</td>
              <td>{formatCurrency(invoice.paidAmount, invoice.currency)}</td>
              <td>
                <StatusPill status={billingStatusLabel(invoice.status, t)} tone={invoice.status === "PAGADA" ? "ok" : "warning"} />
              </td>
              <td>{formatDate(invoice.dueAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      <PaginationControls {...paging} />
    </>
  );
}

export function BillingView({
  credentials,
  licenses,
  permissions,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const overviewRequestId = useRef(0);
  const visibleCompanyIds = useMemo(() => new Set(licenses.map((license) => license.companyId)), [licenses]);
  const [summary, setSummary] = useState<BillingSummary | null>(null);
  const [selectedCompanyId, setSelectedCompanyId] = useState("");
  const [invoices, setInvoices] = useState<BillingInvoice[]>([]);
  const [invoiceForm, setInvoiceForm] = useState({
    number: "",
    concept: "",
    amount: "",
    currency: "EUR",
    issuedAt: toLocalInput(new Date()),
    dueAt: toLocalInput(addDays(new Date(), 30))
  });
  const [paymentForm, setPaymentForm] = useState({ invoiceId: "", amount: "", method: "TRANSFERENCIA", reference: "" });
  const [planUsage, setPlanUsage] = useState<PlanUsage | null>(null);
  const [planError, setPlanError] = useState<string | null>(null);
  const [reconciliations, setReconciliations] = useState<PaymentReconciliation[]>([]);
  const [reconciliationError, setReconciliationError] = useState<string | null>(null);
  const [reconciliationForm, setReconciliationForm] = useState({ provider: "MANUAL_BANK", externalReference: "", amount: "", currency: "EUR", bookedAt: toLocalInput(new Date()), notes: "" });
  const [fiscalInvoiceId, setFiscalInvoiceId] = useState("");
  const [fiscalDetail, setFiscalDetail] = useState<InvoiceFiscalDetail | null>(null);
  const [fiscalStates, setFiscalStates] = useState<Record<string, InvoiceFiscalDetail | null>>({});
  const [fiscalStatesLoadError, setFiscalStatesLoadError] = useState<string | null>(null);
  const [fiscalForm, setFiscalForm] = useState({
    taxRegime: "" as TaxRegime | "",
    fiscalStatus: "CALCULATED" as "CALCULATED" | "NOT_APPLICABLE",
    taxBase: "", taxRate: "", taxAmount: "", reason: "", legalBasis: "", evidenceReference: ""
  });
  const [fiscalError, setFiscalError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [reconciliationBusy, setReconciliationBusy] = useState(false);
  const [fiscalBusy, setFiscalBusy] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const invoiceRequestId = useRef(0);
  const billingExtrasRequestId = useRef(0);
  const fiscalStatesRequestId = useRef(0);
  const fiscalRequestId = useRef(0);
  const billingContextId = useRef(0);
  const selectedBillingCompanyRef = useRef(selectedCompanyId);
  selectedBillingCompanyRef.current = selectedCompanyId;
  const canManage = permissions.has("MANAGE_BILLING");
  const visibleCompanies = (summary?.companies ?? []).filter((company) => visibleCompanyIds.has(company.companyId));
  const orderedCompanies = visibleCompanies.slice().sort((left, right) => Number(right.overdue) - Number(left.overdue) || Number(right.renewalDueSoon) - Number(left.renewalDueSoon) || left.companyName.localeCompare(right.companyName));
  const localSummary = summary
    ? {
        ...summary,
        totalCompanies: visibleCompanies.length,
        paidCompanies: visibleCompanies.filter((company) => company.billingStatus === "PAGADO").length,
        pendingCompanies: visibleCompanies.filter((company) => ["PENDIENTE", "VENCIDO", "IMPAGADO"].includes(company.billingStatus)).length,
        overdueCompanies: visibleCompanies.filter((company) => company.overdue).length,
        renewalsNext30Days: visibleCompanies.filter((company) => company.renewalDueSoon).length,
        monthlyRecurringRevenue: visibleCompanies.some((company) => company.monthlyPrice == null) ? null
          : visibleCompanies.reduce((total, company) => total + parseAmount(company.monthlyPrice), 0).toFixed(2)
      }
    : null;

  useEffect(() => {
    void loadBilling();
  }, [credentials.accessToken, refreshVersion]);

  useEffect(() => {
    if (!orderedCompanies.some((company) => company.companyId === selectedCompanyId)) {
      setSelectedCompanyId(orderedCompanies[0]?.companyId ?? "");
    }
  }, [orderedCompanies, selectedCompanyId]);

  useEffect(() => {
    billingContextId.current += 1;
    invoiceRequestId.current += 1; billingExtrasRequestId.current += 1; fiscalRequestId.current += 1; fiscalStatesRequestId.current += 1;
    setInvoices([]); setPlanUsage(null); setReconciliations([]); setFiscalDetail(null); setFiscalStates({});
    setPlanError(null); setReconciliationError(null); setFiscalError(null); setFiscalStatesLoadError(null); setFiscalInvoiceId("");
    setBusy(null); setReconciliationBusy(false); setFiscalBusy(false);
    setPaymentForm({ invoiceId: "", amount: "", method: "TRANSFERENCIA", reference: "" });
    if (selectedCompanyId) void loadInvoices(selectedCompanyId);
  }, [selectedCompanyId]);

  useEffect(() => { if (selectedCompanyId) void loadInvoices(selectedCompanyId); }, [refreshVersion]);

  async function loadBilling() {
    const id = ++overviewRequestId.current;
    try {
      const response = await api.billingSummary(credentials);
      if (id !== overviewRequestId.current) return;
      setSummary(response);
    } catch (error) {
      if (id !== overviewRequestId.current) return;
      setSummary(null);
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function loadInvoices(companyId: string) {
    const requestId = ++invoiceRequestId.current;
    try {
      const response = await api.billingInvoices(credentials, companyId);
      if (requestId !== invoiceRequestId.current || !isCurrentSelection(companyId, selectedBillingCompanyRef.current)) return;
      setInvoices(response);
      setLoadError(null);
      onNotice(null);
      void loadBillingExtras(companyId);
      void loadInvoiceFiscalStates(companyId, response, requestId);
    } catch (error) {
      if (requestId !== invoiceRequestId.current || !isCurrentSelection(companyId, selectedBillingCompanyRef.current)) return;
      setInvoices([]);
      const message = errorMessage(error);
      setLoadError(message);
      onNotice({ type: "error", text: message });
    }
  }

  async function loadInvoiceFiscalStates(companyId: string, companyInvoices: BillingInvoice[], invoiceLoadRequestId: number) {
    const requestId = ++fiscalStatesRequestId.current;
    setFiscalStates({}); setFiscalStatesLoadError(null);
    const results = await settleWithConcurrency(companyInvoices, (invoice) => api.invoiceFiscalDetail(credentials, invoice.id), 4);
    if (requestId !== fiscalStatesRequestId.current || invoiceLoadRequestId !== invoiceRequestId.current
        || !isCurrentSelection(companyId, selectedBillingCompanyRef.current)) return;
    const next: Record<string, InvoiceFiscalDetail | null> = {};
    companyInvoices.forEach((invoice, index) => {
      const result = results[index];
      next[invoice.id] = result.status === "fulfilled" && result.value.companyId === companyId ? result.value : null;
    });
    setFiscalStates(next);
    const failed = Object.values(next).filter((detail) => detail === null).length;
    setFiscalStatesLoadError(failed > 0 ? t("fiscalVerificationFailed").replace("{count}", String(failed)) : null);
  }

  async function loadBillingExtras(companyId: string) {
    const requestId = ++billingExtrasRequestId.current;
    const [planResult, reconciliationResult] = await Promise.allSettled([
      api.planUsage(credentials, companyId), api.paymentReconciliations(credentials, companyId)
    ]);
    if (requestId !== billingExtrasRequestId.current || !isCurrentSelection(companyId, selectedBillingCompanyRef.current)) return;
    if (planResult.status === "fulfilled") { setPlanUsage(planResult.value); setPlanError(null); }
    else { setPlanUsage(null); setPlanError(errorMessage(planResult.reason)); }
    if (reconciliationResult.status === "fulfilled") { setReconciliations(reconciliationResult.value); setReconciliationError(null); }
    else { setReconciliations([]); setReconciliationError(errorMessage(reconciliationResult.reason)); }
  }

  async function createInvoice(event: FormEvent) {
    event.preventDefault();
    if (!selectedCompanyId) return;
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(invoiceForm.amount)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
    const requestedCompanyId = selectedCompanyId;
    const operationContext = billingContextId.current;
    const isCurrent = () => operationContext === billingContextId.current && isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current);
    setBusy("invoice");
    try {
      await api.createBillingInvoice(credentials, requestedCompanyId, {
        ...invoiceForm,
        issuedAt: new Date(invoiceForm.issuedAt).toISOString(),
        dueAt: new Date(invoiceForm.dueAt).toISOString()
      });
      if (!isCurrent()) return;
      setInvoiceForm({
        number: "",
        concept: "",
        amount: "",
        currency: "EUR",
        issuedAt: toLocalInput(new Date()),
        dueAt: toLocalInput(addDays(new Date(), 30))
      });
      await loadInvoices(requestedCompanyId);
      if (!isCurrent()) return;
      await loadBilling();
      if (isCurrent()) onNotice({ type: "success", text: t("createInvoice") });
    } catch (error) {
      if (isCurrent()) onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (isCurrent()) setBusy(null);
    }
  }

  async function registerPayment(event: FormEvent) {
    event.preventDefault();
    if (!paymentForm.invoiceId) return;
    const selectedInvoice = invoices.find((invoice) => invoice.id === paymentForm.invoiceId && invoice.companyId === selectedCompanyId);
    if (!selectedInvoice) {
      onNotice({ type: "error", text: t("companySelectionChanged") });
      return;
    }
    if (!canInvoiceBePaid(fiscalStates[selectedInvoice.id])) {
      onNotice({ type: "error", text: t("fiscalPendingPayment") });
      return;
    }
    if (parseAmount(paymentForm.amount) > outstandingAmount(selectedInvoice)) {
      onNotice({ type: "error", text: t("paymentExceedsOutstanding") });
      return;
    }
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(paymentForm.amount)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
    const requestedCompanyId = selectedCompanyId;
    const invoiceId = paymentForm.invoiceId;
    const operationContext = billingContextId.current;
    const isCurrent = () => operationContext === billingContextId.current && isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current);
    setBusy("payment");
    try {
      await api.createBillingPayment(credentials, invoiceId, {
        amount: paymentForm.amount,
        method: paymentForm.method,
        paidAt: new Date().toISOString(),
        reference: paymentForm.reference
      });
      if (!isCurrent()) return;
      setPaymentForm({ invoiceId: "", amount: "", method: "TRANSFERENCIA", reference: "" });
      await loadInvoices(requestedCompanyId);
      if (!isCurrent()) return;
      await loadBilling();
      if (isCurrent()) onNotice({ type: "success", text: t("registerPayment") });
    } catch (error) {
      if (isCurrent()) onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (isCurrent()) setBusy(null);
    }
  }

  async function createReconciliation(event: FormEvent) {
    event.preventDefault();
    if (!selectedCompanyId || !canManage) { onNotice({ type: "error", text: t("noPermissionAction") }); return; }
    if (!/^\d+(?:\.\d{1,2})?$/.test(reconciliationForm.amount) || !isPositiveAmount(reconciliationForm.amount)
        || !/^[A-Za-z]{3}$/.test(reconciliationForm.currency) || !reconciliationForm.externalReference.trim()
        || reconciliationForm.externalReference.length > 160 || reconciliationForm.notes.length > 500) {
      onNotice({ type: "error", text: t("invalidReconciliation") }); return;
    }
    const requestedCompanyId = selectedCompanyId;
    const operationContext = billingContextId.current;
    setReconciliationBusy(true);
    try {
      await api.createPaymentReconciliation(credentials, requestedCompanyId, {
        paymentId: null, ...reconciliationForm,
        provider: reconciliationForm.provider,
        currency: reconciliationForm.currency.toUpperCase(),
        externalReference: reconciliationForm.externalReference.trim(),
        bookedAt: new Date(reconciliationForm.bookedAt).toISOString()
      });
      if (operationContext !== billingContextId.current || !isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current)) return;
      setReconciliationForm({ provider: "MANUAL_BANK", externalReference: "", amount: "", currency: "EUR", bookedAt: toLocalInput(new Date()), notes: "" });
      await loadBillingExtras(requestedCompanyId);
      onNotice({ type: "success", text: t("reconciliationCreated") });
    } catch (error) { if (operationContext === billingContextId.current && isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current)) onNotice({ type: "error", text: errorMessage(error) }); }
    finally { if (operationContext === billingContextId.current && isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current)) setReconciliationBusy(false); }
  }

  async function loadFiscalDetail(invoiceId = fiscalInvoiceId) {
    const invoice = invoices.find((item) => item.id === invoiceId && item.companyId === selectedCompanyId);
    if (!invoice) return;
    const requestedCompanyId = selectedCompanyId;
    const requestId = ++fiscalRequestId.current;
    setFiscalBusy(true); setFiscalError(null); setFiscalDetail(null);
    try {
      const detail = await api.invoiceFiscalDetail(credentials, invoiceId);
      if (requestId !== fiscalRequestId.current || !isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current) || detail.companyId !== requestedCompanyId) return;
      setFiscalDetail(detail);
      setFiscalStates((current) => ({ ...current, [invoiceId]: detail }));
      setFiscalForm({
        taxRegime: detail.taxRegime ?? "",
        fiscalStatus: detail.fiscalStatus === "NOT_APPLICABLE" ? "NOT_APPLICABLE" : "CALCULATED",
        taxBase: detail.taxBase ?? "", taxRate: detail.taxRate ?? "", taxAmount: detail.taxAmount ?? "",
        reason: detail.reason ?? "", legalBasis: detail.legalBasis ?? "", evidenceReference: detail.evidenceReference ?? ""
      });
    } catch (error) { if (requestId === fiscalRequestId.current && isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current)) setFiscalError(errorMessage(error)); }
    finally { if (requestId === fiscalRequestId.current && isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current)) setFiscalBusy(false); }
  }

  async function saveFiscalDecision(event: FormEvent) {
    event.preventDefault();
    if (!canManage || !fiscalDetail || !(fiscalDetail.taxRegime ?? fiscalForm.taxRegime) || !validateFiscalDecision(fiscalForm)) {
      onNotice({ type: "error", text: canManage ? t("invalidFiscalDecision") : t("noPermissionAction") });
      return;
    }
    const invoiceId = fiscalDetail.invoiceId;
    const requestedCompanyId = selectedCompanyId;
    const requestId = ++fiscalRequestId.current;
    setFiscalBusy(true); setFiscalError(null);
    try {
      const calculated = fiscalForm.fiscalStatus === "CALCULATED";
      const detail = await api.updateInvoiceFiscal(credentials, invoiceId, {
        ...(fiscalDetail.taxRegime ? {} : { taxRegime: fiscalForm.taxRegime as TaxRegime }),
        fiscalStatus: fiscalForm.fiscalStatus,
        taxBase: calculated ? fiscalForm.taxBase.trim() : null,
        taxRate: calculated ? fiscalForm.taxRate.trim() : null,
        taxAmount: calculated ? fiscalForm.taxAmount.trim() : null,
        reason: calculated ? null : fiscalForm.reason.trim(),
        legalBasis: calculated ? null : fiscalForm.legalBasis.trim(),
        evidenceReference: calculated ? null : fiscalForm.evidenceReference.trim()
      });
      if (requestId !== fiscalRequestId.current || !isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current)
          || detail.companyId !== requestedCompanyId || detail.invoiceId !== invoiceId) return;
      setFiscalDetail(detail);
      setFiscalStates((current) => ({ ...current, [invoiceId]: detail }));
      if (paymentForm.invoiceId === invoiceId && !canInvoiceBePaid(detail)) setPaymentForm({ ...paymentForm, invoiceId: "", amount: "" });
      onNotice({ type: "success", text: t("fiscalDecisionSaved") });
    } catch (error) {
      if (requestId === fiscalRequestId.current && isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current)) setFiscalError(errorMessage(error));
    } finally {
      if (requestId === fiscalRequestId.current && isCurrentSelection(requestedCompanyId, selectedBillingCompanyRef.current)) setFiscalBusy(false);
    }
  }

  return (
    <div className="view-grid">
      <section className="metric-grid">
        <Metric label={t("monthlyRecurringRevenue")} value={localSummary?.monthlyRecurringRevenue == null ? "—" : formatMoney(localSummary.monthlyRecurringRevenue)} />
        <Metric label={t("paidCompanies")} value={localSummary?.paidCompanies ?? "-"} />
        <Metric label={t("pendingBilling")} value={localSummary?.pendingCompanies ?? "-"} tone={(localSummary?.pendingCompanies ?? 0) > 0 ? "warning" : undefined} />
        <Metric label={t("overdueBilling")} value={localSummary?.overdueCompanies ?? "-"} tone={(localSummary?.overdueCompanies ?? 0) > 0 ? "warning" : undefined} />
        <Metric label={t("renewalsNext30Days")} value={localSummary?.renewalsNext30Days ?? "-"} tone={(localSummary?.renewalsNext30Days ?? 0) > 0 ? "warning" : undefined} />
        <Metric label={t("company")} value={localSummary?.totalCompanies ?? "-"} />
      </section>

      <section className="content-section billing-board">
        <SectionHeader title={t("billingPortfolio")} subtitle={t("billingPortfolioSubtitle")} />
        {orderedCompanies.length === 0 ? (
          <EmptyState text={t("noBillingData")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("company")}</th>
                  <th>{t("plan")}</th>
                  <th>{t("billingStatus")}</th>
                  <th>{t("renewalDate")}</th>
                  <th>{t("monthlyPrice")}</th>
                  <th>{t("license")}</th>
                </tr>
              </thead>
              <tbody>
                {orderedCompanies.map((company) => (
                  <tr key={company.companyId} className={company.overdue ? "billing-overdue" : company.renewalDueSoon ? "billing-due" : ""}>
                    <td>
                      <strong>{company.companyName}</strong>
                      <small>{company.taxId}</small>
                    </td>
                    <td>{company.planName}</td>
                    <td>
                      <StatusPill
                        status={billingStatusLabel(company.billingStatus, t)}
                        tone={company.overdue || company.renewalDueSoon ? "warning" : "ok"}
                      />
                      {company.renewalDueSoon && <small>{t("dueSoon")}</small>}
                    </td>
                    <td>{company.renewalDate ? formatDate(company.renewalDate) : t("pending")}</td>
                    <td>{company.monthlyPrice == null ? "—" : formatMoney(company.monthlyPrice)}</td>
                    <td>
                      <strong>{company.licenseReference ?? t("notAvailable")}</strong>
                      <small>{company.validUntil ? formatDate(company.validUntil) : t("pending")}</small>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="content-section">
        <SectionHeader title={t("realBilling")} subtitle={t("invoices")} />
        {loadError && <RetryError message={loadError} onRetry={() => selectedCompanyId && void loadInvoices(selectedCompanyId)} />}
        <div className="toolbar">
          <label className="toolbar-field">{t("company")}
          <select aria-label={t("company")} className="control-input" value={selectedCompanyId} onChange={(event) => setSelectedCompanyId(event.target.value)}>
            {orderedCompanies.map((company) => (
              <option value={company.companyId} key={company.companyId}>{company.companyName}</option>
            ))}
          </select>
          </label>
        </div>
        <SectionHeader title={t("planUsage")} subtitle={planUsage?.planName ?? "-"} />
        {planError && <RetryError message={planError} onRetry={() => selectedCompanyId && void loadBillingExtras(selectedCompanyId)} />}
        {planUsage && <div className="metric-grid">
          {Object.entries(planUsage.limits).map(([resource, limit]) => <Metric key={resource} label={resource.replaceAll("_", " ")} value={t("usedOfLimit").replace("{used}", String(planUsage.usage[resource] ?? 0)).replace("{limit}", String(limit))} tone={(planUsage.usage[resource] ?? 0) >= limit ? "warning" : undefined} />)}
        </div>}
        {canManage && (
          <>
            <form className="compact-form-grid" onSubmit={createInvoice}>
              <Input label={t("invoiceNumber")} value={invoiceForm.number} onChange={(number) => setInvoiceForm({ ...invoiceForm, number })} required />
              <Input label={t("concept")} value={invoiceForm.concept} onChange={(concept) => setInvoiceForm({ ...invoiceForm, concept })} required />
              <Input label={t("amount")} value={invoiceForm.amount} onChange={(amount) => setInvoiceForm({ ...invoiceForm, amount })} required />
              <Input label={t("currency")} value={invoiceForm.currency} onChange={(currency) => setInvoiceForm({ ...invoiceForm, currency })} required />
              <Input label={t("issuedAt")} type="datetime-local" value={invoiceForm.issuedAt} onChange={(issuedAt) => setInvoiceForm({ ...invoiceForm, issuedAt })} required />
              <Input label={t("dueAt")} type="datetime-local" value={invoiceForm.dueAt} onChange={(dueAt) => setInvoiceForm({ ...invoiceForm, dueAt })} required />
              <button className="primary-button" type="submit" disabled={busy === "invoice"}>{t("createInvoice")}</button>
            </form>
            <form className="compact-form-grid" onSubmit={registerPayment}>
              <label>
                {t("invoices")}
                <select
                  className="control-input"
                  value={paymentForm.invoiceId}
                  onChange={(event) => {
                    const invoiceId = event.target.value;
                    const invoice = invoices.find((value) => value.id === invoiceId);
                    setPaymentForm({ ...paymentForm, invoiceId, amount: invoice ? String(outstandingAmount(invoice)) : "" });
                  }}
                >
                  <option value="">{t("pending")}</option>
                  {invoices.map((invoice) => (
                    <option value={invoice.id} key={invoice.id} disabled={!canInvoiceBePaid(fiscalStates[invoice.id])}>
                      {invoice.number} - {formatCurrency(invoice.amount, invoice.currency)}{!canInvoiceBePaid(fiscalStates[invoice.id]) ? ` — ${t("fiscalStatus")}: ${Object.prototype.hasOwnProperty.call(fiscalStates, invoice.id) ? (fiscalStates[invoice.id]?.fiscalStatus ?? t("technicalDegraded")) : t("fiscalVerifying")}` : ""}
                    </option>
                  ))}
                </select>
              </label>
              <Input label={t("amount")} value={paymentForm.amount} onChange={(amount) => setPaymentForm({ ...paymentForm, amount })} required />
              <Input label={t("paymentMethod")} value={paymentForm.method} onChange={(method) => setPaymentForm({ ...paymentForm, method })} required />
              <Input label={t("paymentReference")} value={paymentForm.reference} onChange={(reference) => setPaymentForm({ ...paymentForm, reference })} />
              <button className="primary-button" type="submit" disabled={busy === "payment" || !paymentForm.invoiceId}>{t("registerPayment")}</button>
            </form>
          </>
        )}
        <InvoiceTable invoices={invoices} />

        <SectionHeader title={t("invoiceFiscalDetail")} subtitle={fiscalDetail ? `${fiscalDetail.series}-${fiscalDetail.number}` : ""} />
        {fiscalStatesLoadError && <RetryError message={fiscalStatesLoadError} onRetry={() => selectedCompanyId && void loadInvoiceFiscalStates(selectedCompanyId, invoices, invoiceRequestId.current)} />}
        {fiscalError && <RetryError message={fiscalError} onRetry={() => void loadFiscalDetail()} />}
        <div className="toolbar">
          <select aria-label={t("invoiceFiscalDetail")} className="control-input" value={fiscalInvoiceId} onChange={(event) => { setFiscalInvoiceId(event.target.value); setFiscalDetail(null); setFiscalError(null); }}>
            <option value="">{t("invoices")}</option>
            {invoices.map((invoice) => <option key={invoice.id} value={invoice.id}>{invoice.number} - {formatCurrency(invoice.amount, invoice.currency)}</option>)}
          </select>
          <button className="secondary-button" type="button" disabled={!fiscalInvoiceId || fiscalBusy} onClick={() => void loadFiscalDetail()}>{t("viewFiscalDetail")}</button>
        </div>
        {fiscalDetail && <div className="metric-grid" aria-live="polite">
          <Metric label={t("series")} value={fiscalDetail.series} /><Metric label={t("fiscalYear")} value={fiscalDetail.fiscalYear} />
          <Metric label={t("taxRegime")} value={fiscalDetail.taxRegime ?? t("fiscalValuePending")} /><Metric label={t("fiscalStatus")} value={fiscalDetail.fiscalStatus} />
          <Metric label={t("taxBase")} value={fiscalDetail.taxBase === null ? t(fiscalDetail.fiscalStatus === "PENDING_TAX_DATA" ? "fiscalValuePending" : "fiscalValueNotApplicable") : formatCurrency(fiscalDetail.taxBase, fiscalDetail.currency)} />
          <Metric label={t("taxRate")} value={fiscalDetail.taxRate === null ? t(fiscalDetail.fiscalStatus === "PENDING_TAX_DATA" ? "fiscalValuePending" : "fiscalValueNotApplicable") : `${formatQuantity(fiscalDetail.taxRate)}%`} /><Metric label={t("taxAmount")} value={fiscalDetail.taxAmount === null ? t(fiscalDetail.fiscalStatus === "PENDING_TAX_DATA" ? "fiscalValuePending" : "fiscalValueNotApplicable") : formatCurrency(fiscalDetail.taxAmount, fiscalDetail.currency)} />
          <Metric label={t("total")} value={formatCurrency(fiscalDetail.total, fiscalDetail.currency)} />
          {fiscalDetail.reason && <Metric label={t("fiscalReason")} value={fiscalDetail.reason} />}
          {fiscalDetail.legalBasis && <Metric label={t("legalBasis")} value={fiscalDetail.legalBasis} />}
          {fiscalDetail.evidenceReference && <Metric label={t("evidenceReference")} value={fiscalDetail.evidenceReference} />}
        </div>}
        {fiscalDetail?.fiscalStatus === "PENDING_TAX_DATA" && <p className="notice error" role="alert">{t("fiscalPendingPayment")}</p>}
        {fiscalDetail && canManage && <form className="compact-form-grid" onSubmit={saveFiscalDecision} aria-label={t("fiscalDecision")}>
          {!fiscalDetail.taxRegime && <label>{t("taxRegime")}<select className="control-input" value={fiscalForm.taxRegime} onChange={(event) => setFiscalForm({ ...fiscalForm, taxRegime: event.target.value as TaxRegime | "" })} required disabled={fiscalBusy}><option value="">{t("fiscalValuePending")}</option><option value="IVA">IVA</option><option value="IGIC">IGIC</option></select></label>}
          <label>{t("fiscalDecision")}<select className="control-input" value={fiscalForm.fiscalStatus} onChange={(event) => setFiscalForm({ ...fiscalForm, fiscalStatus: event.target.value as "CALCULATED" | "NOT_APPLICABLE" })}><option value="CALCULATED">{t("fiscalCalculated")}</option><option value="NOT_APPLICABLE">{t("fiscalNotApplicable")}</option></select></label>
          {fiscalForm.fiscalStatus === "CALCULATED" ? <>
            <Input label={t("taxBase")} type="number" min={0} step="0.01" value={fiscalForm.taxBase} onChange={(taxBase) => setFiscalForm({ ...fiscalForm, taxBase })} required />
            <Input label={t("taxRate")} type="number" min={0} step="0.01" value={fiscalForm.taxRate} onChange={(taxRate) => setFiscalForm({ ...fiscalForm, taxRate })} required />
            <Input label={t("taxAmount")} type="number" min={0} step="0.01" value={fiscalForm.taxAmount} onChange={(taxAmount) => setFiscalForm({ ...fiscalForm, taxAmount })} required />
          </> : <>
            <Input label={t("fiscalReason")} value={fiscalForm.reason} onChange={(reason) => setFiscalForm({ ...fiscalForm, reason })} minLength={10} maxLength={500} required />
            <Input label={t("legalBasis")} value={fiscalForm.legalBasis} onChange={(legalBasis) => setFiscalForm({ ...fiscalForm, legalBasis })} minLength={8} maxLength={500} required />
            <Input label={t("evidenceReference")} value={fiscalForm.evidenceReference} onChange={(evidenceReference) => setFiscalForm({ ...fiscalForm, evidenceReference })} minLength={8} maxLength={500} required />
          </>}
          <button className="primary-button" type="submit" disabled={fiscalBusy}>{t("saveFiscalDecision")}</button>
        </form>}

        <SectionHeader title={t("reconciliations")} subtitle={`${reconciliations.length} ${t("records")}`} />
        {reconciliationError && <RetryError message={reconciliationError} onRetry={() => selectedCompanyId && void loadBillingExtras(selectedCompanyId)} />}
        {canManage && <form className="compact-form-grid" onSubmit={createReconciliation}>
          <label>{t("provider")}<select className="control-input" value={reconciliationForm.provider} onChange={(event) => setReconciliationForm({ ...reconciliationForm, provider: event.target.value })}><option value="MANUAL_BANK">MANUAL_BANK</option><option value="MANUAL_GATEWAY">MANUAL_GATEWAY</option></select></label>
          <Input label={t("externalReference")} value={reconciliationForm.externalReference} onChange={(externalReference) => setReconciliationForm({ ...reconciliationForm, externalReference })} required />
          <Input label={t("amount")} value={reconciliationForm.amount} onChange={(amount) => setReconciliationForm({ ...reconciliationForm, amount })} required />
          <Input label={t("currency")} value={reconciliationForm.currency} onChange={(currency) => setReconciliationForm({ ...reconciliationForm, currency })} required />
          <Input label={t("bookedAt")} type="datetime-local" value={reconciliationForm.bookedAt} onChange={(bookedAt) => setReconciliationForm({ ...reconciliationForm, bookedAt })} required />
          <Input label={t("notes")} value={reconciliationForm.notes} onChange={(notes) => setReconciliationForm({ ...reconciliationForm, notes })} />
          <button className="primary-button" type="submit" disabled={reconciliationBusy}>{t("createReconciliation")}</button>
        </form>}
        {reconciliations.length === 0 ? <EmptyState text={t("noReconciliations")} /> : <div className="table-wrap"><table>
          <thead><tr><th>{t("provider")}</th><th>{t("externalReference")}</th><th>{t("amount")}</th><th>{t("bookedAt")}</th><th>{t("status")}</th></tr></thead>
          <tbody>{reconciliations.map((item) => <tr key={item.id}><td>{item.provider}</td><td>{item.externalReference}</td><td>{formatCurrency(item.amount, item.currency)}</td><td>{formatDate(item.bookedAt)}</td><td><StatusPill status={item.status} tone={item.status === "MATCHED" ? "ok" : "muted"} /></td></tr>)}</tbody>
        </table></div>}
      </section>
    </div>
  );
}
