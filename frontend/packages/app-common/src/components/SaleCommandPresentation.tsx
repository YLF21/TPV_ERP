type SaleCommandLabels = {
  shortcuts: string;
  priceLookup: string;
  calculator: string;
  eanGenerator: string;
  printProductLabel: string;
  cashDrawer: string;
  cashWithdrawal: string;
  logout: string;
  selectedStock: string;
  productSales: string;
  editProduct: string;
  ticketReturn: string;
  cancelTicket: string;
  cancelOtherTicket: string;
  convertInvoice: string;
  checkout: string;
  setQuantity: string;
  selectItem: string;
  temporaryName: string;
  desiredPrice: string;
  temporaryPrice: string;
  printMethod: string;
  saleComment: string;
  serialNumber: string;
  parkSale: string;
  lineDiscount: string;
  saleDiscount: string;
  nextPackage: string;
  nextUnits: string;
  addQuantity: string;
  subtractQuantity: string;
  document: string;
  search: string;
  quantity: string;
  discount: string;
  customer: string;
  removeLine: string;
  deleteKey: string;
  parkedSales: string;
  parkedSalesHint: string;
  manageTickets: string;
  manageTicketsHint: string;
  receivables: string;
  cash: string;
  card: string;
  pending: string;
  pageDownKey: string;
  operations: string;
};

type KeyboardSaleCommandBarProps = {
  labels: SaleCommandLabels;
  documentAvailable: boolean;
};

export function KeyboardSaleCommandBar({
  labels,
  documentAvailable
}: KeyboardSaleCommandBarProps) {
  return (
    <nav className="sale-shortcut-bar keyboard-sale-command-bar" aria-label={labels.shortcuts}>
      <span><kbd>F1</kbd> {labels.priceLookup}</span>
      <span><kbd>F2</kbd> {labels.calculator}</span>
      <span><kbd>F3</kbd> {labels.cashDrawer}</span>
      <span><kbd>F4</kbd> {labels.logout}</span>
      <span><kbd>F5</kbd> {labels.selectedStock}</span>
      <span><kbd>F6</kbd> {labels.productSales}</span>
      <span><kbd>F7</kbd> {labels.editProduct}</span>
      <span><kbd>F10</kbd> {labels.ticketReturn}</span>
      <span><kbd>F11</kbd> {labels.cancelTicket}</span>
      <span><kbd>Ctrl+F11</kbd> {labels.cancelOtherTicket}</span>
      <span><kbd>F12</kbd> {labels.convertInvoice}</span>
      <span><kbd>{labels.pageDownKey}</kbd> {labels.checkout}</span>
      <span><kbd>Pausa</kbd> {labels.setQuantity}</span>
      <span><kbd>Fin</kbd> {labels.customer}</span>
      <span><kbd>Insert</kbd> {labels.selectItem}</span>
      <span><kbd>Inicio</kbd> {labels.temporaryName}</span>
      <span><kbd>RePág</kbd> {labels.desiredPrice}</span>
      <span><kbd>Ctrl+RePág</kbd> {labels.temporaryPrice}</span>
      {documentAvailable && <span><kbd>Ctrl+F</kbd> {labels.document}</span>}
      <span><kbd>Ctrl+P</kbd> {labels.printMethod}</span>
      <span><kbd>Ctrl+O</kbd> {labels.saleComment}</span>
      <span><kbd>Ctrl+N</kbd> {labels.serialNumber}</span>
      <span><kbd>Ctrl+G</kbd> {labels.parkSale}</span>
      <span><kbd>/</kbd> {labels.lineDiscount}</span>
      <span><kbd>Ctrl+/</kbd> {labels.saleDiscount}</span>
      <span><kbd>*</kbd> {labels.nextPackage}</span>
      <span><kbd>+</kbd> {labels.nextUnits}</span>
      <span><kbd>Ctrl++</kbd> {labels.addQuantity}</span>
      <span><kbd>Ctrl+-</kbd> {labels.subtractQuantity}</span>
    </nav>
  );
}

