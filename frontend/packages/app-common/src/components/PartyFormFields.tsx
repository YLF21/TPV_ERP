import { Fragment, type ReactNode } from "react";
import { ErpSelect } from "./ErpSelect";
import { PartyAddressPicker } from "./PartyAddressPicker";
import type { PartyForm } from "./PartyDirectoryPanel";
import type { LocaleCode, UserSession } from "../types";
import { customerDocumentType } from "./customerDocumentIdentity";
import { PartyAddressAutocomplete, type SuggestedPartyAddress } from "./PartyAddressAutocomplete";

export type CommercialChannelOption = {
  id: string;
  code: string;
  name: string;
  active: boolean;
};

export type PartyContactField = "phone" | "email" | "address" | "postalCode" | "city" | "province" | "country" | "notes";
export type PartyContactForm = Pick<PartyForm, PartyContactField>;
export type PartyContactEditor = {
  value: PartyContactForm;
  onChange: (field: PartyContactField, value: string) => void;
  heading: ReactNode;
  footer?: ReactNode;
};

type Props = {
  form: PartyForm;
  errors: string[];
  documentError?: string;
  identityAction?: ReactNode;
  documentAction?: ReactNode;
  customerEditor?: boolean;
  documentFirst?: boolean;
  uppercaseAddress?: boolean;
  contactEditor?: PartyContactEditor;
  addressSuggestions?: {
    session: UserSession;
    scopeKey: string;
    disabled: boolean;
    onSelect: (address: SuggestedPartyAddress) => void;
  };
  channels: CommercialChannelOption[];
  supplier?: boolean;
  grouped?: boolean;
  locale?: LocaleCode;
  autoFocusName?: boolean;
  autoFocusDocument?: boolean;
  t: (key: string) => string;
  onChange: <K extends keyof PartyForm>(field: K, value: PartyForm[K]) => void;
};

