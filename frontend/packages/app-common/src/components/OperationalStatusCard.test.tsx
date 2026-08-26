// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { OperationalStatusCard } from "./OperationalStatusCard";

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("OperationalStatusCard", () => {
  it("summarizes fiscal, clock and synchronization state", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/verifactu/admin/status") {
        return {
          certificateConfigured: true,
          certificateValid: true,
          endpointMode: "TEST",
          workerEnabled: true,
          signatureRequired: true,
          verifactuActive: true,
        };
      }
      if (path === "/verifactu/admin/clock") {
        return { warning: false, driftSeconds: 2 };
      }
      if (path === "/sync/outbox/status") {
        return { pending: 3, sending: 1, sent: 42, error: 0, deadLetter: 2 };
      }
      if (path === "/sync/outbox/incidents") return [];
      if (path === "/admin/member-balance-recovery") return [];
      return undefined;
    }) as unknown as typeof apiRequest;

    render(<OperationalStatusCard locale="es" token="token" request={request} />);

    expect(await screen.findByText("Estado operativo")).toBeVisible();
    expect(await screen.findByText("TEST")).toBeVisible();
    expect(screen.getByText("42")).toBeVisible();
    expect(screen.getByText("Bloqueados")).toBeVisible();
    expect(screen.getAllByText("Activo").length).toBeGreaterThan(0);
  });

  it("allows an administrator to flush the synchronization outbox", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/verifactu/admin/status") {
        return {
          certificateConfigured: false,
          certificateValid: false,
          workerEnabled: false,
          signatureRequired: false,
          verifactuActive: false,
        };
      }
      if (path === "/verifactu/admin/clock") return { warning: false, driftSeconds: 0 };
      if (path === "/sync/outbox/status") return { pending: 1, sending: 0, sent: 0, error: 0 };
      if (path === "/sync/outbox/incidents") return [];
      if (path === "/admin/member-balance-recovery") return [];
      return undefined;
    }) as unknown as typeof apiRequest;

    render(<OperationalStatusCard locale="es" token="token" request={request} />);

    const button = await screen.findByRole("button", { name: "Sincronizar ahora" });
    fireEvent.click(button);

    await waitFor(() =>
      expect(request).toHaveBeenCalledWith("/sync/outbox/flush", {
        token: "token",
        method: "POST",
      }),
    );
  });

  it("requires an audited reason and preserves the outbox incident version on manual retry", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/verifactu/admin/status") return { verifactuActive: false };
      if (path === "/verifactu/admin/clock") return { warning: false, driftSeconds: 0 };
      if (path === "/sync/outbox/status") return { pending: 0, sending: 0, sent: 20, error: 0, deadLetter: 1 };
      if (path === "/sync/outbox/incidents") {
        return [{
          eventId: "11111111-1111-4111-8111-111111111111",
          entityType: "FISCAL_STATUS",
          entityId: "22222222-2222-4222-8222-222222222222",
          operation: "UPSERT",
          status: "DEAD_LETTER",
          attempts: 10,
          lastError: "Tiempo de espera agotado",
          updatedAt: "2026-08-25T12:00:00Z",
          version: 7,
        }];
      }
      if (path === "/admin/member-balance-recovery") return [];
      return undefined;
    }) as unknown as typeof apiRequest;

    render(<OperationalStatusCard locale="es" token="token" request={request} />);

    fireEvent.click(await screen.findByRole("button", { name: "Reintentar evento" }));
    const confirm = screen.getByRole("button", { name: "Confirmar reintento" });
    expect(confirm).toBeDisabled();

    fireEvent.change(screen.getByRole("textbox", { name: "Motivo del reintento" }), {
      target: { value: "Conectividad con SaaS comprobada" },
    });
    fireEvent.click(confirm);

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/sync/outbox/events/11111111-1111-4111-8111-111111111111/retry",
      {
        token: "token",
        method: "POST",
        body: {
          expectedVersion: 7,
          reason: "Conectividad con SaaS comprobada",
        },
      },
    ));
  });

  it("blocks manual reconciliation and only exposes member balance retries explicitly allowed by the backend", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/verifactu/admin/status") return { verifactuActive: false };
      if (path === "/verifactu/admin/clock") return { warning: false, driftSeconds: 0 };
      if (path === "/sync/outbox/status") return { pending: 0, sending: 0, sent: 20, error: 0, deadLetter: 0 };
      if (path === "/sync/outbox/incidents") return [];
      if (path === "/admin/member-balance-recovery") {
        return [
          {
            sessionId: "33333333-3333-4333-8333-333333333333",
            recoveryKind: "FINALIZATION",
            paymentStatus: "FINALIZED",
            ticketNumber: "T-0001",
            appliedAmount: 0.36,
            attempts: 1,
            lastError: "La reserva local ya fue liberada",
            manualReviewRequired: true,
            disposition: "MANUAL_RECONCILIATION_REQUIRED",
            retryAllowed: false,
            version: 3,
          },
          {
            sessionId: "44444444-4444-4444-8444-444444444444",
            recoveryKind: "ABORT",
            paymentStatus: "ABORT_PENDING",
            ticketNumber: "T-0002",
            requestedAmount: 2,
            attempts: 10,
            lastError: "Error transitorio",
            manualReviewRequired: true,
            disposition: "AUTOMATIC_RETRY",
            retryAllowed: true,
            version: 4,
          },
        ];
      }
      return undefined;
    }) as unknown as typeof apiRequest;

    render(<OperationalStatusCard locale="es" token="token" request={request} />);

    expect(await screen.findByText("Conciliación manual obligatoria")).toBeVisible();
    expect(screen.getByText("Sin reintento automático")).toBeVisible();
    const allowedRetry = screen.getByRole("button", { name: "Reintentar recuperación" });
    fireEvent.click(allowedRetry);
    fireEvent.change(screen.getByRole("textbox", { name: "Motivo del reintento" }), {
      target: { value: "Servicio central restablecido" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar reintento" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/admin/member-balance-recovery/44444444-4444-4444-8444-444444444444/retry",
      {
        token: "token",
        method: "POST",
        body: {
          expectedVersion: 4,
          reason: "Servicio central restablecido",
        },
      },
    ));
  });

  it("keeps the operational incident interface translated in Chinese", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/sync/outbox/incidents" || path === "/admin/member-balance-recovery") return [];
      if (path === "/sync/outbox/status") return { deadLetter: 0 };
      return {};
    }) as unknown as typeof apiRequest;

    render(<OperationalStatusCard locale="zh" token="token" request={request} />);

    expect(await screen.findByText("运行事件")).toBeVisible();
    expect(screen.getByText("没有被阻止的同步事件。")).toBeVisible();
    expect(screen.getByText("没有等待处理的会员余额恢复。")).toBeVisible();
  });
});
