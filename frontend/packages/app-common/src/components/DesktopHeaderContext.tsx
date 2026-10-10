import type { TerminalContext } from "../types";
import "./DesktopHeaderContext.css";

export function DesktopHeaderContext({ terminalContext, inline = false, inverse = false }: {
  terminalContext: TerminalContext;
  inline?: boolean;
  inverse?: boolean;
}) {
  const company = terminalContext.companyName?.trim() || "—";
  return <div className="desktop-header-context" style={inline ? {
    position: "static", maxWidth: "min(420px,30vw)", ...(inverse ? { color: "#fff" } : {})
  } : undefined}>
    <strong title={company}>{company}</strong>
  </div>;
}
