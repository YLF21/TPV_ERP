import { useEffect, useId, useRef, useState, type PointerEvent } from "react";
import { createPortal } from "react-dom";
import type { LocaleCode } from "./types";
import { quantityPricingMessages } from "./i18n/QuantityPricingMessages";
import { formatQuantityPrice, isQuantityPricingEnabled, normalizeQuantityPricingTiers, simulateQuantityPricing, validateQuantityPricing, type QuantityPricing, type QuantityPricingMode } from "./quantityPricing";
import { ErpConfirmDialog } from "./components/ErpConfirmDialog";
import { WindowCloseButton } from "./components/WindowCloseButton";
import { activateModalFocusTrap, type ModalFocusRoot } from "./components/modalFocusTrap";
import "./components/ErpClassicWindow.css";
import "./QuantityPricingEditor.css";

export type QuantityPricingEditorIntent = "save" | "apply" | "doNotApply";
export type QuantityPricingEditorProps = {
  locale: LocaleCode;
  value: QuantityPricing;
  basePrice: number | string;
  saveHint?: string;
  onClose: () => void;
} & ({ onConfirm: (value: QuantityPricing) => void; onConfirmIntent?: never }
  | { onConfirm?: never; onConfirmIntent: (value: QuantityPricing, intent: QuantityPricingEditorIntent) => void });

type DraftTier = { quantity: string; price: string };
function initialTiers(value: QuantityPricing): DraftTier[] {
  return value?.tiers.map((tier) => ({ quantity: String(tier.quantity), price: String(tier.price) }))
    ?? [{ quantity: "2", price: "" }];
}

