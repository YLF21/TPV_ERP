import { type ComponentProps, FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { CaretDown, Info } from "@phosphor-icons/react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import { ErpSelect } from "./ErpSelect";
import "./PromotionWizard.css";
import {
  assertValidPromotionDraft,
  buildPromotionRequest,
  buyXPayYModes,
  createDefaultPromotionDraft,
  promotionCustomerSegments,
  promotionScopes,
  promotionTypes,
  validatePromotionDraft,
  type BuyXPayYMode,
  type PromotionCustomerSegment,
  type PromotionDraft,
  type PromotionRequest,
  type PromotionScope,
  type PromotionType,
  type PromotionValidationError,
  type PromotionView
} from "./PromotionForm";

export {
  buildPromotionRequest,
  createDefaultPromotionDraft,
  validatePromotionDraft
};
export type {
  BuyXPayYMode,
  PromotionCustomerSegment,
  PromotionDraft,
  PromotionRequest,
  PromotionScope,
  PromotionStatus,
  PromotionTargetRequest,
  PromotionTargetType,
  PromotionType,
  PromotionValidationError,
  PromotionView
} from "./PromotionForm";

export const promotionWizardSteps = [
  "basic",
  "type",
  "scope",
  "conditions",
  "benefit",
  "coupon",
  "summary"
] as const;

export type PromotionWizardStep = typeof promotionWizardSteps[number];
type PromotionApiRequest = <T>(path: string, options: { method?: string; token?: string; body?: unknown }) => Promise<T>;
type CouponValidityMode = "NONE" | "DATE" | "DAYS";
type PromotionNumericDraftField =
  | "minimumAmount"
  | "minimumQuantity"
  | "buyQuantity"
  | "payQuantity"
  | "discountAmount"
  | "discountPercent"
  | "maximumDiscount"
  | "packPrice"
  | "couponAmount"
  | "couponPercent"
  | "couponMaximumDiscount"
  | "couponMinimumAmount"
  | "couponValidFromDays"
  | "couponValidDays";

type CatalogProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  name?: string | null;
};

type CatalogFamily = {
  id: string;
  name?: string | null;
};

type CatalogSubfamily = {
  id: string;
  familyId?: string | null;
  name?: string | null;
};

type MemberCategory = {
  id: string;
  code?: string | null;
  name?: string | null;
  active?: boolean | null;
};

export type PromotionTargetOption = {
  id: string;
  label: string;
  familyId?: string;
};

export const promotionProductsPath = "/products";
export const promotionFamiliesPath = "/families";
export const promotionMemberCategoriesPath = "/member-categories";

export function promotionSubfamiliesPath(familyId: string) {
  return `/families/${encodeURIComponent(familyId)}/subfamilies`;
}

export function filterPromotionTargetOptions(options: PromotionTargetOption[], search: string) {
  const normalized = search.trim().toLocaleLowerCase();
  return normalized === ""
    ? options
    : options.filter((option) => option.label.toLocaleLowerCase().includes(normalized));
}

export function advancePromotionWizardStep(step: PromotionWizardStep): PromotionWizardStep {
  const index = promotionWizardSteps.indexOf(step);
  return promotionWizardSteps[Math.min(index + 1, promotionWizardSteps.length - 1)];
}

export function retreatPromotionWizardStep(step: PromotionWizardStep): PromotionWizardStep {
  const index = promotionWizardSteps.indexOf(step);
  return promotionWizardSteps[Math.max(index - 1, 0)];
}

export async function submitPromotionDraft(
  draft: PromotionDraft,
  token: string | undefined,
  request: PromotionApiRequest = apiRequest
) {
  assertValidPromotionDraft(draft);
  return request<PromotionView>("/promotions", {
    token,
    method: "POST",
    body: buildPromotionRequest(draft)
  });
}

type PromotionWizardProps = {
  locale: LocaleCode;
  session: UserSession;
  initialDraft?: PromotionDraft;
  onClose?: () => void;
  onCreated?: (promotion: PromotionView) => void;
};

