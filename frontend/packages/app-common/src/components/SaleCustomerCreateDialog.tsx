import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { apiProblemCode, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, Permission, UserSession } from "../types";
import {
  buildPartyRequest,
  emptyPartyForm,
  validatePartyForm,
  type CustomerView,
  type PartyForm,
} from "./PartyDirectoryPanel";
import { PartyFormFields, type CommercialChannelOption } from "./PartyFormFields";
import { CustomerAddressBookHeading } from "./CustomerAddressBookHeading";
import { customerContactRequests, firstInvalidCustomerContact, formWithDefaultContact, uppercaseContactLocation } from "./customerContactAddresses";
import { customerAddressError, useCustomerAddressBookEditor } from "./useCustomerAddressBookEditor";
import { CustomerDocumentValidation, ExistingCustomerNotice, type ExistingCentralCustomer } from "./CustomerDocumentValidation";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { customerIdentityFailure } from "./customerDocumentIdentity";
import { ErpConfirmDialog } from "./ErpConfirmDialog";
import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import { SaleCustomerEditDialog } from "./SaleCustomerEditDialog";
import "./PartyDirectoryDesktop.css";
import "./PartyCustomerEditor.css";
import "./ErpClassicWindow.css";

type Props = {
  locale: LocaleCode;
  session: UserSession;
  customerId?: string;
  onCancel: () => void;
  onCreated: (customer: CustomerView) => void;
};

export function canCreateSaleCustomer(permissions: Permission[]): boolean {
  return permissions.some((permission) => [
    "ADMIN",
    "VENTA",
    "CUSTOMERS_WRITE",
    "GESTION_CLIENTE_PROVEEDOR",
  ].includes(permission));
}

export function SaleCustomerCreateDialog(props: Props) {
  if (props.customerId) return <SaleCustomerEditDialog {...props} customerId={props.customerId} />;
  return <SaleCustomerNewDialog {...props} />;
}

const newCustomerForm: PartyForm = { ...emptyPartyForm, creditEnabled: false, createMember: false };

