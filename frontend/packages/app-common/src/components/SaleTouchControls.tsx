import { useEffect, useId, useRef } from "react";
import {
  ArrowDown, ArrowUp, ArrowUUpLeft, Barcode, Calculator, DotsThree,
  FileArrowUp, FileText, FileX, Gift, Minus, Note, Pause, PencilSimple,
  Percent, Plus, Printer, Tag, Trash, Wallet, X,
  type Icon,
} from "@phosphor-icons/react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import "./SaleTouchControls.css";

export type SaleTouchAction = {
  id: string;
  label: string;
  disabled?: boolean;
  title?: string;
  onClick: () => void;
};

const icons: Record<string, Icon> = {
  document: FileText,
  calculator: Calculator,
  parked: Pause,
  "parked-sales": Pause,
  return: ArrowUUpLeft,
  "ticket-return": ArrowUUpLeft,
  copy: Printer,
  "print-last-ticket": Printer,
  more: DotsThree,
  previous: ArrowUp,
  next: ArrowDown,
  increase: Plus,
  decrease: Minus,
  quantity: Calculator,
  price: Tag,
  discount: Percent,
  remove: Trash,
  checkout: Wallet,
  "temporary-name": PencilSimple,
  "serial-number": Barcode,
  "sale-discount": Percent,
  "sale-comment": Note,
  "convert-ticket": FileArrowUp,
  "gift-receipt": Gift,
  "clear-lines": Trash,
  "clear-sale": Trash,
  "cancel-ticket": FileX,
};

const destructiveIds = new Set(["remove", "clear-lines", "clear-sale", "cancel-ticket"]);

function TouchAction({ action, slot }: { action: SaleTouchAction; slot?: string }) {
  const ActionIcon = icons[action.id] ?? icons[slot ?? ""] ?? DotsThree;
  const destructive = destructiveIds.has(action.id) || destructiveIds.has(slot ?? "");
  return (
    <button
      type="button"
      className={`sale-touch-button${destructive ? " sale-touch-button-danger" : ""}${slot === "checkout" ? " sale-touch-button-checkout" : ""}`}
      data-touch-action={action.id}
      data-touch-slot={slot}
      disabled={action.disabled}
      title={action.title}
      onClick={action.onClick}
    >
      <ActionIcon aria-hidden="true" focusable="false" weight="bold" />
      <span>{action.label}</span>
    </button>
  );
}

export function TouchSaleTopActions({
  document: documentAction,
  calculator,
}: { document?: SaleTouchAction; calculator: SaleTouchAction }) {
  return (
    <div className="sale-touch-top-actions">
      {documentAction && <TouchAction action={documentAction} slot="document" />}
      <TouchAction action={calculator} slot="calculator" />
    </div>
  );
}

export function TouchSaleSideActions({ parked, returnAction, copy, more }: {
  parked: SaleTouchAction;
  returnAction: SaleTouchAction;
  copy: SaleTouchAction;
  more: SaleTouchAction;
}) {
  return (
    <div className="sale-touch-side-actions">
      <TouchAction action={parked} slot="parked" />
      <TouchAction action={returnAction} slot="return" />
      <TouchAction action={copy} slot="copy" />
      <TouchAction action={more} slot="more" />
    </div>
  );
}

type BottomActionSlot = "previous" | "next" | "increase" | "decrease"
  | "quantity" | "price" | "discount" | "remove" | "checkout";

export function TouchSaleBottomActions(props: Record<BottomActionSlot, SaleTouchAction>) {
  const order: BottomActionSlot[] = [
    "previous", "increase", "quantity", "price", "checkout",
    "next", "decrease", "discount", "remove",
  ];
  return (
    <div className="sale-touch-bottom-actions">
      {order.map((slot) => <TouchAction key={slot} action={props[slot]} slot={slot} />)}
    </div>
  );
}

const optionGroups = [
  { heading: "sale.touch.productDocumentGroup", ids: ["temporary-name", "serial-number", "sale-discount"] },
  { heading: "sale.touch.commentsTicketsGroup", ids: ["sale-comment", "convert-ticket", "gift-receipt"] },
  { heading: "sale.touch.destructiveGroup", ids: ["clear-lines", "clear-sale", "cancel-ticket"] },
] as const;

export function TouchSaleMoreOptionsDialog({ locale, actions, onClose }: {
  locale: LocaleCode;
  actions: readonly SaleTouchAction[];
  onClose: () => void;
}) {
  const t = createTranslator(locale);
  const titleId = useId();
  const dialogRef = useRef<HTMLElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const releaseFocus = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      closeRef.current();
    };
    root.addEventListener("keydown", onKeyDown);
    return () => {
      root.removeEventListener("keydown", onKeyDown);
      releaseFocus();
    };
  }, []);

  return (
    <div className="sale-touch-more-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-touch-more-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={(event) => event.stopPropagation()}
      >
        <header className="sale-touch-more-header">
          <h2 id={titleId}>{t("sale.touch.moreOptions")}</h2>
          <button type="button" className="sale-touch-more-close" aria-label={t("common.close")} onClick={onClose}>
            <X size={28} weight="bold" aria-hidden="true" focusable="false" />
          </button>
        </header>
        <div className="sale-touch-more-groups">
          {optionGroups.map((group, index) => {
            const groupId = `${titleId}-group-${index}`;
            return (
              <section className="sale-touch-more-group" aria-labelledby={groupId} key={group.heading}>
                <h3 id={groupId}>{t(group.heading)}</h3>
                {group.ids.map((id) => {
                  const action = actions.find((item) => item.id === id);
                  return action ? <TouchAction action={action} key={id} /> : null;
                })}
                {index === 0 && actions.some((action) => action.id === "sale-discount") && (
                  <p className="sale-touch-more-help">{t("sale.touch.documentDiscountHint")}</p>
                )}
              </section>
            );
          })}
        </div>
        <footer className="sale-touch-more-footer">
          <span className="sale-touch-more-help">{t("sale.touch.optionsHint")}</span>
          <button type="button" className="sale-touch-button" onClick={onClose}>{t("common.close")}</button>
        </footer>
      </section>
    </div>
  );
}
