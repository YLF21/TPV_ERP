/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createTranslator,
  defaultHardwareConfig,
  type HardwareBridge,
  type UserSession
} from "@tpverp/app-common";
import { VoucherManagementScreen } from "./VoucherManagementScreen";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN", "GESTION_VENTAS"]
};

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, "tpvDesktop");
  vi.unstubAllGlobals();
});

describe("VoucherManagementScreen", () => {
  it("shows expiry, opens traceability and limits reactivation to expired vouchers", async () => {
    const voucher = {
      code: "VEXP",
      familyIdentifier: "001-000001",
      initialAmount: 100,
      balance: 40,
      status: "EXPIRED",
      createdAt: "2026-08-16T10:00:00Z",
      expiresOn: "2027-08-16",
      originTickets: ["T-1", "T-2"]
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/vouchers/VEXP/management")) {
        return response({ voucher, events: [] });
      }
      if (url.includes("/vouchers/management")) {
        return response({ items: [voucher], page: 0, size: 50, totalElements: 1, totalPages: 1 });
      }
      return response({});
    }));

    render(<VoucherManagementScreen
      locale="es"
      session={session}
      terminalContext={{ storeName: "Tienda", terminalCode: "SERVIDOR" }}
      t={createTranslator("es")}
    />);

    expect(await screen.findByText("VEXP")).toBeInTheDocument();
    expect(screen.getByText("001-000001")).toBeInTheDocument();
    expect(screen.getByText("Caducado")).toBeInTheDocument();
    fireEvent.click(screen.getByText("VEXP"));

    expect(await screen.findByText("T-1 · T-2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reactivar" })).toBeInTheDocument();
    expect(screen.queryByText("Caducidad de nuevos vales")).not.toBeInTheDocument();
  });

  it("uses the print-event response as the updated audited detail", async () => {
    const voucher = {
      code: "VPRINT",
      familyIdentifier: "001-000002",
      initialAmount: 25,
      balance: 25,
      status: "ACTIVE",
      createdAt: "2026-08-16T10:00:00Z",
      expiresOn: null,
      originTickets: ["T-1"]
    };
    const initialDetail = { voucher, events: [] };
    const auditedDetail = {
      voucher,
      events: [{
        type: "REPRINTED",
        userId: "00000000-0000-0000-0000-000000000001",
        operatorUsername: "ADMIN",
        terminalId: null,
        occurredAt: "2026-08-16T12:00:00Z",
        reason: null
      }]
    };
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/vouchers/VPRINT/print-document")) {
        return response({
          code: voucher.code,
          familyIdentifier: voucher.familyIdentifier,
          amount: voucher.initialAmount,
          issuedAt: voucher.createdAt,
          expiresOn: voucher.expiresOn,
          originTicketNumber: "T-1",
          renderedPdf: { contentType: "application/pdf", base64: "JVBERi0=" },
          ticketRenderedImage: { contentType: "image/png", base64: "iVBORw0=" }
        });
      }
      if (url.includes("/vouchers/VPRINT/print-events")) {
        expect(init?.method).toBe("POST");
        return response(auditedDetail);
      }
      if (url.includes("/vouchers/VPRINT/management")) {
        return response(initialDetail);
      }
      if (url.includes("/vouchers/management")) {
        return response({
          items: [voucher], page: 0, size: 50, totalElements: 1, totalPages: 1
        });
      }
      return response({});
    });
    vi.stubGlobal("fetch", fetch);
    window.tpvDesktop = {
      closeApplication: vi.fn(async () => undefined),
      hardware: {
        getHardwareConfig: vi.fn(async () => ({
          ...defaultHardwareConfig,
          ticketPrinterName: "EPSON"
        })),
        printA4Document: vi.fn(async () => ({ ok: true }))
      } as unknown as HardwareBridge
    };

    render(<VoucherManagementScreen
      locale="es"
      session={session}
      terminalContext={{ storeName: "Tienda", terminalCode: "SERVIDOR" }}
      t={createTranslator("es")}
    />);

    fireEvent.click(await screen.findByText("VPRINT"));
    fireEvent.click(await screen.findByRole("button", { name: "Reimprimir vale" }));

    expect(await screen.findByText("Vale enviado a la impresora.")).toBeInTheDocument();
    expect(await screen.findByText("Reimpresión correcta")).toBeInTheDocument();
    expect(screen.getByText("ADMIN")).toBeInTheDocument();
    expect(screen.queryByText(
      "La impresión terminó, pero no pudo registrarse en la auditoría."
    )).not.toBeInTheDocument();
    await waitFor(() => expect(fetch.mock.calls.filter(([input]) =>
      String(input).includes("/vouchers/VPRINT/management")
    )).toHaveLength(1));
  });
});

function response(body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  }));
}