export function QuantityPricingEditor({ locale, value, basePrice, saveHint, onConfirm, onConfirmIntent, onClose }: QuantityPricingEditorProps) {
  const words = quantityPricingMessages(locale);
  const titleId = useId();
  const root = useRef<HTMLElement>(null);
  const drag = useRef<{ pointerId: number; grabX: number; grabY: number } | null>(null);
  const [mode, setMode] = useState<QuantityPricingMode>(() => value?.mode ?? "PACK");
  const [tiers, setTiers] = useState<DraftTier[]>(() => initialTiers(value));
  const [quantity, setQuantity] = useState("1");
  const [attempted, setAttempted] = useState(false);
  const [discardOpen, setDiscardOpen] = useState(false);
  const initial = useRef(JSON.stringify({ mode, tiers }));
  const enabled = isQuantityPricingEnabled(value);

  useEffect(() => {
    if (!root.current) return;
    return activateModalFocusTrap(root.current as ModalFocusRoot, document);
  }, []);

  const dirty = JSON.stringify({ mode, tiers }) !== initial.current;
  const parsedTiers = tiers.map((tier) => ({ quantity: Number(tier.quantity), price: tier.price }));
  const draft = { mode, tiers: parsedTiers };
  const issues = validateQuantityPricing(draft);
  const missingFields = tiers.some(tier => !tier.quantity.trim() || !tier.price.trim());
  const previewCount = Number(quantity);
  const validSimulationQuantity = Number.isSafeInteger(previewCount) && previewCount >= 1 && previewCount <= 10000;
  const simulation = issues.length === 0 && validSimulationQuantity
    ? simulateQuantityPricing(draft, basePrice, previewCount) : null;

  function requestClose() { if (dirty) setDiscardOpen(true); else onClose(); }
  function chooseMode(next: QuantityPricingMode) {
    setMode(next);
    setAttempted(false);
  }
  function saveRule(nextEnabled: boolean, intent: QuantityPricingEditorIntent) {
    setAttempted(true);
    if (issues.length) return;
    const rule = normalizeQuantityPricingTiers({ ...draft, enabled: nextEnabled });
    if (onConfirmIntent) onConfirmIntent(rule, intent);
    else onConfirm?.(rule);
  }
  function updateTier(index: number, key: keyof DraftTier, next: string) {
    setTiers((current) => current.map((tier, tierIndex) => tierIndex === index ? { ...tier, [key]: next } : tier));
  }

  function startDrag(event: PointerEvent<HTMLElement>) {
    if (!root.current || !event.isPrimary || event.button !== 0 || (event.target as Element).closest("button")) return;
    const bounds = root.current.getBoundingClientRect();
    drag.current = { pointerId: event.pointerId, grabX: event.clientX - bounds.left, grabY: event.clientY - bounds.top };
    event.currentTarget.setPointerCapture(event.pointerId);
    event.preventDefault();
  }

  function moveDrag(event: PointerEvent<HTMLElement>) {
    const grab = drag.current;
    if (!grab || grab.pointerId !== event.pointerId || !root.current) return;
    root.current.style.setProperty("--pricing-left", `${event.clientX - grab.grabX}px`);
    root.current.style.setProperty("--pricing-top", `${event.clientY - grab.grabY}px`);
  }

  return createPortal(<>
    <div className="filter-overlay erp-classic-overlay quantity-pricing-overlay" role="presentation" onKeyDown={(event) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); if (!discardOpen) requestClose(); }
      if (event.key === "F9") { event.preventDefault(); event.stopPropagation(); }
    }}>
      <section ref={root} className="filter-dialog erp-classic-window quantity-pricing-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} inert={discardOpen || undefined}>
        <header className="quantity-pricing-header" onPointerDown={startDrag} onPointerMove={moveDrag}
          onLostPointerCapture={() => { drag.current = null; }}>
          <h2 id={titleId}>{words.title}</h2>
          <WindowCloseButton aria-label={words.close} onClick={requestClose}>×</WindowCloseButton>
        </header>
        <div className="quantity-pricing-body">
          <div className="quantity-pricing-modes" role="radiogroup" aria-label={words.title}>
            <label><input type="radio" name={`${titleId}-mode`} checked={mode === "PACK"} onChange={() => chooseMode("PACK")} />{words.pack}</label>
            <label><input type="radio" name={`${titleId}-mode`} checked={mode === "UNIT_THRESHOLD"} onChange={() => chooseMode("UNIT_THRESHOLD")} />{words.threshold}</label>
          </div>
          <p className="quantity-pricing-help">{mode === "PACK" ? words.packHelp : words.thresholdHelp}</p>
          <div className="quantity-pricing-tiers">
            {tiers.map((tier, index) => <div className="quantity-pricing-tier" key={index}>
              <label>{words.quantity}<input inputMode="numeric" type="number" min="1" step="1" value={tier.quantity} aria-invalid={attempted && (!Number.isSafeInteger(Number(tier.quantity)) || Number(tier.quantity) < 1)} onChange={(event) => updateTier(index, "quantity", event.target.value)} /></label>
              <label>{mode === "PACK" ? words.packPrice : words.unitPrice}<input inputMode="decimal" value={tier.price} aria-invalid={attempted && !/^\d{1,17}(?:[.,]\d{1,3})?$/.test(tier.price.trim())} onChange={(event) => updateTier(index, "price", event.target.value)} /></label>
              <span className="quantity-pricing-remove">
                <WindowCloseButton onLight aria-label={`${words.remove} ${index + 1}`} title={`${words.remove} ${index + 1}`}
                  onClick={() => setTiers((current) => current.filter((_, tierIndex) => tierIndex !== index))}>×</WindowCloseButton>
              </span>
            </div>)}
          </div>
          <button type="button" className="quantity-pricing-add" disabled={tiers.length >= 100} onClick={() => setTiers((current) => [...current, { quantity: String(current.length ? Math.max(2, ...current.map((tier) => Number(tier.quantity) || 1)) + 1 : 2), price: "" }])}>{words.add}</button>
          {attempted && issues.length > 0 && <p role="alert" className="quantity-pricing-error">
            {missingFields ? words.missingFields : issues.map(issue => ({
              empty: words.empty, tooMany: words.tooMany, quantity: words.invalidQuantity,
              price: words.invalidPrice, duplicate: words.duplicate
            })[issue]).join(" ")}
          </p>}
          <fieldset className="quantity-pricing-simulator"><legend>{words.simulate}</legend>
            <label>{words.quantity}<input type="number" min="1" max="10000" step="1" value={quantity} onChange={(event) => setQuantity(event.target.value)} /></label>
            {simulation ? <div aria-live="polite"><strong>{words.total}: {formatQuantityPrice(simulation.total, locale)}</strong>
              {mode === "UNIT_THRESHOLD" && <span>{words.unitPrice}: {formatQuantityPrice(simulation.unitPrice!, locale)}</span>}
              {mode === "PACK" && <span>{words.packs}: {simulation.packs.map((pack) => `${pack.count} × ${pack.quantity}`).join(", ") || "—"}; {words.loose}: {simulation.looseUnits}</span>}
            </div> : issues.length === 0 && <p>{validSimulationQuantity ? words.invalidBase : words.invalidSimulationQuantity}</p>}
          </fieldset>
          {saveHint && <p className="quantity-pricing-hint">{saveHint}</p>}
        </div>
        <footer className="quantity-pricing-footer erp-dialog-actions-row">
          <span className="quantity-pricing-state" data-enabled={enabled}>{enabled ? words.applied : words.notApplied}</span>
          <button type="button" title={words.saveTitle} onClick={() => saveRule(enabled, "save")}>{words.save}</button>
          <button type="button" onClick={() => !value && !dirty ? onClose() : saveRule(false, "doNotApply")}>{words.doNotApply}</button>
          <button type="button" className="erp-dialog-action-confirm" onClick={() => saveRule(true, "apply")}>{words.apply}</button>
        </footer>
      </section>
    </div>
    {discardOpen && <ErpConfirmDialog title={words.discardTitle} message={words.discardMessage} cancelLabel={words.keepEditing} confirmLabel={words.discard} onCancel={() => setDiscardOpen(false)} onConfirm={onClose} />}
  </>, document.body);
}