export function PromotionWizard({ locale, session, initialDraft, onClose, onCreated }: PromotionWizardProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [draft, setDraft] = useState<PromotionDraft>(() => initialDraft ?? createDefaultPromotionDraft());
  const [status, setStatus] = useState("");
  const [saving, setSaving] = useState(false);
  const [validationErrors, setValidationErrors] = useState<PromotionValidationError[]>([]);
  const [products, setProducts] = useState<CatalogProduct[]>([]);
  const [families, setFamilies] = useState<CatalogFamily[]>([]);
  const [subfamilies, setSubfamilies] = useState<CatalogSubfamily[]>([]);
  const [memberCategories, setMemberCategories] = useState<MemberCategory[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [targetPickerOpen, setTargetPickerOpen] = useState(false);
  const [pickerTargetIds, setPickerTargetIds] = useState<string[]>([]);
  const [targetSearch, setTargetSearch] = useState("");
  const [subfamilyFamilyId, setSubfamilyFamilyId] = useState("");
  const [couponValidFromMode, setCouponValidFromMode] = useState<CouponValidityMode>(() =>
    initialCouponMode(initialDraft?.couponValidFromDate, initialDraft?.couponValidFromDays)
  );
  const [couponValidUntilMode, setCouponValidUntilMode] = useState<CouponValidityMode>(() =>
    initialCouponMode(initialDraft?.couponValidUntilDate, initialDraft?.couponValidDays)
  );
  const productsLoaded = useRef(false);
  const familiesLoaded = useRef(false);
  const memberCategoriesLoaded = useRef(false);
  const subfamiliesLoaded = useRef(new Set<string>());
  const token = session.accessToken;

  useEffect(() => {
    productsLoaded.current = false;
    familiesLoaded.current = false;
    memberCategoriesLoaded.current = false;
    subfamiliesLoaded.current = new Set<string>();
    setProducts([]);
    setFamilies([]);
    setSubfamilies([]);
    setMemberCategories([]);
    setSubfamilyFamilyId("");
  }, [token]);

  useEffect(() => {
    if (draft.scope === "PRODUCT_LIST" && !productsLoaded.current) {
      productsLoaded.current = true;
      setCatalogLoading(true);
      void apiRequest<CatalogProduct[]>(promotionProductsPath, { token })
        .then(setProducts)
        .catch(() => {
          productsLoaded.current = false;
          setStatus(t("promotion.status.loadError"));
        })
        .finally(() => setCatalogLoading(false));
    }
    if ((draft.scope === "FAMILY" || draft.scope === "SUBFAMILY") && !familiesLoaded.current) {
      familiesLoaded.current = true;
      setCatalogLoading(true);
      void apiRequest<CatalogFamily[]>(promotionFamiliesPath, { token })
        .then((rows) => {
          setFamilies(rows);
          setSubfamilyFamilyId((current) => current || rows[0]?.id || "");
        })
        .catch(() => {
          familiesLoaded.current = false;
          setStatus(t("promotion.status.loadError"));
        })
        .finally(() => setCatalogLoading(false));
    }
  }, [draft.scope, token, t]);

  useEffect(() => {
    if (draft.scope !== "SUBFAMILY" || subfamilyFamilyId === ""
        || subfamiliesLoaded.current.has(subfamilyFamilyId)) {
      return;
    }
    subfamiliesLoaded.current.add(subfamilyFamilyId);
    setCatalogLoading(true);
    void apiRequest<CatalogSubfamily[]>(promotionSubfamiliesPath(subfamilyFamilyId), { token })
      .then((rows) => {
        setSubfamilies((current) => [
          ...current.filter((subfamily) => subfamily.familyId !== subfamilyFamilyId),
          ...rows.map((subfamily) => ({ ...subfamily, familyId: subfamily.familyId ?? subfamilyFamilyId }))
        ]);
      })
      .catch(() => {
        subfamiliesLoaded.current.delete(subfamilyFamilyId);
        setStatus(t("promotion.status.loadError"));
      })
      .finally(() => setCatalogLoading(false));
  }, [draft.scope, subfamilyFamilyId, token, t]);

  useEffect(() => {
    if (draft.customerSegment !== "MEMBER_CATEGORY" || memberCategoriesLoaded.current) {
      return;
    }
    memberCategoriesLoaded.current = true;
    setCatalogLoading(true);
    void apiRequest<MemberCategory[]>(promotionMemberCategoriesPath, { token })
      .then((rows) => setMemberCategories(rows.filter((category) => category.active !== false)))
      .catch(() => {
        memberCategoriesLoaded.current = false;
        setStatus(t("promotion.status.loadError"));
      })
      .finally(() => setCatalogLoading(false));
  }, [draft.customerSegment, token, t]);

  const productOptions = useMemo(
    () => products.map((product) => ({ id: product.id, label: productLabel(product) })),
    [products]
  );
  const familyOptions = useMemo(
    () => families.map((family) => ({ id: family.id, label: family.name?.trim() || family.id })),
    [families]
  );
  const subfamilyOptions = useMemo(
    () => subfamilies.map((subfamily) => ({
      id: subfamily.id,
      label: subfamily.name?.trim() || subfamily.id,
      familyId: subfamily.familyId ?? undefined
    })),
    [subfamilies]
  );
  const currentTargetOptions = draft.scope === "PRODUCT_LIST"
    ? productOptions
    : draft.scope === "FAMILY"
      ? familyOptions
      : draft.scope === "SUBFAMILY"
        ? subfamilyOptions.filter((option) => option.familyId === subfamilyFamilyId)
        : [];
  const knownTargetOptions = draft.scope === "PRODUCT_LIST"
    ? productOptions
    : draft.scope === "FAMILY"
      ? familyOptions
      : subfamilyOptions;
  const filteredTargetOptions = useMemo(
    () => filterPromotionTargetOptions(currentTargetOptions, targetSearch),
    [currentTargetOptions, targetSearch]
  );
  const selectedTargetLabels = draft.targetIds.map(
    (targetId) => knownTargetOptions.find((option) => option.id === targetId)?.label ?? targetId
  );
  const selectedCategory = memberCategories.find((category) => category.id === draft.memberCategoryId);
  const minimumAmountLabel = `${t("stock.minimum.title")} - ${t("stock.column.amount")}`;
  const maximumAmountLabel = `${t("stock.column.amount")} (${t("stock.summary.until")})`;
  const dayLabel = t("stock.period.day");

  const summaryRows = useMemo(() => {
    const conditionParts: string[] = [];
    const benefitParts: string[] = [];
    const money = (value: string) => formatPromotionMoney(value, locale);
    const number = (value: string) => formatPromotionNumber(value, locale);
    const amountOrPercent = (amount: string, percent: string) =>
      amount ? money(amount) : percent ? `${number(percent)} %` : "—";

    if (draft.type === "PURCHASE_THRESHOLD_COUPON" || draft.type === "PURCHASE_THRESHOLD_DISCOUNT") {
      conditionParts.push(`${minimumAmountLabel}: ${draft.minimumAmount ? money(draft.minimumAmount) : "—"}`);
    }
    if (draft.type === "QUANTITY_DISCOUNT") {
      conditionParts.push(`${t("stock.minimum.quantity")}: ${draft.minimumQuantity ? number(draft.minimumQuantity) : "—"}`);
    }
    if (draft.type === "BUY_X_PAY_Y" || draft.type === "FIXED_PACK_PRICE") {
      conditionParts.push(`${t("promotion.field.buyQuantity")}: ${draft.buyQuantity ? number(draft.buyQuantity) : "—"}`);
    }
    if (draft.type === "PURCHASE_THRESHOLD_COUPON") {
      benefitParts.push(`${t("promotion.step.coupon")}: ${amountOrPercent(draft.couponAmount, draft.couponPercent)}`);
      if (draft.couponMaximumDiscount) benefitParts.push(`${maximumAmountLabel}: ${money(draft.couponMaximumDiscount)}`);
      if (draft.couponMinimumAmount) benefitParts.push(`${minimumAmountLabel}: ${money(draft.couponMinimumAmount)}`);
      benefitParts.push(`${t("stock.column.promotionValidity")}: ${couponValiditySummary(draft, dayLabel, t("promotion.noEndDate"), locale)}`);
    } else if (draft.type === "PURCHASE_THRESHOLD_DISCOUNT" || draft.type === "QUANTITY_DISCOUNT") {
      benefitParts.push(amountOrPercent(draft.discountAmount, draft.discountPercent));
      if (draft.maximumDiscount) benefitParts.push(`${maximumAmountLabel}: ${money(draft.maximumDiscount)}`);
    } else if (draft.type === "BUY_X_PAY_Y") {
      benefitParts.push(`${t("promotion.field.payQuantity")}: ${draft.payQuantity ? number(draft.payQuantity) : "—"}`);
      benefitParts.push(buyXPayYModeLabel(draft.buyXPayYMode, t));
    } else if (draft.type === "SECOND_UNIT_PERCENT") {
      benefitParts.push(amountOrPercent("", draft.discountPercent));
    } else if (draft.type === "FIXED_PACK_PRICE") {
      benefitParts.push(`${t("venta.column.price")}: ${draft.packPrice ? money(draft.packPrice) : "—"}`);
    }

    const customer = draft.customerSegment === "MEMBER_CATEGORY"
      ? `${t(`promotion.segment.${draft.customerSegment}`)}: ${selectedCategory?.name || draft.memberCategoryId || "—"}`
      : t(`promotion.segment.${draft.customerSegment}`);
    const targets = draft.scope === "SALE"
      ? t("promotion.create.saleScope")
      : `${number(String(draft.targetIds.length))} ${t("promotion.create.selectedTargets")}`;
    return [
      [t("promotion.field.name"), draft.name.trim() || "—"],
      [t("promotion.field.type"), t(`promotion.type.${draft.type}`)],
      [t("promotion.create.validity"), `${formatPromotionDate(draft.startDate, locale)} – ${draft.endDate ? formatPromotionDate(draft.endDate, locale) : t("promotion.noEndDate")}`],
      [t("promotion.field.scope"), t(`promotion.scope.${draft.scope}`)],
      [t("promotion.field.customerSegment"), customer],
      [t("promotion.create.conditions"), conditionParts.join(" · ") || t("promotion.create.noExtraConditions")],
      [t("promotion.create.benefit"), benefitParts.join(" · ")],
      [t("promotion.create.products"), targets]
    ];
  }, [dayLabel, draft, locale, maximumAmountLabel, minimumAmountLabel, selectedCategory?.name, t]);

  const previewSentence = useMemo(() => promotionPreviewSentence(draft, locale, t), [draft, locale, t]);

  function updateDraft<K extends keyof PromotionDraft>(key: K, value: PromotionDraft[K]) {
    setDraft((current) => ({ ...current, [key]: value }));
    setValidationErrors([]);
  }

  function updateScope(scope: PromotionScope) {
    setDraft((current) => ({ ...current, scope, targetIds: [] }));
    setPickerTargetIds([]);
    setTargetPickerOpen(false);
    setValidationErrors([]);
  }

  function updateCustomerSegment(customerSegment: PromotionCustomerSegment) {
    setDraft((current) => ({
      ...current,
      customerSegment,
      memberCategoryId: customerSegment === "MEMBER_CATEGORY" ? current.memberCategoryId : ""
    }));
    setValidationErrors([]);
  }

  function openTargetPicker() {
    setPickerTargetIds([...draft.targetIds]);
    setTargetSearch("");
    setTargetPickerOpen(true);
  }

  function togglePickerTarget(targetId: string) {
    setPickerTargetIds((current) => current.includes(targetId)
      ? current.filter((id) => id !== targetId)
      : [...current, targetId]);
  }

  function applyTargetSelection(targetIds = pickerTargetIds) {
    setDraft((current) => ({ ...current, targetIds: [...new Set(targetIds)] }));
    setValidationErrors([]);
    setTargetPickerOpen(false);
  }

  function applyTargetOnDoubleClick(targetId: string) {
    const targetIds = pickerTargetIds.includes(targetId) ? pickerTargetIds : [...pickerTargetIds, targetId];
    applyTargetSelection(targetIds);
  }

  function updateCouponFromMode(mode: CouponValidityMode) {
    setCouponValidFromMode(mode);
    setDraft((current) => ({
      ...current,
      couponValidFromDate: mode === "DATE" ? current.couponValidFromDate : "",
      couponValidFromDays: mode === "DAYS" ? current.couponValidFromDays : ""
    }));
    setValidationErrors([]);
  }

  function updateCouponUntilMode(mode: CouponValidityMode) {
    setCouponValidUntilMode(mode);
    setDraft((current) => ({
      ...current,
      couponValidUntilDate: mode === "DATE" ? current.couponValidUntilDate : "",
      couponValidDays: mode === "DAYS" ? current.couponValidDays : ""
    }));
    setValidationErrors([]);
  }

  function numberField(
    field: PromotionNumericDraftField,
    label: string,
    options: { min?: string; max?: string; step?: string; required?: boolean } = {}
  ) {
    return (
      <label key={field}>
        <span>{label}{options.required && <b className="promotion-required"> *</b>}</span>
        <input
          type="number"
          min={options.min ?? "0"}
          max={options.max}
          step={options.step ?? "0.01"}
          required={options.required}
          value={draft[field]}
          onChange={(event) => updateDraft(field, event.target.value)}
        />
      </label>
    );
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const errors = validatePromotionDraft(draft);
    if (errors.length > 0) {
      setValidationErrors(errors);
      setStatus(t("promotion.status.required"));
      return;
    }
    try {
      setSaving(true);
      const created = await submitPromotionDraft(draft, token);
      setStatus(t("promotion.status.created"));
      onCreated?.(created);
      setDraft(createDefaultPromotionDraft());
      setCouponValidFromMode("NONE");
      setCouponValidUntilMode("NONE");
      setValidationErrors([]);
    } catch {
      setStatus(t("promotion.status.createError"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="promotion-wizard promotion-wizard-page" onSubmit={handleSubmit}>
      <header className="promotion-create-heading">
        <div className="promotion-create-heading__copy">
          <span className="promotion-create-heading__eyebrow">{t("promotion.list.title")}</span>
          <h2 id="promotion-create-title">{t("promotion.create.title")}</h2>
          <p>{t("promotion.create.subtitle")}</p>
        </div>
        <div className="promotion-create-heading__actions">
          <button type="button" className="promotion-create-cancel" onClick={onClose}>{t("common.cancel")}</button>
          <button type="submit" className="promotion-create-save" disabled={saving}>
            {t(saving ? "promotion.action.saving" : "promotion.action.save")}
          </button>
        </div>
      </header>

      <div className="promotion-create-layout">
        <div className="promotion-create-sections">
          <section className="promotion-create-card">
            <h3>1. {t("promotion.create.basic")}</h3>
            <div className="promotion-create-fields">
              <label>
                <span>{t("promotion.field.name")}<b className="promotion-required"> *</b></span>
                <input required maxLength={160} value={draft.name}
                  onChange={(event) => updateDraft("name", event.target.value)} />
              </label>
              <label>
                <span>{t("promotion.field.type")}<b className="promotion-required"> *</b></span>
                <PromotionFieldSelect aria-label={t("promotion.field.type")} value={draft.type}
                  options={promotionTypes.map((type) => ({ value: type, label: t("promotion.type." + type) }))}
                  onChange={(value) => updateDraft("type", value as PromotionType)} />
              </label>
            </div>
          </section>

          <section className="promotion-create-card">
            <h3>2. {t("promotion.create.validity")}</h3>
            <div className="promotion-create-fields">
              <label>
                <span>{t("promotion.field.startDate")}<b className="promotion-required"> *</b></span>
                <input required type="date" value={draft.startDate}
                  onChange={(event) => updateDraft("startDate", event.target.value)} />
              </label>
              <label>
                <span>{t("promotion.field.endDate")}</span>
                <input type="date" min={draft.startDate || undefined} value={draft.endDate}
                  onChange={(event) => updateDraft("endDate", event.target.value)} />
              </label>
            </div>
          </section>

          <section className="promotion-create-card">
            <h3>3. {t("promotion.create.application")}</h3>
            <div className="promotion-create-fields">
              <label>
                <span>{t("promotion.field.scope")}<b className="promotion-required"> *</b></span>
                <PromotionFieldSelect aria-label={t("promotion.field.scope")} value={draft.scope}
                  options={promotionScopes.map((scope) => ({ value: scope, label: t("promotion.scope." + scope) }))}
                  onChange={(value) => updateScope(value as PromotionScope)} />
              </label>
              <label>
                <span>{t("promotion.field.customerSegment")}<b className="promotion-required"> *</b></span>
                <PromotionFieldSelect aria-label={t("promotion.field.customerSegment")} value={draft.customerSegment}
                  options={promotionCustomerSegments.map((segment) => ({
                    value: segment, label: t("promotion.segment." + segment)
                  }))}
                  onChange={(value) => updateCustomerSegment(value as PromotionCustomerSegment)} />
              </label>
              {draft.customerSegment === "MEMBER_CATEGORY" && (
                <label>
                  <span>{t("promotion.field.memberCategoryId")}<b className="promotion-required"> *</b></span>
                  <PromotionFieldSelect aria-label={t("promotion.field.memberCategoryId")} value={draft.memberCategoryId}
                    options={[
                      { value: "", label: t("common.select") },
                      ...memberCategories.map((category) => ({ value: category.id, label: categoryLabel(category) }))
                    ]}
                    onChange={(value) => updateDraft("memberCategoryId", value)} />
                </label>
              )}
            </div>
          </section>

          <section className="promotion-create-card">
            <h3>4. {t("promotion.create.conditions")}</h3>
            <div className="promotion-create-fields">
              {(draft.type === "PURCHASE_THRESHOLD_COUPON" || draft.type === "PURCHASE_THRESHOLD_DISCOUNT")
                && numberField("minimumAmount", minimumAmountLabel, { required: true })}
              {draft.type === "QUANTITY_DISCOUNT"
                && numberField("minimumQuantity", t("stock.minimum.quantity"), {
                  min: "0.001", step: "0.001", required: true
                })}
              {(draft.type === "BUY_X_PAY_Y" || draft.type === "FIXED_PACK_PRICE")
                && numberField("buyQuantity", t("promotion.field.buyQuantity"), {
                  min: "1", step: "1", required: true
                })}
              {draft.type === "SECOND_UNIT_PERCENT" && (
                <p className="promotion-create-empty-condition">{t("promotion.create.noExtraConditions")}</p>
              )}
            </div>
          </section>

          <section className="promotion-create-card">
            <h3>5. {t("promotion.create.benefit")}</h3>
            <div className="promotion-create-fields">
              {draft.type === "BUY_X_PAY_Y" && (
                <>
                  {numberField("payQuantity", t("promotion.field.payQuantity"), {
                    min: "0", step: "1", required: true
                  })}
                  <label>
                    <span>{t("promotion.field.buyXPayYMode")}</span>
                    <PromotionFieldSelect aria-label={t("promotion.field.buyXPayYMode")} value={draft.buyXPayYMode}
                      options={buyXPayYModes.map((mode) => ({ value: mode, label: buyXPayYModeLabel(mode, t) }))}
                      onChange={(value) => updateDraft("buyXPayYMode", value as BuyXPayYMode)} />
                  </label>
                </>
              )}
              {draft.type === "SECOND_UNIT_PERCENT"
                && numberField("discountPercent", t("promotion.field.discountPercent"), {
                  min: "0.01", max: "100", step: "0.01", required: true
                })}
              {draft.type === "FIXED_PACK_PRICE"
                && numberField("packPrice", t("venta.column.price"), { min: "0.01", required: true })}
              {(draft.type === "PURCHASE_THRESHOLD_DISCOUNT" || draft.type === "QUANTITY_DISCOUNT") && (
                <>
                  {numberField("discountAmount", t("stock.column.amount"), { min: "0.01" })}
                  {numberField("discountPercent", t("promotion.field.discountPercent"), {
                    min: "0.01", max: "100", step: "0.01"
                  })}
                  {numberField("maximumDiscount", maximumAmountLabel, { min: "0.01" })}
                </>
              )}
              {draft.type === "PURCHASE_THRESHOLD_COUPON" && (
                <>
                  {numberField("couponAmount", t("stock.column.amount"), { min: "0.01" })}
                  {numberField("couponPercent", t("promotion.field.discountPercent"), {
                    min: "0.01", max: "100", step: "0.01"
                  })}
                  {numberField("couponMaximumDiscount", maximumAmountLabel, { min: "0.01" })}
                  {numberField("couponMinimumAmount", minimumAmountLabel, { min: "0" })}
                  <label>
                    {t("salesReport.filter.dateFrom")}
                    <PromotionFieldSelect
                      aria-label={t("salesReport.filter.dateFrom")}
                      value={couponValidFromMode}
                      options={[
                        { value: "NONE", label: t("common.select") },
                        { value: "DATE", label: t("salesReport.filter.pickDateFrom") },
                        { value: "DAYS", label: dayLabel }
                      ]}
                      onChange={(value) => updateCouponFromMode(value as CouponValidityMode)}
                    />
                  </label>
                  {couponValidFromMode === "DATE" && (
                    <label>
                      {t("salesReport.filter.dateFrom")}
                      <input
                        type="date"
                        value={draft.couponValidFromDate}
                        onChange={(event) => updateDraft("couponValidFromDate", event.target.value)}
                      />
                    </label>
                  )}
                  {couponValidFromMode === "DAYS"
                    && numberField("couponValidFromDays", `${t("salesReport.filter.dateFrom")} (${dayLabel})`, {
                      min: "0", step: "1"
                    })}
                  <label>
                    {t("salesReport.filter.dateTo")}
                    <PromotionFieldSelect
                      aria-label={t("salesReport.filter.dateTo")}
                      value={couponValidUntilMode}
                      options={[
                        { value: "NONE", label: t("common.select") },
                        { value: "DATE", label: t("salesReport.filter.pickDateTo") },
                        { value: "DAYS", label: dayLabel }
                      ]}
                      onChange={(value) => updateCouponUntilMode(value as CouponValidityMode)}
                    />
                  </label>
                  {couponValidUntilMode === "DATE" && (
                    <label>
                      {t("salesReport.filter.dateTo")}
                      <input
                        required
                        type="date"
                        min={draft.couponValidFromDate || undefined}
                        value={draft.couponValidUntilDate}
                        onChange={(event) => updateDraft("couponValidUntilDate", event.target.value)}
                      />
                    </label>
                  )}
                  {couponValidUntilMode === "DAYS"
                    && numberField("couponValidDays", `${t("salesReport.filter.dateTo")} (${dayLabel})`, {
                      min: "1", step: "1", required: true
                    })}
                </>
              )}
            </div>
          </section>

          <section className="promotion-create-card">
            <h3>6. {t("promotion.create.products")}</h3>
            <div className="promotion-create-products">
              <p>{draft.scope === "SALE" ? t("promotion.create.saleScope") : t("promotion.create.selectTargets")}</p>
              {draft.scope !== "SALE" && (
                <button type="button" className="promotion-create-pick" aria-haspopup="dialog" onClick={openTargetPicker}>
                  {t("common.select")} ({draft.targetIds.length})
                </button>
              )}
            </div>
            {draft.scope !== "SALE" && (
              <div className="promotion-create-target-list">
                {selectedTargetLabels.length === 0
                  ? t("promotion.create.noTargets")
                  : selectedTargetLabels.map((label, index) => (
                    <div key={draft.targetIds[index] + "-" + index}>{label}</div>
                  ))}
              </div>
            )}
          </section>
        </div>

        <aside className="promotion-create-aside" aria-label={t("promotion.create.summary")}>
          <section className="promotion-create-summary-card">
            <h3>{t("promotion.create.summary")}</h3>
            <dl className="promotion-create-summary">
              {summaryRows.map(([label, value], index) => (
                <div key={label + "-" + index}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
            <div className="promotion-create-preview">
              <strong><Info size={17} weight="regular" aria-hidden="true" />{t("promotion.create.preview")}</strong>
              <p>{previewSentence}</p>
            </div>
          </section>
        </aside>
      </div>

      {status && <p className="promotion-status" role="status">{status}</p>}
      {validationErrors.length > 0 && <span hidden>{validationErrors.join(",")}</span>}

      {targetPickerOpen && draft.scope !== "SALE" && (
        <div className="filter-overlay stock-family-overlay" role="dialog" aria-modal="true" aria-labelledby="promotion-target-title">
          <section
            className="filter-dialog stock-family-dialog"
            style={{ maxHeight: "calc(100vh - 90px)", gridTemplateRows: "auto auto minmax(0, 1fr) auto" }}
          >
            <header className="filter-header">
              <h2 id="promotion-target-title">{t(`promotion.scope.${draft.scope}`)}</h2>
              <button type="button" onClick={() => setTargetPickerOpen(false)}>{t("common.close")}</button>
            </header>
            <div className="promotion-form-grid" style={{ padding: 0, overflow: "visible" }}>
              {draft.scope === "SUBFAMILY" && (
                <label>
                  {t("stock.column.family")}
                  <PromotionFieldSelect
                    aria-label={t("stock.column.family")}
                    value={subfamilyFamilyId}
                    options={families.map((family) => ({
                      value: family.id,
                      label: family.name?.trim() || family.id
                    }))}
                    onChange={setSubfamilyFamilyId}
                  />
                </label>
              )}
              <label className="report-search">
                <input
                  type="search"
                  value={targetSearch}
                  aria-label={t("salesReport.search")}
                  placeholder={t("salesReport.search")}
                  onChange={(event) => setTargetSearch(event.target.value)}
                />
              </label>
            </div>
            <div className="stock-family-list">
              {catalogLoading && <p>{t("common.loading")}</p>}
              {!catalogLoading && filteredTargetOptions.length === 0 && (
                <p>{t(draft.scope === "PRODUCT_LIST" ? "stock.bulkEdit.noMatches" : "stock.filter.noFamilies")}</p>
              )}
              {filteredTargetOptions.map((option) => {
                const selected = pickerTargetIds.includes(option.id);
                return (
                  <div className="stock-family-group" key={option.id}>
                    <div className={`stock-family-row ${selected ? "selected" : ""}`}>
                      <button
                        type="button"
                        className="stock-family-expand"
                        aria-pressed={selected}
                        aria-label={t("common.select")}
                        onClick={() => togglePickerTarget(option.id)}
                      >
                        {selected ? "X" : ""}
                      </button>
                      <button
                        type="button"
                        className="stock-family-choice"
                        onClick={() => togglePickerTarget(option.id)}
                        onDoubleClick={() => applyTargetOnDoubleClick(option.id)}
                      >
                        <span className={draft.scope === "PRODUCT_LIST" ? "product-name-text" : ""}>{option.label}</span>
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
            <footer className="filter-actions">
              <button type="button" onClick={() => setPickerTargetIds([])}>{t("salesReport.filter.clear")}</button>
              <button type="button" onClick={() => applyTargetSelection()}>{t("stock.filter.apply")}</button>
            </footer>
          </section>
        </div>
      )}
    </form>
  );
}

function initialCouponMode(dateValue?: string, daysValue?: string): CouponValidityMode {
  if (dateValue) {
    return "DATE";
  }
  return daysValue ? "DAYS" : "NONE";
}

function productLabel(product: CatalogProduct) {
  const name = product.name?.trim();
  const identifier = product.code?.trim() || product.barcode?.trim();
  if (identifier && name) {
    return `${identifier} - ${name}`;
  }
  return name || identifier || product.id;
}

function categoryLabel(category: MemberCategory) {
  const name = category.name?.trim();
  const code = category.code?.trim();
  return code && name ? `${code} - ${name}` : name || code || category.id;
}

function buyXPayYModeLabel(mode: BuyXPayYMode, t: (key: string) => string) {
  return mode === "SAME_PRODUCT" ? t("warehouseDocument.product") : t("salesReport.column.products");
}

function promotionLocale(locale: LocaleCode) {
  return locale === "es" ? "es-ES" : locale === "en" ? "en-GB" : "zh-CN";
}

function formatPromotionNumber(value: string, locale: LocaleCode) {
  const trimmed = value.trim();
  if (!/^(?:\d+|\d*\.\d+)$/.test(trimmed)) return trimmed || "—";
  return new Intl.NumberFormat(promotionLocale(locale), { maximumFractionDigits: 20 }).format(Number(trimmed));
}

function formatPromotionMoney(value: string, locale: LocaleCode) {
  const trimmed = value.trim();
  if (!/^(?:\d+|\d*\.\d+)$/.test(trimmed)) return trimmed || "—";
  return new Intl.NumberFormat(promotionLocale(locale), {
    style: "currency", currency: "EUR", minimumFractionDigits: 2, maximumFractionDigits: 20
  }).format(Number(trimmed));
}

function formatPromotionDate(value: string, locale: LocaleCode) {
  if (!value) return "—";
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return value;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  if (date.getUTCFullYear() !== Number(match[1])
      || date.getUTCMonth() + 1 !== Number(match[2])
      || date.getUTCDate() !== Number(match[3])) return value;
  return new Intl.DateTimeFormat(promotionLocale(locale), {
    timeZone: "UTC", day: "2-digit", month: "2-digit", year: "numeric"
  }).format(date);
}

function promotionPreviewSentence(draft: PromotionDraft, locale: LocaleCode, t: (key: string) => string) {
  const number = (value: string) => formatPromotionNumber(value, locale);
  const money = (value: string) => formatPromotionMoney(value, locale);
  const discount = (amount: string, percent: string) =>
    Boolean(amount) === Boolean(percent) ? null : amount ? money(amount) : `${number(percent)} %`;
  let key = "promotion.create.previewIncomplete";
  let values: Record<string, string> = {};

  switch (draft.type) {
    case "BUY_X_PAY_Y":
      if (draft.buyQuantity && draft.payQuantity) {
        key = draft.buyXPayYMode === "SAME_PRODUCT"
          ? "promotion.create.previewBuySame" : "promotion.create.previewBuyMixed";
        values = { buy: number(draft.buyQuantity), pay: number(draft.payQuantity) };
      }
      break;
    case "SECOND_UNIT_PERCENT":
      if (draft.discountPercent) {
        key = "promotion.create.previewSecondUnit";
        values = { discount: `${number(draft.discountPercent)} %` };
      }
      break;
    case "FIXED_PACK_PRICE":
      if (draft.buyQuantity && draft.packPrice) {
        key = "promotion.create.previewPack";
        values = { buy: number(draft.buyQuantity), price: money(draft.packPrice) };
      }
      break;
    case "QUANTITY_DISCOUNT": {
      const value = discount(draft.discountAmount, draft.discountPercent);
      if (draft.minimumQuantity && value) {
        key = "promotion.create.previewQuantityDiscount";
        values = { minimum: number(draft.minimumQuantity), discount: value };
      }
      break;
    }
    case "PURCHASE_THRESHOLD_DISCOUNT": {
      const value = discount(draft.discountAmount, draft.discountPercent);
      if (draft.minimumAmount && value) {
        key = "promotion.create.previewThresholdDiscount";
        values = { minimum: money(draft.minimumAmount), discount: value };
      }
      break;
    }
    case "PURCHASE_THRESHOLD_COUPON": {
      const value = discount(draft.couponAmount, draft.couponPercent);
      if (draft.minimumAmount && value) {
        key = "promotion.create.previewCoupon";
        values = { minimum: money(draft.minimumAmount), benefit: value };
      }
      break;
    }
  }

  return t(key).replace(/\{(\w+)\}/g, (placeholder, name: string) => values[name] ?? placeholder);
}

function couponValiditySummary(draft: PromotionDraft, dayLabel: string, noEndLabel: string, locale: LocaleCode) {
  const from = draft.couponValidFromDate
    ? formatPromotionDate(draft.couponValidFromDate, locale)
    : draft.couponValidFromDays ? `${formatPromotionNumber(draft.couponValidFromDays, locale)} ${dayLabel}` : "—";
  const until = draft.couponValidUntilDate
    ? formatPromotionDate(draft.couponValidUntilDate, locale)
    : draft.couponValidDays ? `${formatPromotionNumber(draft.couponValidDays, locale)} ${dayLabel}` : noEndLabel;
  return `${from} - ${until}`;
}

function PromotionFieldSelect(props: ComponentProps<typeof ErpSelect>) {
  return <span className="promotion-field-select"><ErpSelect {...props} /><CaretDown size={13} aria-hidden="true" /></span>;
}
