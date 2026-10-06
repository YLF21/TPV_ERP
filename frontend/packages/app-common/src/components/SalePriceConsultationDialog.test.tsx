// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SalePriceConsultationDialog } from "./SalePriceConsultationDialog";
import { SaleTouchKeyboardScope } from "./SaleTouchKeyboardScope";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("SalePriceConsultationDialog", () => {
  it("uses hidden capture to show touch entry instead of the prompt and automatically replaces matched codes", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      productId: "product-1", code: "Q1", name: "Consulta táctil", salePrice: 10, activePriceType: "NORMAL",
    }));
    vi.stubGlobal("fetch", fetchMock);
    render(<SaleTouchKeyboardScope locale="es" interfaceMode="TOUCH">
      <SalePriceConsultationDialog locale="es" token="token" interfaceMode="TOUCH" onClose={vi.fn()} />
    </SaleTouchKeyboardScope>);
    const input = screen.getByRole("textbox", { name: "Código del producto" });
    expect(input).toHaveClass("sale-price-consultation-capture");
    fireEvent.focus(input);
    fireEvent.click(screen.getByRole("button", { name: "Q" }));
    fireEvent.click(screen.getByRole("button", { name: "1" }));
    expect(input).toHaveValue("Q1");
    expect(screen.getByText("Q1")).toBeVisible();
    expect(screen.queryByText("ESCANEA PARA CONSULTAR PRECIO")).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Buscar" })).not.toBeInTheDocument();
    expect(await screen.findByText("Consulta táctil", {}, { timeout: 2000 })).toBeVisible();
    expect(fetchMock).toHaveBeenCalledOnce();
    const request = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(request.searchParams.get("identifier")).toBe("Q1");
    fireEvent.click(screen.getByRole("button", { name: "2" }));
    expect(input).toHaveValue("2");
    expect(screen.queryByText("Consulta táctil")).not.toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2), { timeout: 2000 });
    expect(new URL(String(fetchMock.mock.calls[1][0]), "http://localhost").searchParams.get("identifier")).toBe("2");
  });

  it("waits one second after the last edit before searching without Enter or a button", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      productId: "manual", name: "Producto manual", salePrice: 10, activePriceType: "NORMAL",
    }));
    vi.stubGlobal("fetch", fetchMock);
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);
    const input = screen.getByRole("textbox", { name: "Código del producto" });
    fireEvent.change(input, { target: { value: "MAN" } });
    await act(() => vi.advanceTimersByTimeAsync(250));
    fireEvent.change(input, { target: { value: "MANUAL-001" } });
    await act(() => vi.advanceTimersByTimeAsync(999));
    expect(fetchMock).not.toHaveBeenCalled();
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(new URL(String(fetchMock.mock.calls[0][0]), "http://localhost").searchParams.get("identifier")).toBe("MANUAL-001");
    expect(screen.getByText("Producto manual")).toBeVisible();
    expect(input).toHaveFocus();
    expect(input).toHaveValue("MANUAL-001");
    expect((input as HTMLInputElement).selectionStart).toBe(0);
    expect((input as HTMLInputElement).selectionEnd).toBe(10);
  });

  it("replaces the previous match with another scan without Enter", async () => {
    const fetchMock = vi.fn(async (url: string) => jsonResponse({
      productId: "product", name: new URL(url, "http://localhost").searchParams.get("identifier"),
      salePrice: 10, activePriceType: "NORMAL",
    }));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);
    const input = screen.getByRole("textbox", { name: "Código del producto" });
    await user.keyboard("8410000000011");
    await waitFor(() => expect(screen.getByText("Precio de venta")).toBeVisible(), { timeout: 2000 });
    await user.keyboard("8410000000028");
    expect(input).toHaveValue("8410000000028");
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2), { timeout: 2000 });
    expect(new URL(String(fetchMock.mock.calls[1][0]), "http://localhost").searchParams.get("identifier")).toBe("8410000000028");
  });

  it("keeps an incomplete unknown identifier editable as manual typing continues", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({ detail: "not found" }, 404)));
    const user = userEvent.setup();
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);
    const input = screen.getByRole("textbox", { name: "Código del producto" });
    await user.keyboard("MANUAL");
    expect(await screen.findByRole("alert", {}, { timeout: 2000 })).toHaveTextContent("PRODUCTO NO ENCONTRADO");
    await user.keyboard("-001");
    expect(input).toHaveValue("MANUAL-001");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await user.clear(input);
    expect(screen.getByText("ESCANEA PARA CONSULTAR PRECIO")).toBeVisible();
  });

  it("uses a fresh scanner burst instead of appending it to an unknown previous identifier", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ detail: "not found" }, 404));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Código del producto" }), { target: { value: "UNKNOWN" } });
    expect(await screen.findByRole("alert", {}, { timeout: 2000 })).toBeVisible();
    await user.keyboard("8410000000011{Enter}");
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(new URL(String(fetchMock.mock.calls[1][0]), "http://localhost").searchParams.get("identifier")).toBe("8410000000011");
    expect(screen.getByRole("textbox", { name: "Código del producto" })).toHaveValue("");
  });

  it.each([200, 404])("ignores a stale %i response as soon as another code is entered", async (status) => {
    vi.useFakeTimers();
    let finishFirst!: (response: Response) => void;
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { finishFirst = resolve; }))
      .mockResolvedValue(jsonResponse({ productId: "new", name: "Producto nuevo", salePrice: 20, activePriceType: "NORMAL" }));
    vi.stubGlobal("fetch", fetchMock);
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);
    const input = screen.getByRole("textbox", { name: "Código del producto" });
    fireEvent.change(input, { target: { value: "OLD" } });
    fireEvent.submit(input.closest("form")!);
    fireEvent.change(input, { target: { value: "NEW" } });
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
    await act(async () => finishFirst(jsonResponse({ productId: "old", name: "Producto antiguo", salePrice: 1, activePriceType: "NORMAL" }, status)));
    expect(screen.queryByText("Producto antiguo")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(1000));
    expect(screen.getByText("Producto nuevo")).toBeVisible();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("accepts successive Enter scans while the previous request is pending", async () => {
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>(() => {}))
      .mockResolvedValue(jsonResponse({ productId: "new", name: "Segundo escaneo", salePrice: 20, activePriceType: "NORMAL" }));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);
    const input = screen.getByRole("textbox", { name: "Código del producto" });
    await user.keyboard("8410000000011{Enter}");
    expect(input).toHaveValue("");
    await user.keyboard("8410000000028{Enter}");
    expect(await screen.findByText("Segundo escaneo")).toBeVisible();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
    expect(input).toHaveValue("");
    expect(input).toHaveFocus();
  });

  it("cancels pending debounce and active requests when closed", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_url: string, _options: RequestInit) => new Promise<Response>(() => {}));
    vi.stubGlobal("fetch", fetchMock);
    const pending = render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Código del producto" }), { target: { value: "PENDING" } });
    pending.unmount();
    await act(() => vi.advanceTimersByTimeAsync(1000));
    expect(fetchMock).not.toHaveBeenCalled();
    const active = render(<SalePriceConsultationDialog locale="es" token="token" onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Código del producto" }), { target: { value: "ACTIVE" } });
    await act(() => vi.advanceTimersByTimeAsync(1000));
    active.unmount();
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0][1].signal?.aborted).toBe(true);
  });

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
        name: "Producto miembro",
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

    const productName = await screen.findByText("Producto miembro");
    expect(productName).toBeVisible();
    expect(scannedCode.nextElementSibling).toBe(productName);
    expect(screen.getByText(/10,00/)).toBeVisible();
    expect(screen.getByText(/8,50/)).toBeVisible();
    expect(screen.getByText("Precio de miembro")).toBeVisible();
    expect(screen.queryByText("Precio oferta")).not.toBeInTheDocument();
    expect(input).toHaveValue("");

    await user.type(input, "SIGUIENTE");

    expect(screen.getByText("SIGUIENTE")).toBeVisible();
    expect(screen.queryByText("Producto miembro")).not.toBeInTheDocument();
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
    expect(screen.queryByText("Precio de miembro")).not.toBeInTheDocument();
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
