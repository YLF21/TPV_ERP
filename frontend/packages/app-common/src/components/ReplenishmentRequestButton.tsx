import { useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import type { LocaleCode } from "../types";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { createReplenishment } from "./replenishmentApi";
import { ShortcutButtonLabel } from "./ShortcutButtonLabel";
import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import { ErpSelect } from "./ErpSelect";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import "./ErpClassicWindow.css";
import "./ReplenishmentRequestButton.css";

type Warehouse = { id: string; name: string; active?: boolean; defaultWarehouse?: boolean; isDefaultWarehouse?: boolean };
type Props = { productId: string; packageQuantity: string | number | null | undefined; warehouseId?: string; token?: string; locale: LocaleCode };

export function ReplenishmentRequestButton({ productId, packageQuantity, warehouseId, token, locale }: Props) {
  const t = createTranslator(locale);
  const initial = Number(packageQuantity);
  const defaultQuantity = String(Number.isFinite(initial) && initial > 0 ? initial : 1);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const quantityRef = useRef<HTMLInputElement>(null);
  const busyRef = useRef(false);
  const [open, setOpen] = useState(false);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [selectedWarehouse, setSelectedWarehouse] = useState(warehouseId || "");
  const [quantity, setQuantity] = useState(defaultQuantity);
  const [reason, setReason] = useState(t("replenishment.reasonRestock"));
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => { if (warehouseId && warehouseId !== "TOTAL") setSelectedWarehouse(warehouseId); }, [warehouseId]);
  useLayoutEffect(() => { setQuantity(defaultQuantity); setReason(t("replenishment.reasonRestock")); setOpen(false); }, [productId]);

  useLayoutEffect(() => {
    if (!open) return;
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "");
    const deactivateTrap = activateModalFocusTrap(dialog as unknown as ModalFocusRoot, document, { restoreFocus: false });
    quantityRef.current?.focus();
    return () => {
      deactivateTrap();
      if (dialog.open && typeof dialog.close === "function") dialog.close();
      else dialog.removeAttribute("open");
      buttonRef.current?.focus();
    };
  }, [open]);

  useEffect(() => {
    if (!open || !token) return;
    let active = true;
    void apiRequest<Warehouse[]>("/warehouses", { token }).then(value => {
      if (!active) return;
      const available = value.filter(item => item.active !== false);
      setWarehouses(available);
      setSelectedWarehouse(current => available.some(item => item.id === current) ? current
        : available.find(item => item.defaultWarehouse || item.isDefaultWarehouse)?.id || available[0]?.id || "");
    }).catch(error => { if (active) setMessage(error instanceof Error ? error.message : t("replenishment.chooseWarehouse")); });
    return () => { active = false; };
  }, [open, token]);

  useLayoutEffect(() => {
    const handleShortcut = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "F9" || event.repeat || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return;
      if (open) { event.preventDefault(); event.stopImmediatePropagation(); return; }
      const button = buttonRef.current;
      if (!button || button.disabled || !button.isConnected) return;
      const modals = Array.from(document.querySelectorAll<HTMLElement>('dialog[open], [aria-modal="true"]'));
      const top = modals.at(-1);
      if (top && !top.contains(button)) return;
      event.preventDefault(); event.stopImmediatePropagation();
      button.click();
    };
    window.addEventListener("keydown", handleShortcut, true);
    return () => window.removeEventListener("keydown", handleShortcut, true);
  }, [open]);

  function close() { if (!busyRef.current) setOpen(false); }
  async function submit(event: FormEvent) {
    event.preventDefault();
    const amount = Number(quantity);
    if (busyRef.current || !token || !selectedWarehouse || !Number.isFinite(amount)
      || amount <= 0 || Number(amount.toFixed(3)) !== amount || !reason.trim()) return;
    busyRef.current = true; setBusy(true); setMessage("");
    try {
      await createReplenishment(token, { productId, warehouseId: selectedWarehouse, quantity: amount, reason: reason.trim() });
      setOpen(false); setMessage(t("replenishment.requested"));
    } catch (error) { setMessage(error instanceof Error ? error.message : t("replenishment.saveError")); }
    finally { busyRef.current = false; setBusy(false); }
  }
  function handleModalKeyDown(event: KeyboardEvent<HTMLDialogElement>) {
    // Portals bubble through the product information dialog in React's event tree.
    event.stopPropagation();
    if (event.key === "Escape" && !event.defaultPrevented) { event.preventDefault(); close(); }
    else if (event.key === "Insert" || event.key === "F9") event.preventDefault();
  }
  const reasonOptions = ["reasonRestock", "reasonCustomer", "reasonSeason", "reasonForecast", "reasonCampaign", "reasonDisplay", "reasonDamage"]
    .map(key => ({ value: t(`replenishment.${key}`), label: t(`replenishment.${key}`) }));

  return <div className="replenishment-request">
    <button ref={buttonRef} className="erp-dialog-action-confirm replenishment-request-trigger" type="button" aria-keyshortcuts="F9" onClick={() => { if (!open) { setMessage(""); setOpen(true); } }}>
      <ShortcutButtonLabel label={t("replenishment.request")} shortcut="F9" />
    </button>
    {!open && message && <p role="status">{message}</p>}
    {open && createPortal(<dialog ref={dialogRef} className="replenishment-request-dialog filter-dialog erp-classic-window" aria-labelledby="replenishment-request-title"
      onCancel={event => { event.preventDefault(); event.stopPropagation(); close(); }} onKeyDown={handleModalKeyDown}>
      <header className="filter-header">
        <h2 id="replenishment-request-title">{t("replenishment.request")}</h2>
        <WindowCloseButton type="button" aria-label={t("replenishment.cancel")} disabled={busy} onClick={close} />
      </header>
      <form onSubmit={event => void submit(event)}>
        <div className="replenishment-request-fields">
          <label>{t("replenishment.warehouse")}
            <ErpSelect aria-label={t("replenishment.warehouse")} value={selectedWarehouse} disabled={busy}
              maxPopoverWidth="trigger" popoverAnchor="control"
              options={warehouses.map(item => ({ value: item.id, label: item.name }))} onChange={setSelectedWarehouse}
              placeholder={t("replenishment.chooseWarehouse")} />
          </label>
          <label>{t("replenishment.quantity")}
            <input ref={quantityRef} type="number" min="0.001" step="0.001" required value={quantity} disabled={busy} onChange={event => setQuantity(event.target.value)} />
            <small>{t("replenishment.package").replace("{count}", defaultQuantity)}</small>
          </label>
          <label>{t("replenishment.reason")}
            <ErpSelect aria-label={t("replenishment.reason")} value={reason} disabled={busy}
              maxPopoverWidth="trigger" popoverAnchor="control"
              editable={{ text: reason, onChange: setReason, label: t("replenishment.reason"), maxLength: 500 }}
              options={reasonOptions} onChange={setReason} />
          </label>
        </div>
        {message && <p className="replenishment-request-error" role="alert">{message}</p>}
        <footer className="erp-dialog-actions-row">
          <DialogDismissButton notice className="erp-dialog-action-cancel erp-dialog-dismiss" type="button" disabled={busy} onClick={close}>{t("replenishment.cancel")}</DialogDismissButton>
          <button type="submit" className="primary erp-dialog-action-confirm" disabled={busy || !selectedWarehouse || !reason.trim()}>{t("replenishment.request")}</button>
        </footer>
      </form>
    </dialog>, document.body)}
  </div>;
}
