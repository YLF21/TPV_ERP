import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { GestionDashboard } from "./GestionDashboard";

describe("GestionDashboard", () => {
  it("renders dashboard content without owning a second navigation shell", () => {
    const html = renderToStaticMarkup(
      <GestionDashboard
        session={{
          username: "manager",
          displayName: "RESPONSABLE",
          accessToken: "token",
          permissions: ["APP_GESTION_ACCESS", "GESTION_VENTAS"]
        }}
        t={(key) => key}
        onOpenSales={vi.fn()}
        onOpenStock={vi.fn()}
        onOpenPromotions={vi.fn()}
        onOpenControlAlerts={vi.fn()}
      />
    );

    expect(html).toContain("gestion.dashboard");
    expect(html).not.toContain('class="gestion-nav"');
    expect(html).not.toContain("gestion.placeholder");
    expect(html).not.toContain("gestion.products");
  });

  it("keeps the operational incident surface out of the summary for administrators", () => {
    const html = renderToStaticMarkup(
      <GestionDashboard
        session={{
          username: "admin",
          displayName: "ADMIN",
          accessToken: "admin-token",
          permissions: ["ADMIN", "APP_GESTION_ACCESS"]
        }}
        locale="zh"
        t={(key) => key}
        onOpenSales={vi.fn()}
        onOpenStock={vi.fn()}
        onOpenPromotions={vi.fn()}
        onOpenControlAlerts={vi.fn()}
      />
    );

    expect(html).not.toContain("运行状态");
    expect(html).not.toContain("gestion-operational-status");
  });
});
