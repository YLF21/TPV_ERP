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
});
