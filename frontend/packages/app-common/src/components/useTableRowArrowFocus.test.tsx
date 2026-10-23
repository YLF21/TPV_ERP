// @vitest-environment jsdom
import { useRef } from "react";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useTableRowArrowFocus } from "./useTableRowArrowFocus";

function FocusTable({ onActivate }: { onActivate: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useTableRowArrowFocus(ref, "tbody > tr");
  return <div ref={ref}><table><tbody>{[1, 2, 3].map((number) => <tr key={number} tabIndex={0}
    onKeyDown={navigate} onClick={onActivate} aria-label={`Row ${number}`}>
    <td><input type="checkbox" aria-label={`Select ${number}`} defaultChecked={number === 1} /></td>
    <td><button type="button">Open {number}</button></td>
  </tr>)}</tbody></table></div>;
}

describe("useTableRowArrowFocus", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it("advances the cursor without changing checkboxes or activating rows", () => {
    const activate = vi.fn();
    render(<FocusTable onActivate={activate} />);
    const rows = screen.getAllByRole("row");
    rows[0].focus();
    fireEvent.keyDown(rows[0], { key: "ArrowDown" });
    expect(document.activeElement).toBe(rows[1]);
    fireEvent.keyDown(rows[1], { key: "ArrowDown", repeat: true });
    expect(document.activeElement).toBe(rows[1]);
    act(() => vi.advanceTimersByTime(180));
    expect(document.activeElement).toBe(rows[2]);
    fireEvent.keyUp(rows[2], { key: "ArrowDown" });
    act(() => vi.advanceTimersByTime(500));
    expect(document.activeElement).toBe(rows[2]);
    expect(activate).not.toHaveBeenCalled();
    expect((screen.getByRole("checkbox", { name: "Select 1" }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole("checkbox", { name: "Select 2" }) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByRole("checkbox", { name: "Select 3" }) as HTMLInputElement).checked).toBe(false);
  });

  it("leaves nested controls and modified arrows to their own handlers", () => {
    render(<FocusTable onActivate={vi.fn()} />);
    const control = screen.getByRole("button", { name: "Open 1" });
    control.focus();
    expect(fireEvent.keyDown(control, { key: "ArrowDown" })).toBe(true);
    act(() => vi.advanceTimersByTime(500));
    expect(document.activeElement).toBe(control);
    const row = screen.getAllByRole("row")[0];
    row.focus();
    expect(fireEvent.keyDown(row, { key: "ArrowDown", shiftKey: true })).toBe(true);
    act(() => vi.advanceTimersByTime(500));
    expect(document.activeElement).toBe(row);
  });
});
