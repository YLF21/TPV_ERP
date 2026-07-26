// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SalePriceConsultationDialog } from "./SalePriceConsultationDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("SalePriceConsultationDialog", () => {
  it("opens empty and waits for a scan or a manually entered code", () => {
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);

    expect(screen.getByText("ESCANEA PARA CONSULTAR PRECIO")).toBeVisible();
    expect(screen.getByRole("textbox", { name: "Código del producto" }))
      .toHaveClass("sale-price-consultation-capture");
    expect(screen.getByRole("textbox", { name: "Código del producto" })).toHaveFocus();
    expect(screen.queryByText("Precio de venta")).not.toBeInTheDocument();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("consults the exact backend identifier when manual input ends with Enter", async () => {
    const fetchMock = vi.fn(async (url: string) => {
      const parsed = new URL(url, "http://localhost");
      expect(parsed.pathname).toBe("/api/v1/products/sale/price-consultation");
      expect(parsed.searchParams.get("identifier")).toBe("MANUAL-001");
      return jsonResponse({
        productId: "product-1",
        code: "P-001",
        name: "Producto socio",
        salePrice: 10,
        activePriceType: "MEMBER_PRICE",
        memberPrice: 8.5,
      });
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);

    const input = screen.getByRole("textbox", { name: "Código del producto" });
    await user.type(input, "MANUAL-001");

    expect(screen.queryByText("ESCANEA PARA CONSULTAR PRECIO")).not.toBeInTheDocument();
    const scannedCode = screen.getByText("MANUAL-001");
    expect(scannedCode).toBeVisible();

    await user.keyboard("{Enter}");

    const productName = await screen.findByText("Producto socio");
    expect(productName).toBeVisible();
    expect(scannedCode.nextElementSibling).toBe(productName);
    expect(screen.getByText(/10,00/)).toBeVisible();
    expect(screen.getByText(/8,50/)).toBeVisible();
    expect(screen.getByText("Precio socio")).toBeVisible();
    expect(screen.queryByText("Precio oferta")).not.toBeInTheDocument();
    expect(input).toHaveValue("");

    await user.type(input, "SIGUIENTE");

    expect(screen.getByText("SIGUIENTE")).toBeVisible();
    expect(screen.queryByText("Producto socio")).not.toBeInTheDocument();
    expect(screen.queryByText("Precio de venta")).not.toBeInTheDocument();
  });

  it("loads the authenticated product thumbnail when the consulted product has an image", async () => {
    const NativeUrl = URL;
    const createObjectURL = vi.fn(() => "blob:product-thumbnail");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", { createObjectURL, revokeObjectURL });
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const parsed = new NativeUrl(url, "http://localhost");
      if (parsed.pathname.endsWith("/price-consultation")) {
        return jsonResponse({
          productId: "product-image",
          code: "IMG-1",
          name: "Producto con imagen",
          hasImage: true,
          salePrice: 12,
          activePriceType: "NORMAL",
        });
      }
      return {
        ok: true,
        blob: async () => new Blob(["image"], { type: "image/webp" }),
      } as Response;
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);

    await user.type(screen.getByRole("textbox", { name: /digo del producto/ }), "IMG-1{Enter}");

    const image = await screen.findByRole("img", { name: "Producto con imagen" });
    expect(image).toHaveAttribute("src", "blob:product-thumbnail");
    expect(createObjectURL).toHaveBeenCalledOnce();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const imageRequest = new NativeUrl(String(fetchMock.mock.calls[1]?.[0]), "http://localhost");
    expect(imageRequest.pathname).toBe("/api/v1/products/product-image/image");
    expect(imageRequest.searchParams.get("thumbnail")).toBe("true");
    expect(fetchMock.mock.calls[1]?.[1]?.headers).toEqual({ Authorization: "Bearer token" });

    await user.type(screen.getByRole("textbox", { name: /digo del producto/ }), "SIGUIENTE");

    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith("blob:product-thumbnail"));
    expect(screen.queryByRole("img", { name: "Producto con imagen" })).not.toBeInTheDocument();
  });

  it("shows an active offer discount and its end date without unrelated prices", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({
      productId: "product-2",
      code: "P-002",
      name: "Producto rebajado",
      salePrice: 20,
      activePriceType: "OFFER_DISCOUNT",
      offerDiscountPercent: 15,
      offerUntil: "2026-07-31",
    })));
    const user = userEvent.setup();
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);

    await user.type(screen.getByRole("textbox", { name: "Código del producto" }), "8410000000001{Enter}");

    expect(await screen.findByText("Descuento oferta")).toBeVisible();
    expect(screen.getByText("15%")).toBeVisible();
    expect(screen.getByText("Oferta válida hasta")).toBeVisible();
    expect(screen.getByText(/31\/0?7\/2026/)).toBeVisible();
    expect(screen.queryByText("Precio socio")).not.toBeInTheDocument();
    expect(screen.queryByText("Precio oferta")).not.toBeInTheDocument();
  });

  it("reports an unknown code and remains ready for the next scan", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({
      status: 404,
      detail: "Recurso no encontrado",
    }, 404)));
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={onClose} />);

    const input = screen.getByRole("textbox", { name: "Código del producto" });
    await user.type(input, "NO-EXISTE{Enter}");

    const scannedCode = screen.getByText("NO-EXISTE");
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("PRODUCTO NO ENCONTRADO");
    expect(scannedCode.nextElementSibling).toBe(alert);
    await waitFor(() => expect(input).toHaveFocus());
    expect(input).toHaveValue("");

    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledOnce();
  });
});
