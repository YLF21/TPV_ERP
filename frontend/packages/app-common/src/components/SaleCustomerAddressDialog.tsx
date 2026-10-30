import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import type { CustomerContactAddress } from "./customerContactAddresses";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { ensureTableRowVisible } from "./ensureTableRowVisible";
import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import "./ErpClassicWindow.css";
import "./SaleCustomerAddressDialog.css";

type Props = {
  locale: LocaleCode;
  customer: { id: string; clientId?: string | null; fiscalName?: string | null };
  addresses: CustomerContactAddress[];
  onSelect: (address: CustomerContactAddress) => void;
  onCancel: () => void;
};

export function SaleCustomerAddressDialog({ locale, customer, addresses, onSelect, onCancel }: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [highlightedIndex, setHighlightedIndex] = useState(0);
  const selectedIndex = Math.min(highlightedIndex, Math.max(0, addresses.length - 1));
  const selectedAddress = addresses[selectedIndex];

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const release = activateModalFocusTrap(dialog as unknown as ModalFocusRoot, document);
    listRef.current?.querySelector<HTMLElement>("tbody tr")?.focus({ preventScroll: true });
    return release;
  }, []);

  useLayoutEffect(() => {
    const row = listRef.current?.querySelectorAll<HTMLElement>("tbody tr")[selectedIndex];
    if (row && listRef.current) ensureTableRowVisible(listRef.current, row);
  }, [selectedIndex, addresses]);

  function highlight(index: number) {
    const next = Math.max(0, Math.min(addresses.length - 1, index));
    setHighlightedIndex(next);
    listRef.current?.querySelectorAll<HTMLElement>("tbody tr")[next]?.focus({ preventScroll: true });
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onCancel();
      return;
    }
    if (event.nativeEvent.isComposing || event.ctrlKey || event.altKey || event.metaKey) return;
    const row = event.target instanceof HTMLElement ? event.target.closest<HTMLTableRowElement>("tbody tr") : null;
    if (!row || !listRef.current?.contains(row)) return;
    const current = Number(row.dataset.index);
    if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Home" || event.key === "End") {
      event.preventDefault();
      event.stopPropagation();
      if (event.key === "Home") highlight(0);
      else if (event.key === "End") highlight(addresses.length - 1);
      else highlight(current + (event.key === "ArrowDown" ? 1 : -1));
    } else if ((event.key === "Enter" || event.key === "Insert") && !event.repeat) {
      event.preventDefault();
      event.stopPropagation();
      const address = addresses[current];
      if (address) onSelect(address);
    }
  }

  return <div className="sale-customer-address-overlay" role="presentation">
    <section ref={dialogRef} className="erp-classic-window sale-customer-address-dialog" role="dialog"
      aria-modal="true" aria-labelledby="sale-customer-address-title" onKeyDown={handleKeyDown}>
      <header>
        <div>
          <h2 id="sale-customer-address-title">{t("sale.customer.address.title")}</h2>
          <p>{t("sale.customer.address.subtitle")}</p>
        </div>
        <WindowCloseButton aria-label={t("common.close")} onClick={onCancel} />
      </header>
      <div className="sale-customer-address-content">
        {(customer.fiscalName || customer.clientId) && <p className="sale-customer-address-customer">
          {customer.clientId && <span>{customer.clientId}</span>}{customer.fiscalName && <strong>{customer.fiscalName}</strong>}
        </p>}
        <div ref={listRef} className="sale-customer-address-list">
          <table>
            <thead><tr>
              <th scope="col">{t("sale.customer.address.column.address")}</th>
              <th scope="col">{t("sale.customer.address.column.location")}</th>
              <th scope="col">{t("sale.customer.address.column.phone")}</th>
            </tr></thead>
            <tbody>{addresses.map((entry, index) => <tr key={entry.id} data-index={index}
              tabIndex={index === selectedIndex ? 0 : -1} aria-selected={index === selectedIndex}
              className={index === selectedIndex ? "selected" : undefined}
              onFocus={() => setHighlightedIndex(index)}
              onClick={(event) => { setHighlightedIndex(index); event.currentTarget.focus({ preventScroll: true }); }}
              onDoubleClick={() => onSelect(entry)}>
              <td><span>{entry.address.address || "—"}</span>{index === 0 && <small>{t("sale.customer.address.default")}</small>}</td>
              <td><span>{[entry.address.postalCode, entry.address.city].filter(Boolean).join(" ") || "—"}</span>
                {(entry.address.province || entry.address.country) && <small>{[entry.address.province, entry.address.country].filter(Boolean).join(" · ")}</small>}
              </td>
              <td>{entry.phone || "—"}</td>
            </tr>)}</tbody>
          </table>
        </div>
        <p className="sale-customer-address-hint">{t("sale.customer.address.insertHint")}</p>
      </div>
      <footer className="erp-dialog-actions-row">
        <DialogDismissButton className="erp-dialog-action-cancel erp-dialog-dismiss" type="button" onClick={onCancel}>{t("common.close")}</DialogDismissButton>
        <button className="erp-dialog-action-confirm" type="button" disabled={!selectedAddress} onClick={() => selectedAddress && onSelect(selectedAddress)}>
          {t("sale.customer.address.select")}
        </button>
      </footer>
    </section>
  </div>;
}
