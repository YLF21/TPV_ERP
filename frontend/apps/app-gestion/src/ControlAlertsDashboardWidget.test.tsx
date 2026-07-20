// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { GestionDashboard } from "./GestionDashboard";
import * as dashboard from "./dashboardModel";

vi.mock("./dashboardModel", async (importOriginal) => {
  const original = await importOriginal<typeof import("./dashboardModel")>();
  return {
    ...original,
    loadDashboardPreference: vi.fn(),
    loadControlAlertsSummary: vi.fn(),
    saveDashboardPreference: vi.fn()
  };
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("control alerts dashboard widget", () => {
  it("renders counters, recent alerts and opens the real module", async () => {
    vi.mocked(dashboard.loadDashboardPreference).mockResolvedValue({
      widgets: [{ key: "control.alerts", width: 4, height: 2 }],
      availableWidgets: ["control.alerts"]
    });
    vi.mocked(dashboard.loadControlAlertsSummary).mockResolvedValue({
      newCount: 4,
      reviewedCount: 7,
      recentAlerts: [{
        id: "alert-1",
        type: "TICKET_CANCELLED",
        status: "NEW",
        occurredAt: "2026-07-18T10:30:00Z",
        documentNumber: "T-100",
        userName: "cashier"
      }]
    });
    const onOpenControlAlerts = vi.fn();

    render(
      <GestionDashboard
        session={{ username: "manager", displayName: "MANAGER", accessToken: "token", permissions: ["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"] }}
        t={(key) => key}
        onOpenSales={vi.fn()}
        onOpenStock={vi.fn()}
        onOpenPromotions={vi.fn()}
        onOpenControlAlerts={onOpenControlAlerts}
      />
    );

    expect(await screen.findByText("gestion.controlAlerts.type.TICKET_CANCELLED")).not.toBeNull();
    expect(screen.getByText("4")).not.toBeNull();
    expect(screen.getByText("7")).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /gestion.widget.controlAlerts.open/ }));
    expect(onOpenControlAlerts).toHaveBeenCalledOnce();
  });
});
