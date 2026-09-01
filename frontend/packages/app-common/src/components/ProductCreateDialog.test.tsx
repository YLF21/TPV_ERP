// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  cleanup,
  fireEvent,
  render,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import {
  applyProductRequiredDefaults,
  buildCreateProductRequest,
  canLeaveProductField,
  canNavigateProductField,
  createDefaultProductForm,
  createProductFormFromEditProduct,
  nextProductFieldIndex,
  productCreateKeyAction,
  productCreateErrorMessage,
  productDiscountTypeOptions,
  productCreateValidationErrors,
  duplicatedProductIdentifierFields,
  productImageReadPath,
  productImageUploadPath,
  preferredProductSupplier,
  saveProductWithOptionalImage,
  ProductCreateDialog,
} from "./ProductCreateDialog";
import type { ProductCreateFormState } from "./ProductCreateDialog";

afterEach(cleanup);

describe("ProductCreateDialog", () => {
  it("filters the family explorer by code or accent-insensitive name", async () => {
    const families = [
      { id: "family-1", familyCode: "123", name: "Bebidas" },
      { id: "family-2", familyCode: "456", name: "Café" },
    ];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/families"))
          return new Response(JSON.stringify(families), { status: 200 });
        if (url.endsWith("/taxes/selectable") || url.endsWith("/products"))
          return new Response("[]", { status: 200 });
        return new Response("not found", { status: 404 });
      }),
    );
    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(view.getAllByText("Bebidas").length).toBeGreaterThan(0),
    );
    fireEvent.click(view.getByRole("button", { name: "Explorar" }));
    const search = view.getByRole("searchbox", {
      name: "Buscar por código o nombre",
    });
    expect(search).toHaveAttribute("aria-controls", "product-family-tree");
    fireEvent.change(search, { target: { value: "cafe" } });
    expect(view.getByText("Café")).toBeInTheDocument();
    expect(
      view.container.querySelector(".stock-family-list")?.textContent,
    ).not.toContain("Bebidas");
    vi.unstubAllGlobals();
  });

  it("finds and applies a subfamily that is not loaded in the local tree", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/families")) {
        return new Response(
          JSON.stringify([
            { id: "family-1", familyCode: "123", name: "Bebidas" },
          ]),
          { status: 200 },
        );
      }
      if (url.includes("/families/search?q=cafe&limit=50")) {
        if (url.includes("cursor=next")) {
          return new Response(JSON.stringify({ items: [], nextCursor: "", hasMore: false }), { status: 200 });
        }
        return new Response(
          JSON.stringify({
            items: [
              {
                kind: "SUBFAMILY",
                id: "subfamily-remote",
                familyId: "family-1",
                subfamilyId: "subfamily-remote",
                code: "123456",
                suffix: "456",
                familyCode: "123",
                name: "Café remoto",
                defaultFamily: false,
              },
            ],
            nextCursor: "next",
            hasMore: true,
          }),
          { status: 200 },
        );
      }
      if (url.includes("/families/family-1/subfamilies")) {
        return new Response(JSON.stringify([
          { id: "sister", familyId: "family-1", subfamilyCode: "123457", subfamilySuffix: "457", name: "Hermana" },
        ]), { status: 200 });
      }
      if (url.endsWith("/taxes/selectable") || url.endsWith("/products"))
        return new Response("[]", { status: 200 });
      return new Response("not found", { status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);
    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(view.getAllByText("Bebidas").length).toBeGreaterThan(0),
    );
    fireEvent.click(view.getByRole("button", { name: "Explorar" }));
    const search = view.getByRole("searchbox", {
      name: "Buscar por código o nombre",
    });
    fireEvent.change(search, { target: { value: "c" } });
    expect(view.getByText(/al menos 2 caracteres/i)).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(
      fetchMock.mock.calls.some(([input]) => String(input).includes("/families/search")),
    ).toBe(false);
    fireEvent.change(search, { target: { value: "cafe" } });
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([input]) =>
          String(input).includes("/families/search?q=cafe&limit=50"),
        ),
      ).toBe(true),
    );
    const remoteSubfamily = await view.findByText("Café remoto");
    expect(view.getByText(/Hay más resultados/i)).toBeInTheDocument();
    fireEvent.click(view.getByRole("button", { name: "Cargar más" }));
    await waitFor(() => expect(view.queryByText(/Hay más resultados/i)).toBeNull());
    fireEvent.click(remoteSubfamily.closest("button") ?? remoteSubfamily);
    fireEvent.click(view.getByRole("button", { name: "Aplicar" }));
    expect(
      view.container.querySelector<HTMLInputElement>(
        'input[data-product-field-name="familyBusinessCode"]',
      ),
    ).toHaveValue("123456");
    fireEvent.click(view.getByRole("button", { name: "Explorar" }));
    fireEvent.click(view.getByRole("button", { name: "Desplegar familia" }));
    expect(await view.findByText("Hermana")).toBeInTheDocument();
    vi.unstubAllGlobals();
  });

  it("shows a translated global search error and retries it", async () => {
    let attempts = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/families"))
        return new Response(JSON.stringify([{ id: "family-1", familyCode: "123", name: "Bebidas" }]), { status: 200 });
      if (url.includes("/families/search?q=cafe&limit=50")) {
        attempts += 1;
        if (attempts === 1) return new Response(JSON.stringify({ detail: "backend failure" }), { status: 503 });
        return new Response(JSON.stringify({ items: [], nextCursor: "", hasMore: false }), { status: 200 });
      }
      if (url.endsWith("/taxes/selectable") || url.endsWith("/products")) return new Response("[]", { status: 200 });
      return new Response("not found", { status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);
    const view = render(<ProductCreateDialog open locale="es" token="token" onClose={() => undefined} />);
    await waitFor(() => expect(view.getAllByText("Bebidas").length).toBeGreaterThan(0));
    fireEvent.click(view.getByRole("button", { name: "Explorar" }));
    fireEvent.change(view.getByRole("searchbox", { name: "Buscar por código o nombre" }), { target: { value: "cafe" } });
    expect(await view.findByRole("alert")).toHaveTextContent("No se pudo buscar la clasificación.");
    fireEvent.click(view.getByRole("button", { name: "Reintentar búsqueda" }));
    await waitFor(() => expect(attempts).toBe(2));
    expect(view.queryByRole("alert")).toBeNull();
    vi.unstubAllGlobals();
  });

  it("uses the business-id resolver and loads subfamilies only when a family expands", async () => {
    const calls: string[] = [];
    const family = {
      id: "family-1",
      familyId: "legacy-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const subfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyId: "legacy-1",
      subfamilySuffix: "456",
      subfamilyCode: "123456",
      name: "Café",
      order: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        calls.push(url);
        if (url.endsWith("/families"))
          return new Response(JSON.stringify([family]), { status: 200 });
        if (url.includes("/families/resolve?code=123"))
          return new Response(JSON.stringify({ family }), { status: 200 });
        if (url.includes("/families/family-1/subfamilies"))
          return new Response(JSON.stringify([subfamily]), { status: 200 });
        if (url.endsWith("/taxes/selectable"))
          return new Response(
            JSON.stringify([{ id: "tax-1", percentage: 7 }]),
            { status: 200 },
          );
        if (url.endsWith("/products"))
          return new Response(JSON.stringify([]), { status: 200 });
        return new Response("not found", { status: 404 });
      }),
    );

    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(view.getAllByText("Bebidas").length).toBeGreaterThan(0),
    );
    expect(calls.some((url) => url.includes("/subfamilies"))).toBe(false);

    const businessId = view.getByRole("textbox", {
      name: /ID familia\/subfamilia/i,
    });
    fireEvent.change(businessId, { target: { value: "123" } });
    fireEvent.keyDown(businessId, { key: "Enter" });
    await waitFor(() =>
      expect(
        calls.some((url) => url.includes("/families/resolve?code=123")),
      ).toBe(true),
    );

    fireEvent.click(view.getByRole("button", { name: "Explorar" }));
    fireEvent.click(view.getByRole("button", { name: /Desplegar familia/i }));
    await waitFor(() =>
      expect(view.getAllByText("Café").length).toBeGreaterThan(0),
    );
    const familyTreeItem = view.container.querySelector<HTMLElement>(
      '[data-family-tree-key="family:family-1"]',
    )!;
    const subfamilyTreeItem = view.container.querySelector<HTMLElement>(
      '[data-family-tree-key="subfamily:subfamily-1"]',
    )!;
    expect(familyTreeItem).toHaveAttribute("role", "treeitem");
    expect(subfamilyTreeItem).toHaveAttribute("role", "treeitem");
    expect(subfamilyTreeItem).toHaveAttribute("aria-level", "2");
    familyTreeItem.focus();
    fireEvent.keyDown(familyTreeItem, { key: "ArrowDown" });
    await waitFor(() => expect(document.activeElement).toBe(subfamilyTreeItem));
    fireEvent.keyDown(subfamilyTreeItem, { key: "ArrowUp" });
    await waitFor(() => expect(document.activeElement).toBe(familyTreeItem));
    expect(
      calls.some((url) => url.includes("/families/family-1/subfamilies")),
    ).toBe(true);
    vi.unstubAllGlobals();
  });

  it("deduplicates a resolved subfamily already present in the expanded tree", async () => {
    let resolveLookup!: (response: Response) => void;
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const loadedSubfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyCode: "123456",
      subfamilySuffix: "456",
      name: "Café cargado",
      order: 1,
    };
    const resolvedSubfamily = { ...loadedSubfamily, name: "Café resuelto" };
    const lookupResponse = new Promise<Response>((resolve) => {
      resolveLookup = resolve;
    });
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/families"))
        return new Response(JSON.stringify([family]), { status: 200 });
      if (url.includes("/families/family-1/subfamilies"))
        return new Response(JSON.stringify([loadedSubfamily]), { status: 200 });
      if (url.includes("/families/resolve?code=123456")) return lookupResponse;
      if (url.endsWith("/taxes/selectable"))
        return new Response(JSON.stringify([]), { status: 200 });
      if (url.endsWith("/products"))
        return new Response(JSON.stringify([]), { status: 200 });
      return new Response("not found", { status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(view.container.textContent).toContain("Bebidas"),
    );
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Explorar"),
      )!,
    );
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find(
        (button) => button.getAttribute("aria-label") === "Desplegar familia",
      )!,
    );
    await waitFor(() =>
      expect(view.container.textContent).toContain("Café cargado"),
    );

    const businessId = view.container.querySelector<HTMLInputElement>(
      'input[data-product-field-name="familyBusinessCode"]',
    )!;
    fireEvent.change(businessId, { target: { value: "123456" } });
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([input]) =>
          String(input).includes("/families/resolve?code=123456"),
        ),
      ).toBe(true),
    );
    resolveLookup(
      new Response(JSON.stringify({ family, subfamily: resolvedSubfamily }), {
        status: 200,
      }),
    );
    await waitFor(() =>
      expect(view.container.textContent).toContain("Café resuelto"),
    );
    expect(
      view.container.querySelectorAll(".stock-subfamily-list button"),
    ).toHaveLength(1);
    vi.unstubAllGlobals();
  });

  it("does not let a stale three-digit resolution overwrite a later six-digit entry", async () => {
    const pending = new Map<string, (response: Response) => void>();
    const family = {
      id: "family-1",
      familyId: "legacy-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const subfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyId: "legacy-1",
      subfamilySuffix: "456",
      subfamilyCode: "123456",
      name: "Café",
      order: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/families"))
          return Promise.resolve(
            new Response(JSON.stringify([family]), { status: 200 }),
          );
        if (url.endsWith("/taxes/selectable"))
          return Promise.resolve(
            new Response(JSON.stringify([]), { status: 200 }),
          );
        if (url.endsWith("/products"))
          return Promise.resolve(
            new Response(JSON.stringify([]), { status: 200 }),
          );
        const code = /resolve\?code=(\d+)/.exec(url)?.[1];
        if (code)
          return new Promise<Response>((resolve) => pending.set(code, resolve));
        return Promise.resolve(new Response("not found", { status: 404 }));
      }),
    );

    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );
    const businessId = view.container.querySelector<HTMLInputElement>(
      'input[data-product-field-name="familyBusinessCode"]',
    )!;
    fireEvent.change(businessId, { target: { value: "123" } });
    fireEvent.change(businessId, { target: { value: "1234" } });
    fireEvent.change(businessId, { target: { value: "12345" } });
    fireEvent.change(businessId, { target: { value: "123456" } });
    await waitFor(() => expect(pending.has("123456")).toBe(true));

    pending.get("123")?.(
      new Response(JSON.stringify({ family }), { status: 200 }),
    );
    await waitFor(() => expect(businessId).toHaveValue("123456"));

    pending.get("123456")?.(
      new Response(JSON.stringify({ family, subfamily }), { status: 200 }),
    );
    await waitFor(() => expect(businessId).toHaveValue("123456"));
    vi.unstubAllGlobals();
  });

  it("clears the subfamily UUID when resolving a three-digit family code", async () => {
    const updateProduct = vi.fn().mockResolvedValue({ id: "product-1" });
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/families"))
        return new Response(JSON.stringify([family]), { status: 200 });
      if (url.includes("/families/family-1/subfamilies"))
        return new Response(JSON.stringify([]), { status: 200 });
      if (url.includes("/families/resolve?code=123"))
        return new Response(JSON.stringify({ family }), { status: 200 });
      if (url.endsWith("/taxes/selectable"))
        return new Response(JSON.stringify([{ id: "tax-1", percentage: 7 }]), {
          status: 200,
        });
      if (url.endsWith("/products"))
        return new Response(JSON.stringify([]), { status: 200 });
      return new Response("not found", { status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);
    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{
          id: "product-1",
          form: {
            familyId: "family-1",
            subfamilyId: "subfamily-old",
            taxId: "tax-1",
            name: "Cafe",
            code: "A001",
            purchasePrice: "1",
            salePrice: "2",
          },
        }}
        updateProduct={updateProduct}
        onClose={() => undefined}
      />,
    );
    const businessId = view.container.querySelector<HTMLInputElement>(
      'input[data-product-field-name="familyBusinessCode"]',
    )!;
    fireEvent.change(businessId, { target: { value: "123" } });
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([input]) =>
          String(input).includes("/families/resolve?code=123"),
        ),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(view.container.textContent).not.toContain("Resolviendo..."),
    );
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Guardar"),
      )!,
    );
    await waitFor(() => expect(updateProduct).toHaveBeenCalled());
    expect(updateProduct.mock.calls[0][1]).toEqual(
      expect.objectContaining({ familyId: "family-1", subfamilyId: null }),
    );
    vi.unstubAllGlobals();
  });

  it("resolves six digits to both UUIDs and blocks an incomplete code on blur", async () => {
    const updateProduct = vi.fn().mockResolvedValue({ id: "product-1" });
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const subfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyCode: "123456",
      subfamilySuffix: "456",
      name: "Café",
      order: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/families"))
          return new Response(JSON.stringify([family]), { status: 200 });
        if (url.includes("/families/resolve?code=123456"))
          return new Response(JSON.stringify({ family, subfamily }), {
            status: 200,
          });
        if (url.endsWith("/taxes/selectable"))
          return new Response(
            JSON.stringify([{ id: "tax-1", percentage: 7 }]),
            { status: 200 },
          );
        if (url.endsWith("/products"))
          return new Response(JSON.stringify([]), { status: 200 });
        return new Response("not found", { status: 404 });
      }),
    );
    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{
          id: "product-1",
          form: {
            familyId: "family-1",
            taxId: "tax-1",
            name: "Cafe",
            code: "A001",
            purchasePrice: "1",
            salePrice: "2",
          },
        }}
        updateProduct={updateProduct}
        onClose={() => undefined}
      />,
    );
    const businessId = view.container.querySelector<HTMLInputElement>(
      'input[data-product-field-name="familyBusinessCode"]',
    )!;
    fireEvent.change(businessId, { target: { value: "123456" } });
    await waitFor(() => expect(view.container.textContent).toContain("Café"));
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Guardar"),
      )!,
    );
    await waitFor(() => expect(updateProduct).toHaveBeenCalled());
    expect(updateProduct.mock.calls[0][1]).toEqual(
      expect.objectContaining({
        familyId: "family-1",
        subfamilyId: "subfamily-1",
      }),
    );

    vi.unstubAllGlobals();
  });

  it("marks an incomplete family code on blur and blocks saving", async () => {
    const updateProduct = vi.fn().mockResolvedValue({ id: "product-1" });
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/families"))
          return new Response(JSON.stringify([family]), { status: 200 });
        if (url.endsWith("/taxes/selectable"))
          return new Response(
            JSON.stringify([{ id: "tax-1", percentage: 7 }]),
            { status: 200 },
          );
        if (url.endsWith("/products"))
          return new Response(JSON.stringify([]), { status: 200 });
        return new Response("not found", { status: 404 });
      }),
    );
    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{
          id: "product-1",
          form: {
            familyId: "family-1",
            taxId: "tax-1",
            name: "Cafe",
            code: "A001",
            purchasePrice: "1",
            salePrice: "2",
          },
        }}
        updateProduct={updateProduct}
        onClose={() => undefined}
      />,
    );
    const businessId = view.container.querySelector<HTMLInputElement>(
      'input[data-product-field-name="familyBusinessCode"]',
    )!;
    fireEvent.change(businessId, { target: { value: "12" } });
    fireEvent.blur(businessId);
    await waitFor(() =>
      expect(view.container.textContent).toContain(
        "Completa un ID de familia válido (3 o 6 dígitos)",
      ),
    );
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Guardar"),
      )!,
    );
    expect(updateProduct).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("does not keep UUIDs valid when resolving an unknown code fails", async () => {
    const updateProduct = vi.fn().mockResolvedValue({ id: "product-1" });
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/families"))
          return new Response(JSON.stringify([family]), { status: 200 });
        if (url.includes("/families/resolve?code=999"))
          return new Response("not found", { status: 404 });
        if (url.endsWith("/taxes/selectable"))
          return new Response(
            JSON.stringify([{ id: "tax-1", percentage: 7 }]),
            { status: 200 },
          );
        if (url.endsWith("/products"))
          return new Response(JSON.stringify([]), { status: 200 });
        return new Response("not found", { status: 404 });
      }),
    );
    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{
          id: "product-1",
          form: {
            familyId: "family-1",
            taxId: "tax-1",
            name: "Cafe",
            code: "A001",
            purchasePrice: "1",
            salePrice: "2",
          },
        }}
        updateProduct={updateProduct}
        onClose={() => undefined}
      />,
    );
    const businessId = view.container.querySelector<HTMLInputElement>(
      'input[data-product-field-name="familyBusinessCode"]',
    )!;
    fireEvent.change(businessId, { target: { value: "999" } });
    await waitFor(() =>
      expect(view.container.textContent).toContain("api_error"),
    );
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Guardar"),
      )!,
    );
    expect(updateProduct).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("applies a tree subfamily with Enter and closes the explorer", async () => {
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const subfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyCode: "123456",
      subfamilySuffix: "456",
      name: "Café",
      order: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/families"))
          return new Response(JSON.stringify([family]), { status: 200 });
        if (url.includes("/families/family-1/subfamilies"))
          return new Response(JSON.stringify([subfamily]), { status: 200 });
        if (url.endsWith("/taxes/selectable"))
          return new Response(
            JSON.stringify([{ id: "tax-1", percentage: 7 }]),
            { status: 200 },
          );
        if (url.endsWith("/products"))
          return new Response(JSON.stringify([]), { status: 200 });
        return new Response("not found", { status: 404 });
      }),
    );
    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(view.container.textContent).toContain("Bebidas"),
    );
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Explorar"),
      )!,
    );
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find(
        (button) => button.getAttribute("aria-label") === "Desplegar familia",
      )!,
    );
    await waitFor(() => expect(view.container.textContent).toContain("Café"));
    const subfamilyButton = Array.from(
      view.container.querySelectorAll("button"),
    ).find((button) => button.textContent?.includes("Café"))!;
    fireEvent.keyDown(subfamilyButton, { key: "Enter" });
    await waitFor(() =>
      expect(view.container.querySelector(".stock-family-overlay")).toBeNull(),
    );
    expect(
      view.container.querySelector<HTMLInputElement>(
        'input[data-product-field-name="familyBusinessCode"]',
      ),
    ).toHaveValue("123456");
    vi.unstubAllGlobals();
  });

  it("does not let a blurred code resolution overwrite a tree selection", async () => {
    let resolveLookup!: (response: Response) => void;
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const subfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyCode: "123456",
      subfamilySuffix: "456",
      name: "Café",
      order: 1,
    };
    const lookupResponse = new Promise<Response>((resolve) => {
      resolveLookup = resolve;
    });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/families"))
          return new Response(JSON.stringify([family]), { status: 200 });
        if (url.includes("/families/family-1/subfamilies"))
          return new Response(JSON.stringify([subfamily]), { status: 200 });
        if (url.includes("/families/resolve?code=123")) return lookupResponse;
        if (url.endsWith("/taxes/selectable"))
          return new Response(JSON.stringify([]), { status: 200 });
        if (url.endsWith("/products"))
          return new Response(JSON.stringify([]), { status: 200 });
        return new Response("not found", { status: 404 });
      }),
    );

    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(view.container.textContent).toContain("Bebidas"),
    );
    const businessId = view.container.querySelector<HTMLInputElement>(
      'input[data-product-field-name="familyBusinessCode"]',
    )!;
    fireEvent.change(businessId, { target: { value: "123" } });
    fireEvent.blur(businessId);
    await waitFor(() =>
      expect(view.container.textContent).toContain("Resolviendo..."),
    );

    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Explorar"),
      )!,
    );
    expect(view.container.textContent).not.toContain("Limpiar");
    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find(
        (button) => button.getAttribute("aria-label") === "Desplegar familia",
      )!,
    );
    await waitFor(() => expect(view.container.textContent).toContain("Café"));
    fireEvent.keyDown(
      Array.from(
        view.container.querySelectorAll(".stock-subfamily-list button"),
      )[0],
      { key: "Enter" },
    );
    await waitFor(() =>
      expect(
        view.container.querySelector<HTMLInputElement>(
          'input[data-product-field-name="familyBusinessCode"]',
        ),
      ).toHaveValue("123456"),
    );

    resolveLookup(new Response(JSON.stringify({ family }), { status: 200 }));
    await waitFor(() =>
      expect(
        view.container.querySelector<HTMLInputElement>(
          'input[data-product-field-name="familyBusinessCode"]',
        ),
      ).toHaveValue("123456"),
    );
    vi.unstubAllGlobals();
  });

  it("shows the existing business code after loading an edited product", async () => {
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const subfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyCode: "123456",
      subfamilySuffix: "456",
      name: "Café",
      order: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/families"))
          return new Response(JSON.stringify([family]), { status: 200 });
        if (url.includes("/families/family-1/subfamilies"))
          return new Response(JSON.stringify([subfamily]), { status: 200 });
        if (url.endsWith("/taxes/selectable"))
          return new Response(
            JSON.stringify([{ id: "tax-1", percentage: 7 }]),
            { status: 200 },
          );
        if (url.endsWith("/products"))
          return new Response(JSON.stringify([]), { status: 200 });
        return new Response("not found", { status: 404 });
      }),
    );
    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{
          id: "product-1",
          form: {
            familyId: "family-1",
            subfamilyId: "subfamily-1",
            taxId: "tax-1",
            name: "Cafe",
            code: "A001",
            purchasePrice: "1",
            salePrice: "2",
          },
        }}
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(
        view.container.querySelector<HTMLInputElement>(
          'input[data-product-field-name="familyBusinessCode"]',
        ),
      ).toHaveValue("123456"),
    );
    vi.unstubAllGlobals();
  });

  it("blocks editing when the existing subfamily cannot be loaded", async () => {
    const updateProduct = vi.fn().mockResolvedValue({ id: "product-1" });
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/families"))
        return new Response(JSON.stringify([family]), { status: 200 });
      if (url.includes("/families/family-1/subfamilies"))
        return new Response("network failure", { status: 503 });
      if (url.endsWith("/taxes/selectable"))
        return new Response(JSON.stringify([{ id: "tax-1", percentage: 7 }]), {
          status: 200,
        });
      if (url.endsWith("/products"))
        return new Response(JSON.stringify([]), { status: 200 });
      return new Response("not found", { status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{
          id: "product-1",
          form: {
            familyId: "family-1",
            subfamilyId: "subfamily-1",
            taxId: "tax-1",
            name: "Cafe",
            code: "A001",
            purchasePrice: "1",
            salePrice: "2",
          },
        }}
        updateProduct={updateProduct}
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([input]) =>
          String(input).includes("/families/family-1/subfamilies"),
        ),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(view.container.textContent).toContain(
        "No se pudieron cargar las subfamilias",
      ),
    );
    expect(
      view.container.querySelector<HTMLInputElement>(
        'input[data-product-field-name="familyBusinessCode"]',
      ),
    ).toHaveValue("");

    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Explorar"),
      )!,
    );
    fireEvent.click(
      Array.from(
        view.container.querySelectorAll(".stock-family-overlay button"),
      ).find((button) => button.textContent?.includes("Aplicar"))!,
    );
    expect(
      view.container.querySelector(".stock-family-overlay"),
    ).not.toBeNull();
    expect(
      view.container.querySelector<HTMLInputElement>(
        'input[data-product-field-name="familyBusinessCode"]',
      ),
    ).toHaveAttribute("aria-invalid", "true");
    expect(view.container.textContent).toContain(
      "No se pudieron cargar las subfamilias",
    );

    fireEvent.click(
      Array.from(view.container.querySelectorAll("button")).find((button) =>
        button.textContent?.includes("Guardar"),
      )!,
    );
    expect(updateProduct).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("reuses an in-flight subfamily request while the edit catalog is loading", async () => {
    let resolveFamilies!: (response: Response) => void;
    let resolveSubfamilies!: (response: Response) => void;
    const family = {
      id: "family-1",
      familyCode: "123",
      name: "Bebidas",
      order: 1,
    };
    const subfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyCode: "123456",
      subfamilySuffix: "456",
      name: "Café",
      order: 1,
    };
    const familiesResponse = new Promise<Response>((resolve) => {
      resolveFamilies = resolve;
    });
    const subfamiliesResponse = new Promise<Response>((resolve) => {
      resolveSubfamilies = resolve;
    });
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/families")) return familiesResponse;
      if (url.includes("/families/family-1/subfamilies"))
        return subfamiliesResponse;
      if (url.endsWith("/taxes/selectable"))
        return Promise.resolve(
          new Response(JSON.stringify([{ id: "tax-1", percentage: 7 }]), {
            status: 200,
          }),
        );
      if (url.endsWith("/products"))
        return Promise.resolve(
          new Response(JSON.stringify([]), { status: 200 }),
        );
      return Promise.resolve(new Response("not found", { status: 404 }));
    });
    vi.stubGlobal("fetch", fetchMock);

    const view = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{
          id: "product-1",
          form: {
            familyId: "family-1",
            subfamilyId: "subfamily-1",
            taxId: "tax-1",
            name: "Cafe",
            code: "A001",
            purchasePrice: "1",
            salePrice: "2",
          },
        }}
        onClose={() => undefined}
      />,
    );
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.filter(([input]) =>
          String(input).includes("/families/family-1/subfamilies"),
        ).length,
      ).toBe(1),
    );

    resolveFamilies(new Response(JSON.stringify([family]), { status: 200 }));
    await waitFor(() =>
      expect(view.container.textContent).toContain("Bebidas"),
    );
    expect(
      fetchMock.mock.calls.filter(([input]) =>
        String(input).includes("/families/family-1/subfamilies"),
      ).length,
    ).toBe(1);

    resolveSubfamilies(
      new Response(JSON.stringify([subfamily]), { status: 200 }),
    );
    await waitFor(() =>
      expect(
        view.container.querySelector<HTMLInputElement>(
          'input[data-product-field-name="familyBusinessCode"]',
        ),
      ).toHaveValue("123456"),
    );
    vi.unstubAllGlobals();
  });

  it("builds the full product creation request from form state", () => {
    const form: ProductCreateFormState = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      subfamilyId: "subfamily-1",
      taxId: "tax-1",
      productType: "UNIT",
      priceUseMode: "NORMAL",
      discountType: "NORMAL",
      name: " Cafe molido ",
      description: "Paquete 250g",
      comments: "Alta desde stock",
      purchasePrice: "2.40",
      taxesIncluded: true,
      code: " A001 ",
      barcode: "8430000000011",
      barcode2: "8430000000012",
      salePrice: "3.95",
      memberPrice: "3.70",
      wholesalePrice: "3.40",
      offerPrice: "3.20",
      offerDiscountPercent: "10",
      offerActive: false,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31",
    };

    expect(buildCreateProductRequest(form)).toEqual({
      active: true,
      familyId: "family-1",
      subfamilyId: "subfamily-1",
      taxId: "tax-1",
      productType: "UNIT",
      priceUseMode: "NORMAL",
      discountType: "NORMAL",
      name: "Cafe molido",
      description: "Paquete 250g",
      comments: "Alta desde stock",
      purchasePrice: "2.40",
      taxesIncluded: true,
      code: "A001",
      barcode: "8430000000011",
      barcode2: "8430000000012",
      salePrice: "3.95",
      memberPrice: "3.70",
      wholesalePrice: "3.40",
      offerPrice: "3.20",
      offerDiscountPercent: "10",
      purchaseDiscountPercent: null,
      packageQuantity: "1",
      stockMin: null,
      stockMax: null,
      requiresSerialNumber: false,
      offerActive: false,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31",
    });
  });

  it("moves product fields forwards and backwards", () => {
    expect(nextProductFieldIndex(0, 4, false)).toBe(1);
    expect(nextProductFieldIndex(2, 4, false)).toBe(3);
    expect(nextProductFieldIndex(0, 4, true)).toBe(3);
    expect(nextProductFieldIndex(2, 4, true)).toBe(1);
    expect(nextProductFieldIndex(0, 0, false)).toBe(-1);
  });

  it("maps product dialog keyboard shortcuts to actions", () => {
    expect(productCreateKeyAction("Escape")).toBe("close");
    expect(productCreateKeyAction("F8")).toBeNull();
    expect(productCreateKeyAction("F9")).toBe("save");
    expect(productCreateKeyAction("Enter")).toBeNull();
  });

  it("requires the fields that the backend cannot store as null", () => {
    expect(productCreateValidationErrors(createDefaultProductForm())).toEqual([
      "familyId",
      "taxId",
      "name",
      "code",
      "barcode",
    ]);

    expect(
      productCreateValidationErrors({
        ...createDefaultProductForm(),
        familyId: "family-1",
        taxId: "tax-1",
        name: "Cafe",
        code: "A001",
        purchasePrice: "",
        salePrice: "",
      }),
    ).toEqual(["purchasePrice", "salePrice"]);
  });

  it("does not allow advancing from an empty required product field", () => {
    const form: ProductCreateFormState = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "",
      code: "A001",
      purchasePrice: "2.40",
      salePrice: "3.95",
    };

    expect(canLeaveProductField(form, "name")).toBe(false);
    expect(canLeaveProductField({ ...form, name: "Cafe" }, "name")).toBe(true);
    expect(
      canLeaveProductField(
        { ...form, name: "Cafe", code: "", barcode: "" },
        "code",
      ),
    ).toBe(false);
    expect(
      canLeaveProductField(
        { ...form, name: "Cafe", code: "", barcode: "843" },
        "code",
      ),
    ).toBe(true);
  });

  it("allows moving from the first identifier to the second before requiring one of them", () => {
    const form = createDefaultProductForm();

    expect(canNavigateProductField(form, "barcode", false)).toBe(true);
    expect(canNavigateProductField(form, "code", false)).toBe(false);
    expect(canNavigateProductField(form, "code", true)).toBe(true);
  });

  it("does not allow advancing from offer price when an active offer has no price", () => {
    const form: ProductCreateFormState = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      priceUseMode: "OFFER_PRICE",
      offerPrice: "",
      offerFrom: "",
    };

    expect(canLeaveProductField(form, "offerPrice")).toBe(false);
    expect(
      canLeaveProductField({ ...form, offerPrice: "2.50" }, "offerRange"),
    ).toBe(true);
    expect(
      canLeaveProductField(
        { ...form, offerPrice: "2.50", offerFrom: "2026-07-01" },
        "offerRange",
      ),
    ).toBe(true);
  });

  it("keeps every persisted product dialog attribute bound to the create request", () => {
    const request = buildCreateProductRequest({
      ...createDefaultProductForm(),
      familyId: "family-1",
      subfamilyId: "subfamily-1",
      taxId: "tax-1",
      productType: "WEIGHT",
      priceUseMode: "OFFER_PRICE",
      discountType: "DISCOUNT_PRICE",
      name: "Cafe",
      description: "Descripcion",
      comments: "Comentario",
      purchasePrice: "1.20",
      taxesIncluded: false,
      code: "A001",
      barcode: "843",
      barcode2: "844",
      salePrice: "2.40",
      memberPrice: "2.10",
      wholesalePrice: "1.90",
      offerPrice: "1.80",
      offerFrom: "2026-07-01",
      offerUntil: "",
    });

    expect(Object.keys(request)).toEqual([
      "active",
      "familyId",
      "subfamilyId",
      "taxId",
      "productType",
      "priceUseMode",
      "discountType",
      "name",
      "description",
      "comments",
      "purchasePrice",
      "taxesIncluded",
      "code",
      "barcode",
      "barcode2",
      "salePrice",
      "memberPrice",
      "wholesalePrice",
      "offerPrice",
      "offerDiscountPercent",
      "purchaseDiscountPercent",
      "packageQuantity",
      "stockMin",
      "stockMax",
      "requiresSerialNumber",
      "offerActive",
      "offerFrom",
      "offerUntil",
    ]);
    expect(request.offerUntil).toBeNull();
    expect(request.priceUseMode).toBe("OFFER_PRICE");
    expect(request.offerDiscountPercent).toBeNull();
    expect(request.purchaseDiscountPercent).toBeNull();
    expect(request.packageQuantity).toBe("1");
    expect(request.stockMin).toBeNull();
    expect(request.stockMax).toBeNull();
  });

  it("sends serial tracking only for unit products and normalizes no discount", () => {
    const base = {
      ...createDefaultProductForm(),
      familyId: "f",
      taxId: "t",
      name: "A",
      code: "A",
      purchasePrice: "1",
      salePrice: "2",
    };
    expect(
      buildCreateProductRequest({ ...base, requiresSerialNumber: true }),
    ).toMatchObject({
      productType: "UNIT",
      requiresSerialNumber: true,
      priceUseMode: "NORMAL",
    });
    expect(
      buildCreateProductRequest({
        ...base,
        productType: "WEIGHT",
        requiresSerialNumber: true,
      }),
    ).toMatchObject({
      productType: "WEIGHT",
      requiresSerialNumber: false,
    });
    expect(
      buildCreateProductRequest({
        ...base,
        discountType: "NONE",
        priceUseMode: "OFFER_PRICE",
      }),
    ).toMatchObject({
      discountType: "NONE",
      priceUseMode: "NORMAL",
      offerPrice: null,
    });
  });

  it("uses edited form values as authoritative when clearing persisted fields", () => {
    const form = {
      ...createDefaultProductForm(),
      familyId: "f",
      taxId: "t",
      name: "A",
      code: "A",
      purchasePrice: "1",
      salePrice: "2",
      purchaseDiscountPercent: "",
      packageQuantity: "",
      stockMin: "",
      stockMax: "",
    };
    expect(
      buildCreateProductRequest(form, {
        purchaseDiscountPercent: "12",
        packageQuantity: "6",
        stockMin: "2",
        stockMax: "10",
      }),
    ).toMatchObject({
      purchaseDiscountPercent: null,
      packageQuantity: "1",
      stockMin: null,
      stockMax: null,
    });
  });

  it("validates stock bounds and non-negative package quantities", () => {
    const base = {
      ...createDefaultProductForm(),
      familyId: "f",
      taxId: "t",
      name: "A",
      code: "A",
      purchasePrice: "1",
      salePrice: "2",
    };
    expect(
      productCreateValidationErrors({ ...base, packageQuantity: "-1" }),
    ).toContain("packageQuantity");
    expect(
      productCreateValidationErrors({ ...base, packageQuantity: "abc" }),
    ).toContain("packageQuantity");
    expect(
      productCreateValidationErrors({ ...base, stockMin: "8", stockMax: "3" }),
    ).toContain("stockMax");
    expect(
      productCreateValidationErrors({ ...base, stockMin: "-1" }),
    ).toContain("stockMin");
    expect(
      productCreateValidationErrors({
        ...base,
        packageQuantity: "0",
        stockMin: "0",
        stockMax: "0",
      }),
    ).not.toEqual(
      expect.arrayContaining(["packageQuantity", "stockMin", "stockMax"]),
    );
  });

  it("detects duplicated product identifiers before saving", () => {
    const form = {
      ...createDefaultProductForm(),
      code: " A001 ",
      barcode: "843",
      barcode2: "844",
    };

    expect(
      duplicatedProductIdentifierFields(form, [
        { id: "product-2", code: "A001", barcode: null, barcode2: null },
      ]),
    ).toEqual(["code"]);
    expect(
      duplicatedProductIdentifierFields({ ...form, code: "843" }, []),
    ).toEqual([]);
    expect(
      duplicatedProductIdentifierFields(
        { ...form, code: "843", barcode2: "843" },
        [],
      ),
    ).toEqual(["code", "barcode", "barcode2"]);
    expect(
      productCreateValidationErrors(
        {
          ...createDefaultProductForm(),
          familyId: "family-1",
          taxId: "tax-1",
          name: "Articulo",
          code: "0",
          barcode: "0",
          purchasePrice: "0",
          salePrice: "5",
        },
        [{ id: "product-1", code: "0", barcode: "0", barcode2: null }],
        "product-1",
      ),
    ).not.toContain("identifierDuplicate");
    expect(
      buildCreateProductRequest({
        ...createDefaultProductForm(),
        familyId: "family-1",
        taxId: "tax-1",
        name: "Articulo",
        code: "2",
        barcode: "2",
        purchasePrice: "0",
        salePrice: "2",
      }),
    ).toMatchObject({
      code: "2",
      barcode: "2",
    });
    expect(
      productCreateValidationErrors(
        {
          ...createDefaultProductForm(),
          familyId: "family-1",
          taxId: "tax-1",
          name: "Cafe",
          barcode: "843",
          code: "A001",
        },
        [{ id: "product-2", code: null, barcode: null, barcode2: "843" }],
      ),
    ).toContain("identifierDuplicate");
  });

  it("requires offer price and start date when the offer is active", () => {
    expect(
      productCreateValidationErrors({
        ...createDefaultProductForm(),
        familyId: "family-1",
        taxId: "tax-1",
        name: "Cafe",
        code: "A001",
        priceUseMode: "OFFER_PRICE",
      }),
    ).toEqual(["offerPrice"]);
  });

  it("derives active offer from offer price modes", () => {
    expect(
      productCreateValidationErrors({
        ...createDefaultProductForm(),
        familyId: "family-1",
        taxId: "tax-1",
        name: "Cafe",
        code: "A001",
        priceUseMode: "OFFER_PRICE",
        discountType: "DISCOUNT_PRICE",
        offerPrice: "2.50",
        offerFrom: "2026-07-01",
      }),
    ).toEqual([]);

    expect(
      productCreateValidationErrors(
        {
          ...createDefaultProductForm(),
          familyId: "family-1",
          taxId: "tax-1",
          name: "ARTICULO",
          code: "0",
          barcode: "",
          purchasePrice: "0",
          salePrice: "05",
          priceUseMode: "OFFER_PRICE",
          discountType: "DISCOUNT_PRICE",
          offerPrice: "3",
          offerFrom: "2026-07-01",
          offerUntil: "2026-07-31",
        },
        [{ id: "product-1", code: "0", barcode: "", barcode2: null }],
        "product-1",
      ),
    ).toEqual([]);

    expect(
      productCreateValidationErrors(
        {
          ...createDefaultProductForm(),
          familyId: "family-1",
          taxId: "tax-1",
          name: "ARTICULO",
          code: "0",
          barcode: "",
          purchasePrice: "0",
          salePrice: "05",
          priceUseMode: "OFFER_PRICE",
          discountType: "DISCOUNT_PRICE",
          offerPrice: "3",
          offerFrom: "",
          offerUntil: "",
        },
        [{ id: "product-1", code: "0", barcode: "", barcode2: null }],
        "product-1",
      ),
    ).toEqual([]);

    expect(
      buildCreateProductRequest({
        ...createDefaultProductForm(),
        familyId: "family-1",
        taxId: "tax-1",
        name: "Cafe",
        code: "A001",
        priceUseMode: "OFFER_DISCOUNT",
        discountType: "NORMAL",
        salePrice: "10.00",
        offerDiscountPercent: "15",
        offerFrom: "2026-07-01",
      }),
    ).toMatchObject({
      priceUseMode: "OFFER_DISCOUNT",
      discountType: "DISCOUNT_PRICE",
      offerPrice: "8.50",
      offerDiscountPercent: "15",
      offerActive: true,
    });
  });

  it("sends no-discount lock as DiscountType none with sale price mode", () => {
    expect(
      buildCreateProductRequest({
        ...createDefaultProductForm(),
        familyId: "family-1",
        taxId: "tax-1",
        name: "Cafe",
        code: "A001",
        priceUseMode: "OFFER_PRICE",
        discountType: "NONE",
        offerPrice: "8.50",
        offerFrom: "2026-07-01",
      }),
    ).toMatchObject({
      priceUseMode: "NORMAL",
      discountType: "NONE",
      offerActive: false,
      offerPrice: "8.50",
    });
  });

  it("restores the persisted no-discount lock when editing a product", () => {
    const initialData = {
      discountType: "NONE" as const,
      purchaseDiscountPercent: "12.50",
    };
    const form = createProductFormFromEditProduct({
      id: "product-1",
      form: {
        priceUseMode: "OFFER_PRICE",
        discountType: "NORMAL",
        offerActive: true,
        offerPrice: "8.50",
        offerFrom: "2026-07-01",
      },
      initialData,
    });

    expect(form).toMatchObject({
      priceUseMode: "NORMAL",
      discountType: "NONE",
      offerActive: false,
    });
    expect(buildCreateProductRequest(form, initialData)).toMatchObject({
      priceUseMode: "NORMAL",
      discountType: "NONE",
      purchaseDiscountPercent: "12.50",
      offerActive: false,
      offerPrice: "8.50",
      offerDiscountPercent: null,
    });
  });

  it("uses required catalog defaults before validating an edited product", () => {
    const form = createProductFormFromEditProduct({
      id: "product-1",
      form: {
        familyId: "",
        taxId: "",
        name: "ARTICULO",
        code: "0",
        barcode: "",
        purchasePrice: "0",
        salePrice: "5",
        priceUseMode: "OFFER_PRICE",
        offerPrice: "3",
        offerFrom: "2026-07-01",
        offerUntil: "2026-07-31",
      },
    });
    const resolved = applyProductRequiredDefaults(
      form,
      [{ id: "family-general", name: "GENERAL", defaultFamily: true }],
      [{ id: "tax-21", percentage: 21, defaultTax: true }],
    );

    expect(resolved).toMatchObject({
      familyId: "family-general",
      taxId: "tax-21",
    });
    expect(productCreateValidationErrors(resolved, [], "product-1")).toEqual(
      [],
    );
  });

  it("renders the reorganized product form with image panel", () => {
    const html = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );

    expect(html).toContain('class="product-create-body"');
    expect(html).toContain('class="filter-overlay product-create-overlay"');
    expect(html).toContain('class="filter-dialog product-create-dialog"');
    expect(html).toContain('class="product-create-form"');
    expect(html).toContain('class="product-create-media"');
    expect(html).toContain("Código");
    expect(html).toContain("Código de barras 2");
    expect(html).toContain("Tipo");
    expect(html).toContain("Familia");
    expect(html).toContain("Impuesto");
    expect(html).toContain("Oferta desde y hasta");
    expect(html).toContain("Descuento oferta%");
    expect(html).toContain("Usar precio");
    expect(html).toContain("Precio venta");
    expect(html).toContain("No aplicar descuento");
    expect(html).toContain("bloquea precio de miembro");
    expect(html).toContain("el precio mayorista sí está permitido");
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain(
      'aria-describedby="product-no-discount-description"',
    );
    expect(html).toContain('id="product-no-discount-description"');
    expect(html).toContain("Precio de miembro");
    expect(html).toContain("Precio oferta");
    expect(html).toContain("Descuento oferta");
    expect(html).toContain("required");
    expect(html).toContain("Examinar archivo");
    expect(html).toContain("Eliminar imagen");
    expect(html.indexOf('data-product-field-name="barcode"')).toBeLessThan(
      html.indexOf('data-product-field-name="code"'),
    );
    expect(html).toContain("Guardar F9");
    expect(html).not.toContain("Registrar producto y continuar F8");
    expect(html).not.toContain("Registrar producto y cerrar F9");
    expect(html).toContain("Impuestos incluidos en el precio");
    expect(html.indexOf("Usar precio")).toBeLessThan(
      html.indexOf("Precio oferta"),
    );
    expect(html.indexOf("Precio oferta")).toBeLessThan(
      html.indexOf("Descuento oferta%"),
    );
    expect(html.indexOf("Descuento oferta%")).toBeLessThan(
      html.indexOf("Oferta desde y hasta"),
    );
    expect(html.indexOf("Oferta desde y hasta")).toBeLessThan(
      html.indexOf("Oferta activa"),
    );
    expect(html).not.toContain("<select");
  });

  it("renders full English price and discount labels", () => {
    const html = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="en"
        token="token"
        onClose={() => undefined}
      />,
    );

    expect(html).toContain("Purchase price");
    expect(html).toContain("Sale price");
    expect(html).toContain("Member price");
    expect(html).toContain("Wholesale price");
    expect(html).toContain("Offer price");
    expect(html).toContain("Use price");
    expect(html).toContain("Sale price");
    expect(html).toContain("Do not apply discount");
    expect(html).toContain(
      "member prices, offers, category/member discounts, coupons, promotions, member balance and manual discounts are blocked; wholesale pricing is allowed",
    );
    expect(html).toContain("Offer discount%");
    expect(html).toContain("Save F9");
    expect(html).not.toContain("Register product and continue F8");
    expect(html).not.toContain("Register product and close F9");
  });

  it("exposes the primary supplier selector when modifying an existing product", () => {
    const html = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{ id: "product-1", form: { name: "Cafe" } }}
        onClose={() => undefined}
      />,
    );

    expect(html).toContain("Proveedor principal");
    expect(html).toContain("Sin proveedores vinculados");
  });

  it("creates products active by default and exposes the state only while editing", () => {
    const createHtml = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />,
    );
    const editHtml = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{ id: "product-1", form: { name: "Cafe", active: false } }}
        onClose={() => undefined}
      />,
    );

    expect(createDefaultProductForm().active).toBe(true);
    expect(createHtml).not.toContain('data-product-field-name="active"');
    expect(editHtml).toContain('data-product-field-name="active"');
    expect(editHtml).toContain("Desactivado");
    expect(
      buildCreateProductRequest(
        createProductFormFromEditProduct({
          id: "product-1",
          form: { active: false },
        }),
      ),
    ).toMatchObject({ active: false });
  });

  it("does not expose the deprecated none or member discount options", () => {
    expect(productDiscountTypeOptions).toEqual([
      "NORMAL",
      "MEMBER_PRICE",
      "OFFER_PRICE",
      "OFFER_DISCOUNT",
    ]);
    expect(productDiscountTypeOptions).not.toContain("NONE");
    expect(productDiscountTypeOptions).not.toContain("MEMBER_DISCOUNT");
  });

  it("locks every use-price option while no discount is active and unlocks them from the lock control", () => {
    const { container } = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{ id: "product-1", form: { discountType: "NONE" } }}
        onClose={() => undefined}
      />,
    );

    const priceOptions = within(container).getAllByRole("radio");
    expect(priceOptions).not.toHaveLength(0);
    priceOptions.forEach((option) => expect(option).toBeDisabled());

    fireEvent.click(
      container.querySelector<HTMLButtonElement>(
        'button[data-product-field-name="discountType"]',
      )!,
    );

    priceOptions.forEach((option) => expect(option).not.toBeDisabled());
  });

  it("builds the product image upload path", () => {
    expect(productImageUploadPath("product-1")).toBe(
      "/products/product-1/image",
    );
    expect(productImageReadPath("product-1")).toBe("/products/product-1/image");
  });

  it("shows a manually selected primary supplier before the latest supplier", () => {
    const suppliers = [
      {
        supplierId: "supplier-last",
        legalName: "Proveedor ultimo",
        active: true,
        principal: false,
        lastSupplier: true,
      },
      {
        supplierId: "supplier-primary",
        legalName: "Proveedor principal",
        active: true,
        principal: true,
        lastSupplier: false,
      },
    ];

    expect(preferredProductSupplier(suppliers)?.supplierId).toBe(
      "supplier-primary",
    );
    expect(preferredProductSupplier(suppliers.slice(0, 1))?.supplierId).toBe(
      "supplier-last",
    );
    expect(preferredProductSupplier([])).toBeNull();
  });

  it("does not expose low-level network write errors in product dialog status", () => {
    expect(
      productCreateErrorMessage(
        new TypeError("Failed to write request"),
        "No se pudo cargar",
      ),
    ).toBe("No se pudo cargar");
    expect(
      productCreateErrorMessage(
        new Error("Failed to write request"),
        "No se pudo cargar",
      ),
    ).toBe("No se pudo cargar");
    expect(
      productCreateErrorMessage(
        new Error("Codigo duplicado"),
        "No se pudo cargar",
      ),
    ).toBe("Codigo duplicado");
    expect(
      productCreateErrorMessage(
        new ApiError(
          "La operacion entra en conflicto con los datos existentes",
          409,
          { code: "DATA_INTEGRITY_CONFLICT" },
        ),
        "No se pudo registrar",
        "El codigo o codigo de barras ya existe",
      ),
    ).toBe("El codigo o codigo de barras ya existe");
  });

  it("keeps the product creation when the optional image upload fails", async () => {
    const form = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      purchasePrice: "1.20",
      salePrice: "2.40",
    };
    const createdProduct = { id: "product-1", code: "A001", name: "Cafe" };
    const createProduct = vi.fn().mockResolvedValue(createdProduct);
    const uploadImage = vi
      .fn()
      .mockRejectedValue(new TypeError("Failed to write request"));

    await expect(
      saveProductWithOptionalImage({
        form,
        token: "token",
        imageFile: {} as File,
        createProduct,
        uploadImage,
      }),
    ).resolves.toEqual({
      product: createdProduct,
      imageUploadFailed: true,
    });
    expect(createProduct).toHaveBeenCalledWith(
      buildCreateProductRequest(form),
      "token",
    );
    expect(uploadImage).toHaveBeenCalledWith(
      "product-1",
      expect.anything(),
      "token",
    );
  });

  it("updates an existing product when a product id is provided", async () => {
    const initialData = {
      discountType: "NONE" as const,
      purchaseDiscountPercent: "7.25",
    };
    const form = createProductFormFromEditProduct({
      id: "product-1",
      form: {
        familyId: "family-1",
        taxId: "tax-1",
        name: "Cafe",
        code: "A001",
        purchasePrice: "1.20",
        salePrice: "2.40",
        priceUseMode: "NORMAL",
        discountType: "NORMAL",
      },
      initialData,
    });
    const updatedProduct = { id: "product-1", code: "A001", name: "Cafe" };
    const createProduct = vi.fn();
    const updateProduct = vi.fn().mockResolvedValue(updatedProduct);

    await expect(
      saveProductWithOptionalImage({
        form,
        token: "token",
        imageFile: null,
        productId: "product-1",
        initialData,
        createProduct,
        updateProduct,
      }),
    ).resolves.toEqual({
      product: updatedProduct,
      imageUploadFailed: false,
    });
    expect(createProduct).not.toHaveBeenCalled();
    expect(updateProduct).toHaveBeenCalledWith(
      "product-1",
      buildCreateProductRequest(form, initialData),
      "token",
    );
    expect(updateProduct).toHaveBeenCalledWith(
      "product-1",
      expect.objectContaining({
        discountType: "NONE",
        purchaseDiscountPercent: "7.25",
      }),
      "token",
    );
  });
});