function SaleCustomerNewDialog({ locale, session, onCancel, onCreated }: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const [form, setForm] = useState<PartyForm>({ ...newCustomerForm });
  const [errors, setErrors] = useState<string[]>([]);
  const [documentError, setDocumentError] = useState("");
  const [channels, setChannels] = useState<CommercialChannelOption[]>([]);
  const [saving, setSaving] = useState(false);
  const [centralBusy, setCentralBusy] = useState(false);
  const [confirmation, setConfirmation] = useState(false);
  const [existingCentralCustomer, setExistingCentralCustomer] = useState<ExistingCentralCustomer | null>(null);
  const [status, setStatus] = useState("");
  const addressBook = useCustomerAddressBookEditor(session, () => {});
  const dirty = JSON.stringify(form) !== JSON.stringify(newCustomerForm) || addressBook.dirty;
  const busy = saving || centralBusy || addressBook.busy;

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    addressBook.openNew(newCustomerForm);
    formRef.current?.querySelector<HTMLInputElement>('[data-party-field="documentNumber"]')?.focus();
    return () => addressBook.cancel();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    apiRequest<CommercialChannelOption[]>("/commercial-contact-channels", {
      token: session.accessToken, signal: controller.signal,
    })
      .then(loaded => { if (!controller.signal.aborted) setChannels(loaded.filter(channel => channel.active)); })
      .catch(() => { if (!controller.signal.aborted) setChannels([]); });
    return () => controller.abort();
  }, [session.accessToken]);

  function update<K extends keyof PartyForm>(field: K, value: PartyForm[K]) {
    setForm((current) => ({ ...current, [field]: value }));
    const identityChanged = field === "documentType" || field === "documentNumber";
    if (identityChanged) setDocumentError("");
    setErrors((current) => current.filter((candidate) => candidate !== field && !(identityChanged && candidate === "documentNumber")));
  }

  function close() {
    if (busy || existingCentralCustomer) return;
    if (dirty) { setConfirmation(true); return; }
    onCancel();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== "Escape" || event.defaultPrevented || confirmation || existingCentralCustomer || busy) return;
    if ((event.target as HTMLElement).closest('.erp-select--open, .party-address-picker [aria-expanded="true"]')) return;
    event.preventDefault();
    event.stopPropagation();
    close();
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || confirmation || existingCentralCustomer || !addressBook.drafts.length) return;
    setDocumentError("");
    const addressDrafts = addressBook.drafts.map(uppercaseContactLocation);
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
    setErrors([]);
    setStatus("");
    try {
      const created = await apiRequest<CustomerView>("/customers", {
        method: "POST", token: session.accessToken,
        body: { ...buildPartyRequest(requestForm, false, requestForm.createMember), addresses: customerContactRequests(addressDrafts) },
      });
      onCreated(created);
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
    {confirmation && <ErpConfirmDialog title={t("common.close")} message={t("party.confirm.discard")}
      confirmLabel={t("common.confirm")} cancelLabel={t("common.cancel")}
      onCancel={() => setConfirmation(false)} onConfirm={onCancel} />}
    {existingCentralCustomer && <ExistingCustomerNotice customer={existingCentralCustomer} locale={locale} onAccept={onCancel} />}
    <div className="filter-overlay erp-classic-overlay sale-customer-create-overlay" role="dialog" aria-modal="true" aria-labelledby="sale-customer-create-title" onKeyDown={handleKeyDown}>
      <section ref={dialogRef} inert={confirmation || existingCentralCustomer !== null || undefined}
        className="filter-dialog party-create-dialog party-desktop-dialog party-desktop-dialog--customers erp-classic-window party-editor--venta party-customer-editor--venta">
        <header className="filter-header">
          <div><h2 id="sale-customer-create-title">{t("party.customers.new")}</h2><span>{t("party.form.subtitle")}</span></div>
          <WindowCloseButton aria-label={t("common.close")} disabled={busy} onClick={close}>{t("common.close")}</WindowCloseButton>
        </header>
        <form ref={formRef} className="product-create-form party-create-form" onSubmit={submit}>
          <fieldset disabled={busy}>
            <PartyFormFields form={form} errors={errors} documentError={documentError}
              customerEditor documentFirst uppercaseAddress grouped locale={locale} channels={channels}
              autoFocusDocument t={t} onChange={update}
              documentAction={<CustomerDocumentValidation documentType={form.documentType} documentNumber={form.documentNumber}
                session={session} locale={locale} disabled={busy} onBusyChange={setCentralBusy}
                onAvailable={() => setStatus("")} onExisting={setExistingCentralCustomer} />}
              identityAction={<label className="party-commercial-consent">
                <input type="checkbox" checked={form.createMember} onChange={event => update("createMember", event.target.checked)} />
                <span>{t("party.customerEditor.createMember")}</span>
              </label>}
              addressSuggestions={{ session, scopeKey: addressBook.selectedId, disabled: busy || confirmation || existingCentralCustomer !== null,
                onSelect: address => {
                  addressBook.applyAddress(uppercaseContactLocation(address));
                  setErrors(current => current.filter(field => !["address", "postalCode", "city", "province", "country"].includes(field)));
                } }}
              contactEditor={addressBook.selected ? {
                value: addressBook.selected,
                onChange: (field, value) => { addressBook.change(field, value); setErrors(current => current.filter(error => error !== field)); },
                heading: <CustomerAddressBookHeading addresses={addressBook.drafts} selectedId={addressBook.selectedId} disabled={busy}
                  onSelect={addressBook.select} onAdd={addressBook.add} t={t} />,
                footer: <div className="party-address-book-footer">
                  {addressBook.selectedId !== addressBook.drafts[0]?.id && <>
                    <button type="button" onClick={addressBook.makeDefault}>{t("party.customerEditor.address.makeDefault")}</button>
                    <button type="button" onClick={() => {
                      addressBook.removeSelected();
                      setErrors(current => current.filter(field => !["phone", "email", "address", "postalCode", "city", "province", "country", "notes"].includes(field)));
                    }}>{t("party.customerEditor.address.remove")}</button>
                  </>}
                  {addressBook.error && <p role="alert">{t(addressBook.error)}</p>}
                </div>,
              } : undefined} />
          </fieldset>
          {status && <p className="product-create-status" role="status">{status}</p>}
          <footer className="filter-actions erp-dialog-actions-row">
            <DialogDismissButton type="button" className="erp-dialog-action-cancel erp-dialog-dismiss" disabled={busy} onClick={close}>{t("common.cancel")}</DialogDismissButton>
            <button type="submit" className="erp-dialog-action-confirm" disabled={busy || !addressBook.drafts.length}>{saving ? t("party.saving") : t("common.save")}</button>
          </footer>
        </form>
      </section>
    </div>
  </>;
}
