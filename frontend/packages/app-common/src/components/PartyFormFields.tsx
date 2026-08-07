import type { ReactNode } from "react";
import { ErpSelect } from "./ErpSelect";
import type { PartyForm } from "./PartyDirectoryPanel";

export type CommercialChannelOption = {
  id: string;
  code: string;
  name: string;
  active: boolean;
};

type Props = {
  form: PartyForm;
  errors: string[];
  channels: CommercialChannelOption[];
  supplier?: boolean;
  autoFocusName?: boolean;
  t: (key: string) => string;
  onChange: <K extends keyof PartyForm>(field: K, value: PartyForm[K]) => void;
};

export function PartyFormFields({
  form,
  errors,
  channels,
  supplier = false,
  autoFocusName = false,
  t,
  onChange,
}: Props) {
  function error(field: keyof PartyForm): ReactNode {
    return errors.includes(field)
      ? <small className="party-field-error" role="alert">{t("party.field.invalid")}</small>
      : null;
  }

  function invalidClass(field: keyof PartyForm): string | undefined {
    return errors.includes(field) ? "party-field-invalid" : undefined;
  }

  return <>
    <div className="product-create-row product-create-row-two">
      <label className={invalidClass("name")}>
        <span>{t(supplier ? "party.field.legalName" : "party.field.fiscalName")}</span>
        <input
          required
          autoFocus={autoFocusName}
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
    </div>
    <div className="product-create-row product-create-row-two">
      <label>
        <span>{t("party.field.documentType")}</span>
        <ErpSelect
          value={form.documentType}
          onChange={(value) => onChange("documentType", value)}
          options={["NIF", "CIF", "NIE", "PASAPORTE", "OTRO"].map((value) => ({ value, label: value }))}
        />
      </label>
      <label className={invalidClass("documentNumber")}>
        <span>{t("party.field.documentNumber")}</span>
        <input
          required
          aria-invalid={errors.includes("documentNumber")}
          value={form.documentNumber}
          onChange={(event) => onChange("documentNumber", event.target.value)}
        />
        {error("documentNumber")}
      </label>
    </div>
    <div className="product-create-row product-create-row-two">
      <label><span>{t("party.field.phone")}</span><input value={form.phone} onChange={(event) => onChange("phone", event.target.value)} /></label>
      <label><span>{t("party.field.email")}</span><input type="email" value={form.email} onChange={(event) => onChange("email", event.target.value)} /></label>
    </div>
    <label><span>{t("party.field.address")}</span><input value={form.address} onChange={(event) => onChange("address", event.target.value)} /></label>
    <div className="product-create-row product-create-row-three">
      <label><span>{t("party.field.postalCode")}</span><input value={form.postalCode} onChange={(event) => onChange("postalCode", event.target.value)} /></label>
      <label><span>{t("party.field.city")}</span><input value={form.city} onChange={(event) => onChange("city", event.target.value)} /></label>
      <label><span>{t("party.field.province")}</span><input value={form.province} onChange={(event) => onChange("province", event.target.value)} /></label>
    </div>
    <div className="product-create-row product-create-row-two">
      <label className={invalidClass("country")}>
        <span>{t("party.field.country")}</span>
        <input
          required
          maxLength={2}
          aria-invalid={errors.includes("country")}
          value={form.country}
          onChange={(event) => onChange("country", event.target.value.toUpperCase())}
        />
        {error("country")}
      </label>
      <label><span>{t("party.field.notes")}</span><input value={form.notes} onChange={(event) => onChange("notes", event.target.value)} /></label>
    </div>
    {!supplier && <>
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
    </>}
  </>;
}
