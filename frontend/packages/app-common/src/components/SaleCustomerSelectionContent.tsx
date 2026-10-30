import type { ComponentProps, ReactNode } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import { DialogDismissButton } from "./DialogDismissButton";
import { SaleCustomerList } from "./SaleCustomerList";
import type { SaleCustomer } from "./SaleScreen";

type Props = ComponentProps<typeof SaleCustomerList> & {
  selectedCustomer: SaleCustomer | null | undefined;
  onCreate?: () => void;
  onEdit?: () => void;
  onCollectDebt?: () => void;
  onClose: () => void;
  children?: ReactNode;
};

/** Shared customer list, actions and keyboard hints used by the sales window. */
export function SaleCustomerSelectionContent({ selectedCustomer, onCreate, onEdit, onCollectDebt,
  onClose, children, ...list }: Props) {
  const t = createTranslator(list.locale);
  return <>
    <div className="sale-customer-action-bar" aria-label={t("sale.customer.actions")}>
      {onCreate && <button type="button" onClick={onCreate}>
        <span>{t("sale.customer.create")}</span><kbd>F5</kbd>
      </button>}
      {onEdit && <button type="button" disabled={!selectedCustomer} onClick={onEdit}>
        <span>{t("sale.customer.edit")}</span><kbd>Ctrl+F7</kbd>
      </button>}
      {onCollectDebt && <button type="button" className="debt-action"
        disabled={!selectedCustomer || Number(selectedCustomer.outstandingDebt ?? 0) <= 0} onClick={onCollectDebt}>
        <span>{t("sale.customer.collectDebt")}</span><kbd>Enter</kbd>
      </button>}
    </div>
    <SaleCustomerList {...list} />
    {children}
    <footer className="sale-customer-selection-footer erp-dialog-actions-row">
      <DialogDismissButton className="erp-dialog-action-cancel erp-dialog-dismiss" type="button" onClick={onClose}>
        {t("sale.dialog.close")}
      </DialogDismissButton>
      <p className="sale-dialog-hint erp-dialog-actions-group"><kbd>Insert</kbd> {t("sale.customer.insertHint")}</p>
      <div className="sale-action-buttons erp-dialog-actions-group">
        <button className="erp-dialog-action-confirm" type="button" disabled={selectedCustomer === undefined}
          onClick={() => { if (selectedCustomer !== undefined) list.onActivate(selectedCustomer); }}>
          {t("sale.customer.select")}
        </button>
      </div>
    </footer>
  </>;
}
