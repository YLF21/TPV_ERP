import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { LoginScreen } from "./LoginScreen";
import type { TerminalContext } from "../types";

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

describe("LoginScreen", () => {
  it("renders the shared brand and context footer without user control", () => {
    const html = renderToStaticMarkup(
      <LoginScreen
        app="venta"
        locale="es"
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onLogin={vi.fn()}
      />
    );

    expect(html).toContain('class="entry-topbar"');
    expect(html).toContain('class="top-date-time"');
    expect(html).toContain("APP VENTA");
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("DB:");
    expect(html).toContain("Conexión");
    expect(html).not.toContain('class="report-user-button"');
  });
});
