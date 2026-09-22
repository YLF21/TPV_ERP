// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { StockScreen } from "./StockScreen";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});

afterEach(() => { cleanup(); localStorage.clear(); vi.resetAllMocks(); });

function mockProductEditing(initialCursor: string | null = null) {
  let product: Record<string, unknown> = {
    id: "product-1", code: "CAFE-1", name: "Cafe de prueba", familyId: "family-1", taxId: "tax-1",
    salePrice: "4.50", purchasePrice: "2.00", productType: "UNIT", priceUseMode: "NORMAL",
    discountType: "NORMAL", active: true, taxesIncluded: true, comments: "Comentario anterior",
    description: "Tueste natural", barcode: "8430000000001", packageQuantity: "1",
  };
  let stockRequests = 0;
  const refreshes: ((value: unknown) => void)[] = [];
  const updates: Record<string, unknown>[] = [];
  vi.mocked(apiRequest).mockImplementation(async (path, options) => {
    if (path.startsWith("/stock/page")) {
      if (new URL(path, "http://test").searchParams.has("cursor")) return { items: [], hasMore: false };
      if (stockRequests++ > 0) return new Promise((resolve) => refreshes.push(resolve));
      return { items: [{ product, stock: [{ productId: product.id, warehouseId: "warehouse-1", quantity: 3 }] }],
        hasMore: initialCursor !== null, nextCursor: initialCursor };
    }
    if (path === "/products/management/product-1" && options?.method === "PUT") {
      const body = options.body as Record<string, unknown>;
      updates.push(body);
      // ProductView omits null properties in its canonical JSON response.
      product = Object.fromEntries(Object.entries({ ...product, ...body }).filter(([, value]) => value !== null));
      return product;
    }
    if (path === "/warehouses") return [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true, active: true }];
    if (path === "/families") return [{ id: "family-1", familyCode: "001", name: "Bebidas", defaultFamily: true }];
    if (path === "/taxes/selectable") return [{ id: "tax-1", percentage: "7", defaultTax: true }];
    if (path === "/products") return [product];
    return [];
  });
  return {
    updates,
    finishStockRefreshOutsideCurrentPage: () => {
      for (const resolve of refreshes.splice(0)) resolve({ items: [], hasMore: false });
    },
    finishStockRefreshWithCursor: (nextCursor: string) => {
      for (const resolve of refreshes.splice(0)) resolve({
        items: [{ product, stock: [{ productId: product.id, warehouseId: "warehouse-1", quantity: 3 }] }],
        hasMore: true, nextCursor,
      });
    },
  };
}

