// @vitest-environment jsdom
import { useState } from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ErpFilterChips } from "./ErpFilterChips";

afterEach(cleanup);

describe("ERP applied filter chips", () => {
  it("removes only the chosen criterion and retains keyboard focus after the last chip disappears", async () => {
    function Filters() {
      const [values, setValues] = useState(["Familia", "Estado"]);
      return <section><input aria-label="Buscar" /><ErpFilterChips chips={values.map(value => ({
        key: value, label: value, value: "Activo", onRemove: () => setValues(current => current.filter(item => item !== value))
      }))} /></section>;
    }
    render(<Filters />);
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Familia" }));
    expect(screen.queryByRole("button", { name: "Quitar filtro Familia" })).toBeNull();
    const remaining = screen.getByRole("button", { name: "Quitar filtro Estado" });
    await waitFor(() => expect(document.activeElement).toBe(remaining));
    fireEvent.click(remaining);
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("textbox", { name: "Buscar" })));
    expect(screen.queryByRole("group")).toBeNull();
  });

  it("does not let Enter on a filter button invoke the parent dialog action", () => {
    const submit = vi.fn();
    const remove = vi.fn();
    render(<div onKeyDown={submit}><ErpFilterChips chips={[
      { key: "search", label: "Buscar", value: "Artículo", onRemove: remove }
    ]} /></div>);
    const button = screen.getByRole("button", { name: "Quitar filtro Buscar" });
    fireEvent.keyDown(button, { key: "Enter" });
    expect(submit).not.toHaveBeenCalled();
    fireEvent.click(button);
    expect(remove).toHaveBeenCalledOnce();
  });

  it("clears all criteria once and uses localized accessible labels", async () => {
    const clear = vi.fn();
    render(<section><input aria-label="Search" /><ErpFilterChips locale="en" chips={[
      { key: "state", label: "State", value: "Active", onRemove: vi.fn() }
    ]} onClear={clear} /></section>);
    expect(screen.getByRole("group", { name: "Applied filters" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Clear all" }));
    expect(clear).toHaveBeenCalledOnce();
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("textbox", { name: "Search" })));
  });
});
