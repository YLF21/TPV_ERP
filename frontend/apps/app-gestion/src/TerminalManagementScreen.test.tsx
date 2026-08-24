import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  TerminalManagementScreen,
  terminalApprovePath,
  terminalDeactivatePath,
  terminalDisplayStatus
} from "./TerminalManagementScreen";

describe("TerminalManagementScreen", () => {
  it("encodes terminal identifiers in mutation paths", () => {
    expect(terminalApprovePath("pda/1")).toBe("/terminals/pda%2F1/approve");
    expect(terminalDeactivatePath("pda/1")).toBe("/terminals/pda%2F1/deactivate");
  });

  it("shows an inactive unapproved registration as pending", () => {
    expect(terminalDisplayStatus({ approved: false, active: false })).toBe("pending");
    expect(terminalDisplayStatus({ approved: true, active: false })).toBe("inactive");
    expect(terminalDisplayStatus({ approved: true, active: true })).toBe("approved");
  });

  it("renders approval metrics for administrators", () => {
    const html = renderToStaticMarkup(
      <TerminalManagementScreen
        session={{ username: "ADMIN", accessToken: "token", displayName: "ADMIN", permissions: ["ADMIN"] }}
        t={(key) => key}
      />
    );

    expect(html).toContain("gestion.terminals.title");
    expect(html).toContain("gestion.terminals.pending");
    expect(html).toContain("gestion.terminals.total");
  });
});
