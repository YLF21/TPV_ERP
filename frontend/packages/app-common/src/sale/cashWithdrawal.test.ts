import { describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import { printCashWithdrawalReceipt, registerCashEntry } from "./cashWithdrawal";

describe("cash withdrawal receipt printing", () => {
  it("registers a manual cash entry with operation credentials", async () => {
    const request = vi.fn().mockResolvedValue({ id: "entry-1", type: "ENTRADA" });

    await registerCashEntry({
      terminalId: "terminal-1",
      amount: 15,
      comment: " Aporte de cambio ",
      denominations: [],
      authorizerUsername: " manager ",
      authorizerPassword: "secret",
    }, "token", request);

    expect(request).toHaveBeenCalledWith("/cash/movements/entry", {
      token: "token",
      method: "POST",
      body: {
        terminalId: "terminal-1",
        amount: 15,
        comment: "Aporte de cambio",
        denominations: [],
        authorizerUsername: "manager",
        authorizerPassword: "secret",
      },
    });
  });

  it("loads the persisted receipt and prints its audit data and denomination breakdown", async () => {
    const request = vi.fn().mockResolvedValue({
      movementId: "movement-123456789",
      sessionId: "session-1",
      terminalId: "terminal-1",
      terminalName: "TPV 1",
      createdAt: "2026-07-30T12:00:00Z",
      userName: "SELLER",
      authorizerName: "MANAGER",
      amount: 21,
      comment: "Ingreso\u0007 en banco",
      denominations: [
        { denomination: 20, quantity: 1 },
        { denomination: 1, quantity: 1 },
      ],
    });
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printTicket,
    };

    const outcome = await printCashWithdrawalReceipt(
      "movement-123456789",
      "token",
      { storeName: "Tienda", terminalCode: "01" },
      "es",
      hardware as never,
      request,
    );

    expect(outcome).toEqual({ status: "PRINTED" });
    expect(request).toHaveBeenCalledWith(
      "/cash/receipts/withdrawals/movement-123456789",
      { token: "token" },
    );
    const printed = printTicket.mock.calls[0][0];
    expect(printed.documentNumber).toBe("RETIRADA movement-123");
    expect(printed.total).toBe(21);
    expect(printed.lines[0].name).toContain("Motivo: Ingreso en banco");
    expect(printed.lines[0].name).toContain("Operador: SELLER");
    expect(printed.lines[0].name).toContain("Autoriza: MANAGER");
    expect(printed.lines.slice(1)).toEqual([
      { name: "Efectivo 20.00", quantity: 1, price: 20, total: 20 },
      { name: "Efectivo 1.00", quantity: 1, price: 1, total: 1 },
    ]);
  });

  it("returns a failed outcome instead of throwing so retry cannot duplicate the movement", async () => {
    const outcome = await printCashWithdrawalReceipt(
      "movement-1",
      "token",
      { storeName: "Tienda", terminalCode: "01" },
      "es",
      {} as never,
      vi.fn().mockRejectedValue(new Error("offline")),
    );

    expect(outcome).toEqual({ status: "FAILED", technicalMessage: "offline" });
  });
});
