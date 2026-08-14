// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ModuleNavBackButton } from "./ModuleNavBackButton";

describe("ModuleNavBackButton", () => {
  it("renders the shared back icon and keeps the supplied action", () => {
    const onBack = vi.fn();

    const { container } = render(<ModuleNavBackButton label="Volver" onBack={onBack} />);

    expect(container.querySelector(".module-nav-back-icon")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Volver" }));
    expect(onBack).toHaveBeenCalledOnce();
  });
});
