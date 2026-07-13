import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import { ErpSelect } from "./ErpSelect";
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
  onCreated?: (promotion: PromotionView) => void;
};

export function PromotionWizard({ locale, session, initialDraft, onCreated }: PromotionWizardProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [step, setStep] = useState<PromotionWizardStep>("basic");
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
  const currentStepIndex = promotionWizardSteps.indexOf(step);
  const isSummary = step === "summary";

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
    const rows: Array<[string, string]> = [
      [t("promotion.field.name"), draft.name || "-"],
      [t("promotion.field.type"), t(`promotion.type.${draft.type}`)],
      [t("promotion.field.startDate"), draft.startDate || "-"],
      [t("promotion.field.endDate"), draft.endDate || t("promotion.noEndDate")],
      [t("promotion.field.scope"), t(`promotion.scope.${draft.scope}`)],
      [t("promotion.field.customerSegment"), t(`promotion.segment.${draft.customerSegment}`)]
    ];
    if (selectedTargetLabels.length > 0) {
      rows.push([t("common.select"), selectedTargetLabels.join(", ")]);
    }
    if (draft.customerSegment === "MEMBER_CATEGORY") {
      rows.push([t("promotion.field.memberCategoryId"), selectedCategory?.name || draft.memberCategoryId || "-"]);
    }
    switch (draft.type) {
      case "PURCHASE_THRESHOLD_COUPON":
        rows.push(
          [minimumAmountLabel, draft.minimumAmount || "-"],
          [t("promotion.step.coupon"), draft.couponAmount || draft.couponPercent || "-"],
          [t("stock.column.promotionValidity"), couponValiditySummary(draft, dayLabel, t("promotion.noEndDate"))]
        );
        break;
      case "PURCHASE_THRESHOLD_DISCOUNT":
        rows.push(
          [minimumAmountLabel, draft.minimumAmount || "-"],
          [t("promotion.field.discountPercent"), draft.discountAmount || draft.discountPercent || "-"]
        );
        break;
      case "BUY_X_PAY_Y":
        rows.push(
          [t("promotion.field.buyQuantity"), draft.buyQuantity || "-"],
          [t("promotion.field.payQuantity"), draft.payQuantity || "-"],
          [t("promotion.field.type"), buyXPayYModeLabel(draft.buyXPayYMode, t)]
        );
        break;
      case "SECOND_UNIT_PERCENT":
        rows.push([t("promotion.field.discountPercent"), draft.discountPercent || "-"]);
        break;
      case "FIXED_PACK_PRICE":
        rows.push(
          [t("promotion.field.buyQuantity"), draft.buyQuantity || "-"],
          [t("venta.column.price"), draft.packPrice || "-"]
        );
        break;
      case "QUANTITY_DISCOUNT":
        rows.push(
          [t("stock.minimum.quantity"), draft.minimumQuantity || "-"],
          [t("promotion.field.discountPercent"), draft.discountAmount || draft.discountPercent || "-"]
        );
        break;
    }
    return rows;
  }, [dayLabel, draft, minimumAmountLabel, selectedCategory?.name, selectedTargetLabels, t]);

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
        {label}
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
    if (!isSummary) {
      setStep(advancePromotionWizardStep(step));
      return;
    }
    const errors = validatePromotionDraft(draft);
    if (errors.length > 0) {
      setValidationErrors(errors);
      setStatus(t("promotion.status.required"));
      setStep(promotionValidationStep(errors[0]));
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
      setStep("basic");
    } catch {
      setStatus(t("promotion.status.createError"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="promotion-wizard" onSubmit={handleSubmit}>
      <header className="work-panel-heading">
        <h2>{t("promotion.wizard.title")}</h2>
        <span>{t(`promotion.step.${step}`)} ({currentStepIndex + 1}/{promotionWizardSteps.length})</span>
      </header>

      <nav className="promotion-steps" aria-label={t("promotion.wizard.steps")}>
        {promotionWizardSteps.map((wizardStep, index) => (
          <button
            type="button"
            className={wizardStep === step ? "selected" : ""}
            key={wizardStep}
            onClick={() => setStep(wizardStep)}
          >
            {index + 1}. {t(`promotion.step.${wizardStep}`)}
          </button>
        ))}
      </nav>

      <section className="promotion-form-grid">
        {step === "basic" && (
          <>
            <label>
              {t("promotion.field.name")}
              <input
                required
                maxLength={160}
                value={draft.name}
                onChange={(event) => updateDraft("name", event.target.value)}
              />
            </label>
            <label>
              {t("promotion.field.startDate")}
              <input
                required
                type="date"
                value={draft.startDate}
                onChange={(event) => updateDraft("startDate", event.target.value)}
              />
            </label>
            <label>
              {t("promotion.field.endDate")}
              <input
                type="date"
                min={draft.startDate || undefined}
                value={draft.endDate}
                onChange={(event) => updateDraft("endDate", event.target.value)}
              />
            </label>
          </>
        )}

        {step === "type" && (
          <label>
            {t("promotion.field.type")}
            <ErpSelect
              aria-label={t("promotion.field.type")}
              value={draft.type}
              options={promotionTypes.map((type) => ({
                value: type,
                label: t(`promotion.type.${type}`)
              }))}
              onChange={(value) => updateDraft("type", value as PromotionType)}
            />
          </label>
        )}

        {step === "scope" && (
          <>
            <label>
              {t("promotion.field.scope")}
              <ErpSelect
                aria-label={t("promotion.field.scope")}
                value={draft.scope}
                options={promotionScopes.map((scope) => ({
                  value: scope,
                  label: t(`promotion.scope.${scope}`)
                }))}
                onChange={(value) => updateScope(value as PromotionScope)}
              />
            </label>
            {draft.scope !== "SALE" && (
              <>
                <button
                  type="button"
                  className="filter-select-button"
                  aria-haspopup="dialog"
                  onClick={openTargetPicker}
                >
                  <span>{t("common.select")}</span>
                  <strong>{draft.targetIds.length}</strong>
                </button>
                {selectedTargetLabels.length > 0 && (
                  <dl className="promotion-summary">
                    {selectedTargetLabels.map((label, index) => (
                      <div key={`${draft.targetIds[index]}-${index}`}>
                        <dt>{t(`promotion.scope.${draft.scope}`)}</dt>
                        <dd>{label}</dd>
                      </div>
                    ))}
                  </dl>
                )}
              </>
            )}
          </>
        )}

        {step === "conditions" && (
          <>
            <label>
              {t("promotion.field.customerSegment")}
              <ErpSelect
                aria-label={t("promotion.field.customerSegment")}
                value={draft.customerSegment}
                options={promotionCustomerSegments.map((segment) => ({
                  value: segment,
                  label: t(`promotion.segment.${segment}`)
                }))}
                onChange={(value) => updateCustomerSegment(value as PromotionCustomerSegment)}
              />
            </label>
            {draft.customerSegment === "MEMBER_CATEGORY" && (
              <label>
                {t("promotion.field.memberCategoryId")}
                <ErpSelect
                  aria-label={t("promotion.field.memberCategoryId")}
                  value={draft.memberCategoryId}
                  options={[
                    { value: "", label: t("common.select") },
                    ...memberCategories.map((category) => ({
                      value: category.id,
                      label: categoryLabel(category)
                    }))
                  ]}
                  onChange={(value) => updateDraft("memberCategoryId", value)}
                />
              </label>
            )}
          </>
        )}

        {step === "benefit" && (
          <>
            {(draft.type === "PURCHASE_THRESHOLD_COUPON" || draft.type === "PURCHASE_THRESHOLD_DISCOUNT")
              && numberField("minimumAmount", minimumAmountLabel, { required: true })}
            {draft.type === "BUY_X_PAY_Y" && (
              <>
                {numberField("buyQuantity", t("promotion.field.buyQuantity"), { min: "1", step: "1", required: true })}
                {numberField("payQuantity", t("promotion.field.payQuantity"), { min: "0", step: "1", required: true })}
                <label>
                  {t("promotion.field.type")}
                  <ErpSelect
                    aria-label={t("promotion.field.type")}
                    value={draft.buyXPayYMode}
                    options={buyXPayYModes.map((mode) => ({
                      value: mode,
                      label: buyXPayYModeLabel(mode, t)
                    }))}
                    onChange={(value) => updateDraft("buyXPayYMode", value as BuyXPayYMode)}
                  />
                </label>
              </>
            )}
            {draft.type === "SECOND_UNIT_PERCENT"
              && numberField("discountPercent", t("promotion.field.discountPercent"), {
                min: "0.01", max: "100", step: "0.01", required: true
              })}
            {draft.type === "FIXED_PACK_PRICE" && (
              <>
                {numberField("buyQuantity", t("promotion.field.buyQuantity"), { min: "1", step: "1", required: true })}
                {numberField("packPrice", t("venta.column.price"), { min: "0.01", required: true })}
              </>
            )}
            {draft.type === "QUANTITY_DISCOUNT"
              && numberField("minimumQuantity", t("stock.minimum.quantity"), {
                min: "0.001", step: "0.001", required: true
              })}
            {(draft.type === "PURCHASE_THRESHOLD_DISCOUNT" || draft.type === "QUANTITY_DISCOUNT") && (
              <>
                {numberField("discountAmount", t("stock.column.amount"), { min: "0.01" })}
                {numberField("discountPercent", t("promotion.field.discountPercent"), {
                  min: "0.01", max: "100", step: "0.01"
                })}
                {numberField("maximumDiscount", maximumAmountLabel, { min: "0.01" })}
              </>
            )}
          </>
        )}

        {step === "coupon" && draft.type === "PURCHASE_THRESHOLD_COUPON" && (
          <>
            {numberField("couponAmount", t("stock.column.amount"), { min: "0.01" })}
            {numberField("couponPercent", t("promotion.field.discountPercent"), {
              min: "0.01", max: "100", step: "0.01"
            })}
            {numberField("couponMaximumDiscount", maximumAmountLabel, { min: "0.01" })}
            {numberField("couponMinimumAmount", minimumAmountLabel, { min: "0" })}
            <label>
              {t("salesReport.filter.dateFrom")}
              <ErpSelect
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
              <ErpSelect
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

        {step === "coupon" && draft.type !== "PURCHASE_THRESHOLD_COUPON" && (
          <p className="promotion-help">{t(`promotion.type.${draft.type}`)}</p>
        )}

        {step === "summary" && (
          <dl className="promotion-summary">
            {summaryRows.map(([label, value], index) => (
              <div key={`${label}-${index}`}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        )}
      </section>

      {status && <p className="promotion-status" role="status">{status}</p>}
      {validationErrors.length > 0 && (
        <span hidden>{validationErrors.join(",")}</span>
      )}

      <footer className="filter-actions promotion-actions">
        <button type="button" disabled={step === "basic"} onClick={() => setStep(retreatPromotionWizardStep(step))}>
          {t("promotion.action.previous")}
        </button>
        <button type="submit" disabled={saving}>
          {isSummary ? t(saving ? "promotion.action.saving" : "promotion.action.createDraft") : t("promotion.action.next")}
        </button>
      </footer>

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
                  <ErpSelect
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
                        {option.label}
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

function promotionValidationStep(error: PromotionValidationError): PromotionWizardStep {
  if (error === "NAME_REQUIRED" || error === "NAME_TOO_LONG"
      || error === "START_DATE_REQUIRED" || error === "DATE_RANGE_INVALID") {
    return "basic";
  }
  if (error === "TARGETS_NOT_ALLOWED" || error === "TARGETS_REQUIRED" || error === "TARGETS_INVALID") {
    return "scope";
  }
  if (error === "MEMBER_CATEGORY_REQUIRED") {
    return "conditions";
  }
  if (error.startsWith("COUPON_")) {
    return "coupon";
  }
  return "benefit";
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

function couponValiditySummary(draft: PromotionDraft, dayLabel: string, noEndLabel: string) {
  const from = draft.couponValidFromDate || (draft.couponValidFromDays ? `${draft.couponValidFromDays} ${dayLabel}` : "-");
  const until = draft.couponValidUntilDate || (draft.couponValidDays ? `${draft.couponValidDays} ${dayLabel}` : noEndLabel);
  return `${from} - ${until}`;
}
