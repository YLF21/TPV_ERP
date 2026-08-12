// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { TerminalContext, UserSession } from "../types";
import { SalesDocumentScreen } from "./SalesDocumentScreen";

const { apiRequest } = vi.hoisted(() => ({ apiRequest: vi.fn() }));

vi.mock("../api/client", () => ({
  apiRequest,
  ApiError: class ApiError extends Error {
    constructor(message: string, readonly status: number) {
      super(message);
    }
  },
}));

const session: UserSession = {
  username: "seller",
  displayName: "Vendedor",
  permissions: ["VENTA"],
  accessToken: "token",
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01",
};

function directOperation(code: string, permissions: string[] = ["VENTA"]) {
  return {
    code,
    category: "SALE",
    shortcuts: [],
    permissions,
    defaultRequirePermission: false,
    defaultRequirePassword: false,
    requirePermission: false,
    requirePassword: false,
    customized: false,
  };
}

const documentOperationSecurity = {
  storeId: "store-1",
  version: 1,
  operations: [
    "CREATE_PENDING_RECEIVABLE",
    "CREDIT_OVERRIDE",
    "CONFIRM_TRANSFER_PAYMENT",
    "CONFIRM_MANUAL_CARD_PAYMENT",
    "TEMPORARY_NAME",
    "TEMPORARY_PRICE_CHANGE",
    "OPEN_PRICE_PRODUCT",
    "APPLY_SALE_DISCOUNT",
  ].map((code) => directOperation(code)),
};

function configureDocumentApi(
  products: Array<Record<string, unknown>>,
  savedBodies: unknown[] = [],
  operationSecurity = documentOperationSecurity,
  customers: Array<Record<string, unknown>> = [{
    id: "customer-1",
    clientId: "C-001",
    fiscalName: "Cliente Fiscal SL",
    documentNumber: "B11111111",
    paymentTermDays: 30,
  }],
) {
  apiRequest.mockImplementation((path: string, options?: { body?: unknown }) => {
    if (path === "/sales/operation-security") {
      return Promise.resolve(operationSecurity);
    }
    if (path === "/terminal-configuration/payment") {
      return Promise.resolve({
        rules: { cardManualEnabled: true, integratedCardEnabled: false },
        configuration: { provider: "NONE", enabled: false },
      });
    }
    if (path === "/products/sale") return Promise.resolve(products);
    if (path.startsWith("/products/")) {
      const productId = decodeURIComponent(path.slice("/products/".length));
      return Promise.resolve(products.find((product) => product.id === productId));
    }
    if (path.startsWith("/stock?productId=")) return Promise.resolve([]);
    if (path === "/families" || path === "/taxes/selectable") return Promise.resolve([]);
    if (path === "/customers/sale-options") return Promise.resolve(customers);
    if (path === "/warehouses") return Promise.resolve([{
      id: "warehouse-1",
      active: true,
      defaultWarehouse: true,
    }]);
    if (path === "/payment-methods") return Promise.resolve([{
      id: "cash-method",
      name: "EFECTIVO",
      active: true,
    }]);
    if (path === "/pos/sales-document-checkouts/quote") {
      const body = options?.body as {
        lines?: Array<{ cantidad: number; precioUnitario: string; descuento: string }>;
      };
      const total = (body.lines ?? []).reduce((sum, line) => (
        sum + line.cantidad * Number(line.precioUnitario) * (1 - Number(line.descuento) / 100)
      ), 0);
      return Promise.resolve({ total: total.toFixed(2) });
    }
    if (path === "/pos/sale-operation-authorizations/temporary-price") {
      return Promise.resolve({
        token: "temporary-price-proof",
        expiresAt: new Date(Date.now() + 20 * 60_000).toISOString(),
        policyVersion: 1,
      });
    }
    if (path === "/pos/sales-document-checkouts") {
      savedBodies.push(options?.body);
      return Promise.resolve({
        document: { id: "draft-shortcuts" },
        printDocument: null,
      });
    }
    return Promise.reject(new Error(`unexpected request ${path}`));
  });
}

