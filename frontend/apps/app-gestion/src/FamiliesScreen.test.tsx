// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  createTranslator,
  type apiRequest,
  type UserSession,
} from "@tpverp/app-common";
import {
  FamiliesScreen,
  formatDeleteDependency,
  normalizeImpact,
} from "./FamiliesScreen";
import { normalizeSubfamily } from "./familiesApi";

const gestionStyles = document.createElement("style");
gestionStyles.textContent = readFileSync(
  resolve(process.cwd(), "apps/app-gestion/src/gestion.css"),
  "utf8",
);
document.head.append(gestionStyles);

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
  accessToken: "token",
};
const families = [
  { id: "general", name: "GENERAL", familyCode: "000", defaultFamily: true },
  { id: "drinks", name: "Bebidas", familyCode: "001", defaultFamily: false },
  { id: "snacks", name: "Snacks", familyCode: "002", defaultFamily: false },
];
const subfamilies = [
  {
    id: "water",
    familyId: "drinks",
    name: "Agua",
    subfamilyCode: "001001",
    subfamilySuffix: "001",
  },
];
const DRINK_PRODUCTS_NAME_ASC =
  "/families/products?familyId=drinks&limit=25&sortBy=name&sortDirection=asc";
const DRINK_PRODUCTS_NAME_ASC_CURSOR_2 =
  `${DRINK_PRODUCTS_NAME_ASC}&cursor=cursor-2`;
const DRINK_PRODUCTS_CODE_ASC =
  "/families/products?familyId=drinks&limit=25&sortBy=code&sortDirection=asc";
const DRINK_PRODUCTS_CODE_DESC =
  "/families/products?familyId=drinks&limit=25&sortBy=code&sortDirection=desc";
const DRINK_PRODUCTS_PRICE_ASC =
  "/families/products?familyId=drinks&limit=25&sortBy=salePrice&sortDirection=asc";
const WATER_PRODUCTS_NAME_ASC =
  "/families/products?subfamilyId=water&limit=25&sortBy=name&sortDirection=asc";

afterEach(cleanup);

function renderScreen(request: typeof apiRequest) {
  return render(
    <FamiliesScreen
      session={session}
      t={createTranslator("es")}
      request={request}
    />,
  );
}

