import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import type { LocaleCode } from "../types";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { StockInventoryRow, StockTopSalesFamilyNode } from "./StockScreen";
import { ErpSelect } from "./ErpSelect";
import { StockBulkFamilyDialog } from "./StockBulkFamilyDialog";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import {
  cloneStockBulkPriceRuleDraft,
  newStockBulkPriceRuleActionForScope,
  newStockBulkPriceRuleConditionForScope,
  newStockBulkPriceRuleDraft,
  newStockBulkPriceRuleForm,
  serializeStockBulkPriceRuleDraft,
  stockBulkPriceRuleDeletePath,
  stockBulkPriceRuleExecutionBody,
  stockBulkPriceRulePreviewPath,
  stockBulkPriceRuleRequest,
  stockBulkRuleNumericComparators,
  stockBulkRuleActionTypes,
  stockBulkRuleConditionTypes,
  stockBulkRuleNumericFieldsForScope,
  stockBulkRulePriceFieldsForScope,
  stockBulkRulePriceUseModes,
  stockBulkRuleReferenceFieldsForScope,
  stockBulkRuleScopes,
  stockBulkRuleSetComparators,
  validateStockBulkPriceRuleDraft
} from "./stockBulkPriceRules";
import type {
  StockBulkPriceRuleAction,
  StockBulkPriceRuleCondition,
  StockBulkPriceRuleDraft,
  StockBulkPriceRuleForm,
  StockBulkPriceRulePreview,
  StockBulkPriceRuleView
} from "./stockBulkPriceRules";

type RuleOption = { value: string; label: string };
type RuleFamilyPickerTarget = {
  formIndex: number;
  conditionIndex: number;
  field: "FAMILY" | "SUBFAMILY";
  values: string[];
};

type StockBulkPriceRulesDialogProps = {
  open: boolean;
  locale: LocaleCode;
  token: string;
  currentUsername: string;
  isAdmin: boolean;
  products: StockInventoryRow[];
  families: StockTopSalesFamilyNode[];
  suppliers: RuleOption[];
  warehouses: RuleOption[];
  onApplied: (preview: StockBulkPriceRulePreview) => void;
  onClose: () => void;
};

function selectedValues(select: HTMLSelectElement) {
  return Array.from(select.selectedOptions, (option) => option.value);
}

