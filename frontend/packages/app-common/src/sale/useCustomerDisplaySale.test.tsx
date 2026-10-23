// @vitest-environment jsdom
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { defaultHardwareConfig, getHardwareBridge, type HardwareBridge } from "../hardware/hardware";
import { createCustomerDisplayPublisher, useCustomerDisplaySale } from "./useCustomerDisplaySale";
import type { CustomerDisplaySaleInput } from "./customerDisplaySale";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
vi.mock("../hardware/hardware", async importOriginal => ({
  ...await importOriginal<typeof import("../hardware/hardware")>(),
  getHardwareBridge: vi.fn(),
}));

const terminal = { storeName: "Tienda", companyName: "Empresa", terminalCode: "001" };
const initial = {
  enabled: true, token: "token", terminal, locale: "es" as const,
  lines: [{ id: "a", name: "Artículo", quantity: 1 }], quote: null,
};
let bridge: HardwareBridge;

beforeEach(() => {
  bridge = {
    getHardwareConfig: vi.fn().mockResolvedValue({ ...defaultHardwareConfig, customerDisplayEnabled: true }),
    updateCustomerDisplay: vi.fn().mockResolvedValue({ ok: true }),
    openCustomerDisplay: vi.fn(),
  } as unknown as HardwareBridge;
  vi.mocked(getHardwareBridge).mockReturnValue(bridge);
  vi.mocked(apiRequest).mockResolvedValue({
    ...terminal, timeZone: "Atlantic/Canary", currency: "EUR", logoDataUri: "data:image/png;base64,AA",
  });
});
afterEach(() => vi.clearAllMocks());