afterEach(() => {
  cleanup();
  localStorage.clear();
  apiRequest.mockReset();
});

describe("SalesDocumentScreen", () => {
  it("starts blank, quotes authoritatively and saves an independent invoice draft", async () => {
    apiRequest.mockImplementation((path: string, options?: { body?: unknown }) => {
      if (path === "/products/sale") return Promise.resolve([{
        id: "product-1",
        code: "P-001",
        barcode: "840000000001",
        name: "Producto fiscal",
        salePrice: 10,
        active: true,
        taxesIncluded: true,
        taxRegime: "IVA",
        taxPercentage: 21,
      }]);
      if (path === "/customers/sale-options") return Promise.resolve([{
        id: "customer-1",
        clientId: "C-001",
        fiscalName: "Cliente Fiscal SL",
        documentNumber: "B11111111",
      }]);
      if (path === "/warehouses") return Promise.resolve([{
        id: "warehouse-1",
        active: true,
        defaultWarehouse: true,
      }]);
      if (path === "/pos/sales-document-checkouts/quote") {
        expect(options?.body).toMatchObject({
          type: "FACTURA_VENTA",
          customerId: "customer-1",
          completionMode: "DRAFT",
          payments: [],
        });
        return Promise.resolve({ total: "10.00" });
      }
      if (path === "/pos/sales-document-checkouts") {
        expect(options?.body).toMatchObject({
          type: "FACTURA_VENTA",
          customerId: "customer-1",
          warehouseId: "warehouse-1",
          completionMode: "DRAFT",
          quotedTotal: "10.00",
          payments: [],
        });
        return Promise.resolve({ document: { id: "draft-1" }, printDocument: null });
      }
      return Promise.reject(new Error(`unexpected request ${path}`));
    });

    render(
      <SalesDocumentScreen
        locale="es"
        session={session}
        terminalContext={terminalContext}
      />,
    );

    expect(screen.getByText("Añade productos por código o abre el buscador.")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: /seleccionar cliente/i }));
    const customerDialog = screen.getByRole("dialog", { name: /seleccionar cliente/i });
    fireEvent.doubleClick(within(customerDialog).getByRole("option", {
      name: /cliente fiscal sl/i,
    }));

    const quickEntry = screen.getByLabelText(/entrada rápida/i);
    fireEvent.change(quickEntry, { target: { value: "P-001" } });
    fireEvent.submit(quickEntry.closest("form")!);

    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(
      "/pos/sales-document-checkouts/quote",
      expect.any(Object),
    ));
    await waitFor(() => expect(
      screen.getByRole("button", { name: "Guardar borrador" }),
    ).toBeEnabled());
    const finalActions = document.querySelector<HTMLElement>(".sales-document-final-actions")!;
    expect(within(finalActions).getAllByRole("button")).toHaveLength(2);
    expect(within(finalActions).queryByRole("button", { name: /pendiente de pago/i }))
      .not.toBeInTheDocument();
    expect(within(finalActions).getByRole("button", { name: /confirmar y cobrar/i }))
      .toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Guardar borrador" }));

    expect(await screen.findByText("Borrador guardado")).toBeInTheDocument();
    expect(screen.getByText("Añade productos por código o abre el buscador.")).toBeInTheDocument();
  });

  it("opens the full product finder when the quick code is unknown", async () => {
    apiRequest.mockImplementation((path: string) => {
      if (path === "/products/sale") return Promise.resolve([{
        id: "product-1",
        code: "P-001",
        name: "Café molido",
        salePrice: 10,
        active: true,
      }]);
      if (path === "/customers/sale-options") return Promise.resolve([]);
      if (path === "/warehouses") return Promise.resolve([{ id: "warehouse-1", active: true }]);
      return Promise.reject(new Error(`unexpected request ${path}`));
    });

    render(
      <SalesDocumentScreen
        locale="es"
        session={session}
        terminalContext={terminalContext}
      />,
    );

    const quickEntry = await screen.findByLabelText(/entrada rápida/i);
    fireEvent.change(quickEntry, { target: { value: "Café" } });
    fireEvent.submit(quickEntry.closest("form")!);

    expect(screen.getByRole("dialog", { name: /buscador de productos/i })).toBeInTheDocument();
  });

  it("imports a persisted draft with the keyboard and updates the same document", async () => {
    const updatedBodies: unknown[] = [];
    apiRequest.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
      if (path === "/sales/operation-security") {
        return Promise.resolve(documentOperationSecurity);
      }
      if (path === "/terminal-configuration/payment") {
        return Promise.resolve({
          rules: { cardManualEnabled: true, integratedCardEnabled: false },
          configuration: { provider: "NONE", enabled: false },
        });
      }
      if (path === "/products/sale") return Promise.resolve([{
        id: "product-1",
        code: "P-001",
        barcode: "840000000001",
        name: "Producto fiscal",
        salePrice: 20,
        active: true,
        taxesIncluded: true,
        taxRegime: "IVA",
        taxPercentage: 21,
      }]);
      if (path === "/customers/sale-options") return Promise.resolve([{
        id: "customer-2",
        clientId: "C-002",
        fiscalName: "Cliente Borrador SL",
        documentNumber: "B22222222",
        paymentTermDays: 30,
      }]);
      if (path === "/warehouses") return Promise.resolve([{
        id: "warehouse-1",
        active: true,
        defaultWarehouse: true,
      }]);
      if (path === "/pos/sales-document-drafts") return Promise.resolve([{
        id: "draft-1",
        version: 2,
        type: "ALBARAN_VENTA",
        date: "2026-08-10",
        customerId: "customer-1",
        customerName: "Cliente anterior",
        total: "5.00",
        createdAt: "2026-08-10T09:00:00Z",
      }, {
        id: "draft-2",
        version: 7,
        type: "FACTURA_VENTA",
        date: "2026-08-11",
        customerId: "customer-2",
        customerName: "Cliente Borrador SL",
        total: "19.00",
        createdAt: "2026-08-11T10:00:00Z",
      }]);
      if (path === "/pos/sales-document-drafts/draft-2" && options?.method === "PUT") {
        updatedBodies.push(options.body);
        return Promise.resolve({ document: { id: "draft-2" }, printDocument: null });
      }
      if (path === "/pos/sales-document-drafts/draft-2") return Promise.resolve({
        id: "draft-2",
        version: 7,
        type: "FACTURA_VENTA",
        date: "2026-08-11",
        dueDate: "2026-09-10",
        warehouseId: "warehouse-1",
        customerId: "customer-2",
        customerName: "Cliente Borrador SL",
        globalDiscount: "5.00",
        total: "19.00",
        internalComment: "Conservar observación interna",
        createdAt: "2026-08-11T10:00:00Z",
        lines: [{
          id: "line-2",
          productId: "product-1",
          position: 1,
          quantity: "1",
          code: "P-001",
          barcode: "840000000001",
          name: "Producto fiscal",
          rate: null,
          unitPrice: "20.00",
          discount: "0.00",
          taxesIncluded: true,
          taxRegime: "IVA",
          taxPercentage: "21.00",
          serialNumbers: [],
          temporaryNameOverride: false,
          temporaryPriceOverride: false,
        }],
      });
      if (path === "/pos/sales-document-drafts/draft-2/quote") {
        expect(options?.body).toMatchObject({
          draftVersion: 7,
          globalDiscount: "5.00",
          internalComment: "Conservar observación interna",
          lines: [{ cartLineId: "line-2" }],
        });
        return Promise.resolve({ total: "19.00" });
      }
      return Promise.reject(new Error(`unexpected request ${path}`));
    });

    render(<SalesDocumentScreen
      locale="es"
      session={session}
      terminalContext={terminalContext}
    />);

    fireEvent.click(await screen.findByRole("button", { name: "Importar borrador" }));
    const dialog = await screen.findByRole("dialog", { name: "Importar borrador" });
    const searchInput = within(dialog).getByRole("textbox", { name: "Buscar borrador" });
    const options = await within(dialog).findAllByRole("option");
    expect(options[0]).toHaveAttribute("aria-selected", "true");
    expect(within(dialog).queryByText("draft-1")).not.toBeInTheDocument();
    expect(within(dialog).queryByText("draft-2")).not.toBeInTheDocument();

    fireEvent.keyDown(searchInput, { key: "ArrowDown" });
    expect(options[1]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(searchInput, { key: "Enter" });

    expect(await screen.findByText("Borrador importado")).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Importar borrador" }))
      .not.toBeInTheDocument();
    expect(screen.getByText("Producto fiscal")).toBeVisible();
    expect(screen.getByRole("button", { name: /cliente borrador sl/i })).toBeVisible();
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(
      "/pos/sales-document-drafts/draft-2/quote",
      expect.any(Object),
    ));

    const save = screen.getByRole("button", { name: "Guardar borrador" });
    await waitFor(() => expect(save).toBeEnabled());
    fireEvent.click(save);

    await waitFor(() => expect(updatedBodies).toHaveLength(1));
    expect(updatedBodies[0]).toMatchObject({
      draftVersion: 7,
      date: "2026-08-11",
      dueDate: "2026-09-10",
      globalDiscount: "5.00",
      internalComment: "Conservar observación interna",
      completionMode: "DRAFT",
      lines: [{
        productoId: "product-1",
        cartLineId: "line-2",
      }],
    });
    expect(await screen.findByText("Borrador guardado")).toBeInTheDocument();
  });

  it("starts checkout with a new id after a draft save response is lost", async () => {
    const draftSaveBodies: Array<{ checkoutId: string }> = [];
    const checkoutBodies: Array<{ checkoutId: string; completionMode: string }> = [];
    apiRequest.mockImplementation((path: string, options?: {
      method?: string;
      body?: unknown;
    }) => {
      if (path === "/sales/operation-security") {
        return Promise.resolve(documentOperationSecurity);
      }
      if (path === "/terminal-configuration/payment") {
        return Promise.resolve({
          rules: { cardManualEnabled: true, integratedCardEnabled: false },
          configuration: { provider: "NONE", enabled: false },
        });
      }
      if (path === "/products/sale") return Promise.resolve([{
        id: "product-1",
        code: "P-001",
        name: "Producto fiscal",
        salePrice: 22.10,
        active: true,
        taxesIncluded: true,
        taxRegime: "IVA",
        taxPercentage: 21,
      }]);
      if (path === "/customers/sale-options") return Promise.resolve([{
        id: "customer-1",
        clientId: "C-001",
        fiscalName: "Cliente Fiscal SL",
        documentNumber: "B11111111",
        paymentTermDays: 30,
      }]);
      if (path === "/warehouses") return Promise.resolve([{
        id: "warehouse-1",
        active: true,
        defaultWarehouse: true,
      }]);
      if (path === "/payment-methods") return Promise.resolve([{
        id: "cash-method",
        name: "EFECTIVO",
        active: true,
      }]);
      if (path.endsWith("/quote")) {
        return Promise.resolve({
          total: "22.10",
          credit: {
            enabled: true,
            blocked: false,
            outstandingDebt: "0.00",
            overdueDebt: "0.00",
            limit: null,
            availableCredit: null,
          },
        });
      }
      if (path === "/pos/sales-document-checkouts" && options?.method !== "PUT") {
        const body = options?.body as { checkoutId: string; completionMode: string };
        if (body.completionMode === "DRAFT") {
          draftSaveBodies.push(body);
          return Promise.reject(new Error("Respuesta del guardado perdida"));
        }
        checkoutBodies.push(body);
        return Promise.resolve({
          document: { id: "invoice-1", numero: "FV-001-26-000001" },
          printDocument: null,
        });
      }
      return Promise.reject(new Error(`unexpected request ${path}`));
    });

    render(<SalesDocumentScreen
      locale="es"
      session={session}
      terminalContext={terminalContext}
    />);

    fireEvent.click(await screen.findByRole("button", { name: /seleccionar cliente/i }));
    fireEvent.doubleClick(within(screen.getByRole("dialog", {
      name: /seleccionar cliente/i,
    })).getByRole("option", { name: /cliente fiscal sl/i }));
    const quickEntry = screen.getByLabelText(/entrada.*c.digo/i);
    fireEvent.change(quickEntry, { target: { value: "P-001" } });
    fireEvent.submit(quickEntry.closest("form")!);

    const save = screen.getByRole("button", { name: "Guardar borrador" });
    await waitFor(() => expect(save).toBeEnabled());
    fireEvent.click(save);
    expect(await screen.findByText("Respuesta del guardado perdida")).toBeVisible();
    expect(draftSaveBodies).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: /confirmar y cobrar/i }));
    const payment = await screen.findByRole("dialog", { name: "COBRO" });
    await waitFor(() => expect(within(payment).getByRole("button", {
      name: "ACEPTAR",
    })).toBeEnabled());
    fireEvent.keyDown(window, { key: "F8" });
    await waitFor(() => expect(within(payment).getByRole("button", {
      name: /pendiente/i,
    })).toHaveClass("selected"));
    fireEvent.click(within(payment).getByRole("button", { name: "ACEPTAR" }));

    await waitFor(() => expect(checkoutBodies).toHaveLength(1));
    expect(checkoutBodies[0]).toMatchObject({ completionMode: "CONFIRM_PENDING" });
    expect(checkoutBodies[0].checkoutId).not.toBe(draftSaveBodies[0].checkoutId);
  });

  it("sorts, reorders, resizes and aligns the document line columns", async () => {
    configureDocumentApi([{
      id: "product-z",
      code: "P-002",
      name: "Producto zeta",
      salePrice: 2,
      active: true,
      taxesIncluded: true,
      taxRegime: "IVA",
      taxPercentage: 21,
    }, {
      id: "product-a",
      code: "P-001",
      name: "Producto alfa",
      salePrice: 1,
      active: true,
      taxesIncluded: true,
      taxRegime: "IVA",
      taxPercentage: 21,
    }]);

    render(<SalesDocumentScreen
      locale="es"
      session={session}
      terminalContext={terminalContext}
    />);

    const quickEntry = await screen.findByLabelText(/entrada.*c.digo/i);
    for (const code of ["P-002", "P-001"]) {
      fireEvent.change(quickEntry, { target: { value: code } });
      fireEvent.submit(quickEntry.closest("form")!);
    }

    const table = screen.getByRole("table", { name: /l.neas del documento/i });
    const lineRows = () => Array.from(
      table.querySelectorAll<HTMLTableRowElement>("tbody tr[data-sales-document-line-id]"),
    );
    expect(lineRows()[0]).toHaveTextContent("Producto zeta");
    expect(lineRows()[1]).toHaveTextContent("Producto alfa");

    fireEvent.click(within(table).getByRole("button", { name: "Ordenar por Nombre" }));
    expect(lineRows()[0]).toHaveTextContent("Producto alfa");
    expect(lineRows()[1]).toHaveTextContent("Producto zeta");

    const quantityHeader = within(table).getByRole("columnheader", { name: /cantidad/i });
    fireEvent.keyDown(quantityHeader, { key: "ArrowLeft", ctrlKey: true });
    expect(Array.from(table.querySelectorAll("thead th"), (header) => (
      header.getAttribute("data-column-key")
    )).slice(0, 3)).toEqual(["code", "quantity", "name"]);
    expect(Array.from(lineRows()[0].cells, (cell) => (
      cell.getAttribute("data-column-key")
    )).slice(0, 3)).toEqual(["code", "quantity", "name"]);

    const codeResizer = within(table).getByRole("button", {
      name: "Modificar ancho Código",
    });
    fireEvent.keyDown(codeResizer, { key: "ArrowRight" });
    expect(table.querySelector('col[data-column-key="code"]')).toHaveStyle({ width: "158px" });

    const firstLine = lineRows()[0];
    expect(firstLine.querySelector('[data-column-key="code"]'))
      .not.toHaveClass("sales-document-line-number");
    for (const column of ["quantity", "price", "discount", "total"]) {
      expect(firstLine.querySelector(`[data-column-key="${column}"]`))
        .toHaveClass("sales-document-line-number");
    }
  });

  it("supports the operational SaleScreen shortcuts in the Ctrl+F document window", async () => {
    configureDocumentApi([{
      id: "product-1",
      code: "P-001",
      name: "Producto uno",
      salePrice: 10,
      active: true,
      taxesIncluded: true,
      taxRegime: "IVA",
      taxPercentage: 21,
    }, {
      id: "product-2",
      code: "P-002",
      name: "Producto dos",
      salePrice: 5,
      active: true,
      taxesIncluded: true,
      taxRegime: "IVA",
      taxPercentage: 21,
    }, {
      id: "product-3",
      code: "P-003",
      name: "Producto por paquete",
      salePrice: 2,
      packageQuantity: 6,
      active: true,
      taxesIncluded: true,
      taxRegime: "IVA",
      taxPercentage: 21,
    }], [], documentOperationSecurity, [{
      id: "customer-1",
      clientId: "C-001",
      fiscalName: "Cliente Fiscal SL",
      documentNumber: "B11111111",
      paymentTermDays: 30,
    }, {
      id: "customer-2",
      clientId: "C-002",
      fiscalName: "Cliente Dos SL",
      documentNumber: "B22222222",
      paymentTermDays: 15,
    }]);

    render(<SalesDocumentScreen
      locale="es"
      session={session}
      terminalContext={terminalContext}
    />);

    const quickEntry = await screen.findByLabelText(/entrada.*c.digo/i);
    fireEvent.keyDown(window, { key: "End" });
    const customerDialog = screen.getByRole("dialog", { name: /seleccionar cliente/i });
    const customerSearch = within(customerDialog).getByRole("textbox", {
      name: /buscar cliente/i,
    });
    const firstCustomer = within(customerDialog).getByRole("option", {
      name: /cliente fiscal sl/i,
    });
    const secondCustomer = within(customerDialog).getByRole("option", {
      name: /cliente dos sl/i,
    });
    expect(firstCustomer).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(customerSearch, { key: "ArrowDown" });
    expect(secondCustomer).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(customerSearch, { key: "Insert" });
    expect(screen.queryByRole("dialog", { name: /seleccionar cliente/i }))
      .not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /cliente.*dos sl/i })).toBeVisible();

    fireEvent.change(quickEntry, { target: { value: "P-001" } });
    fireEvent.submit(quickEntry.closest("form")!);
    let firstLine = screen.getByText("Producto uno").closest("tr") as HTMLElement;
    expect(firstLine).toHaveClass("selected");
    expect(within(firstLine).queryByRole("button", { name: /cantidad/i }))
      .not.toBeInTheDocument();
    const lineHeader = document.querySelector<HTMLElement>(".sales-document-line-head")!;
    expect(within(lineHeader).getByText("Descuento")).toBeVisible();
    expect(within(lineHeader).queryByText("Acciones")).not.toBeInTheDocument();
    expect(screen.getByText(/^Cantidad: 1$/)).toBeVisible();

    fireEvent.change(quickEntry, { target: { value: "3" } });
    fireEvent.keyDown(quickEntry, { key: "Pause" });
    expect(within(firstLine).getByText("3")).toBeInTheDocument();

    fireEvent.change(quickEntry, { target: { value: "2" } });
    fireEvent.keyDown(quickEntry, { key: "+", code: "NumpadAdd", ctrlKey: true });
    expect(within(firstLine).getByText("5")).toBeInTheDocument();
    fireEvent.change(quickEntry, { target: { value: "1" } });
    fireEvent.keyDown(quickEntry, { key: "-", code: "NumpadSubtract", ctrlKey: true });
    expect(within(firstLine).getByText("4")).toBeInTheDocument();

    fireEvent.change(quickEntry, { target: { value: "2" } });
    fireEvent.keyDown(quickEntry, { key: "+", code: "NumpadAdd" });
    expect(screen.getByText(/^Cantidad: 2$/)).toBeVisible();
    fireEvent.change(quickEntry, { target: { value: "P-002" } });
    fireEvent.submit(quickEntry.closest("form")!);
    const secondLine = screen.getByText("Producto dos").closest("tr") as HTMLElement;
    expect(within(secondLine).getByText("2")).toBeInTheDocument();
    expect(secondLine).toHaveClass("selected");
    fireEvent.keyDown(window, { key: "ArrowUp" });
    expect(firstLine).toHaveClass("selected");

    fireEvent.change(quickEntry, { target: { value: "2" } });
    fireEvent.keyDown(quickEntry, { key: "*", code: "NumpadMultiply" });
    expect(screen.getByText(/^Cantidad: 2 paquetes$/)).toBeVisible();
    fireEvent.change(quickEntry, { target: { value: "P-003" } });
    fireEvent.submit(quickEntry.closest("form")!);
    const packageLine = screen.getByText("Producto por paquete").closest("tr") as HTMLElement;
    expect(within(packageLine).getByText("12")).toBeInTheDocument();

    fireEvent.change(quickEntry, { target: { value: "Producto" } });
    fireEvent.keyDown(quickEntry, { key: "Delete" });
    const productDialog = screen.getByRole("dialog", { name: /buscador de productos/i });
    fireEvent.keyDown(window, { key: "PageDown" });
    expect(screen.queryByRole("dialog", { name: "COBRO" })).not.toBeInTheDocument();
    const productSearch = within(productDialog).getByRole("combobox");
    const productOptions = within(productDialog).getAllByRole("option");
    expect(productOptions[0]).toHaveAttribute("aria-selected", "true");
    expect(within(productDialog).getByText("Añadir")).toBeVisible();
    expect(within(productDialog).queryByText("Añadir al ticket")).not.toBeInTheDocument();

    fireEvent.keyDown(productSearch, { key: "Enter" });
    const informationDialog = await screen.findByRole("dialog", { name: "Producto uno" });
    expect(within(informationDialog).getByText(/añadir al carrito/i)).toBeVisible();
    fireEvent.keyDown(informationDialog, { key: "Escape" });

    const returnedProductDialog = await screen.findByRole("dialog", {
      name: /buscador de productos/i,
    });
    const returnedProductSearch = within(returnedProductDialog).getByRole("combobox");
    const returnedProductOptions = within(returnedProductDialog).getAllByRole("option");
    expect(returnedProductOptions[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(returnedProductSearch, { key: "ArrowDown" });
    expect(returnedProductOptions[1]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(returnedProductSearch, { key: "Insert" });
    expect(screen.queryByRole("dialog", { name: /buscador de productos/i }))
      .not.toBeInTheDocument();
    expect(within(secondLine).getByText("3")).toBeVisible();

    await waitFor(() => expect(
      screen.getByRole("button", { name: /confirmar y cobrar/i }),
    ).toBeEnabled());
    fireEvent.keyDown(window, { key: "PageDown" });
    expect(await screen.findByRole("dialog", { name: "COBRO" })).toBeInTheDocument();
  });

  it("preserves fiscal data and authorization for economic Ctrl+F shortcuts", async () => {
    const savedBodies: unknown[] = [];
    const delegatedTemporaryNameSecurity = {
      ...documentOperationSecurity,
      operations: documentOperationSecurity.operations.map((operation) => (
        operation.code === "TEMPORARY_NAME"
          ? {
              ...operation,
              permissions: ["ADMIN"],
              requirePermission: true,
              requirePassword: true,
            }
          : operation
      )),
    };
    configureDocumentApi([{
      id: "product-1",
      code: "P-001",
      name: "Producto fiscal",
      salePrice: 10,
      active: true,
      taxesIncluded: true,
      taxRegime: "IVA",
      taxPercentage: 21,
    }], savedBodies, delegatedTemporaryNameSecurity);

    render(<SalesDocumentScreen
      locale="es"
      session={session}
      terminalContext={terminalContext}
    />);

    const quickEntry = await screen.findByLabelText(/entrada.*c.digo/i);
    fireEvent.keyDown(window, { key: "End" });
    fireEvent.doubleClick(within(screen.getByRole("dialog", {
      name: /seleccionar cliente/i,
    })).getByRole("option", { name: /cliente fiscal sl/i }));
    fireEvent.change(quickEntry, { target: { value: "P-001" } });
    fireEvent.submit(quickEntry.closest("form")!);

    fireEvent.change(quickEntry, { target: { value: "8" } });
    fireEvent.keyDown(quickEntry, { key: "PageUp" });
    let line = screen.getByText("Producto fiscal").closest("tr") as HTMLElement;
    expect(within(line).getByText("8,00")).toBeVisible();
    expect(within(line).getByText("20 %")).toBeVisible();

    fireEvent.change(quickEntry, { target: { value: "10" } });
    fireEvent.keyDown(quickEntry, { key: "/", code: "NumpadDivide" });
    expect(within(line).getByText("9,00")).toBeVisible();
    expect(within(line).getByText("10 %")).toBeVisible();

    fireEvent.keyDown(window, { key: "Home" });
    const nameDialog = screen.getByRole("dialog", { name: /cambiar nombre/i });
    fireEvent.change(within(nameDialog).getByLabelText(/nombre para esta compra/i), {
      target: { value: "Nombre documental" },
    });
    fireEvent.click(within(nameDialog).getByRole("button", { name: "Guardar" }));
    line = screen.getByText("Nombre documental").closest("tr") as HTMLElement;

    fireEvent.keyDown(window, { key: "PageUp", ctrlKey: true });
    const priceDialog = screen.getByRole("dialog", { name: /cambiar precio/i });
    fireEvent.change(within(priceDialog).getByLabelText(/precio para esta compra/i), {
      target: { value: "7" },
    });
    fireEvent.click(within(priceDialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(
      "/pos/sale-operation-authorizations/temporary-price",
      expect.objectContaining({
        body: expect.objectContaining({
          productId: "product-1",
          unitPrice: 7,
          cartLineId: expect.any(String),
          authorization: {},
        }),
      }),
    ));
    await waitFor(() => expect(
      screen.queryByRole("dialog", { name: /cambiar precio/i }),
    ).not.toBeInTheDocument());

    fireEvent.keyDown(window, { key: "n", ctrlKey: true });
    const serialDialog = await screen.findByRole("dialog", { name: /n.* de serie/i });
    fireEvent.change(within(serialDialog).getByLabelText(/unidad 1/i), {
      target: { value: "serie-001" },
    });
    fireEvent.click(within(serialDialog).getByRole("button", { name: "Aceptar" }));

    await waitFor(() => expect(
      screen.getByRole("button", { name: "Guardar borrador" }),
    ).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Guardar borrador" }));

    const authorizationDialog = await screen.findByRole("dialog", {
      name: /autorizaci.n de la venta/i,
    });
    fireEvent.change(within(authorizationDialog).getByLabelText("Usuario autorizador"), {
      target: { value: "supervisor" },
    });
    fireEvent.change(within(authorizationDialog).getByLabelText("Contraseña del autorizador"), {
      target: { value: "secreto" },
    });
    fireEvent.click(within(authorizationDialog).getByRole("button", {
      name: /confirmar y continuar/i,
    }));

    await waitFor(() => expect(savedBodies).toHaveLength(1));
    expect(savedBodies[0]).toMatchObject({
      completionMode: "DRAFT",
      operationAuthorizations: {
        TEMPORARY_NAME: {
          authorizerUsername: "supervisor",
          authorizerPassword: "secreto",
        },
      },
      lines: [{
        productoId: "product-1",
        nombre: "Nombre documental",
        precioUnitario: "7.00",
        descuento: "10.00",
        serialNumbers: ["serie-001"],
        temporaryNameOverride: true,
        temporaryPriceOverride: true,
        cartLineId: expect.any(String),
        temporaryPriceAuthorizationToken: "temporary-price-proof",
      }],
    });
  });
});
