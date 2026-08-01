// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { KeyboardSaleCommandBar, TouchSaleActionPanel, type SaleCommandLabels } from "./SaleCommandPresentation";

const labels: SaleCommandLabels = {
  shortcuts: "Atajos",
  priceLookup: "Consulta precio",
  calculator: "Calculadora",
  eanGenerator: "Generador EAN",
  printProductLabel: "Imprimir etiqueta",
  cashDrawer: "Abrir cajón",
  cashWithdrawal: "Retirada de efectivo",
  logout: "Cerrar sesión",
  selectedStock: "Stock de línea",
  productSales: "Ventas del producto",
  editProduct: "Modificar producto",
  ticketReturn: "Devolución por ticket",
  cancelTicket: "Anular último ticket",
  cancelOtherTicket: "Anular ticket por código",
  convertInvoice: "Convertir a factura",
  checkout: "Cobro",
  setQuantity: "Cambiar cantidad",
  selectItem: "Elegir",
  temporaryName: "Nombre temporal",
  desiredPrice: "Precio por descuento",
  temporaryPrice: "Precio temporal",
  printMethod: "Método de impresión",
  saleComment: "Comentario",
  serialNumber: "Número de serie",
  parkSale: "Guardar venta",
  lineDiscount: "Descuento línea",
  saleDiscount: "Descuento compra",
  nextPackage: "Paquetes",
  nextUnits: "Unidades",
  addQuantity: "Sumar cantidad",
  subtractQuantity: "Restar cantidad",
  document: "Factura / albarán",
  search: "Buscar",
  quantity: "Cantidad",
  discount: "Descuento",
  customer: "Cliente",
  removeLine: "Anular línea",
  deleteKey: "Supr",
  parkedSales: "Ventas aparcadas",
  parkedSalesHint: "Guardar o recuperar",
  manageTickets: "Gestionar tickets",
  manageTicketsHint: "Buscar y realizar acciones",
  receivables: "Deudas de clientes",
  cash: "Efectivo",
  card: "Tarjeta",
  pending: "Pendiente cliente",
  pageDownKey: "AvPág",
  operations: "Gestión"
};

describe("sale command presentations", () => {
  afterEach(cleanup);

  it("uses written shortcuts without function buttons in keyboard mode", () => {
    render(
      <KeyboardSaleCommandBar
        labels={labels}
        documentAvailable
      />
    );

    expect(screen.getByRole("navigation", { name: "Atajos" })).toBeTruthy();
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByText("Ctrl+F")).toBeTruthy();
    expect(screen.getByText("F12")).toBeTruthy();
    expect(screen.getByText("Ctrl+-")).toBeTruthy();
    expect(screen.getByText("Devolución por ticket")).toBeTruthy();
    expect(screen.queryByText("F8")).toBeNull();
    expect(screen.queryByText("F9")).toBeNull();
  });

  it("exposes the same operational functions as buttons in touch mode", () => {
    const onSearch = vi.fn();
    const onCashDrawer = vi.fn();
    const onEanGenerator = vi.fn();
    const onPrintProductLabel = vi.fn();
    const onCashWithdrawal = vi.fn();
    const onEditProduct = vi.fn();
    const onDocument = vi.fn();
    const onQuantity = vi.fn();
    const onTemporaryName = vi.fn();
    const onTemporaryPrice = vi.fn();
    render(
      <TouchSaleActionPanel
        labels={labels}
        paymentLocked={false}
        searchDisabled={false}
        quantityDisabled={false}
        editProductDisabled={false}
        serialNumberDisabled={false}
        ticketReturnDisabled={false}
        discountDisabled={false}
        documentAvailable
        receivablesAvailable
        onSearch={onSearch}
        onEanGenerator={onEanGenerator}
        onPrintProductLabel={onPrintProductLabel}
        onCashDrawer={onCashDrawer}
        onCashWithdrawal={onCashWithdrawal}
        onEditProduct={onEditProduct}
        onSerialNumber={vi.fn()}
        onTicketReturn={vi.fn()}
        onDocument={onDocument}
        onQuantity={onQuantity}
        onTemporaryName={onTemporaryName}
        onTemporaryPrice={onTemporaryPrice}
        onDiscount={vi.fn()}
        onCustomer={vi.fn()}
        onRemoveLine={vi.fn()}
        onParkedSales={vi.fn()}
        onCancelLastTicket={vi.fn()}
        onCancelTicket={vi.fn()}
        onConvertTicket={vi.fn()}
        onReceivables={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Buscar" }));
    fireEvent.click(screen.getByRole("button", { name: "Abrir cajón" }));
    fireEvent.click(screen.getByRole("button", { name: "Retirada de efectivo" }));
    fireEvent.click(screen.getByRole("button", { name: "Modificar producto" }));
    fireEvent.click(screen.getByRole("button", { name: "Factura / albarán" }));
    fireEvent.click(screen.getByRole("button", { name: "Cantidad" }));
    fireEvent.click(screen.getByRole("button", { name: "Nombre temporal" }));
    fireEvent.click(screen.getByRole("button", { name: "Precio temporal" }));

    expect(onSearch).toHaveBeenCalledOnce();
    expect(onCashWithdrawal).toHaveBeenCalledOnce();
    expect(onCashDrawer).toHaveBeenCalledOnce();
    expect(onEditProduct).toHaveBeenCalledOnce();
    expect(onDocument).toHaveBeenCalledOnce();
    expect(onQuantity).toHaveBeenCalledOnce();
    expect(onTemporaryName).toHaveBeenCalledOnce();
    expect(onTemporaryPrice).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: /Ventas aparcadas/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /Deudas de clientes/ })).toBeTruthy();
  });
});