type TouchSaleActionPanelProps = {
  labels: SaleCommandLabels;
  paymentLocked: boolean;
  searchDisabled: boolean;
  quantityDisabled: boolean;
  temporaryNameDisabled?: boolean;
  temporaryPriceDisabled?: boolean;
  editProductDisabled: boolean;
  serialNumberDisabled: boolean;
  ticketReturnDisabled: boolean;
  discountDisabled: boolean;
  discountTitle?: string;
  documentAvailable: boolean;
  receivablesAvailable: boolean;
  receivablesCustomer?: string;
  onSearch: () => void;
  onEanGenerator: () => void;
  onPrintProductLabel: () => void;
  onCashDrawer: () => void;
  onCashWithdrawal: () => void;
  onEditProduct: () => void;
  onSerialNumber: () => void;
  onTicketReturn: () => void;
  onDocument: () => void;
  onQuantity: () => void;
  onTemporaryName?: () => void;
  onTemporaryPrice?: () => void;
  onDiscount: () => void;
  onCustomer: () => void;
  onRemoveLine: () => void;
  onParkedSales: () => void;
  onCancelLastTicket: () => void;
  onCancelTicket: () => void;
  onConvertTicket: () => void;
  onReceivables: () => void;
};

export function TouchSaleActionPanel({
  labels,
  paymentLocked,
  searchDisabled,
  quantityDisabled,
  temporaryNameDisabled = quantityDisabled,
  temporaryPriceDisabled = quantityDisabled,
  editProductDisabled,
  serialNumberDisabled,
  ticketReturnDisabled,
  discountDisabled,
  discountTitle,
  documentAvailable,
  receivablesAvailable,
  receivablesCustomer,
  onSearch,
  onEanGenerator,
  onPrintProductLabel,
  onCashDrawer,
  onCashWithdrawal,
  onEditProduct,
  onSerialNumber,
  onTicketReturn,
  onDocument,
  onQuantity,
  onTemporaryName,
  onTemporaryPrice,
  onDiscount,
  onCustomer,
  onRemoveLine,
  onParkedSales,
  onCancelLastTicket,
  onCancelTicket,
  onConvertTicket,
  onReceivables
}: TouchSaleActionPanelProps) {
  return (
    <section className="touch-sale-actions" aria-label={labels.operations}>
      <h2>{labels.operations}</h2>
      <div className="touch-sale-action-grid">
        <button type="button" disabled={searchDisabled} onClick={onSearch}>{labels.search}</button>
        <button type="button" disabled={paymentLocked} onClick={onEanGenerator}>{labels.eanGenerator}</button>
        <button type="button" disabled={paymentLocked} onClick={onPrintProductLabel}>{labels.printProductLabel}</button>
        <button type="button" onClick={onCashDrawer}>{labels.cashDrawer}</button>
        <button type="button" disabled={paymentLocked} onClick={onCashWithdrawal}>{labels.cashWithdrawal}</button>
        <button type="button" disabled={editProductDisabled} onClick={onEditProduct}>{labels.editProduct}</button>
        <button type="button" disabled={serialNumberDisabled} onClick={onSerialNumber}>{labels.serialNumber}</button>
        <button type="button" disabled={ticketReturnDisabled} onClick={onTicketReturn}>{labels.ticketReturn}</button>
        {documentAvailable && (
          <button type="button" disabled={paymentLocked} onClick={onDocument}>{labels.document}</button>
        )}
        <button type="button" disabled={quantityDisabled} onClick={onQuantity}>{labels.quantity}</button>
        {onTemporaryName && (
          <button type="button" disabled={temporaryNameDisabled} onClick={onTemporaryName}>
            {labels.temporaryName}
          </button>
        )}
        {onTemporaryPrice && (
          <button type="button" disabled={temporaryPriceDisabled} onClick={onTemporaryPrice}>
            {labels.temporaryPrice}
          </button>
        )}
        <button
          type="button"
          disabled={discountDisabled}
          title={discountTitle}
          onClick={onDiscount}
        >
          {labels.discount}
        </button>
        <button type="button" disabled={paymentLocked} onClick={onCustomer}>{labels.customer}</button>
        <button type="button" disabled={quantityDisabled} onClick={onRemoveLine}>{labels.removeLine}</button>
        <button type="button" disabled={paymentLocked} onClick={onParkedSales}>
          <span>{labels.parkedSales}</span>
          <small>{labels.parkedSalesHint}</small>
        </button>
        <button type="button" disabled={paymentLocked} onClick={onCancelLastTicket}>
          {labels.cancelTicket}
        </button>
        <button type="button" disabled={paymentLocked} onClick={onCancelTicket}>
          {labels.cancelOtherTicket}
        </button>
        <button type="button" disabled={paymentLocked} onClick={onConvertTicket}>
          {labels.convertInvoice}
        </button>
        {receivablesAvailable && (
          <button
            type="button"
            className="touch-sale-receivables"
            disabled={paymentLocked}
            onClick={onReceivables}
          >
            <span>{labels.receivables}</span>
            {receivablesCustomer && <small>{receivablesCustomer}</small>}
          </button>
        )}
      </div>
    </section>
  );
}

export type { SaleCommandLabels };
