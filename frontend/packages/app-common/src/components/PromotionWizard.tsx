import { FormEvent, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";

export type PromotionType =
  | "PURCHASE_THRESHOLD_COUPON"
  | "PURCHASE_THRESHOLD_DISCOUNT"
  | "BUY_X_PAY_Y"
  | "SECOND_UNIT_PERCENT"
  | "FIXED_PACK_PRICE"
  | "QUANTITY_DISCOUNT";

export type PromotionScope = "SALE" | "PRODUCT_LIST" | "FAMILY" | "SUBFAMILY";
export type PromotionCustomerSegment = "ALL" | "IDENTIFIED_CUSTOMERS" | "MEMBERS_ONLY" | "MEMBER_CATEGORY";
export type PromotionStatus = "DRAFT" | "ACTIVE" | "INACTIVE";

export type PromotionView = {
  id: string;
  name: string;
  type: PromotionType;
  status: PromotionStatus;
  startDate: string;
  endDate: string | null;
  scope: PromotionScope | null;
  customerSegment: PromotionCustomerSegment | null;
  memberCategoryId: string | null;
  buyQuantity: string | null;
  payQuantity: string | null;
  discountPercent: string | null;
  versionOrigenId?: string | null;
  used?: boolean;
};

export type PromotionDraft = {
  name: string;
  type: PromotionType;
  startDate: string;
  endDate: string;
  scope: PromotionScope;
  customerSegment: PromotionCustomerSegment;
  memberCategoryId: string;
  buyQuantity: string;
  payQuantity: string;
  discountPercent: string;
};

export type PromotionRequest = {
  name: string;
  type: PromotionType;
  startDate: string;
  endDate: string | null;
  scope: PromotionScope;
  customerSegment: PromotionCustomerSegment;
  memberCategoryId: string | null;
  buyQuantity: string | null;
  payQuantity: string | null;
  discountPercent: string | null;
};

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

const promotionTypes: PromotionType[] = [
  "PURCHASE_THRESHOLD_COUPON",
  "PURCHASE_THRESHOLD_DISCOUNT",
  "BUY_X_PAY_Y",
  "SECOND_UNIT_PERCENT",
  "FIXED_PACK_PRICE",
  "QUANTITY_DISCOUNT"
];

const promotionScopes: PromotionScope[] = ["SALE", "PRODUCT_LIST", "FAMILY", "SUBFAMILY"];
const customerSegments: PromotionCustomerSegment[] = ["ALL", "IDENTIFIED_CUSTOMERS", "MEMBERS_ONLY", "MEMBER_CATEGORY"];

export function createDefaultPromotionDraft(startDate = new Date().toISOString().slice(0, 10)): PromotionDraft {
  return {
    name: "",
    type: "PURCHASE_THRESHOLD_DISCOUNT",
    startDate,
    endDate: "",
    scope: "SALE",
    customerSegment: "ALL",
    memberCategoryId: "",
    buyQuantity: "",
    payQuantity: "",
    discountPercent: ""
  };
}

export function advancePromotionWizardStep(step: PromotionWizardStep): PromotionWizardStep {
  const index = promotionWizardSteps.indexOf(step);
  return promotionWizardSteps[Math.min(index + 1, promotionWizardSteps.length - 1)];
}

export function retreatPromotionWizardStep(step: PromotionWizardStep): PromotionWizardStep {
  const index = promotionWizardSteps.indexOf(step);
  return promotionWizardSteps[Math.max(index - 1, 0)];
}

export function buildPromotionRequest(draft: PromotionDraft): PromotionRequest {
  return {
    name: draft.name.trim(),
    type: draft.type,
    startDate: draft.startDate,
    endDate: blankToNull(draft.endDate),
    scope: draft.scope,
    customerSegment: draft.customerSegment,
    memberCategoryId: blankToNull(draft.memberCategoryId),
    buyQuantity: draft.type === "BUY_X_PAY_Y" ? blankToNull(draft.buyQuantity) : null,
    payQuantity: draft.type === "BUY_X_PAY_Y" ? blankToNull(draft.payQuantity) : null,
    discountPercent: draft.type === "SECOND_UNIT_PERCENT" ? blankToNull(draft.discountPercent) : null
  };
}

export async function submitPromotionDraft(
  draft: PromotionDraft,
  token: string | undefined,
  request: PromotionApiRequest = apiRequest
) {
  return request<PromotionView>("/promotions", {
    token,
    method: "POST",
    body: buildPromotionRequest(draft)
  });
}

function blankToNull(value: string) {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

function requiredDraftReady(draft: PromotionDraft) {
  return draft.name.trim() !== "" && draft.startDate.trim() !== "";
}

type PromotionWizardProps = {
  locale: LocaleCode;
  session: UserSession;
  initialDraft?: PromotionDraft;
  onCreated?: (promotion: PromotionView) => void;
};

export function PromotionWizard({ locale, session, initialDraft, onCreated }: PromotionWizardProps) {
  const t = createTranslator(locale);
  const [step, setStep] = useState<PromotionWizardStep>("basic");
  const [draft, setDraft] = useState<PromotionDraft>(() => initialDraft ?? createDefaultPromotionDraft());
  const [status, setStatus] = useState("");
  const [saving, setSaving] = useState(false);
  const currentStepIndex = promotionWizardSteps.indexOf(step);
  const isSummary = step === "summary";

  const summaryRows = useMemo(() => [
    ["promotion.field.name", draft.name || "-"],
    ["promotion.field.type", t(`promotion.type.${draft.type}`)],
    ["promotion.field.startDate", draft.startDate || "-"],
    ["promotion.field.endDate", draft.endDate || t("promotion.noEndDate")],
    ["promotion.field.scope", t(`promotion.scope.${draft.scope}`)],
    ["promotion.field.customerSegment", t(`promotion.segment.${draft.customerSegment}`)]
  ], [draft, t]);

  function updateDraft<K extends keyof PromotionDraft>(key: K, value: PromotionDraft[K]) {
    setDraft((current) => ({ ...current, [key]: value }));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!isSummary) {
      setStep(advancePromotionWizardStep(step));
      return;
    }
    if (!requiredDraftReady(draft)) {
      setStatus(t("promotion.status.required"));
      return;
    }
    try {
      setSaving(true);
      const created = await submitPromotionDraft(draft, session.accessToken);
      setStatus(t("promotion.status.created"));
      onCreated?.(created);
      setDraft(createDefaultPromotionDraft());
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
              <input value={draft.name} onChange={(event) => updateDraft("name", event.target.value)} />
            </label>
            <label>
              {t("promotion.field.startDate")}
              <input type="date" value={draft.startDate} onChange={(event) => updateDraft("startDate", event.target.value)} />
            </label>
            <label>
              {t("promotion.field.endDate")}
              <input type="date" value={draft.endDate} onChange={(event) => updateDraft("endDate", event.target.value)} />
            </label>
          </>
        )}

        {step === "type" && (
          <label>
            {t("promotion.field.type")}
            <select value={draft.type} onChange={(event) => updateDraft("type", event.target.value as PromotionType)}>
              {promotionTypes.map((type) => (
                <option value={type} key={type}>{t(`promotion.type.${type}`)}</option>
              ))}
            </select>
          </label>
        )}

        {step === "scope" && (
          <label>
            {t("promotion.field.scope")}
            <select value={draft.scope} onChange={(event) => updateDraft("scope", event.target.value as PromotionScope)}>
              {promotionScopes.map((scope) => (
                <option value={scope} key={scope}>{t(`promotion.scope.${scope}`)}</option>
              ))}
            </select>
          </label>
        )}

        {step === "conditions" && (
          <>
            <label>
              {t("promotion.field.customerSegment")}
              <select value={draft.customerSegment} onChange={(event) => updateDraft("customerSegment", event.target.value as PromotionCustomerSegment)}>
                {customerSegments.map((segment) => (
                  <option value={segment} key={segment}>{t(`promotion.segment.${segment}`)}</option>
                ))}
              </select>
            </label>
            <label>
              {t("promotion.field.memberCategoryId")}
              <input value={draft.memberCategoryId} onChange={(event) => updateDraft("memberCategoryId", event.target.value)} />
            </label>
          </>
        )}

        {step === "benefit" && (
          <>
            <label>
              {t("promotion.field.buyQuantity")}
              <input type="number" min="0" step="0.001" value={draft.buyQuantity} onChange={(event) => updateDraft("buyQuantity", event.target.value)} />
            </label>
            <label>
              {t("promotion.field.payQuantity")}
              <input type="number" min="0" step="0.001" value={draft.payQuantity} onChange={(event) => updateDraft("payQuantity", event.target.value)} />
            </label>
            <label>
              {t("promotion.field.discountPercent")}
              <input type="number" min="0" max="100" step="0.01" value={draft.discountPercent} onChange={(event) => updateDraft("discountPercent", event.target.value)} />
            </label>
          </>
        )}

        {step === "coupon" && (
          <p className="promotion-help">{t("promotion.coupon.placeholder")}</p>
        )}

        {step === "summary" && (
          <dl className="promotion-summary">
            {summaryRows.map(([label, value]) => (
              <div key={label}>
                <dt>{t(label)}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        )}
      </section>

      {status && <p className="promotion-status">{status}</p>}

      <footer className="filter-actions promotion-actions">
        <button type="button" disabled={step === "basic"} onClick={() => setStep(retreatPromotionWizardStep(step))}>
          {t("promotion.action.previous")}
        </button>
        <button type="submit" disabled={saving || (isSummary && !requiredDraftReady(draft))}>
          {isSummary ? t(saving ? "promotion.action.saving" : "promotion.action.createDraft") : t("promotion.action.next")}
        </button>
      </footer>
    </form>
  );
}
