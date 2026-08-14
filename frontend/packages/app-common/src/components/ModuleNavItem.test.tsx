// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Package } from "@phosphor-icons/react";
import { ModuleNavItem } from "./ModuleNavItem";

describe("ModuleNavItem", () => {
  it("renders the icon above an accessible label and keeps the supplied action", () => {
    const onClick = vi.fn();

    const { container } = render(
      <ModuleNavItem
        icon={<Package />}
        label="Stock"
        selected
        onClick={onClick}
      />
    );

    const button = screen.getByRole("button", { name: "Stock" });
    expect(button).toHaveClass("module-nav-item", "selected");
    expect(button).toHaveAttribute("aria-current", "page");
    expect(container.querySelector(".module-nav-item-icon svg")).toBeInTheDocument();
    expect(container.querySelector(".module-nav-item-label")).toHaveTextContent("Stock");

    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledOnce();
  });
});
