// @vitest-environment jsdom
import { useRef } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useTableInitialFocus } from "./useTableInitialFocus";
import { useTableRowArrowFocus } from "./useTableRowArrowFocus";

function Table({ count = 3, ready = true, scope = "one", onSelect = vi.fn(), modal = false }: {
  count?: number; ready?: boolean; scope?: string; onSelect?: (row: HTMLElement) => void; modal?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useTableInitialFocus(ref, "tbody tr", { ready, scopeKey: scope, onSelect });
  const navigate = useTableRowArrowFocus(ref, "tbody tr", false);
  return <><input aria-label="Search" />{modal && <section role="dialog" aria-modal="true"><input aria-label="Edit" /></section>}
    <div ref={ref}><table><tbody>{Array.from({ length: count }, (_, i) => <tr key={i} tabIndex={0}
      onKeyDown={navigate} aria-label={`Row ${i + 1}`}><td><input type="checkbox" aria-label={`Check ${i + 1}`} /></td></tr>)}</tbody></table></div></>;
}

afterEach(() => cleanup());

describe("useTableInitialFocus", () => {
  it("prepares the first row without clicking or checking it, then preserves later focus on refresh", () => {
    const select = vi.fn();
    const { rerender } = render(<Table onSelect={select} />);
    expect(document.activeElement).toBe(screen.getByRole("row", { name: "Row 1" }));
    expect(select).toHaveBeenCalledTimes(1);
    screen.getByRole("row", { name: "Row 2" }).focus();
    rerender(<Table count={5} onSelect={select} />);
    expect(document.activeElement).toBe(screen.getByRole("row", { name: "Row 2" }));
    expect(select).toHaveBeenCalledTimes(1);
    expect((screen.getByLabelText("Check 1") as HTMLInputElement).checked).toBe(false);
  });

  it("waits for the first asynchronous rows", () => {
    const select = vi.fn();
    const { rerender } = render(<Table count={0} ready={false} onSelect={select} />);
    expect(select).not.toHaveBeenCalled();
    rerender(<Table ready onSelect={select} />);
    expect(document.activeElement).toBe(screen.getByRole("row", { name: "Row 1" }));
    expect(select).toHaveBeenCalledTimes(1);
  });

  it("selects after loading without interrupting a user who started typing", () => {
    const select = vi.fn();
    const { rerender } = render(<Table count={0} ready={false} onSelect={select} />);
    const search = screen.getByRole("textbox", { name: "Search" });
    search.focus();
    fireEvent.keyDown(search, { key: "a" });
    rerender(<Table ready onSelect={select} />);
    expect(document.activeElement).toBe(search);
    expect(select).toHaveBeenCalledTimes(1);
  });

  it("prepares the first row again when entering another screen scope", () => {
    const select = vi.fn();
    const { rerender } = render(<Table onSelect={select} />);
    screen.getByRole("row", { name: "Row 3" }).focus();
    rerender(<Table scope="two" onSelect={select} />);
    expect(document.activeElement).toBe(screen.getByRole("row", { name: "Row 1" }));
    expect(select).toHaveBeenCalledTimes(2);
  });

  it("never takes focus from a foreground dialog", () => {
    const select = vi.fn();
    render(<Table modal onSelect={select} />);
    expect(select).not.toHaveBeenCalled();
    expect(document.activeElement?.getAttribute("aria-label")).not.toBe("Row 1");
  });

  it("ignores hidden dialogs while preparing the visible screen", () => {
    const hidden = document.createElement("section");
    hidden.hidden = true;
    hidden.setAttribute("aria-modal", "true");
    document.body.append(hidden);
    render(<Table />);
    expect(document.activeElement).toBe(screen.getByRole("row", { name: "Row 1" }));
    hidden.remove();
  });
});
