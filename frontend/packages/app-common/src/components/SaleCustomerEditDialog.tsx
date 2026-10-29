import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { apiProblemCode, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import { buildPartyRequest, emptyPartyForm, partyFormFromView, validatePartyForm, type CustomerView, type PartyForm } from "./PartyDirectoryPanel";
import { PartyFormFields, type CommercialChannelOption } from "./PartyFormFields";
import { CustomerAddressBookHeading } from "./CustomerAddressBookHeading";
import { customerContactRequests, firstInvalidCustomerContact, formWithDefaultContact, uppercaseContactLocation } from "./customerContactAddresses";
import { customerAddressError, useCustomerAddressBookEditor } from "./useCustomerAddressBookEditor";
import { customerIdentityFailure } from "./customerDocumentIdentity";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { ErpConfirmDialog } from "./ErpConfirmDialog";
import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import "./PartyDirectoryDesktop.css";
import "./PartyCustomerEditor.css";
import "./ErpClassicWindow.css";

type Props = {
  locale: LocaleCode;
  session: UserSession;
  customerId: string;
  onCancel: () => void;
  onCreated: (customer: CustomerView) => void;
};

export function SaleCustomerEditDialog({ locale, session, customerId, onCancel, onCreated }: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const focusRequested = useRef(true);
  const [form, setForm] = useState<PartyForm>({ ...emptyPartyForm });
  const [initialForm, setInitialForm] = useState<PartyForm>({ ...emptyPartyForm });
  const [customer, setCustomer] = useState<CustomerView | null>(null);
  const [channels, setChannels] = useState<CommercialChannelOption[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [documentError, setDocumentError] = useState("");
  const [status, setStatus] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [confirmation, setConfirmation] = useState<"discard" | "reload" | null>(null);
  const addressBook = useCustomerAddressBookEditor(session, (loaded, resetForm) => {
    setCustomer(loaded);
    if (resetForm) {
      const next = partyFormFromView(loaded, false);
      setForm(next);
      setInitialForm(next);
      setErrors([]);
      setDocumentError("");
      setStatus("");
    }
  });
  const dirty = JSON.stringify(form) !== JSON.stringify(initialForm) || addressBook.dirty;
  const busy = loading || saving || addressBook.busy;

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    const controller = new AbortController();
    void apiRequest<CommercialChannelOption[]>("/commercial-contact-channels", {
      token: session.accessToken, signal: controller.signal,
    }).then(loaded => { if (!controller.signal.aborted) setChannels(loaded.filter(channel => channel.active)); })
      .catch(() => { if (!controller.signal.aborted) setChannels([]); });
    return () => controller.abort();
  }, [session.accessToken]);

  useEffect(() => {
    const controller = new AbortController();
    focusRequested.current = true;
    setLoading(true);
    setLoadFailed(false);
    setCustomer(null);
    setStatus("");
    addressBook.cancel();
    void apiRequest<CustomerView>(`/customers/${encodeURIComponent(customerId)}`, {
      token: session.accessToken, signal: controller.signal,
    }).then(loaded => {
      if (controller.signal.aborted) return;
      if (loaded.id !== customerId) throw new Error(t("party.loadError"));
      const next = partyFormFromView(loaded, false);
      setCustomer(loaded);
      setForm(next);
      setInitialForm(next);
      addressBook.openExisting(loaded, next);
    }).catch(failure => {
      if (controller.signal.aborted) return;
      setLoadFailed(true);
      setStatus(failure instanceof Error ? failure.message : t("party.loadError"));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => { controller.abort(); addressBook.cancel(); };
  }, [customerId, session.accessToken]);

  useEffect(() => {
    if (!focusRequested.current || !customer || loading || addressBook.busy) return;
    formRef.current?.querySelector<HTMLInputElement>('[data-party-field="name"]')?.focus();
    focusRequested.current = false;
  }, [customer?.id, loading, addressBook.busy]);

  function update<K extends keyof PartyForm>(field: K, value: PartyForm[K]) {
    setForm(current => ({ ...current, [field]: value }));
    const identityChanged = field === "documentType" || field === "documentNumber";
    if (identityChanged) setDocumentError("");
    setErrors(current => current.filter(candidate => candidate !== field && !(identityChanged && candidate === "documentNumber")));
  }

  function close() {
    if (saving) return;
    if (dirty) { setConfirmation("discard"); return; }
    onCancel();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== "Escape" || event.defaultPrevented || confirmation || saving) return;
    if ((event.target as HTMLElement).closest('.erp-select--open, .party-address-picker [aria-expanded="true"]')) return;
    event.preventDefault();
    event.stopPropagation();
    close();
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || loadFailed || !customer || !addressBook.source || confirmation) return;
    setDocumentError("");
    if (addressBook.dirty && (addressBook.status === "PENDING" || addressBook.status === "CONFLICT")) {
      setStatus(t(addressBook.status === "PENDING" ? "party.customerEditor.address.pending" : "party.customerEditor.address.conflict"));
      return;
    }
    // An unsettled address book must keep its exact payload until it is reconciled.
    const addressDrafts = addressBook.status === "PENDING" || addressBook.status === "CONFLICT"
      ? addressBook.drafts : addressBook.drafts.map(uppercaseContactLocation);
    const requestForm = formWithDefaultContact(form, addressDrafts);
    const invalidContact = firstInvalidCustomerContact(addressBook.drafts);
    if (invalidContact) {
      addressBook.select(addressBook.drafts[invalidContact.index].id);
      setErrors([invalidContact.field]);
      setStatus(t("party.customerEditor.address.invalid"));
      return;
    }
    const nextErrors = validatePartyForm(requestForm, false).filter(field => field !== "country" || !!requestForm.country.trim());
    if (nextErrors.length) { setErrors(nextErrors); setStatus(t("party.form.invalid")); return; }
    setSaving(true);
    setStatus("");
    setErrors([]);
    try {
      const saved = await apiRequest<CustomerView>(`/customers/${encodeURIComponent(customerId)}`, {
        method: "PUT", token: session.accessToken,
        body: {
          ...buildPartyRequest(requestForm, false, customer.isMember),
          addresses: customerContactRequests(addressDrafts),
          expectedVersion: addressBook.source.version ?? customer.version,
        },
      });
      onCreated(saved);
    } catch (failure) {
      if (apiProblemCode(failure)?.startsWith("CUSTOMER_ADDRESSES_")) setStatus(t(customerAddressError(failure)));
      else {
        const identityFailure = customerIdentityFailure(failure);
        if (identityFailure) {
          const message = t(identityFailure.messageKey);
          if (identityFailure.documentField) { setErrors(["documentNumber"]); setDocumentError(message); }
          setStatus(message);
        } else setStatus(failure instanceof Error ? failure.message : t("party.saveError"));
      }
    } finally { setSaving(false); }
  }

  return <>
    {confirmation === "discard" && <ErpConfirmDialog title={t("common.close")} message={t("party.confirm.discard")}
      confirmLabel={t("common.confirm")} cancelLabel={t("common.cancel")}
      onCancel={() => setConfirmation(null)} onConfirm={onCancel} />}
    {confirmation === "reload" && <ErpConfirmDialog title={t("party.customerEditor.address.replaceTitle")}
      message={t("party.customerEditor.address.replaceMessage")}
      confirmLabel={t("common.accept")} cancelLabel={t("common.cancel")}
      onCancel={() => setConfirmation(null)} onConfirm={() => {
        setConfirmation(null);
        void addressBook.reload(form, addressBook.status === "CONFLICT");
      }} />}
    <div className="filter-overlay erp-classic-overlay sale-customer-create-overlay" role="dialog" aria-modal="true" aria-labelledby="sale-customer-edit-title" onKeyDown={handleKeyDown}>
      <section ref={dialogRef} inert={confirmation !== null || undefined}
        className="filter-dialog party-create-dialog party-desktop-dialog party-desktop-dialog--customers erp-classic-window party-editor--venta party-customer-editor--venta">
        <header className="filter-header">
          <div><h2 id="sale-customer-edit-title">{t("party.customers.edit")}</h2>
            <span>{customer ? <>{customer.clientId} <span className={`party-desktop-status ${customer.active ? "is-active" : "is-inactive"}`}>{t(customer.active ? "party.active" : "party.inactive")}</span></> : t("party.form.subtitle")}</span></div>
          {customer && addressBook.source && <button type="button" className="party-customer-refresh" disabled={busy || loadFailed}
            onClick={() => { if (dirty || addressBook.status === "CONFLICT") setConfirmation("reload"); else void addressBook.reload(form); }}>
            {t("party.customerEditor.address.reload")}</button>}
          <WindowCloseButton aria-label={t("common.close")} disabled={saving} onClick={close}>{t("common.close")}</WindowCloseButton>
        </header>
        <form ref={formRef} className="product-create-form party-create-form" onSubmit={submit}>
          {customer && <section className="party-desktop-credit-overview" aria-label={t("party.gestion.section.account")}>
            <h3>{t("party.gestion.section.account")}</h3><dl>
              <div><dt>{t("party.gestion.column.debt")}</dt><dd className={Number(customer.outstandingDebt) > 0 ? "has-debt" : ""}>{customer.outstandingDebt == null ? "—" : Number(customer.outstandingDebt).toLocaleString(locale, { style: "currency", currency: "EUR" })}</dd></div>
              <div><dt>{t("party.gestion.column.creditEnabled")}</dt><dd>{customer.creditEnabled == null ? "—" : t(customer.creditEnabled ? "common.yes" : "common.no")}</dd></div>
              <div><dt>{t("party.gestion.column.creditLimit")}</dt><dd>{customer.creditLimit == null ? t("party.credit.unlimited") : Number(customer.creditLimit).toLocaleString(locale, { style: "currency", currency: "EUR" })}</dd></div>
              <div><dt>{t("party.gestion.column.creditBlocked")}</dt><dd>{customer.creditBlocked == null ? "—" : t(customer.creditBlocked ? "common.yes" : "common.no")}</dd></div>
            </dl>
          </section>}
          <fieldset disabled={busy || loadFailed}>
            <PartyFormFields form={form} errors={errors} documentError={documentError}
              customerEditor documentFirst uppercaseAddress grouped locale={locale} channels={channels} autoFocusName t={t} onChange={update}
              addressSuggestions={{ session, scopeKey: addressBook.selectedId, disabled: busy || loadFailed || confirmation !== null,
                onSelect: address => {
                  addressBook.applyAddress(uppercaseContactLocation(address));
                  setErrors(current => current.filter(field => !["address", "postalCode", "city", "province", "country"].includes(field)));
                } }}
              contactEditor={addressBook.selected ? {
                value: addressBook.selected,
                onChange: (field, value) => { addressBook.change(field, value); setErrors(current => current.filter(error => error !== field)); },
                heading: <CustomerAddressBookHeading addresses={addressBook.drafts} selectedId={addressBook.selectedId} disabled={busy || loadFailed}
                  onSelect={addressBook.select} onAdd={addressBook.add} t={t} />,
                footer: <div className="party-address-book-footer">
                  {addressBook.selectedId !== addressBook.drafts[0]?.id && <>
                    <button type="button" onClick={addressBook.makeDefault}>{t("party.customerEditor.address.makeDefault")}</button>
                    <button type="button" onClick={() => { addressBook.removeSelected(); setErrors(current => current.filter(field => !["phone", "email", "address", "postalCode", "city", "province", "country", "notes"].includes(field))); }}>{t("party.customerEditor.address.remove")}</button>
                  </>}
                  {addressBook.busy && <p role="status">{t("party.customerEditor.address.loading")}</p>}
                  {!addressBook.busy && addressBook.status && addressBook.status !== "SYNCED" && <p role="status">{t(`party.customerEditor.address.${addressBook.status.toLowerCase()}`)}</p>}
                  {addressBook.error && <p role="alert">{t(addressBook.error)}</p>}
                </div>,
              } : undefined} />
          </fieldset>
          {status && <p className="product-create-status" role="status">{status}</p>}
          <footer className="filter-actions erp-dialog-actions-row">
            <DialogDismissButton type="button" className="erp-dialog-action-cancel erp-dialog-dismiss" disabled={saving} onClick={close}>{t("common.cancel")}</DialogDismissButton>
            <button type="submit" className="erp-dialog-action-confirm" disabled={busy || loadFailed || !customer || !addressBook.source}>{saving ? t("party.saving") : t("common.save")}</button>
          </footer>
        </form>
      </section>
    </div>
  </>;
}