function displayValue(value: unknown) {
  if (value === null || value === undefined || value === "") return "-";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

export function priceRuleConflictFormIndexes(error: unknown) {
  if (!(error instanceof ApiError) || error.problem?.code !== "PRODUCT_PRICE_RULE_CONFLICT") {
    return [];
  }
  const indexes = error.problem.formIndexes;
  return Array.isArray(indexes)
    ? indexes.filter((value): value is number => Number.isInteger(value) && Number(value) >= 0)
    : [];
}

export function StockBulkPriceRulesDialog({
  open,
  locale,
  token,
  currentUsername,
  isAdmin,
  products,
  families,
  suppliers,
  warehouses,
  onApplied,
  onClose
}: StockBulkPriceRulesDialogProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [rules, setRules] = useState<StockBulkPriceRuleView[]>([]);
  const [selectedRule, setSelectedRule] = useState<StockBulkPriceRuleView | null>(null);
  const [draft, setDraft] = useState<StockBulkPriceRuleDraft>(() => newStockBulkPriceRuleDraft());
  const [baseline, setBaseline] = useState("");
  const [preview, setPreview] = useState<StockBulkPriceRulePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [reloadAvailable, setReloadAvailable] = useState(false);
  const [confirmAction, setConfirmAction] = useState<"apply" | "delete" | null>(null);
  const [conflictFormIndexes, setConflictFormIndexes] = useState<number[]>([]);
  const [familyPickerTarget, setFamilyPickerTarget] = useState<RuleFamilyPickerTarget | null>(null);
  const dialogRef = useRef<HTMLElement | null>(null);

  const chooseRule = (rule: StockBulkPriceRuleView) => {
    const next = cloneStockBulkPriceRuleDraft(rule);
    setSelectedRule(rule);
    setDraft(next);
    setBaseline(serializeStockBulkPriceRuleDraft(next));
    setPreview(null);
    setError("");
    setReloadAvailable(false);
    setConfirmAction(null);
    setConflictFormIndexes([]);
  };

  const startNewRule = () => {
    const next = newStockBulkPriceRuleDraft();
    setSelectedRule(null);
    setDraft(next);
    setBaseline("");
    setPreview(null);
    setError("");
    setReloadAvailable(false);
    setConfirmAction(null);
    setConflictFormIndexes([]);
  };

  const loadRules = async (preferredId?: string) => {
    const loaded = await apiRequest<StockBulkPriceRuleView[]>("/product-price-rules", { token });
    setRules(loaded);
    const preferred = loaded.find((rule) => rule.id === preferredId) ?? loaded[0];
    if (preferred) chooseRule(preferred);
    else startNewRule();
    return loaded;
  };

  useEffect(() => {
    if (!open || !token) return;
    let cancelled = false;
    setBusy(true);
    setError("");
    void apiRequest<StockBulkPriceRuleView[]>("/product-price-rules", { token })
      .then((loaded) => {
        if (cancelled) return;
        setRules(loaded);
        if (loaded[0]) chooseRule(loaded[0]);
        else startNewRule();
      })
      .catch((caught) => {
        if (!cancelled) setError(caught instanceof Error ? caught.message : t("stock.bulkEdit.rules.loadError"));
      })
      .finally(() => {
        if (!cancelled) setBusy(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, token]);

  if (!open) return null;

  const dirty = !selectedRule || serializeStockBulkPriceRuleDraft(draft) !== baseline;
  const canDelete = Boolean(selectedRule && (isAdmin || selectedRule.createdBy === currentUsername));
  const subfamilies = families.flatMap((family) => family.subfamilies.map((subfamily) => ({
    value: subfamily.id,
    label: `${family.name} / ${subfamily.name}`
  })));
  const productOptions = products.map((product) => ({
    value: product.productId,
    label: `${product.code || product.barcode || product.barcode2 || "-"} / ${product.name}`
  }));

  const operationError = (caught: unknown, fallbackKey: string, canReload = false) => {
    setError(caught instanceof Error ? caught.message : t(fallbackKey));
    setReloadAvailable(canReload && caught instanceof ApiError && caught.status === 409);
    setConflictFormIndexes(priceRuleConflictFormIndexes(caught));
  };

  const persistDraft = async () => {
    const validation = validateStockBulkPriceRuleDraft(draft);
    if (validation) {
      setError(t(validation));
      return null;
    }
    if (selectedRule && !dirty) return selectedRule;
    setBusy(true);
    setError("");
    setReloadAvailable(false);
    try {
      const saved = await apiRequest<StockBulkPriceRuleView>(
        selectedRule ? `/product-price-rules/${encodeURIComponent(selectedRule.id)}` : "/product-price-rules",
        {
          method: selectedRule ? "PUT" : "POST",
          token,
          body: stockBulkPriceRuleRequest(draft, selectedRule?.version)
        }
      );
      setRules((current) => [saved, ...current.filter((rule) => rule.id !== saved.id)]);
      chooseRule(saved);
      return saved;
    } catch (caught) {
      operationError(caught, "stock.bulkEdit.rules.saveError", true);
      return null;
    } finally {
      setBusy(false);
    }
  };

  const runRule = async (apply: boolean) => {
    const saved = await persistDraft();
    if (!saved) return;
    setBusy(true);
    setError("");
    setReloadAvailable(false);
    setConfirmAction(null);
    try {
      const result = await apiRequest<StockBulkPriceRulePreview>(
        stockBulkPriceRulePreviewPath(saved.id),
        {
          method: "POST",
          token,
          body: stockBulkPriceRuleExecutionBody(saved.version, products.map((product) => product.productId))
        }
      );
      setPreview(result);
      if (apply) onApplied(result);
    } catch (caught) {
      operationError(
        caught,
        apply ? "stock.bulkEdit.rules.applyError" : "stock.bulkEdit.rules.previewError",
        true
      );
    } finally {
      setBusy(false);
    }
  };

  const deleteRule = async () => {
    if (!selectedRule || !canDelete) return;
    setBusy(true);
    setError("");
    setReloadAvailable(false);
    try {
      await apiRequest<void>(stockBulkPriceRuleDeletePath(selectedRule.id, selectedRule.version), {
        method: "DELETE",
        token
      });
      setConfirmAction(null);
      await loadRules();
    } catch (caught) {
      operationError(caught, "stock.bulkEdit.rules.deleteError", true);
    } finally {
      setBusy(false);
    }
  };

  const reloadSelected = async () => {
    setBusy(true);
    setError("");
    try {
      if (selectedRule) {
        const loaded = await apiRequest<StockBulkPriceRuleView>(
          `/product-price-rules/${encodeURIComponent(selectedRule.id)}`,
          { token }
        );
        setRules((current) => [loaded, ...current.filter((rule) => rule.id !== loaded.id)]);
        chooseRule(loaded);
      } else {
        await loadRules();
      }
    } catch (caught) {
      operationError(caught, "stock.bulkEdit.rules.loadError");
    } finally {
      setBusy(false);
    }
  };

  const updateForm = (formIndex: number, next: StockBulkPriceRuleForm) => {
    setDraft((current) => ({
      ...current,
      forms: current.forms.map((form, index) => index === formIndex ? next : form)
    }));
    setPreview(null);
    setConflictFormIndexes([]);
  };

  const updateCondition = (formIndex: number, conditionIndex: number, next: StockBulkPriceRuleCondition) => {
    const form = draft.forms[formIndex];
    updateForm(formIndex, {
      ...form,
      conditions: form.conditions.map((condition, index) => index === conditionIndex ? next : condition)
    });
  };

  const updateAction = (formIndex: number, actionIndex: number, next: StockBulkPriceRuleAction) => {
    const form = draft.forms[formIndex];
    updateForm(formIndex, {
      ...form,
      actions: form.actions.map((action, index) => index === actionIndex ? next : action)
    });
  };

  const ruleControlSelector = [
    ".stock-bulk-rule-editor input:not(:disabled)",
    ".stock-bulk-rule-editor select:not(:disabled)",
    ".stock-bulk-rule-editor .erp-select__trigger:not(:disabled)",
    ".stock-bulk-rule-editor button:not(:disabled)"
  ].join(", ");
  const focusNextRuleControl = () => {
    const active = document.activeElement;
    if (!(active instanceof HTMLElement)) return;
    window.requestAnimationFrame(() => focusRelativeEnterTarget(
      dialogRef.current,
      active,
      "next",
      ruleControlSelector
    ));
  };
  const handleRuleEnter = (event: KeyboardEvent<HTMLElement>) => {
    const target = event.target as HTMLInputElement | HTMLSelectElement;
    const intent = enterNavigationIntent(event.key, {
      shiftKey: event.shiftKey,
      ctrlKey: event.ctrlKey,
      altKey: event.altKey,
      metaKey: event.metaKey,
      isComposing: event.nativeEvent.isComposing
    });
    if (event.defaultPrevented || !intent || !target.matches("input, select")) return;
    event.preventDefault();
    if (intent === "next" && target.matches("input[type='checkbox'], input[type='radio']")) {
      (target as HTMLInputElement).click();
    }
    focusRelativeEnterTarget(dialogRef.current, target, intent, ruleControlSelector);
  };
  const renderCondition = (
    condition: StockBulkPriceRuleCondition,
    formIndex: number,
    conditionIndex: number,
    scope: StockBulkPriceRuleForm["scope"]
  ) => {
    const remove = () => {
      const form = draft.forms[formIndex];
      updateForm(formIndex, { ...form, conditions: form.conditions.filter((_, index) => index !== conditionIndex) });
    };
    return (
      <div className="stock-bulk-rule-line" key={`condition-${formIndex}-${conditionIndex}`}>
        <ErpSelect
          onCommit={focusNextRuleControl}
          aria-label={t("stock.bulkEdit.rules.conditionType")}
          value={condition.type}
          options={stockBulkRuleConditionTypes(scope).map((type) => ({
            value: type,
            label: t(`stock.bulkEdit.rules.condition.${type}`)
          }))}
          onChange={(value) => updateCondition(
            formIndex,
            conditionIndex,
            newStockBulkPriceRuleConditionForScope(scope, value as StockBulkPriceRuleCondition["type"])
          )}
        />
        {condition.type === "NUMBER" && (
          <>
            <ErpSelect
              onCommit={focusNextRuleControl}
              aria-label={t("stock.bulkEdit.rules.field")}
              value={condition.field}
              options={stockBulkRuleNumericFieldsForScope(scope).map((field) => ({
                value: field,
                label: t(`stock.bulkEdit.rules.numeric.${field}`)
              }))}
              onChange={(value) => updateCondition(formIndex, conditionIndex, {
                ...condition,
                field: value as typeof condition.field,
                warehouseId: value === "LOCAL_STOCK" ? condition.warehouseId : null
              })}
            />
            <ErpSelect
              onCommit={focusNextRuleControl}
              aria-label={t("stock.bulkEdit.rules.condition.NUMBER")}
              value={condition.comparator}
              options={stockBulkRuleNumericComparators.map((value) => ({
                value,
                label: t(`stock.bulkEdit.rules.comparator.${value}`)
              }))}
              onChange={(value) => updateCondition(formIndex, conditionIndex, {
                ...condition,
                comparator: value as typeof condition.comparator
              })}
            />
            <input aria-label={t("stock.bulkEdit.rules.value")} type="number" step="0.001" value={condition.value} onChange={(event) => updateCondition(formIndex, conditionIndex, { ...condition, value: event.target.value })} />
            {condition.field === "LOCAL_STOCK" && (
              <ErpSelect
                onCommit={focusNextRuleControl}
                aria-label={t("stock.bulkEdit.rules.defaultWarehouse")}
                value={condition.warehouseId ?? ""}
                options={[
                  { value: "", label: t("stock.bulkEdit.rules.defaultWarehouse") },
                  ...warehouses
                ]}
                onChange={(value) => updateCondition(formIndex, conditionIndex, {
                  ...condition,
                  warehouseId: value || null
                })}
              />
            )}
          </>
        )}
        {condition.type === "REFERENCE" && (
          <>
            <ErpSelect
              onCommit={focusNextRuleControl}
              aria-label={t("stock.bulkEdit.rules.field")}
              value={condition.field}
              options={stockBulkRuleReferenceFieldsForScope(scope).map((field) => ({
                value: field,
                label: t(`stock.bulkEdit.rules.reference.${field}`)
              }))}
              onChange={(value) => updateCondition(formIndex, conditionIndex, {
                ...condition,
                field: value as typeof condition.field,
                values: []
              })}
            />
            <ErpSelect
              onCommit={focusNextRuleControl}
              aria-label={t("stock.bulkEdit.rules.condition.REFERENCE")}
              value={condition.comparator}
              options={stockBulkRuleSetComparators.map((value) => ({
                value,
                label: t(`stock.bulkEdit.rules.comparator.${value}`)
              }))}
              onChange={(value) => updateCondition(formIndex, conditionIndex, {
                ...condition,
                comparator: value as typeof condition.comparator
              })}
            />
            {condition.field === "SUPPLIER" ? (
              <select multiple size={3} value={condition.values} onChange={(event) => updateCondition(formIndex, conditionIndex, { ...condition, values: selectedValues(event.currentTarget) })}>
                {suppliers.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            ) : (
              <button
                type="button"
                className="stock-bulk-rule-family-picker"
                onClick={() => setFamilyPickerTarget({
                  formIndex,
                  conditionIndex,
                  field: condition.field as "FAMILY" | "SUBFAMILY",
                  values: condition.values
                })}
              >
                {t("stock.bulkEdit.rules.chooseFamilies")} ({condition.values.length})
              </button>
            )}
          </>
        )}
        {condition.type === "PRODUCT_TYPE" && (
          <>
            <ErpSelect
              onCommit={focusNextRuleControl}
              aria-label={t("stock.bulkEdit.rules.condition.PRODUCT_TYPE")}
              value={condition.comparator}
              options={stockBulkRuleSetComparators.map((value) => ({
                value,
                label: t(`stock.bulkEdit.rules.comparator.${value}`)
              }))}
              onChange={(value) => updateCondition(formIndex, conditionIndex, {
                ...condition,
                comparator: value as typeof condition.comparator
              })}
            />
            <select multiple size={3} value={condition.values} onChange={(event) => updateCondition(formIndex, conditionIndex, { ...condition, values: selectedValues(event.currentTarget) })}>
              {(["UNIT", "WEIGHT", "SERVICE"] as const).map((value) => <option key={value} value={value}>{t(`product.type.${value.toLowerCase()}`)}</option>)}
            </select>
          </>
        )}
        {condition.type === "PRODUCT_LIST" && (
          <>
            <ErpSelect
              onCommit={focusNextRuleControl}
              aria-label={t("stock.bulkEdit.rules.condition.PRODUCT_LIST")}
              value={condition.comparator}
              options={stockBulkRuleSetComparators.map((value) => ({
                value,
                label: t(`stock.bulkEdit.rules.comparator.${value}`)
              }))}
              onChange={(value) => updateCondition(formIndex, conditionIndex, {
                ...condition,
                comparator: value as typeof condition.comparator
              })}
            />
            <select multiple size={4} value={condition.productIds} onChange={(event) => updateCondition(formIndex, conditionIndex, { ...condition, productIds: selectedValues(event.currentTarget) })}>
              {productOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </>
        )}
        <button type="button" className="secondary" onClick={remove}>{t("stock.bulkEdit.rules.remove")}</button>
      </div>
    );
  };

  const renderAction = (
    action: StockBulkPriceRuleAction,
    formIndex: number,
    actionIndex: number,
    scope: StockBulkPriceRuleForm["scope"]
  ) => {
    const remove = () => {
      const form = draft.forms[formIndex];
      updateForm(formIndex, { ...form, actions: form.actions.filter((_, index) => index !== actionIndex) });
    };
    return (
      <div className="stock-bulk-rule-line" key={`action-${formIndex}-${actionIndex}`}>
        <ErpSelect
          onCommit={focusNextRuleControl}
          aria-label={t("stock.bulkEdit.rules.actionType")}
          value={action.type}
          options={stockBulkRuleActionTypes(scope).map((type) => ({
            value: type,
            label: t(`stock.bulkEdit.rules.action.${type}`)
          }))}
          onChange={(value) => updateAction(
            formIndex,
            actionIndex,
            newStockBulkPriceRuleActionForScope(scope, value as StockBulkPriceRuleAction["type"])
          )}
        />
        {(action.type === "FIXED_PRICE" || action.type === "INVERSE_MARGIN" || action.type === "DECIMAL_ENDING") && (
          <ErpSelect
            onCommit={focusNextRuleControl}
            aria-label={t("stock.bulkEdit.rules.field")}
            value={action.field}
            options={stockBulkRulePriceFieldsForScope(scope).map((field) => ({
              value: field,
              label: t(`stock.bulkEdit.rules.priceField.${field}`)
            }))}
            onChange={(value) => updateAction(formIndex, actionIndex, {
              ...action,
              field: value as typeof action.field
            })}
          />
        )}
        {action.type === "FIXED_PRICE" && (
          <input min="0" step="0.01" type="number" value={action.value} onChange={(event) => updateAction(formIndex, actionIndex, { ...action, value: event.target.value })} />
        )}
        {action.type === "INVERSE_MARGIN" && (
          <>
            <ErpSelect
              onCommit={focusNextRuleControl}
              aria-label={t("stock.bulkEdit.rules.action.INVERSE_MARGIN")}
              value={action.costBasis}
              options={(["GROSS", "NET"] as const).map((value) => ({
                value,
                label: t(`stock.bulkEdit.rules.costBasis.${value}`)
              }))}
              onChange={(value) => updateAction(formIndex, actionIndex, {
                ...action,
                costBasis: value as typeof action.costBasis
              })}
            />
            <input min="0" max="99.99" step="0.01" type="number" value={action.marginPercent} onChange={(event) => updateAction(formIndex, actionIndex, { ...action, marginPercent: event.target.value })} />
          </>
        )}
        {action.type === "DECIMAL_ENDING" && (
          <input min="0" max="99" step="1" type="number" value={action.cents} onChange={(event) => updateAction(formIndex, actionIndex, { ...action, cents: Number(event.target.value) })} />
        )}
        {action.type === "PRICE_USE_MODE" && (
          <ErpSelect
            onCommit={focusNextRuleControl}
            aria-label={t("stock.bulkEdit.rules.action.PRICE_USE_MODE")}
            value={action.value}
            options={stockBulkRulePriceUseModes.map((value) => ({
              value,
              label: t(`stock.bulkEdit.rules.priceUse.${value}`)
            }))}
            onChange={(value) => updateAction(formIndex, actionIndex, {
              ...action,
              value: value as typeof action.value
            })}
          />
        )}
        {action.type === "OFFER" && (
          <>
            <label className="stock-bulk-rule-checkbox">
              <input type="checkbox" checked={action.active} onChange={(event) => updateAction(formIndex, actionIndex, { ...action, active: event.target.checked })} />
              <span>{t("stock.bulkEdit.rules.offerActive")}</span>
            </label>
            <input aria-label={t("stock.bulkEdit.rules.discountPercent")} min="0" max="100" step="0.01" placeholder={t("stock.bulkEdit.rules.discountPercent")} type="number" value={action.discountPercent ?? ""} onChange={(event) => updateAction(formIndex, actionIndex, { ...action, discountPercent: event.target.value || null })} />
            <input aria-label={t("stock.bulkEdit.rules.offerFrom")} type="date" value={action.from ?? ""} onChange={(event) => updateAction(formIndex, actionIndex, { ...action, from: event.target.value || null })} />
            <input aria-label={t("stock.bulkEdit.rules.offerUntil")} type="date" value={action.until ?? ""} onChange={(event) => updateAction(formIndex, actionIndex, { ...action, until: event.target.value || null })} />
          </>
        )}
        <button type="button" className="secondary" onClick={remove}>{t("stock.bulkEdit.rules.remove")}</button>
      </div>
    );
  };

  return (
    <>
    <div className="filter-overlay" role="presentation">
      <section
        ref={dialogRef}
        aria-labelledby="stock-bulk-rules-title"
        aria-modal="true"
        className="filter-dialog stock-bulk-price-rules-dialog"
        role="dialog"
        onKeyDown={handleRuleEnter}
      >
        <header className="filter-header">
          <div>
            <h2 id="stock-bulk-rules-title">{t("stock.bulkEdit.rules.title")}</h2>
            <p>{t("stock.bulkEdit.rules.subtitle")}</p>
          </div>
          <button type="button" disabled={busy} onClick={onClose}>{t("common.close")}</button>
        </header>
        <div className="stock-bulk-rules-layout">
          <aside className="stock-bulk-rules-list">
            <button type="button" className="stock-bulk-rule-new" disabled={busy} onClick={startNewRule}>{t("stock.bulkEdit.rules.new")}</button>
            {rules.length === 0 && <p className="stock-empty-state">{t("stock.bulkEdit.rules.empty")}</p>}
            {rules.map((rule) => (
              <button type="button" className={selectedRule?.id === rule.id ? "selected" : ""} key={rule.id} onClick={() => chooseRule(rule)}>
                <strong>{rule.name}</strong>
                <span>{t("stock.bulkEdit.rules.formsCount").replace("{count}", String(rule.forms.length))}</span>
                <small>
                  {rule.createdBy} · {new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale, {
                    dateStyle: "short",
                    timeStyle: "short"
                  }).format(new Date(rule.createdAt))}
                </small>
              </button>
            ))}
          </aside>
          <main className="stock-bulk-rule-editor">
            <div className="stock-bulk-rule-topline">
              <label>
                <span>{t("stock.bulkEdit.rules.name")}</span>
                <input autoFocus maxLength={160} value={draft.name} onChange={(event) => { setDraft((current) => ({ ...current, name: event.target.value })); setPreview(null); }} />
              </label>
              <div className="stock-bulk-rule-commands">
                <button type="button" disabled={busy || !dirty} onClick={() => void persistDraft()}>{t("common.save")}</button>
                <button type="button" className="secondary" disabled={busy} onClick={startNewRule}>{t("stock.bulkEdit.rules.clear")}</button>
                <button type="button" disabled={busy} onClick={() => void runRule(false)}>{t("stock.bulkEdit.rules.preview")}</button>
                <button type="button" disabled={busy} onClick={() => setConfirmAction("apply")}>{t("stock.bulkEdit.rules.apply")}</button>
                <button type="button" disabled={busy || !canDelete} onClick={() => setConfirmAction("delete")}>{t("stock.bulkEdit.rules.delete")}</button>
              </div>
            </div>
            {error && (
              <div className="stock-bulk-rule-error" role="alert">
                <span>{error}</span>
                {reloadAvailable && <button type="button" onClick={() => void reloadSelected()}>{t("stock.bulkEdit.reload")}</button>}
              </div>
            )}
            {confirmAction && (
              <div className="stock-bulk-rule-confirm" role="alertdialog">
                <span>{t(`stock.bulkEdit.rules.confirm.${confirmAction}`)}</span>
                <button type="button" className="secondary" onClick={() => setConfirmAction(null)}>{t("common.cancel")}</button>
                <button autoFocus type="button" disabled={busy} onClick={() => confirmAction === "apply" ? void runRule(true) : void deleteRule()}>{t("common.confirm")}</button>
              </div>
            )}
            <div className="stock-bulk-rule-forms">
              {draft.forms.map((form, formIndex) => (
                <section className={`stock-bulk-rule-form ${conflictFormIndexes.includes(formIndex) ? "conflict" : ""}`} key={`form-${formIndex}`}>
                  <header>
                    <strong>{t("stock.bulkEdit.rules.form").replace("{number}", String(formIndex + 1))}</strong>
                    <label>
                      <span>{t("stock.bulkEdit.rules.scope")}</span>
                      <ErpSelect
                        onCommit={focusNextRuleControl}
                        aria-label={t("stock.bulkEdit.rules.scope")}
                        value={form.scope}
                        options={stockBulkRuleScopes.map((scope) => ({
                          value: scope,
                          label: t(`stock.bulkEdit.rules.scope.${scope}`)
                        }))}
                        onChange={(value) => updateForm(
                          formIndex,
                          newStockBulkPriceRuleForm(value as typeof form.scope)
                        )}
                      />
                    </label>
                    <button type="button" className="secondary" disabled={draft.forms.length === 1} onClick={() => setDraft((current) => ({ ...current, forms: current.forms.filter((_, index) => index !== formIndex) }))}>{t("stock.bulkEdit.rules.removeForm")}</button>
                  </header>
                  <div className="stock-bulk-rule-section-title">
                    <strong>{t("stock.bulkEdit.rules.conditions")}</strong>
                    <button type="button" onClick={() => updateForm(formIndex, { ...form, conditions: [...form.conditions, newStockBulkPriceRuleConditionForScope(form.scope)] })}>{t("stock.bulkEdit.rules.addCondition")}</button>
                  </div>
                  {form.conditions.length === 0 && <p className="stock-bulk-rule-empty-line">{t("stock.bulkEdit.rules.allProducts")}</p>}
                  {form.conditions.map((condition, conditionIndex) => renderCondition(condition, formIndex, conditionIndex, form.scope))}
                  <div className="stock-bulk-rule-section-title">
                    <strong>{t("stock.bulkEdit.rules.actions")}</strong>
                    <button type="button" onClick={() => updateForm(formIndex, { ...form, actions: [...form.actions, newStockBulkPriceRuleActionForScope(form.scope)] })}>{t("stock.bulkEdit.rules.addAction")}</button>
                  </div>
                  {form.actions.map((action, actionIndex) => renderAction(action, formIndex, actionIndex, form.scope))}
                </section>
              ))}
              <button type="button" className="stock-bulk-rule-add-form" onClick={() => setDraft((current) => ({
                ...current,
                forms: [...current.forms, newStockBulkPriceRuleForm()]
              }))}>{t("stock.bulkEdit.rules.addForm")}</button>
            </div>
            {preview && (
              <section className="stock-bulk-rule-preview">
                <header>
                  <strong>{t("stock.bulkEdit.rules.previewTitle")}</strong>
                  <span>{t("stock.bulkEdit.rules.matched").replace("{count}", String(preview.matchedProducts))}</span>
                </header>
                <div className="stock-bulk-rule-preview-table" role="table">
                  <div className="stock-bulk-rule-preview-row head" role="row">
                    <span>{t("stock.bulkEdit.rules.product")}</span>
                    <span>{t("stock.bulkEdit.rules.field")}</span>
                    <span>{t("stock.bulkEdit.rules.before")}</span>
                    <span>{t("stock.bulkEdit.rules.after")}</span>
                    <span>{t("stock.bulkEdit.rules.forms")}</span>
                  </div>
                  {preview.products.flatMap((product) => product.changes.map((change, index) => (
                    <div className="stock-bulk-rule-preview-row" key={`${product.productId}-${change.field}-${index}`} role="row">
                      <span>{product.productName}</span>
                      <span>{t(`stock.bulkEdit.rules.previewField.${change.field}`)}</span>
                      <span>{displayValue(change.before)}</span>
                      <span>{displayValue(change.after)}</span>
                      <span>{change.formIndexes.map((formIndex) => formIndex + 1).join(", ")}</span>
                    </div>
                  )))}
                </div>
              </section>
            )}
          </main>
        </div>
      </section>
    </div>
    <StockBulkFamilyDialog
      open={Boolean(familyPickerTarget)}
      locale={locale}
      families={families}
      initialFamilyIds={familyPickerTarget?.field === "FAMILY" ? familyPickerTarget.values : []}
      initialSubfamilyIds={familyPickerTarget?.field === "SUBFAMILY" ? familyPickerTarget.values : []}
      titleKey="stock.bulkEdit.rules.chooseFamilies"
      applyLabelKey="stock.filter.apply"
      onClose={() => setFamilyPickerTarget(null)}
      onApply={(familyIds, subfamilyIds) => {
        const target = familyPickerTarget;
        if (!target) return;
        const values = target.field === "FAMILY"
          ? Array.from(new Set([
            ...familyIds,
            ...subfamilyIds.flatMap((subfamilyId) => families
              .filter((family) => family.subfamilies.some((subfamily) => subfamily.id === subfamilyId))
              .map((family) => family.id))
          ]))
          : Array.from(new Set([
            ...subfamilyIds,
            ...familyIds.flatMap((familyId) => families
              .find((family) => family.id === familyId)?.subfamilies.map((subfamily) => subfamily.id) ?? [])
          ]));
        const condition = draft.forms[target.formIndex]?.conditions[target.conditionIndex];
        if (condition?.type === "REFERENCE") {
          updateCondition(target.formIndex, target.conditionIndex, { ...condition, values });
        }
        setFamilyPickerTarget(null);
      }}
    />
    </>
  );
}
