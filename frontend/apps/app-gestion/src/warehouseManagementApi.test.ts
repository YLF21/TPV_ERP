import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "@tpverp/app-common";
import { saveManagedWarehouseOrder } from "./warehouseManagementApi";

vi.mock("@tpverp/app-common", () => ({ apiRequest: vi.fn() }));

beforeEach(() => vi.clearAllMocks());

describe("saveManagedWarehouseOrder", () => {
  it("sends the complete ordered ID/version list to the warehouse order endpoint", async () => {
    const input = { warehouses: [{ id: "general", version: 2 }, { id: "secondary", version: 5 }] };
    const response = [{ id: "general", displayOrder: 0, version: 3 },
      { id: "secondary", displayOrder: 1, version: 6 }];
    vi.mocked(apiRequest).mockResolvedValue(response);
    await expect(saveManagedWarehouseOrder(input, "token")).resolves.toEqual(response);
    expect(apiRequest).toHaveBeenCalledWith("/warehouses/order", {
      token: "token", method: "PUT", body: input
    });
  });
});
