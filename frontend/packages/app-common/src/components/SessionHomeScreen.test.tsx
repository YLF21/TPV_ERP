import { renderToStaticMarkup } from "react-dom/server";
import { ReactElement, ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { SessionHomeScreen } from "./SessionHomeScreen";
import type { TerminalContext, UserSession } from "../types";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

function textContent(node: ReactNode): string {
  if (typeof node === "string" || typeof node === "number") {
    return String(node);
  }
  if (Array.isArray(node)) {
    return node.map(textContent).join("");
  }
  if (node && typeof node === "object" && "props" in node) {
    return textContent((node as ReactElement<{ children?: ReactNode }>).props.children);
  }
  return "";
}

function directButtonText(node: ReactNode): string {
  if (Array.isArray(node)) {
    return node
      .map((child) => {
        if (typeof child === "string" || typeof child === "number") {
          return String(child);
        }
        if (child && typeof child === "object" && "type" in child && "props" in child) {
          const element = child as ReactElement<{ children?: ReactNode }>;
          return element.type === "span" ? textContent(element.props.children) : "";
        }
        return "";
      })
      .join("");
  }
  return textContent(node);
}

function findButtonByText(node: ReactNode, text: string): ReactElement<{ onClick?: () => void }> | undefined {
  if (!node || typeof node !== "object") {
    return undefined;
  }
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findButtonByText(child, text);
      if (found) {
        return found;
      }
    }
    return undefined;
  }
  if (!("type" in node) || !("props" in node)) {
    return undefined;
  }
  const element = node as ReactElement<{ children?: ReactNode; onClick?: () => void }>;
  if (element.type === "button" && directButtonText(element.props.children).toLowerCase().includes(text.toLowerCase())) {
    return element;
  }
  return findButtonByText(element.props.children, text);
}

function buttonsIn(node: ReactNode): Array<ReactElement<{ children?: ReactNode; onClick?: () => void }>> {
  if (!node || typeof node !== "object") {
    return [];
  }
  if (Array.isArray(node)) {
    return node.flatMap(buttonsIn);
  }
  if (!("type" in node) || !("props" in node)) {
    return [];
  }
  const element = node as ReactElement<{ children?: ReactNode; onClick?: () => void }>;
  return [
    ...(element.type === "button" ? [element] : []),
    ...buttonsIn(element.props.children)
  ];
}

describe("SessionHomeScreen", () => {
  it("renders the formal home with user language and shutdown controls", () => {
    const html = renderToStaticMarkup(
      <SessionHomeScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        canOpenSalesReport
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenSalesReport={vi.fn()}
        onOpenSettings={vi.fn()}
      />
    );

    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="entry-topbar"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("DB:");
    expect(html).toContain("Conexión");
    expect(html).toContain("ADMIN");
    expect(html).toContain("AJUSTE");
  });

  it("wires stock action to its callback", () => {
    const onOpenStock = vi.fn();

    const tree = SessionHomeScreen({
      app: "venta",
      locale: "es",
      session,
      terminalContext,
      onLocaleChange: vi.fn(),
      onOpenStock
    });
    const stockButton = buttonsIn(tree).find((button) => directButtonText(button.props.children).trim().toUpperCase() === "INVENTARIO");

    expect(stockButton?.props.onClick).toBe(onOpenStock);
  });
});
