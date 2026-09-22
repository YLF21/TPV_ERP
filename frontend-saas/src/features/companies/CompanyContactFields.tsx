import { useEffect, useId, useRef, useState } from "react";
import type { CompanyContactData, CompanyOwner } from "../../lib/types";
import { useI18n } from "../../i18n";
import { Input } from "../../shared/ui";
import { useCompanyLabels } from "./labels";

export const emptyOwner = (): CompanyOwner => ({ name: "", taxId: "", phone: "", email: "" });
export function emptyCompanyContact(): CompanyContactData {
  return { contactName: "", contactPhone: "", contactEmail: "", supportStatus: "NORMAL", notes: "", owners: [emptyOwner()] };
}

export function CompanyContactFields({ value, onChange, disabled, readOnly = false }: {
  value: CompanyContactData; onChange: (value: CompanyContactData) => void; disabled: boolean; readOnly?: boolean;
}) {
  const { t } = useI18n(); const l = useCompanyLabels();
  const [contactOwnerIndex, setContactOwnerIndex] = useState<number | null>(null);
  const contactFields = useRef<HTMLFieldSetElement>(null);
  const hintId = useId();
  const contactOwner = contactOwnerIndex === null ? null : value.owners[contactOwnerIndex] ?? null;
  const missingPhone = Boolean(contactOwner && !value.contactPhone?.trim());
  const missingEmail = Boolean(contactOwner && !value.contactEmail?.trim());
  useEffect(() => {
    contactFields.current?.querySelector<HTMLInputElement>('input[type="tel"]')?.setCustomValidity(missingPhone ? l("ownerPhoneRequired") : "");
    contactFields.current?.querySelector<HTMLInputElement>('input[type="email"]')?.setCustomValidity(missingEmail ? l("ownerEmailRequired") : "");
  }, [missingPhone, missingEmail, l]);
  function ownerContact(owner: CompanyOwner) {
    return { contactName: owner.name, contactPhone: owner.phone ?? "", contactEmail: owner.email ?? "" };
  }
  function changeOwner(index: number, patch: Partial<CompanyOwner>) {
    const owner = { ...value.owners[index], ...patch };
    onChange({ ...value, owners: value.owners.map((current, position) => position === index ? owner : current),
      ...(index === contactOwnerIndex ? ownerContact(owner) : {}) });
  }
  function changeContact(patch: Partial<CompanyOwner>) {
    if (contactOwnerIndex !== null && contactOwner) changeOwner(contactOwnerIndex, patch);
    else onChange({ ...value, ...ownerContact({ name: value.contactName ?? "", taxId: "", phone: value.contactPhone ?? "", email: value.contactEmail ?? "", ...patch }) });
  }
  function selectOwner(selection: string) {
    if (disabled) return;
    const index = selection === "" ? null : Number(selection);
    const owner = index === null ? null : value.owners[index];
    setContactOwnerIndex(owner ? index : null);
    if (!owner) return;
    onChange({ ...value, ...ownerContact(owner) });
    const missing = !owner.phone?.trim() ? "tel" : !owner.email?.trim() ? "email" : null;
    if (missing) requestAnimationFrame(() => contactFields.current?.querySelector<HTMLInputElement>(`input[type="${missing}"]`)?.focus());
  }
  function removeOwner(index: number) {
    if (contactOwnerIndex !== null) setContactOwnerIndex(current => current === index ? null : current !== null && current > index ? current - 1 : current);
    onChange({ ...value, owners: value.owners.filter((_, position) => position !== index) });
  }
  return <>
    <fieldset className="company-fieldset company-owners"><legend>{l("owners")}</legend>
      <p className="company-field-hint">{l("ownersRequired")}</p>
      {value.owners.map((owner, index) => <fieldset className="company-owner" key={index}><legend>{l("owner")} {index + 1}</legend>
        <div className="company-owner-grid">
          <Input label={l("ownerName")} value={owner.name} onChange={name => changeOwner(index, { name })} required maxLength={160} disabled={disabled} />
          <Input label={l("ownerTaxId")} value={owner.taxId ?? ""} onChange={taxId => changeOwner(index, { taxId })} required maxLength={32} disabled={disabled} />
          <Input label={l("phone")} type="tel" value={owner.phone ?? ""} onChange={phone => changeOwner(index, { phone })} maxLength={40} disabled={disabled} />
          <Input label={t("contactEmail")} type="email" value={owner.email ?? ""} onChange={email => changeOwner(index, { email })} maxLength={160} disabled={disabled} />
          {!readOnly && <button className="small-button" type="button" aria-label={`${l("removeOwner")} ${index + 1}`} disabled={disabled || value.owners.length <= 1}
            onClick={() => removeOwner(index)}>{l("removeOwner")}</button>}
        </div>
      </fieldset>)}
      {!readOnly && <button className="secondary-button" type="button" disabled={disabled} onClick={() => onChange({ ...value, owners: [...value.owners, emptyOwner()] })}>{l("addOwner")}</button>}
    </fieldset>
    <fieldset className="company-fieldset company-contact" ref={contactFields} aria-describedby={contactOwner ? hintId : undefined}><legend>{l("contact")}</legend>
      {!readOnly && <label className="company-contact-source">{l("contactOwner")}
        <select className="control-input" aria-label={l("contactOwner")} value={contactOwner ? String(contactOwnerIndex) : ""} disabled={disabled} onChange={event => selectOwner(event.target.value)}>
          <option value="">{l("independentContact")}</option>
          {value.owners.map((owner, index) => <option key={index} value={String(index)}>{l("owner")} {index + 1}{owner.name.trim() ? ` — ${owner.name}` : ""}</option>)}
        </select>
      </label>}
      {contactOwner && <p className="company-field-hint" id={hintId}>{l("selectedOwnerHint")}</p>}
      <div className="company-contact-grid">
        <Input label={l("contactName")} value={value.contactName ?? ""} onChange={name => changeContact({ name })} maxLength={160} disabled={disabled} />
        <Input label={l("phone")} type="tel" value={value.contactPhone ?? ""} onChange={phone => changeContact({ phone })} required={Boolean(contactOwner)} maxLength={40} disabled={disabled} />
        <Input label={t("contactEmail")} type="email" value={value.contactEmail ?? ""} onChange={email => changeContact({ email })} required={Boolean(contactOwner)} maxLength={160} disabled={disabled} />
      </div>
      {(missingPhone || missingEmail) && <p className="company-contact-missing" role="status">{[missingPhone && l("ownerPhoneRequired"), missingEmail && l("ownerEmailRequired")].filter(Boolean).join(" ")}</p>}
    </fieldset>
    <label className="company-notes">{t("notes")}<textarea className="control-input text-area" value={value.notes ?? ""} maxLength={4000} disabled={disabled} onChange={event => onChange({ ...value, notes: event.target.value })} /></label>
  </>;
}
