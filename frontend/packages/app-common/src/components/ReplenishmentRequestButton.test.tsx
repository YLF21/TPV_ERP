// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createReplenishment } from "./replenishmentApi";
import { ReplenishmentRequestButton } from "./ReplenishmentRequestButton";
import { replenishmentMessages } from "../i18n/ReplenishmentMessages";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
vi.mock("./replenishmentApi", () => ({ createReplenishment: vi.fn() }));
const save = vi.mocked(createReplenishment);
beforeEach(() => {
  vi.mocked(apiRequest).mockResolvedValue([
    { id: "general", name: "GENERAL", defaultWarehouse: true, active: true },
    { id: "active", name: "TIENDA", active: true },
    { id: "closed", name: "CERRADO", active: false },
  ]);
  save.mockReset().mockResolvedValue(undefined);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });
async function open() {
  fireEvent.click(screen.getByRole("button", { name: /Solicitar reposición F9/ }));
  const dialog = await screen.findByRole("dialog", { name: "Solicitar reposición" });
  await waitFor(() => expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/warehouses", { token: "token" }));
  return dialog;
}
function amount() { return screen.getByRole("spinbutton") as HTMLInputElement; }
function reason() { return screen.getByRole("combobox", { name: "Motivo" }) as HTMLInputElement; }

describe("request replenishment modal", () => {
  it("translates every suggested reason in Spanish, English and Chinese", () => {
    const keys = ["reasonRestock", "reasonCustomer", "reasonSeason", "reasonForecast", "reasonCampaign", "reasonDisplay", "reasonDamage"];
    for (const locale of ["es", "en", "zh"] as const) {
      const dictionary = replenishmentMessages(locale);
      expect(keys.map(key => dictionary[`replenishment.${key}`])).toHaveLength(7);
      expect(keys.every(key => Boolean(dictionary[`replenishment.${key}`]))).toBe(true);
    }
  });
  it("uses a native portal dialog with package units and the active warehouse", async () => {
    render(<ReplenishmentRequestButton productId="p1" packageQuantity={12} warehouseId="active" token="token" locale="es" />);
    const dialog = await open();
    expect(dialog.tagName).toBe("DIALOG");
    expect(dialog.parentElement).toBe(document.body);
    expect((dialog as HTMLDialogElement).open).toBe(true);
    expect(amount().value).toBe("12");
    expect(screen.getByText(/Paquete: 12 unidades/)).toBeTruthy();
    expect(reason().value).toBe("Reposición por falta de stock");
    fireEvent.click(within(dialog).getByRole("button", { name: "Almacén" }));
    expect(within(dialog).getByRole("option", { name: "TIENDA" }).getAttribute("aria-selected")).toBe("true");
    expect(within(dialog).queryByRole("option", { name: "CERRADO" })).toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "Solicitar reposición" }));
    await waitFor(() => expect(save).toHaveBeenCalledWith("token", { productId: "p1", warehouseId: "active", quantity: 12, reason: "Reposición por falta de stock" }));
  });

  it("offers seven translated reasons while accepting free text and decimal units after an error", async () => {
    save.mockRejectedValueOnce(new Error("Sin conexión"));
    render(<ReplenishmentRequestButton productId="p1" packageQuantity="1.5" warehouseId="active" token="token" locale="es" />);
    const dialog = await open();
    expect(amount().value).toBe("1.5");
    fireEvent.click(within(dialog).getByRole("button", { name: "Motivo" }));
    expect(within(dialog).getAllByRole("option")).toHaveLength(7);
    expect(within(dialog).getByRole("option", { name: "Promoción o campaña" })).toBeTruthy();
    fireEvent.change(amount(), { target: { value: "2.125" } });
    fireEvent.change(reason(), { target: { value: "Encargo especial" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Solicitar reposición" }));
    await screen.findByText("Sin conexión");
    expect(amount().value).toBe("2.125");
    expect(reason().value).toBe("Encargo especial");
    expect(save).toHaveBeenCalledWith("token", expect.objectContaining({ quantity: 2.125, reason: "Encargo especial" }));
  });

  it("opens with F9 once and closes only the nested dialog on Escape", async () => {
    const parentKey = vi.fn(); const parentCancel = vi.fn();
    render(<dialog open onKeyDown={parentKey} onCancel={parentCancel}><ReplenishmentRequestButton productId="p1" packageQuantity={3} token="token" locale="es" /></dialog>);
    const event = new KeyboardEvent("keydown", { key: "F9", bubbles: true, cancelable: true });
    window.dispatchEvent(event);
    const dialog = await screen.findByRole("dialog", { name: "Solicitar reposición" });
    expect(event.defaultPrevented).toBe(true);
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "F9", bubbles: true, cancelable: true }));
    expect(screen.getAllByRole("dialog", { name: "Solicitar reposición" })).toHaveLength(1);
    fireEvent.keyDown(amount(), { key: "Insert" });
    expect(parentKey).not.toHaveBeenCalled();
    fireEvent.keyDown(amount(), { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Solicitar reposición" })).toBeNull());
    expect(parentKey).not.toHaveBeenCalled();
    expect(parentCancel).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: /Solicitar reposición F9/ }));
    expect(dialog.isConnected).toBe(false);
  });

  it("prevents duplicate submits, rejects zero units, and resets for a new product", async () => {
    let finish!: () => void;
    save.mockImplementationOnce(() => new Promise(resolve => { finish = () => resolve(undefined); }));
    const view = render(<ReplenishmentRequestButton productId="p1" packageQuantity={12} token="token" locale="es" />);
    const dialog = await open();
    fireEvent.change(amount(), { target: { value: "0" } });
    fireEvent.submit(amount().closest("form")!);
    expect(save).not.toHaveBeenCalled();
    fireEvent.change(amount(), { target: { value: "4" } });
    fireEvent.submit(amount().closest("form")!);
    fireEvent.submit(amount().closest("form")!);
    expect(save).toHaveBeenCalledTimes(1);
    fireEvent(dialog, new Event("cancel", { bubbles: true, cancelable: true }));
    expect(dialog.isConnected).toBe(true);
    finish();
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Solicitar reposición" })).toBeNull());
    view.rerender(<ReplenishmentRequestButton productId="p2" packageQuantity={null} token="token" locale="es" />);
    await open();
    expect(amount().value).toBe("1");
  });
});