describe("FamiliesScreen", () => {
  it("renders the catalogue as a two-pane workspace using the shared ERP table", async () => {
    const longName =
      "CABLE DE DATOS S.BASIC FLUTE PARA IP6/7/8/X/XS, 1M, 3.4A, BLANCO";
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === DRINK_PRODUCTS_NAME_ASC)
        return {
          items: [
            {
              id: "p1",
              code: "",
              barcode: "8414055002216",
              name: longName,
              salePrice: 1,
              active: true,
              version: 1,
            },
          ],
          hasMore: false,
        };
      return [];
    });
    const view = renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));

    const workspace = view.container.querySelector(
      ".gestion-families-workspace",
    );
    expect(workspace).not.toBeNull();
    expect(workspace?.children).toHaveLength(2);
    expect(workspace?.children[0]).toHaveClass("gestion-families-tree-pane");
    const productsColumn = workspace?.children[1];
    expect(productsColumn).toHaveClass(
      "gestion-families-products-column",
    );
    expect(getComputedStyle(workspace!).display).toBe("grid");
    expect(getComputedStyle(workspace!).gridTemplateColumns).toBe(
      "minmax(300px, 1fr) minmax(0, 2fr)",
    );
    expect(getComputedStyle(workspace!).gridRowStart).toBe("3");
    expect(await screen.findByRole("table")).toHaveClass(
      "report-table",
      "gestion-families-products-table",
    );
    const thumbnail = view.container.querySelector(
      ".gestion-family-product-thumb",
    );
    expect(thumbnail).toBeInTheDocument();
    expect(getComputedStyle(thumbnail!).width).toBe("36px");
    expect(getComputedStyle(thumbnail!).height).toBe("36px");

    const productName = screen.getByText(longName);
    const productNameCell = productName.closest("td")!;
    expect(productNameCell).toHaveClass("gestion-family-product-name-cell");
    expect(productNameCell).toHaveAttribute("title", longName);
    expect(getComputedStyle(productNameCell).minWidth).toBe("0px");
    expect(getComputedStyle(productNameCell).overflow).toBe("hidden");
    expect(productName).toHaveClass("gestion-family-product-name");
    expect(getComputedStyle(productName).whiteSpace).toBe("nowrap");
    expect(getComputedStyle(productName).textOverflow).toBe("ellipsis");
    expect(screen.getByText("8414055002216")).toBeInTheDocument();

    const productCheckbox = screen.getByLabelText(`Seleccionar ${longName}`);
    const productRow = productCheckbox.closest("tr")!;
    expect(productRow).toHaveAttribute("tabindex", "0");

    fireEvent.click(screen.getByText("8414055002216"));
    expect(productCheckbox).toBeChecked();
    fireEvent.click(productCheckbox);
    expect(productCheckbox).not.toBeChecked();

    productRow.focus();
    fireEvent.keyDown(productRow, { key: "Enter" });
    expect(productCheckbox).toBeChecked();
    fireEvent.keyDown(productRow, { key: " " });
    expect(productCheckbox).not.toBeChecked();
    fireEvent.keyDown(productRow, { key: "Enter" });
    expect(productRow).toHaveClass("selected");
    expect(
      productsColumn?.querySelector(".gestion-families-selection-bar"),
    ).toBeInTheDocument();
  });

  it("sorts the complete product feed from the table headers without clearing selection", async () => {
    const product = {
      id: "p1",
      code: "A-01",
      barcode: "8414055002216",
      salePrice: 1,
      active: true,
      version: 7,
    };
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === "/families/drinks/subfamilies") return [];
      if (path === DRINK_PRODUCTS_NAME_ASC)
        return {
          items: [{ ...product, name: "Nombre inicial" }],
          hasMore: false,
        };
      if (path === DRINK_PRODUCTS_CODE_ASC)
        return {
          items: [{ ...product, name: "Código ascendente" }],
          hasMore: false,
        };
      if (path === DRINK_PRODUCTS_CODE_DESC)
        return {
          items: [{ ...product, name: "Código descendente" }],
          hasMore: false,
        };
      if (path === DRINK_PRODUCTS_PRICE_ASC)
        return {
          items: [{ ...product, name: "Precio ascendente" }],
          hasMore: false,
        };
      return [];
    });
    const view = renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    await screen.findByText("Nombre inicial");

    expect(request).toHaveBeenCalledWith(DRINK_PRODUCTS_NAME_ASC, {
      token: "token",
    });
    let table = screen.getByRole("table");
    const headers = within(table).getAllByRole("columnheader");
    expect(headers).toHaveLength(5);
    expect(within(headers[0]).queryByRole("button")).toBeNull();
    expect(within(headers[1]).queryByRole("button")).toBeNull();

    let codeSort = within(table).getByRole("button", {
      name: "Ordenar por Código",
    });
    const nameSort = within(table).getByRole("button", {
      name: "Ordenar por Nombre",
    });
    const priceSort = within(table).getByRole("button", {
      name: "Ordenar por Precio venta",
    });
    expect(codeSort).toHaveAttribute("data-sort-direction", "none");
    expect(codeSort.closest("th")).toHaveAttribute("aria-sort", "none");
    expect(nameSort).toHaveAttribute("data-sort-direction", "asc");
    expect(nameSort.closest("th")).toHaveAttribute("aria-sort", "ascending");
    expect(priceSort).toHaveAttribute("data-sort-direction", "none");
    expect(priceSort.closest("th")).toHaveAttribute("aria-sort", "none");
    expect(
      getComputedStyle(
        codeSort.querySelector(".table-layout-sort-indicator")!,
      ).visibility,
    ).toBe("hidden");
    expect(
      getComputedStyle(
        nameSort.querySelector(".table-layout-sort-indicator")!,
      ).visibility,
    ).toBe("visible");

    fireEvent.click(screen.getByLabelText("Seleccionar Nombre inicial"));
    const initialScroll = view.container.querySelector(
      ".gestion-families-products-table-wrap",
    )!;
    Object.defineProperty(initialScroll, "scrollTop", {
      configurable: true,
      value: 320,
      writable: true,
    });
    fireEvent.click(codeSort);

    await screen.findByText("Código ascendente");
    expect(request).toHaveBeenCalledWith(DRINK_PRODUCTS_CODE_ASC, {
      token: "token",
    });
    expect(
      view.container.querySelector<HTMLElement>(
        ".gestion-families-products-table-wrap",
      )?.scrollTop,
    ).toBe(0);
    expect(
      screen.getByLabelText("Seleccionar Código ascendente"),
    ).toBeChecked();
    expect(screen.getByText("1 seleccionados")).toBeInTheDocument();
    table = screen.getByRole("table");
    codeSort = within(table).getByRole("button", {
      name: "Ordenar por Código",
    });
    expect(codeSort).toHaveAttribute("data-sort-direction", "asc");
    expect(codeSort.closest("th")).toHaveAttribute("aria-sort", "ascending");
    expect(
      getComputedStyle(
        codeSort.querySelector(".table-layout-sort-indicator")!,
      ).visibility,
    ).toBe("visible");

    fireEvent.click(codeSort);
    await screen.findByText("Código descendente");
    expect(request).toHaveBeenCalledWith(DRINK_PRODUCTS_CODE_DESC, {
      token: "token",
    });
    codeSort = within(screen.getByRole("table")).getByRole("button", {
      name: "Ordenar por Código",
    });
    expect(codeSort).toHaveAttribute("data-sort-direction", "desc");
    expect(codeSort.closest("th")).toHaveAttribute("aria-sort", "descending");

    fireEvent.click(
      within(screen.getByRole("table")).getByRole("button", {
        name: "Ordenar por Precio venta",
      }),
    );
    await screen.findByText("Precio ascendente");
    expect(request).toHaveBeenCalledWith(DRINK_PRODUCTS_PRICE_ASC, {
      token: "token",
    });
    const activePriceSort = within(screen.getByRole("table")).getByRole(
      "button",
      { name: "Ordenar por Precio venta" },
    );
    expect(activePriceSort).toHaveAttribute("data-sort-direction", "asc");
    expect(activePriceSort.closest("th")).toHaveAttribute(
      "aria-sort",
      "ascending",
    );
    expect(screen.getByLabelText("Seleccionar Precio ascendente")).toBeChecked();
  });

  it("discards a pending cursor response when the global sort changes", async () => {
    let resolveOldCursor!: (value: {
      items: Array<Record<string, unknown>>;
      hasMore: boolean;
    }) => void;
    const oldCursor = new Promise<{
      items: Array<Record<string, unknown>>;
      hasMore: boolean;
    }>((resolve) => {
      resolveOldCursor = resolve;
    });
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === "/families/drinks/subfamilies") return [];
      if (path === DRINK_PRODUCTS_NAME_ASC)
        return {
          items: [
            {
              id: "p1",
              code: "A",
              name: "Resultado inicial",
              salePrice: 1,
              active: true,
              version: 1,
            },
          ],
          nextCursor: "cursor-2",
          hasMore: true,
        };
      if (path === DRINK_PRODUCTS_NAME_ASC_CURSOR_2) return oldCursor;
      if (path === DRINK_PRODUCTS_CODE_ASC)
        return {
          items: [
            {
              id: "p1",
              code: "A",
              name: "Resultado por código",
              salePrice: 1,
              active: true,
              version: 1,
            },
          ],
          hasMore: false,
        };
      return [];
    });
    const view = renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    await screen.findByText("Resultado inicial");
    const scroll = view.container.querySelector(
      ".gestion-families-products-table-wrap",
    )!;
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 400 },
      scrollHeight: { configurable: true, value: 1000 },
      scrollTop: { configurable: true, value: 550, writable: true },
    });
    fireEvent.scroll(scroll);
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith(
        DRINK_PRODUCTS_NAME_ASC_CURSOR_2,
        { token: "token" },
      ),
    );

    fireEvent.click(
      within(screen.getByRole("table")).getByRole("button", {
        name: "Ordenar por Código",
      }),
    );
    await screen.findByText("Resultado por código");
    expect(request).toHaveBeenCalledWith(DRINK_PRODUCTS_CODE_ASC, {
      token: "token",
    });

    await act(async () => {
      resolveOldCursor({
        items: [
          {
            id: "p2",
            code: "Z",
            name: "Respuesta antigua",
            salePrice: 9,
            active: true,
            version: 2,
          },
        ],
        hasMore: false,
      });
      await oldCursor;
    });
    expect(screen.queryByText("Respuesta antigua")).toBeNull();
    expect(screen.getByText("Resultado por código")).toBeInTheDocument();
  });

  it("normalizes delete impact and dependency objects", () => {
    expect(
      normalizeImpact({
        productCount: 3,
        promotionCount: 2,
        priceRuleCount: 4,
        blocked: true,
        dependencies: [
          {
            sourceType: "PROMOTION",
            targetType: "FAMILY",
            id: "p-1",
            name: "Oferta",
          },
        ],
      }),
    ).toEqual({
      products: 3,
      promotions: 2,
      rules: 4,
      blocked: true,
      dependencies: [
        {
          sourceType: "PROMOTION",
          targetType: "FAMILY",
          id: "p-1",
          name: "Oferta",
        },
      ],
    });
    expect(
      formatDeleteDependency(
        { sourceType: "PROMOTION", targetType: "FAMILY", name: "Oferta" },
        createTranslator("es"),
      ),
    ).toBe("Promoción → Familia: Oferta");
  });

  it("uses business codes and uppercase names in the add-family dialog", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/families") return families;
        if (path === "/families/next-code") return { familyCode: "003" };
        if (options?.method === "POST")
          return {
            id: "new",
            name: "FRUTAS",
            familyCode: "003",
            defaultFamily: false,
          };
        return [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(
      await screen.findByRole("button", { name: "Añadir familia" }),
    );
    const name = await screen.findByLabelText("Nombre");
    fireEvent.change(name, { target: { value: "frutas" } });
    expect(name).toHaveValue("FRUTAS");
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith("/families", {
        method: "POST",
        token: "token",
        body: { name: "FRUTAS", familyCode: "003" },
      }),
    );
  });

  it("uses the formal modal shell and accessible close controls for family actions", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/families") return families;
        if (path === "/families/next-code") return { familyCode: "003" };
        if (path === "/families/drinks/subfamilies") return [];
        if (path === DRINK_PRODUCTS_NAME_ASC)
          return {
            items: [
              {
                id: "p1",
                code: "A",
                name: "Agua producto",
                salePrice: 1,
                active: true,
                familyId: "drinks",
                subfamilyId: "",
                version: 1,
              },
            ],
            hasMore: false,
          };
        if (path === "/families/drinks/delete-impact")
          return { products: 1, promotions: 0, rules: 0, blocked: false };
        return options?.method === "POST" ? undefined : [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);

    fireEvent.click(
      await screen.findByRole("button", { name: "Añadir familia" }),
    );
    const editorDialog = await screen.findByRole("dialog", {
      name: "Añadir familia",
    });
    expect(editorDialog).toHaveClass(
      "gestion-security-dialog",
      "gestion-family-editor-dialog",
      "gestion-family-editor-family",
      "is-create",
    );
    expect(editorDialog.parentElement).toHaveClass("gestion-modal-backdrop");
    const editorClose = within(editorDialog).getByRole("button", {
      name: "Cerrar",
    });
    expect(editorClose).toHaveAttribute("title", "Cerrar");
    expect(editorClose).toHaveTextContent("×");
    fireEvent.click(within(editorDialog).getByRole("button", { name: "Cancelar" }));

    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua producto"));
    fireEvent.click(screen.getByRole("button", { name: "Mover" }));
    const moveDialog = await screen.findByRole("dialog", {
      name: "Mover productos",
    });
    expect(moveDialog).toHaveClass(
      "gestion-security-dialog",
      "gestion-family-move-dialog",
    );
    expect(moveDialog.parentElement).toHaveClass("gestion-modal-backdrop");
    const moveClose = within(moveDialog).getByRole("button", { name: "Cerrar" });
    expect(moveClose).toHaveAttribute("title", "Cerrar");
    expect(moveClose).toHaveTextContent("×");
    fireEvent.click(within(moveDialog).getByRole("button", { name: "Cancelar" }));

    fireEvent.click(screen.getByRole("button", { name: "Eliminar de familia" }));
    const generalDialog = await screen.findByRole("dialog", {
      name: "Eliminar de familia",
    });
    expect(generalDialog).toHaveClass(
      "gestion-security-dialog",
      "gestion-family-general-dialog",
    );
    const generalClose = within(generalDialog).getByRole("button", {
      name: "Cerrar",
    });
    expect(generalClose).toHaveAttribute("title", "Cerrar");
    expect(generalClose).toHaveTextContent("×");
    fireEvent.click(generalClose);

    const family = screen.getByRole("treeitem", { name: /001.*Bebidas/ });
    fireEvent.click(within(family).getByRole("button", { name: "Eliminar" }));
    const impactDialog = await screen.findByRole("dialog", {
      name: "Impacto de la eliminación",
    });
    expect(impactDialog).toHaveClass(
      "gestion-security-dialog",
      "gestion-family-impact-dialog",
      "has-related-products",
    );
    const impactClose = within(impactDialog).getByRole("button", {
      name: "Cerrar",
    });
    expect(impactClose).toHaveAttribute("title", "Cerrar");
    expect(impactClose).toHaveTextContent("×");
  });

  it("opens Add subfamily blank for GENERAL and anchors its list below the family field", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === "/families/drinks/subfamilies") return subfamilies;
      if (path === "/families/drinks/subfamilies/next-suffix")
        return { subfamilySuffix: "002" };
      return [];
    });
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(
      await screen.findByRole("treeitem", { name: /000.*GENERAL/ }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Añadir subfamilia" }),
    );
    const combo = await screen.findByRole("combobox");
    expect(combo).toHaveValue("");
    expect(combo).toHaveAttribute("aria-expanded", "true");
    const listbox = screen.getByRole("listbox");
    expect(combo.parentElement).toHaveClass("gestion-family-combobox");
    expect(combo.parentElement).toContainElement(listbox);
    expect(getComputedStyle(listbox).top).toBe("100%");
    expect(getComputedStyle(listbox).width).toBe("100%");
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    fireEvent.click(screen.getByText("Bebidas"));
    await screen.findByText("Agua");
    fireEvent.click(screen.getByText("Agua"));
    fireEvent.click(screen.getByRole("button", { name: "Añadir subfamilia" }));
    expect(await screen.findByRole("combobox")).toHaveValue("001 — Bebidas");
    expect(screen.getByRole("combobox")).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith(
        "/families/drinks/subfamilies/next-suffix",
        { token: "token" },
      ),
    );
  });

  it("preserves a manually entered suffix when a delayed suggestion arrives", async () => {
    let resolveSuffix!: (value: { subfamilySuffix: string }) => void;
    const suffixResponse = new Promise<{ subfamilySuffix: string }>((resolve) => {
      resolveSuffix = resolve;
    });
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === "/families/drinks/subfamilies") return [];
      if (path === "/families/drinks/subfamilies/next-suffix")
        return suffixResponse;
      return [];
    });
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(screen.getByRole("button", { name: "Añadir subfamilia" }));
    const suffix = await screen.findByLabelText("Sufijo de subfamilia");
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith(
        "/families/drinks/subfamilies/next-suffix",
        { token: "token" },
      ),
    );

    fireEvent.change(suffix, { target: { value: "007" } });
    expect(suffix).toHaveValue("007");
    await act(async () => {
      resolveSuffix({ subfamilySuffix: "001" });
      await suffixResponse;
    });

    expect(suffix).toHaveValue("007");
  });

  it("merges a subfamily created from the blank combobox with the authoritative lazy branch", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/families") return families;
        if (path === "/families/drinks/subfamilies/next-suffix")
          return { subfamilySuffix: "002" };
        if (path === "/families/drinks/subfamilies" && options?.method === "POST")
          return {
            id: "tea",
            familyId: "drinks",
            name: "Te",
            subfamilyCode: "001002",
            subfamilySuffix: "002",
          };
        if (path === "/families/drinks/subfamilies") return subfamilies;
        return [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(
      await screen.findByRole("button", { name: "Añadir subfamilia" }),
    );
    const combo = await screen.findByRole("combobox");
    fireEvent.change(combo, { target: { value: "Bebidas" } });
    fireEvent.click(await screen.findByRole("option", { name: /001 — Bebidas/ }));
    await waitFor(() =>
      expect(screen.getByLabelText("Sufijo de subfamilia")).toHaveValue("002"),
    );
    fireEvent.change(screen.getByLabelText("Sufijo de subfamilia"), {
      target: { value: "002" },
    });
    fireEvent.change(screen.getByLabelText("Nombre"), {
      target: { value: "Te" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith("/families/drinks/subfamilies", {
        method: "POST",
        token: "token",
        body: { name: "TE", subfamilySuffix: "002" },
      }),
    );
    fireEvent.click(await screen.findByRole("treeitem", { name: /001.*Bebidas/ }));
    expect(await screen.findByText("Agua")).toBeInTheDocument();
    expect(screen.getByText("Te")).toBeInTheDocument();
  });

  it("keeps product versions when selecting across cursor pages and sends the bulk contract", async () => {
    let resolveNextPage!: (value: {
      items: Array<Record<string, unknown>>;
      hasMore: boolean;
    }) => void;
    const nextPage = new Promise<{
      items: Array<Record<string, unknown>>;
      hasMore: boolean;
    }>((resolve) => {
      resolveNextPage = resolve;
    });
    const request = vi.fn(
      async (path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/families") return families;
        if (path === "/families/drinks/subfamilies") return subfamilies;
        if (path === DRINK_PRODUCTS_NAME_ASC)
          return {
            items: [
              {
                id: "p1",
                code: "A",
                name: "Agua",
                salePrice: 1,
                active: true,
                version: 7,
              },
            ],
            nextCursor: "cursor-2",
            hasMore: true,
          };
        if (
          path === DRINK_PRODUCTS_NAME_ASC_CURSOR_2
        )
          return nextPage;
        if (path === "/products/classification/move") return undefined;
        return options?.method === "POST" ? undefined : [];
      },
    );
    const view = renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua"));
    const scroll = view.container.querySelector(
      ".gestion-families-products-table-wrap",
    )!;
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 400 },
      scrollHeight: { configurable: true, value: 1000 },
      scrollTop: { configurable: true, value: 550, writable: true },
    });
    fireEvent.scroll(scroll);
    fireEvent.scroll(scroll);
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Cargando productos…",
    );
    expect(
      request.mock.calls.filter(
        ([path]) =>
          path ===
          DRINK_PRODUCTS_NAME_ASC_CURSOR_2,
      ),
    ).toHaveLength(1);
    await act(async () => {
      resolveNextPage({
        items: [
          {
            id: "p1",
            code: "A",
            name: "Agua",
            salePrice: 1,
            active: true,
            version: 7,
          },
          {
            id: "p2",
            code: "B",
            name: "Bebida",
            salePrice: 2,
            active: false,
            version: 9,
          },
        ],
        hasMore: false,
      });
      await nextPage;
    });
    await screen.findByText("Bebida");
    expect(
      within(screen.getByRole("table")).getAllByText("Agua"),
    ).toHaveLength(1);
    expect(screen.getByLabelText("Seleccionar Agua")).toBeChecked();
    expect(
      screen.queryByRole("button", { name: "Siguiente" }),
    ).toBeNull();
    fireEvent.click(screen.getByLabelText("Seleccionar Bebida"));
    fireEvent.click(screen.getByRole("button", { name: "Eliminar de familia" }));
    fireEvent.click(
      (await screen.findAllByRole("button", { name: "Eliminar de familia" })).at(
        -1,
      )!,
    );
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith("/products/classification/move", {
        method: "POST",
        token: "token",
        body: {
          items: [
            { productId: "p1", expectedVersion: 7 },
            { productId: "p2", expectedVersion: 9 },
          ],
          familyId: null,
          subfamilyId: null,
        },
      }),
    );
  });

  it("does not loop after an incremental-load error and retries on a later scroll", async () => {
    let nextPageAttempts = 0;
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === "/families/drinks/subfamilies") return [];
      if (path === DRINK_PRODUCTS_NAME_ASC)
        return {
          items: [
            {
              id: "p1",
              code: "A",
              name: "Agua",
              salePrice: 1,
              active: true,
              version: 7,
            },
          ],
          nextCursor: "cursor-2",
          hasMore: true,
        };
      if (
        path ===
        DRINK_PRODUCTS_NAME_ASC_CURSOR_2
      ) {
        nextPageAttempts += 1;
        if (nextPageAttempts === 1) throw new Error("load_more_failed");
        return {
          items: [
            {
              id: "p2",
              code: "B",
              name: "Bebida",
              salePrice: 2,
              active: true,
              version: 8,
            },
          ],
          hasMore: false,
        };
      }
      return [];
    });
    const view = renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua"));
    const scroll = view.container.querySelector(
      ".gestion-families-products-table-wrap",
    )!;
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 400 },
      scrollHeight: { configurable: true, value: 1000 },
      scrollTop: { configurable: true, value: 550, writable: true },
    });

    fireEvent.scroll(scroll);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No se pudieron cargar los productos.",
    );
    await act(
      () =>
        new Promise<void>((resolve) =>
          window.requestAnimationFrame(() => resolve()),
        ),
    );
    expect(nextPageAttempts).toBe(1);
    expect(screen.getByLabelText("Seleccionar Agua")).toBeChecked();

    fireEvent.scroll(scroll);
    expect(await screen.findByText("Bebida")).toBeInTheDocument();
    expect(nextPageAttempts).toBe(2);
    expect(screen.getByLabelText("Seleccionar Agua")).toBeChecked();
  });

  it("keeps disabled move destinations out of the roving keyboard focus", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === "/families/drinks/subfamilies") return [];
      if (path === DRINK_PRODUCTS_NAME_ASC)
        return {
          items: [
            {
              id: "p1",
              code: "A",
              name: "Agua producto",
              salePrice: 1,
              active: true,
              familyId: "drinks",
              subfamilyId: "",
              version: 7,
            },
          ],
          hasMore: false,
        };
      return [];
    });
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua producto"));
    fireEvent.click(screen.getByRole("button", { name: "Mover" }));
    const moveDialog = await screen.findByRole("dialog", {
      name: "Mover productos",
    });
    const general = within(moveDialog).getByRole("treeitem", {
      name: "000 GENERAL",
    });
    const currentFamily = within(moveDialog).getByRole("treeitem", {
      name: "001 Bebidas",
    });
    const firstDestination = within(moveDialog).getByRole("treeitem", {
      name: "002 Snacks",
    });

    expect(general).toBeDisabled();
    expect(general).toHaveAttribute("tabindex", "-1");
    expect(currentFamily).toBeDisabled();
    expect(currentFamily).toHaveAttribute("tabindex", "-1");
    await waitFor(() =>
      expect(firstDestination).toHaveAttribute("tabindex", "0"),
    );

    firstDestination.focus();
    fireEvent.keyDown(firstDestination, { key: "Home" });
    await waitFor(() => expect(firstDestination).toHaveFocus());
    fireEvent.keyDown(firstDestination, { key: "ArrowUp" });
    await waitFor(() => expect(firstDestination).toHaveFocus());
    fireEvent.keyDown(firstDestination, { key: "Enter" });

    expect(firstDestination).toHaveAttribute("aria-selected", "true");
    expect(
      within(moveDialog).getByRole("button", { name: "Mover" }),
    ).toBeEnabled();
  });

  it("allows clearing subfamilies by moving a mixed selection to its family", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/families") return families;
        if (path === "/families/drinks/subfamilies") return subfamilies;
        if (path === DRINK_PRODUCTS_NAME_ASC)
          return {
            items: [
              {
                id: "family-product",
                code: "F",
                name: "Producto de familia",
                salePrice: 1,
                active: true,
                familyId: "drinks",
                subfamilyId: "",
                version: 4,
              },
              {
                id: "subfamily-product",
                code: "S",
                name: "Producto de subfamilia",
                salePrice: 2,
                active: true,
                familyId: "drinks",
                subfamilyId: "water",
                version: 6,
              },
            ],
            hasMore: false,
          };
        if (path === "/products/classification/move" && options?.method === "POST")
          return undefined;
        return [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(
      await screen.findByLabelText("Seleccionar Producto de familia"),
    );
    fireEvent.click(
      screen.getByLabelText("Seleccionar Producto de subfamilia"),
    );
    fireEvent.click(screen.getByRole("button", { name: "Mover" }));
    const moveDialog = await screen.findByRole("dialog", {
      name: "Mover productos",
    });
    const currentFamily = within(moveDialog).getByRole("treeitem", {
      name: "001 Bebidas",
    });

    expect(currentFamily).toBeEnabled();
    fireEvent.click(currentFamily);
    fireEvent.click(within(moveDialog).getByRole("button", { name: "Mover" }));

    await waitFor(() =>
      expect(request).toHaveBeenCalledWith("/products/classification/move", {
        method: "POST",
        token: "token",
        body: {
          items: [
            { productId: "family-product", expectedVersion: 4 },
            { productId: "subfamily-product", expectedVersion: 6 },
          ],
          familyId: "drinks",
          subfamilyId: null,
        },
      }),
    );
  });

  it("finds an unloaded subfamily globally while choosing a move destination", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/families") return families;
        if (path === "/families/drinks/subfamilies") return [];
        if (path === DRINK_PRODUCTS_NAME_ASC)
          return {
            items: [
              {
                id: "p1",
                code: "A",
                name: "Agua producto",
                salePrice: 1,
                active: true,
                version: 7,
              },
            ],
            hasMore: false,
          };
        if (path === "/families/search?q=refresco&limit=50&cursor=cursor-2")
          return { items: [], nextCursor: "", hasMore: false };
        if (path === "/families/search?q=refresco&limit=50")
          return {
            items: [
              {
                kind: "SUBFAMILY",
                id: "subfamily-remote",
                familyId: "drinks",
                subfamilyId: "subfamily-remote",
                code: "001456",
                suffix: "456",
                familyCode: "001",
                name: "Refrescos remotos",
                defaultFamily: false,
              },
            ],
            nextCursor: "cursor-2",
            hasMore: true,
          };
        if (path === "/products/classification/move") return undefined;
        return options?.method === "POST" ? undefined : [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua producto"));
    fireEvent.click(screen.getAllByRole("button", { name: "Mover" })[0]);
    const search = await screen.findByRole("searchbox", {
      name: "Buscar por código o nombre",
    });
    fireEvent.change(search, { target: { value: "refresco" } });
    await screen.findByText("Refrescos remotos");
    fireEvent.click(screen.getByRole("button", { name: "Cargar más" }));
    await waitFor(() => expect(screen.queryByText(/Hay más resultados/i)).toBeNull());
    fireEvent.click(screen.getByText("Refrescos remotos"));
    fireEvent.click(screen.getAllByRole("button", { name: "Mover" }).at(-1)!);
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith("/products/classification/move", {
        method: "POST",
        token: "token",
        body: {
          items: [{ productId: "p1", expectedVersion: 7 }],
          familyId: "drinks",
          subfamilyId: "subfamily-remote",
        },
      }),
    );
  });

  it("invalidates a remote move destination when the search changes or is cleared", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/families") return families;
        if (path === "/families/drinks/subfamilies") return [];
        if (path === DRINK_PRODUCTS_NAME_ASC)
          return {
            items: [
              {
                id: "p1",
                code: "A",
                name: "Agua producto",
                salePrice: 1,
                active: true,
                version: 7,
              },
            ],
            hasMore: false,
          };
        if (path === "/families/search?q=remoto&limit=50")
          return {
            items: [
              {
                kind: "SUBFAMILY",
                id: "remote-target",
                familyId: "snacks",
                subfamilyId: "remote-target",
                code: "002999",
                suffix: "999",
                familyCode: "002",
                name: "Destino remoto",
                defaultFamily: false,
              },
            ],
            nextCursor: "",
            hasMore: false,
          };
        if (path === "/products/classification/move" && options?.method === "POST")
          return undefined;
        return options?.method === "POST" ? undefined : [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua producto"));
    fireEvent.click(screen.getByRole("button", { name: "Mover" }));
    const moveDialog = await screen.findByRole("dialog", {
      name: "Mover productos",
    });
    const search = within(moveDialog).getByRole("searchbox", {
      name: "Buscar por código o nombre",
    });
    const moveButton = within(moveDialog).getByRole("button", { name: "Mover" });

    fireEvent.change(search, { target: { value: "remoto" } });
    fireEvent.click(
      await within(moveDialog).findByRole("treeitem", {
        name: "002999 Destino remoto",
      }),
    );
    expect(moveButton).toBeEnabled();

    fireEvent.change(search, { target: { value: "cambio" } });
    expect(moveButton).toBeDisabled();
    fireEvent.click(moveButton);
    expect(
      request.mock.calls.some(([path]) => path === "/products/classification/move"),
    ).toBe(false);

    fireEvent.change(search, { target: { value: "remoto" } });
    fireEvent.click(
      await within(moveDialog).findByRole("treeitem", {
        name: "002999 Destino remoto",
      }),
    );
    expect(moveButton).toBeEnabled();

    fireEvent.change(search, { target: { value: "" } });
    expect(moveButton).toBeDisabled();
    fireEvent.click(moveButton);
    expect(
      request.mock.calls.some(([path]) => path === "/products/classification/move"),
    ).toBe(false);
  });

  it("reloads the current product page and clears the move state on a typed version conflict", async () => {
    let productLoads = 0;
    const request = vi.fn(
      async (path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/families") return families;
        if (path === DRINK_PRODUCTS_NAME_ASC) {
          productLoads += 1;
          return {
            items: [
              {
                id: "p1",
                code: "A",
                name: "Agua producto",
                salePrice: 1,
                active: true,
                version: 7,
              },
            ],
            hasMore: false,
          };
        }
        if (path === "/products/classification/move") {
          throw new ApiError("PRODUCT_VERSION_CONFLICT", 409, {
            code: "PRODUCT_VERSION_CONFLICT",
            action: "RELOAD_PRODUCTS",
            retryable: true,
            conflicts: [{ productId: "p1", expected: 7, actual: 8 }],
          });
        }
        return options?.method === "POST" ? undefined : [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua producto"));
    fireEvent.click(screen.getByRole("button", { name: "Mover" }));
    const moveDialog = await screen.findByRole("dialog", {
      name: "Mover productos",
    });
    fireEvent.click(within(moveDialog).getByText("Snacks"));
    fireEvent.click(within(moveDialog).getByRole("button", { name: "Mover" }));

    await waitFor(() => {
      expect(screen.getByText(/Otro usuario modificó algunos productos/)).toBeInTheDocument();
    });
    expect(screen.queryByRole("dialog", { name: "Mover productos" })).toBeNull();
    expect(screen.queryByText("1 seleccionados")).toBeNull();
    expect(productLoads).toBeGreaterThanOrEqual(2);
  });

  it("keeps family save failures visible inside the editor dialog", async () => {
    const request = vi.fn(async (path: string, options?: { method?: string }) => {
      if (path === "/families" && options?.method === "POST") throw new Error("save_failed");
      if (path === "/families") return families;
      if (path === "/families/next-code") return { familyCode: "003" };
      return [];
    });
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByRole("button", { name: "Añadir familia" }));
    fireEvent.change(await screen.findByLabelText("Nombre"), {
      target: { value: "Frutas" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No se pudo guardar la familia.",
    );
  });

  it("keeps global search failures visible inside the move dialog", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === DRINK_PRODUCTS_NAME_ASC)
        return { items: [{ id: "p1", name: "Agua", version: 1 }], hasMore: false };
      if (path === "/families/search?q=xx&limit=50") throw new Error("search_failed");
      return [];
    });
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua"));
    fireEvent.click(screen.getByRole("button", { name: "Mover" }));
    fireEvent.change(
      await screen.findByRole("searchbox", { name: "Buscar por código o nombre" }),
      { target: { value: "xx" } },
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No se pudo buscar la clasificación.",
    );
  });

  it("retries a failed move search and clears the error after recovery", async () => {
    let searchAttempts = 0;
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === DRINK_PRODUCTS_NAME_ASC)
        return { items: [{ id: "p1", name: "Agua", version: 1 }], hasMore: false };
      if (path === "/families/search?q=xx&limit=50") {
        searchAttempts += 1;
        if (searchAttempts === 1) throw new Error("search_failed");
        return {
          items: [{ kind: "FAMILY", id: "drinks", familyId: null, code: "001", name: "Bebidas" }],
          hasMore: false,
          nextCursor: "",
        };
      }
      return [];
    });
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua"));
    fireEvent.click(screen.getByRole("button", { name: "Mover" }));
    const search = await screen.findByRole("searchbox", {
      name: "Buscar por código o nombre",
    });
    fireEvent.change(search, { target: { value: "xx" } });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("No se pudo buscar la clasificación.");
    fireEvent.click(
      within(alert).getByRole("button", { name: "Reintentar búsqueda" }),
    );
    await waitFor(() => expect(searchAttempts).toBe(2));
    expect(
      within(screen.getByRole("dialog", { name: "Mover productos" })).getByRole(
        "treeitem",
        { name: "001 Bebidas" },
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText("No se pudo buscar la clasificación.")).toBeNull();
  });

  it("keeps generic move failures visible inside the move dialog", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string }) => {
        if (path === "/families") return families;
        if (path === DRINK_PRODUCTS_NAME_ASC)
          return { items: [{ id: "p1", name: "Agua", version: 1 }], hasMore: false };
        if (path === "/products/classification/move" && options?.method === "POST")
          throw new Error("move_failed");
        return [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    fireEvent.click(await screen.findByText("Bebidas"));
    fireEvent.click(await screen.findByLabelText("Seleccionar Agua"));
    fireEvent.click(screen.getByRole("button", { name: "Mover" }));
    const moveDialog = await screen.findByRole("dialog", { name: "Mover productos" });
    fireEvent.click(within(moveDialog).getByText("Snacks"));
    fireEvent.click(within(moveDialog).getByRole("button", { name: "Mover" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No se pudieron mover los productos.",
    );
  });

  it("clears a selected child and its bulk actions when deleting the parent family", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string }) => {
        if (path === "/families") return families;
        if (path === "/families/drinks/subfamilies") return subfamilies;
        if (path === DRINK_PRODUCTS_NAME_ASC)
          return { items: [], hasMore: false };
        if (path === WATER_PRODUCTS_NAME_ASC)
          return {
            items: [
              {
                id: "p1",
                code: "A",
                name: "Agua producto",
                salePrice: 1,
                active: true,
                familyId: "drinks",
                subfamilyId: "water",
                version: 7,
              },
            ],
            hasMore: false,
          };
        if (path === "/families/drinks/delete-impact")
          return {
            productCount: 1,
            promotionCount: 0,
            priceRuleCount: 0,
            blocked: false,
          };
        if (
          path === "/families/drinks?confirmProductReassignment=true" &&
          options?.method === "DELETE"
        )
          return undefined;
        return [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    const family = await screen.findByRole("treeitem", {
      name: /001.*Bebidas/,
    });
    fireEvent.click(family);
    const child = await screen.findByRole("treeitem", {
      name: "001001 Agua",
    });
    fireEvent.click(child);
    fireEvent.click(
      await screen.findByLabelText("Seleccionar Agua producto"),
    );
    expect(screen.getAllByText("1 seleccionados")).toHaveLength(1);

    fireEvent.click(within(family).getByRole("button", { name: "Eliminar" }));
    const impactDialog = await screen.findByRole("dialog", {
      name: "Impacto de la eliminación",
    });
    fireEvent.click(within(impactDialog).getByRole("checkbox"));
    fireEvent.click(
      within(impactDialog).getByRole("button", { name: "Eliminar" }),
    );

    await waitFor(() => {
      expect(
        screen.queryByRole("treeitem", { name: /001.*Bebidas/ }),
      ).toBeNull();
      expect(
        screen.queryByRole("treeitem", { name: "001001 Agua" }),
      ).toBeNull();
      expect(screen.queryByText("Agua producto")).toBeNull();
      expect(screen.queryAllByText("1 seleccionados")).toHaveLength(0);
    });
    expect(
      within(screen.getByRole("tree", { name: "Familias" })).queryByRole(
        "treeitem",
        { selected: true },
      ),
    ).toBeNull();
  });

  it("keeps delete failures visible in the impact dialog", async () => {
    const request = vi.fn(
      async (path: string, options?: { method?: string }) => {
        if (path === "/families") return families;
        if (path === "/families/drinks/delete-impact")
          return { products: 0, promotions: 0, rules: 0, blocked: false };
        if (path === "/families/drinks" && options?.method === "DELETE")
          throw new Error("delete_failed");
        return [];
      },
    );
    renderScreen(request as unknown as typeof apiRequest);
    const family = await screen.findByRole("treeitem", { name: /001.*Bebidas/ });
    fireEvent.click(within(family).getByRole("button", { name: "Eliminar" }));
    const impactDialog = await screen.findByRole("dialog", {
      name: "Impacto de la eliminación",
    });
    fireEvent.click(within(impactDialog).getByRole("button", { name: "Eliminar" }));
    expect(await within(impactDialog).findByRole("alert")).toHaveTextContent(
      "No se pudo eliminar.",
    );
  });

  it("exposes an accessible lazy tree and preserves complete subfamily codes", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/families") return families;
      if (path === "/families/drinks/subfamilies") return subfamilies;
      if (path.includes("/families/products"))
        return {
          items: [{ id: "p1", name: "Agua", version: 1 }],
          hasMore: false,
        };
      return [];
    });
    renderScreen(request as unknown as typeof apiRequest);
    const tree = await screen.findByRole("tree", { name: "Familias" });
    expect(tree).toBeTruthy();
    expect(screen.getByRole("treeitem", { name: /GENERAL/ })).toHaveAttribute(
      "tabindex",
      "0",
    );
    expect(
      normalizeSubfamily(
        { subfamilyCode: "010002", subfamilySuffix: "002", name: "Agua" },
        "family-id",
      ),
    ).toMatchObject({
      subfamilyCode: "010002",
      subfamilySuffix: "002",
      familyId: "family-id",
    });
  });
});