export function PartyFormFields({
  form,
  errors,
  documentError,
  identityAction,
  documentAction,
  customerEditor = false,
  documentFirst = customerEditor,
  uppercaseAddress = false,
  contactEditor,
  addressSuggestions,
  channels,
  supplier = false,
  grouped = false,
  locale = "es",
  autoFocusName = false,
  autoFocusDocument = false,
  t,
  onChange,
}: Props) {
  function error(field: keyof PartyForm): ReactNode {
    return errors.includes(field)
      ? <small className="party-field-error" role="alert">{field === "documentNumber" && documentError ? documentError : t("party.field.invalid")}</small>
      : null;
  }

  function invalidClass(field: keyof PartyForm): string | undefined {
    return errors.includes(field) ? "party-field-invalid" : undefined;
  }

  const contact = contactEditor?.value ?? form;
  const locationValue = (value: string) => uppercaseAddress ? value.toUpperCase() : value;
  const changeContact = (field: PartyContactField, value: string) => {
    const next = ["city", "province", "country"].includes(field) ? locationValue(value) : value;
    if (contactEditor) contactEditor.onChange(field, next);
    else onChange(field, next);
  };
  const nameRow = <div className="product-create-row product-create-row-two">
      <label className={invalidClass("name")}>
        <span>{t(supplier ? "party.field.legalName" : "party.field.fiscalName")}</span>
        <input
          required
          autoFocus={autoFocusName && !autoFocusDocument}
          data-party-field="name"
          aria-invalid={errors.includes("name")}
          value={form.name}
          onChange={(event) => onChange("name", event.target.value)}
        />
        {error("name")}
      </label>
      {supplier ? <label>
        <span>{t("party.field.tradeName")}</span>
        <input value={form.tradeName} onChange={(event) => onChange("tradeName", event.target.value)} />
      </label> : <label className={invalidClass("discount")}>
        <span>{t("party.field.discount")}</span>
        <input
          type="number"
          min="0"
          max="100"
          step="0.01"
          aria-invalid={errors.includes("discount")}
          value={form.discount}
          onChange={(event) => onChange("discount", event.target.value)}
        />
        {error("discount")}
      </label>}
    </div>;
  const documentNumberField = <label className={invalidClass("documentNumber")}>
    <span>{t("party.field.documentNumber")}</span>
    <input required autoFocus={autoFocusDocument} data-party-field="documentNumber" aria-invalid={errors.includes("documentNumber")}
      value={form.documentNumber} onChange={(event) => onChange("documentNumber", event.target.value)} />
    {error("documentNumber")}
  </label>;
  const documentRow = <div className={`product-create-row product-create-row-two${documentFirst ? " party-document-row" : ""}`}>
      <label>
        <span>{t("party.field.documentType")}</span>
        <ErpSelect
          aria-label={t("party.field.documentType")}
          value={supplier ? form.documentType : customerDocumentType(form.documentType)}
          onChange={(value) => onChange("documentType", value)}
          options={(supplier ? ["NIF", "CIF", "NIE", "PASAPORTE", "OTRO"] : ["NIE", "DNI", "NIF", "PASAPORTE"])
            .map((value) => ({ value, label: !supplier && value === "PASAPORTE" ? t("party.documentType.passportOther") : value }))}
        />
      </label>
      {customerEditor ? <div className="party-document-number-with-action">{documentNumberField}{documentAction}</div> : documentNumberField}
    </div>;
  const communicationsSection = <PartyFormSection enabled={grouped} sectionKey="communications" title={t("party.gestion.section.communications")}>
    <label className="party-commercial-consent">
      <input type="checkbox" checked={form.commercialConsent} onChange={(event) => onChange("commercialConsent", event.target.checked)} />
      <span>{t("party.field.commercialConsent")}</span>
    </label>
    {form.commercialConsent && <label className={invalidClass("preferredCommercialChannelId")}>
      <span>{t("party.field.preferredCommercialChannel")}</span>
      <ErpSelect
        value={form.preferredCommercialChannelId}
        onChange={(value) => onChange("preferredCommercialChannelId", value)}
        options={[{ value: "", label: t("party.channel.select") }, ...channels.map((channel) => ({ value: channel.id, label: channel.name }))]}
      />
      {error("preferredCommercialChannelId")}
    </label>}
  </PartyFormSection>;
  const Container = grouped ? "div" : Fragment;
  return <Container {...(grouped ? { className: `party-form-sections${supplier ? " party-form-sections--supplier" : ""}` } : {})}>
    <PartyFormSection enabled={grouped} sectionKey="identity" title={t("party.gestion.section.identity")}>
    {documentFirst ? <>{documentRow}{nameRow}</> : <>{nameRow}{documentRow}</>}
    {identityAction}
    </PartyFormSection>
    <PartyFormSection enabled={grouped} sectionKey="contact" title={contactEditor?.heading ?? t("party.gestion.section.contact")}>
    <div className="product-create-row product-create-row-two">
      <label className={invalidClass("phone")}><span>{t("party.field.phone")}</span><input maxLength={64} aria-invalid={errors.includes("phone")} value={contact.phone} onChange={(event) => changeContact("phone", event.target.value)} />{error("phone")}</label>
      <label className={invalidClass("email")}><span>{t("party.field.email")}</span><input type="email" maxLength={320} aria-invalid={errors.includes("email")} value={contact.email} onChange={(event) => changeContact("email", event.target.value)} />{error("email")}</label>
    </div>
    <label className={invalidClass("address")}><span>{t("party.field.address")}</span>{addressSuggestions ? <PartyAddressAutocomplete
      key={addressSuggestions.scopeKey}
      value={contact.address}
      onChange={value => changeContact("address", value)}
      onSelect={addressSuggestions.onSelect}
      context={{ postalCode: contact.postalCode, city: contact.city, country: contact.country }}
      scopeKey={addressSuggestions.scopeKey}
      session={addressSuggestions.session}
      disabled={addressSuggestions.disabled}
      invalid={errors.includes("address")}
      label={t("party.field.address")}
      t={t}
    /> : <input maxLength={255} aria-invalid={errors.includes("address")} value={contact.address} onChange={(event) => changeContact("address", event.target.value)} />}{error("address")}</label>
    {grouped ? <>
      <div className="product-create-row product-create-row-two party-address-postal-row">
        <label className={invalidClass("postalCode")}><span>{t("party.field.postalCode")}</span><input maxLength={16} aria-invalid={errors.includes("postalCode")} value={contact.postalCode} onChange={(event) => changeContact("postalCode", event.target.value)} />{error("postalCode")}</label>
        <label className={invalidClass("city")}><span>{t("party.field.city")}</span><input maxLength={128} aria-invalid={errors.includes("city")} value={locationValue(contact.city)} onChange={(event) => changeContact("city", event.target.value)} />{error("city")}</label>
      </div>
      <div className="product-create-row product-create-row-two party-address-location-row">
        <label><span>{t("party.field.province")}</span><PartyAddressPicker kind="province" country={contact.country} value={contact.province} label={t("party.field.province")} locale={locale} uppercase={uppercaseAddress} onChange={(value) => changeContact("province", value)} /></label>
        <label className={invalidClass("country")}><span>{t("party.field.country")}</span><PartyAddressPicker kind="country" value={contact.country} label={t("party.field.country")} locale={locale} uppercase={uppercaseAddress} invalid={errors.includes("country")} onChange={(value) => changeContact("country", value)} />{error("country")}</label>
      </div>
      <label className={invalidClass("notes")}><span>{t("party.field.notes")}</span><textarea rows={2} maxLength={65536} value={contact.notes} onChange={(event) => changeContact("notes", event.target.value)} />{error("notes")}</label>
    </> : <>
      <div className="product-create-row product-create-row-three">
        <label><span>{t("party.field.postalCode")}</span><input value={contact.postalCode} onChange={(event) => changeContact("postalCode", event.target.value)} /></label>
        <label><span>{t("party.field.city")}</span><input value={contact.city} onChange={(event) => changeContact("city", event.target.value)} /></label>
        <label><span>{t("party.field.province")}</span><input value={contact.province} onChange={(event) => changeContact("province", event.target.value)} /></label>
      </div>
      <div className="product-create-row product-create-row-two">
        <label className={invalidClass("country")}>
          <span>{t("party.field.country")}</span>
          <input required maxLength={2} aria-invalid={errors.includes("country")} value={contact.country} onChange={(event) => changeContact("country", event.target.value.toUpperCase())} />
          {error("country")}
        </label>
        <label><span>{t("party.field.notes")}</span><input value={contact.notes} onChange={(event) => changeContact("notes", event.target.value)} /></label>
      </div>
    </>}
    {contactEditor?.footer}
    </PartyFormSection>
    {!supplier && <>
      <PartyFormSection enabled={grouped} sectionKey="personal" title={t("party.gestion.section.personal")}>
      <div className="product-create-row product-create-row-two">
        <label><span>{t("party.field.birthday")}</span><input type="date" value={form.birthday} onChange={(event) => onChange("birthday", event.target.value)} /></label>
        <label>
          <span>{t("party.field.gender")}</span>
          <ErpSelect
            value={form.gender}
            onChange={(value) => onChange("gender", value)}
            options={["", "MASCULINO", "FEMENINO", "OTRO"].map((value) => ({
              value,
              label: value ? t(`party.gender.${value.toLowerCase()}`) : t("party.gender.unspecified"),
            }))}
          />
        </label>
      </div>
      </PartyFormSection>
      {customerEditor && communicationsSection}
      <section className="party-credit-settings" aria-label={t("party.credit.title")}>
        <h3>{t("party.credit.title")}</h3>
        <label className="party-commercial-consent">
          <input type="checkbox" checked={form.creditEnabled} onChange={(event) => onChange("creditEnabled", event.target.checked)} />
          <span>{t("party.field.creditEnabled")}</span>
        </label>
        <div className="product-create-row product-create-row-two">
          <label className={invalidClass("creditLimit")}>
            <span>{t("party.field.creditLimit")}</span>
            <input type="number" min="0" step="0.01" placeholder={t("party.credit.unlimited")} value={form.creditLimit} onChange={(event) => onChange("creditLimit", event.target.value)} />
            {error("creditLimit")}
          </label>
          <label className={invalidClass("paymentTermDays")}>
            <span>{t("party.field.paymentTermDays")}</span>
            <input required type="number" min="0" max="3650" step="1" aria-invalid={errors.includes("paymentTermDays")} value={form.paymentTermDays} onChange={(event) => onChange("paymentTermDays", event.target.value)} />
            {error("paymentTermDays")}
          </label>
        </div>
        <div className="party-credit-checkboxes">
          <label><input type="checkbox" checked={form.creditBlocked} onChange={(event) => onChange("creditBlocked", event.target.checked)} /><span>{t("party.field.creditBlocked")}</span></label>
          <label><input type="checkbox" checked={form.blockOnOverdue} onChange={(event) => onChange("blockOnOverdue", event.target.checked)} /><span>{t("party.field.blockOnOverdue")}</span></label>
        </div>
      </section>
      {!customerEditor && communicationsSection}
    </>}
  </Container>;
}

function PartyFormSection({ enabled, sectionKey, title, children }: { enabled: boolean; sectionKey: string; title: ReactNode; children: ReactNode }) {
  return enabled ? <section className={`party-form-section party-form-section--${sectionKey}`}>{typeof title === "string" ? <h3>{title}</h3> : title}{children}</section> : <>{children}</>;
}
