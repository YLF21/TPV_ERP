import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { GestionDashboard } from "./GestionDashboard";

describe("GestionDashboard", () => {
  it("keeps the formal ERP sidebar and renders no disconnected placeholder modules", () => {
    const html = renderToStaticMarkup(
      <GestionDashboard
        session={{
          username: "manager",
          displayName: "RESPONSABLE",
          accessToken: "token",
          permissions: ["APP_GESTION_ACCESS", "GESTION_VENTAS"]
        }}
        t={(key) => key}
        navigation={[{ key: "sales", label: "gestion.sales", onOpen: vi.fn() }]}
        onOpenSales={vi.fn()}
        onOpenStock={vi.fn()}
        onOpenPromotions={vi.fn()}
        onOpenControlAlerts={vi.fn()}
      />
    );

    expect(html).toContain('class="gestion-nav"');
    expect(html).toContain("gestion.dashboard");
    expect(html).toContain("gestion.sales");
    expect(html).not.toContain("gestion.placeholder");
    expect(html).not.toContain("gestion.products");
  });
});