async function openProduct(app: "venta" | "gestion") {
  const { container } = render(<StockScreen app={app} locale="es"
    session={{ username: "demo", displayName: "DEMO", permissions: ["ADMIN"], accessToken: "test-token" }}
    terminalContext={{ storeName: "Tienda de prueba", terminalCode: "DEMO" }}
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
  await screen.findByText("Cafe de prueba");
  fireEvent.doubleClick(container.querySelector("article.stock-row")!);
  return screen.getByRole("dialog", { name: "Información del producto" });
}

async function openEditor(information: HTMLElement) {
  fireEvent.keyDown(information, { key: "F7" });
  const editor = await screen.findByRole("dialog", { name: "Modificar producto" });
  await waitFor(() => expect((editor.querySelector('[data-product-field-name="familyBusinessCode"]') as HTMLInputElement)?.value).toBe("001"));
  return editor;
}

async function saveEditor(editor: HTMLElement) {
  fireEvent.click(within(editor).getByRole("button", { name: "Guardar F9" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Modificar producto" })).toBeNull());
}

describe("Stock product editing", () => {
  it("waits for the refreshed first page before requesting its new pagination cursor after a save", async () => {
    const backend = mockProductEditing("before-save");
    const information = await openProduct("venta");
    const editor = await openEditor(information);
    fireEvent.change(within(editor).getByLabelText("Comentarios"), { target: { value: "Comentario guardado" } });
    await saveEditor(editor);
    fireEvent.keyDown(information, { key: "Escape" });
    const pageQueries = () => vi.mocked(apiRequest).mock.calls
      .map(([path]) => path)
      .filter((path) => path.startsWith("/stock/page"))
      .map((path) => new URL(path, "http://test").searchParams);
    await waitFor(() => expect(pageQueries()).toHaveLength(2));
    const table = document.querySelector(".stock-table")!;
    fireEvent.scroll(table);
    await act(async () => { await Promise.resolve(); });
    expect(pageQueries().filter((query) => query.has("cursor"))).toEqual([]);

    await act(async () => { backend.finishStockRefreshWithCursor("after-save"); });
    fireEvent.scroll(table);
    await waitFor(() => expect(pageQueries().filter((query) => query.has("cursor")))
      .toHaveLength(1));
    expect(pageQueries().filter((query) => query.has("cursor")).map((query) => query.get("cursor")))
      .toEqual(["after-save"]);
  });

  it.each(["venta", "gestion"] as const)("closes the calendar before the editor on Escape and retains F9 saving in %s", async (app) => {
    const backend = mockProductEditing();
    const information = await openProduct(app);
    const editor = await openEditor(information);
    const comments = within(editor).getByLabelText("Comentarios") as HTMLTextAreaElement;
    fireEvent.change(comments, { target: { value: "Borrador sin guardar" } });
    const calendar = within(editor).getByRole("button", { name: "Abrir calendario" });
    fireEvent.click(calendar);
    expect(editor.querySelector(".date-range-popover")).not.toBeNull();
    fireEvent.keyDown(calendar, { key: "Escape" });
    expect(editor.querySelector(".date-range-popover")).toBeNull();
    expect(screen.getByRole("dialog", { name: "Modificar producto" })).toBe(editor);
    expect(comments.value).toBe("Borrador sin guardar");
    expect(backend.updates).toHaveLength(0);

    fireEvent.keyDown(comments, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Modificar producto" })).toBeNull();
    expect(screen.getByRole("dialog", { name: "Información del producto" })).toBe(information);
    expect(within(information).getByRole("tabpanel", { name: "Información F5" })).toBeTruthy();
    const reopened = await openEditor(information);
    const nextComments = within(reopened).getByLabelText("Comentarios");
    fireEvent.change(nextComments, { target: { value: "Guardado con F9" } });
    fireEvent.keyDown(nextComments, { key: "F9" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Modificar producto" })).toBeNull());
    expect(backend.updates).toHaveLength(1);
    expect(backend.updates[0].comments).toBe("Guardado con F9");
    expect(information.querySelector(".stock-product-information")?.textContent).toContain("Guardado con F9");
  });

  it.each(["venta", "gestion"] as const)("keeps saved comments in the information panel and an immediate second edit in %s", async (app) => {
    const backend = mockProductEditing();
    const information = await openProduct(app);
    const editor = await openEditor(information);
    fireEvent.change(within(editor).getByLabelText("Comentarios"), { target: { value: "  Comentario guardado\nSegunda línea  " } });
    await saveEditor(editor);

    expect(backend.updates[0].comments).toBe("Comentario guardado\nSegunda línea");
    expect(information.querySelector(".stock-product-information")?.textContent).toContain("Comentario guardado\nSegunda línea");
    const secondEditor = await openEditor(information);
    expect((within(secondEditor).getByLabelText("Comentarios") as HTMLTextAreaElement).value).toBe("Comentario guardado\nSegunda línea");
    fireEvent.change(within(secondEditor).getByLabelText("Descripción"), { target: { value: "Descripción modificada" } });
    // The edited product can leave the filtered/paged list; the open editor must keep its draft.
    await act(async () => backend.finishStockRefreshOutsideCurrentPage());
    expect((within(secondEditor).getByLabelText("Descripción") as HTMLTextAreaElement).value).toBe("Descripción modificada");
    await saveEditor(secondEditor);
    expect(backend.updates[1].comments).toBe("Comentario guardado\nSegunda línea");
    expect(information.querySelector(".stock-product-information")?.textContent).toContain("Descripción modificada");
  });

  it.each(["venta", "gestion"] as const)("clears comments and reopens an empty field when the response omits null in %s", async (app) => {
    const backend = mockProductEditing();
    const information = await openProduct(app);
    const editor = await openEditor(information);
    fireEvent.change(within(editor).getByLabelText("Comentarios"), { target: { value: "" } });
    await saveEditor(editor);
    expect(backend.updates[0].comments).toBeNull();
    expect(information.querySelector(".stock-product-information")?.textContent).not.toContain("Comentario anterior");
    const reopened = await openEditor(information);
    expect((within(reopened).getByLabelText("Comentarios") as HTMLTextAreaElement).value).toBe("");
  });
});
