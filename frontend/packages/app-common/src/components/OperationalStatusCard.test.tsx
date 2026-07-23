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
        return { pending: 3, sending: 1, sent: 42, error: 0 };
      }
      return undefined;
    }) as unknown as typeof apiRequest;

    render(<OperationalStatusCard locale="es" token="token" request={request} />);

    expect(await screen.findByText("Estado operativo")).toBeVisible();
    expect(await screen.findByText("TEST")).toBeVisible();
    expect(screen.getByText("42")).toBeVisible();
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
});
