import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, Permission, UserSession } from "../types";
import {
  buildPartyRequest,
  emptyPartyForm,
  partyFormFromView,
  validatePartyForm,
  type CustomerView,
  type PartyForm,
} from "./PartyDirectoryPanel";
import { PartyFormFields, type CommercialChannelOption } from "./PartyFormFields";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

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

export function SaleCustomerCreateDialog({ locale, session, customerId, onCancel, onCreated }: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const [form, setForm] = useState<PartyForm>({ ...emptyPartyForm });
  const [errors, setErrors] = useState<string[]>([]);
  const [channels, setChannels] = useState<CommercialChannelOption[]>([]);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(Boolean(customerId));
  const [loadFailed, setLoadFailed] = useState(false);
  const [preserveMember, setPreserveMember] = useState(false);
  const [status, setStatus] = useState("");

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    apiRequest<CommercialChannelOption[]>("/commercial-contact-channels", {
      token: session.accessToken,
    })
      .then((loaded) => setChannels(loaded.filter((channel) => channel.active)))
      .catch(() => setChannels([]));
  }, [session.accessToken]);

  useEffect(() => {
    if (!customerId) return;
    let current = true;
    setLoading(true);
    setLoadFailed(false);
    apiRequest<CustomerView>(`/customers/${customerId}`, { token: session.accessToken })
      .then((customer) => {
        if (!current) return;
        setForm(partyFormFromView(customer, false));
        setPreserveMember(customer.isMember);
      })
      .catch((failure) => {
        if (!current) return;
        setLoadFailed(true);
        setStatus(failure instanceof Error ? failure.message : t("party.loadError"));
      })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [customerId, session.accessToken]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || saving) return;
      event.preventDefault();
      onCancel();
    };
    globalThis.addEventListener("keydown", handler);
    return () => globalThis.removeEventListener("keydown", handler);
  }, [onCancel, saving]);

  function update<K extends keyof PartyForm>(field: K, value: PartyForm[K]) {
    setForm((current) => ({ ...current, [field]: value }));
    setErrors((current) => current.filter((candidate) => candidate !== field));
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const nextErrors = validatePartyForm(form, false);
    if (nextErrors.length > 0) {
      setErrors(nextErrors);
      setStatus(t("party.form.invalid"));
      return;
    }
    setSaving(true);
    setStatus("");
    try {
      const created = await apiRequest<CustomerView>(customerId ? `/customers/${customerId}` : "/customers", {
        method: customerId ? "PUT" : "POST",
        token: session.accessToken,
        body: buildPartyRequest(form, false, preserveMember),
      });
      onCreated(created);
    } catch (failure) {
      setStatus(failure instanceof Error ? failure.message : t("party.saveError"));
    } finally {
      setSaving(false);
    }
  }

  return <div className="filter-overlay sale-customer-create-overlay" role="dialog" aria-modal="true" aria-labelledby="sale-customer-create-title">
    <section ref={dialogRef} className="filter-dialog product-create-dialog party-create-dialog sale-customer-create-dialog">
      <header className="filter-header">
        <div>
          <h2 id="sale-customer-create-title">{customerId ? t("party.customers.edit") : t("party.customers.new")}</h2>
          <span>{t("party.form.subtitle")}</span>
        </div>
        <button type="button" aria-label={t("common.close")} disabled={saving} onClick={onCancel}>{t("common.close")}</button>
      </header>
      <form className="product-create-form party-create-form" onSubmit={submit}>
        <fieldset disabled={saving || loading || loadFailed}>
          <PartyFormFields
            form={form}
            errors={errors}
            channels={channels}
            autoFocusName
            t={t}
            onChange={update}
          />
        </fieldset>
        {status && <p className="product-create-status" role="alert">{status}</p>}
        <footer className="filter-actions">
          <button type="button" disabled={saving} onClick={onCancel}>{t("common.cancel")}</button>
          <button type="submit" disabled={saving || loading || loadFailed}>{saving ? t("party.saving") : t("common.save")}</button>
        </footer>
      </form>
    </section>
  </div>;
}
