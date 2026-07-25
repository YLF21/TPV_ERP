import { describe, expect, it, vi } from "vitest";
import type { HardwareBridge } from "../hardware/hardware";
import {
  CashDrawerResultReportingError,
  executeAuthorizedCashDrawerOpen,
} from "./cashDrawer";

describe("executeAuthorizedCashDrawerOpen", () => {
  it("authorizes first, opens local hardware and reports success", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/pos/cash-drawer/open-authorizations") {
        return {
          operationId: "operation-1",
          authorizedBy: "ADMIN",
          delegated: false,
          expiresAt: "2026-07-24T12:02:00Z"
        };
      }
      return { operationId: "operation-1", opened: true };
    });
    const openCashDrawer = vi.fn().mockResolvedValue({ ok: true });
    const hardware = { openCashDrawer } as unknown as HardwareBridge;

    const result = await executeAuthorizedCashDrawerOpen(
      { terminalId: "terminal-1", token: "token" },
      request as never,
      hardware,
    );

    expect(result.operationId).toBe("operation-1");
    expect(request).toHaveBeenNthCalledWith(1, "/pos/cash-drawer/open-authorizations", {
      token: "token",
      body: { terminalId: "terminal-1" }
    });
    expect(openCashDrawer).toHaveBeenCalledOnce();
    expect(request).toHaveBeenNthCalledWith(
      2,
      "/pos/cash-drawer/open-authorizations/operation-1/result",
      { token: "token", body: { opened: true } },
    );
  });

  it("reports the hardware error before rejecting the operation", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/pos/cash-drawer/open-authorizations") {
        return {
          operationId: "operation-2",
          authorizedBy: "ENCARGADO",
          delegated: true,
          expiresAt: "2026-07-24T12:02:00Z"
        };
      }
      return { operationId: "operation-2", opened: false };
    });
    const hardware = {
      openCashDrawer: vi.fn().mockResolvedValue({
        ok: false,
        code: "CASH_DRAWER_UNAVAILABLE",
        message: "Cajón no configurado"
      })
    } as unknown as HardwareBridge;

    await expect(executeAuthorizedCashDrawerOpen(
      {
        terminalId: "terminal-1",
        token: "token",
        authorizerUsername: "encargado",
        authorizerPassword: "1234"
      },
      request as never,
      hardware,
    )).rejects.toThrow("Cajón no configurado");

    expect(request).toHaveBeenNthCalledWith(
      2,
      "/pos/cash-drawer/open-authorizations/operation-2/result",
      {
        token: "token",
        body: {
          opened: false,
          errorCode: "CASH_DRAWER_UNAVAILABLE",
          errorMessage: "Cajón no configurado"
        }
      },
    );
  });

  it("distinguishes an opened drawer whose backend result could not be recorded", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/pos/cash-drawer/open-authorizations") {
        return {
          operationId: "operation-3",
          authorizedBy: "ADMIN",
          delegated: false,
          expiresAt: "2026-07-24T12:02:00Z"
        };
      }
      throw new Error("Backend unavailable");
    });
    const hardware = {
      openCashDrawer: vi.fn().mockResolvedValue({ ok: true })
    } as unknown as HardwareBridge;

    await expect(executeAuthorizedCashDrawerOpen(
      { terminalId: "terminal-1", token: "token" },
      request as never,
      hardware,
    )).rejects.toBeInstanceOf(CashDrawerResultReportingError);
  });
});