describe("customer display publication", () => {
  it("serializes writes, coalesces pending snapshots and deduplicates delivery", async () => {
    let finishFirst!: (value: { ok: true }) => void;
    const update = vi.fn()
      .mockImplementationOnce(() => new Promise<{ ok: true }>(resolve => { finishFirst = resolve; }))
      .mockResolvedValue({ ok: true });
    const publish = createCustomerDisplayPublisher(update);
    publish({ line1: "first", line2: "" });
    publish({ line1: "intermediate", line2: "" });
    publish({ line1: "latest", line2: "" });
    expect(update).toHaveBeenCalledTimes(1);
    finishFirst({ ok: true });
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2));
    expect(update.mock.calls[1][0].line1).toBe("latest");
    publish({ line1: "latest", line2: "" });
    expect(update).toHaveBeenCalledTimes(2);
  });

  it("continues publishing after a hardware failure", async () => {
    const update = vi.fn().mockRejectedValueOnce(new Error("unavailable")).mockResolvedValue({ ok: true });
    const publish = createCustomerDisplayPublisher(update);
    publish({ line1: "first", line2: "" });
    publish({ line1: "latest", line2: "" });
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2));
  });

  it("keeps a forced refresh when a later identical snapshot coalesces into it", async () => {
    let finish!: (value: { ok: true }) => void;
    const update = vi.fn()
      .mockImplementationOnce(() => new Promise<{ ok: true }>(resolve => { finish = resolve; }))
      .mockResolvedValue({ ok: true });
    const publish = createCustomerDisplayPublisher(update);
    const state = { line1: "Welcome", line2: "", phase: "IDLE" as const };
    publish(state);
    publish(state, true);
    publish(state);
    finish({ ok: true });
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2));
  });

  it("loads identity once, publishes pending state and resets to idle on navigation", async () => {
    const { unmount, rerender } = renderHook(props => useCustomerDisplaySale(props), { initialProps: initial });
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenCalledTimes(1));
    expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(expect.objectContaining({
      phase: "SALE",
      identity: expect.objectContaining({ storeName: "Tienda", timeZone: "Atlantic/Canary" }),
      sale: expect.objectContaining({ status: "PENDING", total: null }),
    }));
    rerender({ ...initial, lines: [{ ...initial.lines[0], quantity: 2 }] });
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenCalledTimes(2));
    expect(apiRequest).toHaveBeenCalledTimes(1);
    expect(bridge.openCustomerDisplay).not.toHaveBeenCalled();
    unmount();
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(
      expect.objectContaining({ phase: "IDLE", line1: defaultHardwareConfig.customerDisplayIdleLine1 }),
    ));
  });

  it("does not fetch or publish when the display is disabled", async () => {
    vi.mocked(bridge.getHardwareConfig).mockResolvedValue(defaultHardwareConfig);
    const { unmount } = renderHook(() => useCustomerDisplaySale(initial));
    await act(async () => {});
    expect(apiRequest).not.toHaveBeenCalled();
    expect(bridge.updateCustomerDisplay).not.toHaveBeenCalled();
    unmount();
    expect(bridge.updateCustomerDisplay).not.toHaveBeenCalled();
  });

  it("reloads configuration and sends the current sale when enabled or its mode changes", async () => {
    vi.mocked(bridge.getHardwareConfig).mockResolvedValue(defaultHardwareConfig);
    const { unmount } = renderHook(() => useCustomerDisplaySale(initial));
    await waitFor(() => expect(bridge.getHardwareConfig).toHaveBeenCalledTimes(1));
    expect(bridge.updateCustomerDisplay).not.toHaveBeenCalled();
    vi.mocked(bridge.getHardwareConfig).mockResolvedValue({
      ...defaultHardwareConfig, customerDisplayEnabled: true, customerDisplayMode: "LINE_DISPLAY",
    });
    act(() => window.dispatchEvent(new CustomEvent("tpv:hardware-config-changed")));
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(
      expect.objectContaining({ phase: "SALE", identity: expect.objectContaining({ storeName: "Tienda" }) }),
    ));
    const beforeModeChange = vi.mocked(bridge.updateCustomerDisplay).mock.calls.length;
    vi.mocked(bridge.getHardwareConfig).mockResolvedValue({
      ...defaultHardwareConfig, customerDisplayEnabled: true, customerDisplayMode: "DIGITAL_SCREEN",
    });
    act(() => window.dispatchEvent(new CustomEvent("tpv:hardware-config-changed")));
    await waitFor(() => expect(bridge.getHardwareConfig).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(vi.mocked(bridge.updateCustomerDisplay).mock.calls.length).toBeGreaterThan(beforeModeChange));
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(
      expect.objectContaining({ phase: "SALE", identity: expect.objectContaining({ storeName: "Tienda" }) }),
    ));
    expect(bridge.openCustomerDisplay).not.toHaveBeenCalled();
    unmount();
  });

  it("restores an identical idle snapshot with identity after remount", async () => {
    const idle = { ...initial, lines: [] };
    const first = renderHook(() => useCustomerDisplaySale(idle));
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(
      expect.objectContaining({ phase: "IDLE", identity: expect.objectContaining({ storeName: "Tienda" }) }),
    ));
    first.unmount();
    await act(async () => {});
    const beforeRemount = vi.mocked(bridge.updateCustomerDisplay).mock.calls.length;
    const second = renderHook(() => useCustomerDisplaySale(idle));
    await waitFor(() => expect(vi.mocked(bridge.updateCustomerDisplay).mock.calls.length).toBeGreaterThan(beforeRemount));
    expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(expect.objectContaining({
      phase: "IDLE", identity: expect.objectContaining({ storeName: "Tienda", timeZone: "Atlantic/Canary" }),
    }));
    second.unmount();
  });

  it("uses terminal identity when identity lookup fails", async () => {
    vi.mocked(apiRequest).mockRejectedValue(new Error("offline"));
    const { unmount } = renderHook(() => useCustomerDisplaySale(initial));
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenCalled());
    expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(expect.objectContaining({
      identity: expect.objectContaining({ storeName: "Tienda", companyName: "Empresa", currency: "EUR" }),
    }));
    unmount();
  });

  it("replaces confirmed amounts with pending and error states until the new quote arrives", async () => {
    const quote = {
      total: 10,
      lineBreakdown: [{
        lineId: "p", lineType: "PRODUCT", productId: "p", name: "Artículo",
        quantity: 1, baseUnitPrice: 10, baseSubtotal: 10, commercialSubtotal: 10, finalSubtotal: 10,
        manualDiscount: 0, memberDiscount: 0, promotionDiscount: 0, couponDiscount: 0,
        manualDiscountPercent: 0,
      }],
    };
    const props: Omit<typeof initial, "quote"> & CustomerDisplaySaleInput = { ...initial, quote };
    const { rerender, unmount } = renderHook(
      (value: Omit<typeof initial, "quote"> & CustomerDisplaySaleInput) => useCustomerDisplaySale(value),
      { initialProps: props },
    );
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(
      expect.objectContaining({ sale: expect.objectContaining({ status: "READY", total: 10 }) }),
    ));
    rerender({ ...props, lines: [{ ...initial.lines[0], quantity: 2 }], quote: null });
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(
      expect.objectContaining({ sale: expect.objectContaining({ status: "PENDING", total: null }) }),
    ));
    rerender({ ...props, quote: null, quoteError: true });
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(
      expect.objectContaining({ sale: expect.objectContaining({ status: "ERROR", total: null }) }),
    ));
    unmount();
  });

  it("ignores late setup responses after leaving the sale", async () => {
    let finishIdentity!: (value: unknown) => void;
    vi.mocked(apiRequest).mockImplementation(() => new Promise(resolve => { finishIdentity = resolve; }));
    const { unmount } = renderHook(() => useCustomerDisplaySale(initial));
    await waitFor(() => expect(apiRequest).toHaveBeenCalled());
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenCalled());
    unmount();
    await waitFor(() => expect(bridge.updateCustomerDisplay).toHaveBeenLastCalledWith(
      expect.objectContaining({ phase: "IDLE" }),
    ));
    const callsAfterCleanup = vi.mocked(bridge.updateCustomerDisplay).mock.calls.length;
    await act(async () => { finishIdentity({ storeName: "Late" }); });
    expect(bridge.updateCustomerDisplay).toHaveBeenCalledTimes(callsAfterCleanup);
  });
});
