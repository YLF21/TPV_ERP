import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createReplenishment, deleteReplenishments, editReplenishment, exportReplenishments, listReplenishments, setReplenishmentsOrdered, type ReplenishmentView } from "./replenishmentApi";

vi.mock("../api/client", () => ({ apiRequest: vi.fn().mockResolvedValue(undefined) }));
const request = vi.mocked(apiRequest);
const item = { id: "request-1", version: 7, productId: "product-1", warehouseId: "warehouse-1", quantity: 12, reason: "Reposición" } as ReplenishmentView;
beforeEach(() => request.mockClear());

describe("replenishment API", () => {
  it("sends unit quantities and independent creations without any stock operation", async () => {
    const body = { productId: item.productId, warehouseId: item.warehouseId, quantity: 12, reason: "Reposición" };
    await createReplenishment("token", body);
    await createReplenishment("token", body);
    expect(request).toHaveBeenCalledTimes(2);
    expect(request).toHaveBeenLastCalledWith("/replenishment-requests", { token: "token", body });
    expect(request.mock.calls.every(([path]) => !path.includes("/stock"))).toBe(true);
  });
  it("preserves versions on edits and batch changes instead of sending stale product data", async () => {
    await editReplenishment("token", item, 24, "Urgente");
    expect(request).toHaveBeenLastCalledWith("/replenishment-requests/request-1", { method: "PUT", token: "token", body: { version: 7, quantity: 24, reason: "Urgente" } });
    await setReplenishmentsOrdered("token", [item], false);
    expect(request).toHaveBeenLastCalledWith("/replenishment-requests/status", { token: "token", body: { items: [{ id: "request-1", version: 7 }], ordered: false } });
    await deleteReplenishments("token", [item]);
    expect(request).toHaveBeenLastCalledWith("/replenishment-requests/delete", { token: "token", body: { items: [{ id: "request-1", version: 7 }] } });
  });
  it("encodes combined filters and passes cancellation through", async () => {
    const signal = new AbortController().signal;
    await listReplenishments("token", { warehouseId: "wh", supplierId: "sup", familyId: "fam", subfamilyId: "sub", search: "Café & té" }, signal);
    const [path, options] = request.mock.calls[0];
    const params = new URL(path, "https://local.test").searchParams;
    expect(params.get("search")).toBe("Café & té");
    expect(params.get("subfamilyId")).toBe("sub");
    expect(options?.signal).toBe(signal);
  });
  it("exports large selections in the body and omits selection for all filtered rows", async () => {
    const ids = Array.from({ length: 5000 }, (_, i) => `request-${i}`);
    await exportReplenishments("token", "xlsx", "zh", { supplierId: "sup" }, ids);
    expect(request).toHaveBeenLastCalledWith("/replenishment-requests/export", {
      token: "token", method: "POST", body: { format: "xlsx", locale: "zh", supplierId: "sup", ids }, responseType: "blob",
    });
    await exportReplenishments("token", "pdf", "es", { warehouseId: "wh" });
    expect(request).toHaveBeenLastCalledWith("/replenishment-requests/export", {
      token: "token", method: "POST", body: { format: "pdf", locale: "es", warehouseId: "wh" }, responseType: "blob",
    });
  });
});
