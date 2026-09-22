import { useEffect, useRef, useState, type FormEvent } from "react";
import { request } from "../../lib/api";
import type { StoreRow } from "../../lib/workspace-api";
import type { CommercialProfile, CompanySummary, Credentials, TaxRegime } from "../../lib/types";
import type { Notice } from "../../shared/types";
import { useI18n } from "../../i18n";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { AddressFields, Input } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { emptyFiscalAddress, errorMessage, formatDate, toLocalInput } from "../../shared/lib";
import { useStoreLabels } from "./labels";

type DialogProps = {
  store: StoreRow | "new"; credentials: Credentials; companies: CompanySummary[]; defaultCompanyId: string;
  permissions: Set<string>; restoreFocus: () => HTMLElement | null;
  onClose: () => void; onSaved: () => void; onNotice: (notice: Notice) => void;
};
export function StoreDialog(props: DialogProps) {
  const { store, credentials, onClose, restoreFocus } = props;
  const { t } = useI18n();
  const s = useStoreLabels();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const storeId = store === "new" ? null : store.id;
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ id: string; token: string; attempt: number; data: StoreRow | null; error: string | null } | null>(null);
  // Fetch once per opening/retry: a background directory refresh must not
  // replace the editor or discard a draft or an in-flight save.
  useEffect(() => {
    if (storeId === null) return;
    let current = true;
    const identity = { id: storeId, token: credentials.accessToken, attempt };
    void request<StoreRow>(credentials, "/api/v1/admin/stores/" + encodeURIComponent(storeId))
      .then(data => { if (current) setResult({ ...identity, data, error: null }); })
      .catch(error => { if (current) setResult({ ...identity, data: null, error: errorMessage(error) }); });
    return () => { current = false; };
  }, [credentials.accessToken, storeId, attempt]);
  const current = result?.id === storeId && result?.token === credentials.accessToken && result?.attempt === attempt ? result : null;
  const profile = { data: current?.data ?? null, error: current?.error ?? null, loading: current === null, reload: () => setAttempt(value => value + 1) };
  const title = store === "new" ? s("newStore") : s("storeDetail") + ": " + (profile.data?.name ?? store.name);
  return <WorkspaceDialog className="saas-store-dialog" title={title} subtitle={store === "new" ? s("storeData") : profile.data?.companyName ?? store.companyName}
    closeLabel={t("close")} busy={busy} onClose={onClose} restoreFocus={restoreFocus} focusFirstInput={store === "new"}>
    {notice && <div className={"notice " + notice.type} role={notice.type === "error" ? "alert" : "status"}>{notice.text}</div>}
    {store !== "new" && <LoadState {...profile} />}
    {(store === "new" || profile.data) && <StoreEditor {...props} editing={store === "new" ? "new" : profile.data!}
      onBusy={setBusy} onDialogNotice={setNotice} />}
  </WorkspaceDialog>;
}
function StoreEditor({ editing, credentials, companies, defaultCompanyId, permissions, onClose, onSaved, onNotice, onBusy, onDialogNotice }: DialogProps & {
  editing: StoreRow | "new"; onBusy: (busy: boolean) => void; onDialogNotice: (notice: Notice | null) => void;
}) {
  const { t } = useI18n();
  const l = useWorkspaceLabels();
  const s = useStoreLabels();
  const canEdit = permissions.has(editing === "new" ? "ADD_COMPANY" : "EDIT_COMPANY_DATA");
  const [form, setForm] = useState(() => editing === "new" ? {
    companyId: defaultCompanyId || (companies.length === 1 ? companies[0].companyId : ""),
    code: "", name: "", storeAddress: emptyFiscalAddress(), timeZoneId: "Atlantic/Canary", active: true,
    taxRegime: "" as TaxRegime | "", commercialProfile: "" as CommercialProfile | "",
    servicePrice: "", billingPeriod: "" as "MONTHLY" | "ANNUAL" | "", validUntil: "", maxWindows: 1, maxPda: 0,
  } : {
    companyId: editing.companyId, code: editing.code, name: editing.name, storeAddress: editing.storeAddress ?? emptyFiscalAddress(),
    timeZoneId: editing.timeZoneId, active: editing.active, taxRegime: editing.taxRegime, commercialProfile: editing.commercialProfile ?? "",
    servicePrice: editing.servicePrice ?? "", billingPeriod: editing.billingPeriod ?? "",
    validUntil: editing.validUntil ? toLocalInput(new Date(editing.validUntil)) : "", maxWindows: editing.maxWindows, maxPda: editing.maxPda,
  });
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const mounted = useRef(false);
  const token = useRef(credentials.accessToken);
  token.current = credentials.accessToken;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const disabled = busy || !canEdit;
  const configurationLocked = editing !== "new" && editing.taxRegimeLocked && !permissions.has("RENEW_LICENSE");
  // Keep the current society available even if its directory refresh failed.
  const companyOptions = editing !== "new" && !companies.some(company => company.companyId === editing.companyId)
    ? [...companies, { companyId: editing.companyId, companyName: editing.companyName }] : companies;
  async function mutate(path: string, method: "POST" | "PUT", body: unknown) {
    if (!canEdit || inFlight.current) return;
    const requestToken = credentials.accessToken;
    inFlight.current = true; setBusy(true); onBusy(true); onDialogNotice(null);
    try {
      await request<StoreRow>(credentials, path, { method, body });
      if (!mounted.current || token.current !== requestToken) return;
      onNotice({ type: "success", text: l("saved") });
      onSaved();
    } catch (error) {
      if (mounted.current && token.current === requestToken) onDialogNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (mounted.current && token.current === requestToken) { inFlight.current = false; setBusy(false); onBusy(false); }
    }
  }
  function save(event: FormEvent) {
    event.preventDefault();
    if (disabled || inFlight.current) return;
    const parsedExpiry = new Date(form.validUntil);
    const keepMissingExpiry = editing !== "new" && editing.validUntil == null && !form.validUntil;
    if (!keepMissingExpiry && (!form.validUntil || Number.isNaN(parsedExpiry.getTime()))) {
      onDialogNotice({ type: "error", text: s("dateRequired") }); return;
    }
    const unchangedExpiry = editing !== "new" && editing.validUntil && form.validUntil === toLocalInput(new Date(editing.validUntil));
    const validUntil = keepMissingExpiry ? null : unchangedExpiry ? editing.validUntil : parsedExpiry.toISOString();
    const { companyId, code, active, ...details } = form;
    const payload = { ...details, servicePrice: form.servicePrice.trim(), validUntil };
    void mutate(editing === "new" ? "/api/v1/admin/companies/" + encodeURIComponent(companyId) + "/stores" : "/api/v1/admin/stores/" + editing.id,
      editing === "new" ? "POST" : "PUT", editing === "new" ? { code, ...payload } : { ...payload, active });
  }
  return <>
    {editing !== "new" && <dl className="store-detail-summary">
      <div><dt>{l("internalCode")}</dt><dd>{editing.internalCode ?? l("codePending")}</dd></div>
      <div><dt>{l("status")}</dt><dd>{editing.active ? l("active") : l("inactive")}</dd></div>
      <div><dt>{l("installations")}</dt><dd>{editing.activeInstallations}</dd></div>
      <div><dt>{l("sync")}</dt><dd>{editing.lastSyncAt ? formatDate(editing.lastSyncAt) : "—"}</dd></div>
    </dl>}
    <form onSubmit={save} className="store-form" aria-label={editing === "new" ? l("create") : l("edit")}>
      <fieldset className="compact-form-grid store-fields" disabled={disabled}><legend>{s("storeData")}</legend>
          <label>
            {l("company")}
            <select
              aria-label={l("company")}
              required
              value={form.companyId}
              disabled={editing !== "new" || disabled}
              onChange={(e) => setForm({ ...form, companyId: e.target.value })}
            >
              <option value="">{t("chooseCompany")}</option>
              {companyOptions.map((c) => (
                <option key={c.companyId} value={c.companyId}>
                  {c.companyName}
                </option>
              ))}
            </select>
          </label>
          <Input
            label={l("localCode")}
            value={form.code}
            onChange={(code) => setForm({ ...form, code })}
            required
            minLength={3}
            maxLength={3}
            disabled={editing !== "new" || disabled}
          />
          <Input
            label={l("name")}
            value={form.name}
            onChange={(name) => setForm({ ...form, name })}
            required
            disabled={disabled}
          />
          <label>{t("taxes")}
            <select className="control-input" aria-label={t("taxes")} value={form.taxRegime} required
              disabled={disabled || (editing !== "new" && editing.taxRegimeLocked)}
              onChange={event => setForm({ ...form, taxRegime: event.target.value as TaxRegime })}>
              <option value="">{s("chooseTax")}</option><option value="IVA">IVA</option><option value="IGIC">IGIC</option>
            </select>
            {editing !== "new" && editing.taxRegimeLocked && <small>{s("taxLocked")}</small>}
          </label>
          <label>{t("commercialProfile")}
            <select className="control-input" aria-label={t("commercialProfile")} value={form.commercialProfile} required disabled={disabled}
              onChange={event => setForm({ ...form, commercialProfile: event.target.value as CommercialProfile })}>
              <option value="">{s("chooseProfile")}</option>
              <option value="MAYORISTA">{s("wholesale")}</option>
              <option value="MINORISTA">{s("retail")}</option>
            </select>
          </label>
          <Input label={s("servicePrice")} type="number" min={0} step="0.01" value={form.servicePrice}
            onChange={servicePrice => setForm({ ...form, servicePrice })} required disabled={disabled} />
          <label>{s("billingPeriod")}<select className="control-input" aria-label={s("billingPeriod")} value={form.billingPeriod} required disabled={disabled}
            onChange={event => setForm({ ...form, billingPeriod: event.target.value as "MONTHLY" | "ANNUAL" })}>
            <option value="">{s("choosePeriod")}</option><option value="MONTHLY">{s("monthly")}</option><option value="ANNUAL">{s("annual")}</option>
          </select></label>
          <Input label={t("validUntil")} type="datetime-local" value={form.validUntil} onChange={validUntil => setForm({ ...form, validUntil })} required={editing === "new" || !!editing?.validUntil} disabled={disabled || configurationLocked} />
          <Input label="Windows" type="number" min={1} step="1" value={String(form.maxWindows)} onChange={value => setForm({ ...form, maxWindows: Number(value) })} required disabled={disabled || configurationLocked} />
          <Input label="PDA" type="number" min={0} step="1" value={String(form.maxPda)} onChange={value => setForm({ ...form, maxPda: Number(value) })} required disabled={disabled || configurationLocked} />
          {configurationLocked && <p className="store-form-wide">{s("renewalPermission")}</p>}
          <Input
            label={t("storeTimeZone")}
            value={form.timeZoneId}
            onChange={(timeZoneId) => setForm({ ...form, timeZoneId })}
            required
            disabled={disabled}
          />
          <AddressFields
            title={t("storeAddress")}
            value={form.storeAddress}
            onChange={(storeAddress) => setForm({ ...form, storeAddress })}
            disabled={disabled}
          />

      </fieldset>
      <div className="form-actions">
        {canEdit && <button className="primary-button" disabled={busy} type="submit">{l("save")}</button>}
        <button className="secondary-button" disabled={busy} type="button" onClick={onClose}>{l("cancel")}</button>
        {canEdit && editing !== "new" && <button className="secondary-button store-activity-action" disabled={busy} type="button"
          onClick={() => void mutate("/api/v1/admin/stores/" + editing.id + "/activity", "PUT", { active: !editing.active })}>
          {editing.active ? t("deactivate") : t("activate")}
        </button>}
      </div>
    </form>
  </>;
}
