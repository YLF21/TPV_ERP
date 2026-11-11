// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ZoomableProductImage } from "./ZoomableProductImage";

afterEach(cleanup);

describe("ZoomableProductImage", () => {
  it("opens above its parent, isolates shortcuts and image clicks, and restores focus on outside click", () => {
    const parentKeyDown = vi.fn(), parentClick = vi.fn();
    render(<div role="dialog" aria-label="Ficha" onKeyDown={parentKeyDown} onClick={parentClick}>
      <ZoomableProductImage src="/product.png" alt="Café" locale="es" />
    </div>);
    const thumbnail = screen.getByRole("img", { name: "Café" });
    fireEvent.doubleClick(thumbnail);
    const viewer = screen.getByRole("dialog", { name: "Imagen del producto ampliada" });
    expect(viewer.parentElement).toBe(document.body);
    expect(viewer).toHaveAttribute("open");
    expect(viewer).toHaveFocus();
    fireEvent.keyDown(viewer, { key: "Insert" });
    fireEvent.keyDown(viewer, { key: "F9" });
    fireEvent.click(within(viewer).getByRole("img"));
    expect(parentKeyDown).not.toHaveBeenCalled();
    expect(parentClick).not.toHaveBeenCalled();
    expect(viewer).toHaveAttribute("open");
    fireEvent.click(viewer);
    expect(screen.queryByRole("dialog", { name: "Imagen del producto ampliada" })).not.toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Ficha" })).toBeInTheDocument();
    expect(thumbnail).toHaveFocus();
  });

  it("zooms both ways with bounded wheel input without scrolling and resets when reopened", () => {
    render(<ZoomableProductImage src="/product.png" alt="Café" locale="es" />);
    const thumbnail = screen.getByRole("img", { name: "Café" });
    fireEvent.doubleClick(thumbnail);
    const viewer = screen.getByRole("dialog");
    const image = within(viewer).getByRole("img");
    const wheel = new WheelEvent("wheel", { deltaY: -100, bubbles: true, cancelable: true });
    fireEvent(image, wheel);
    expect(wheel.defaultPrevented).toBe(true);
    expect(Number(image.style.transform.match(/scale\((.+)\)/)?.[1])).toBeGreaterThan(1);
    for (let i = 0; i < 10; i++) fireEvent.wheel(image, { deltaY: -1000 });
    expect(image).toHaveStyle({ transform: "scale(5)" });
    for (let i = 0; i < 10; i++) fireEvent.wheel(image, { deltaY: 1000, deltaMode: 1 });
    expect(image).toHaveStyle({ transform: "scale(0.25)" });
    fireEvent.keyDown(viewer, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    fireEvent.keyDown(thumbnail, { key: "Enter" });
    expect(within(screen.getByRole("dialog")).getByRole("img")).toHaveStyle({ transform: "scale(1)" });
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { bubbles: true, cancelable: true }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(thumbnail).toHaveFocus();
  });
});
