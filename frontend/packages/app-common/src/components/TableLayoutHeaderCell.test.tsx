// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";

afterEach(cleanup);

describe("TableLayoutHeaderCell sorting", () => {
  it("provides a dedicated drag handle so sorting controls do not block column reordering", () => {
    const onReorder = vi.fn();
    const stored = new Map<string, string>();
    const dataTransfer = {
      effectAllowed: "none",
      dropEffect: "none",
      setData: (type: string, value: string) => stored.set(type, value),
      getData: (type: string) => stored.get(type) ?? ""
    };
    const { container } = render(
      <div>
        <TableLayoutHeaderCell
          as="span"
          column={{ key: "name", width: 180, visible: true }}
          sortLabel="Ordenar por nombre"
          onSort={vi.fn()}
          resizeLabel="Cambiar ancho de nombre"
          onReorder={onReorder}
          onMove={vi.fn()}
          onResize={vi.fn()}
        >Nombre</TableLayoutHeaderCell>
        <TableLayoutHeaderCell
          as="span"
          column={{ key: "total", width: 120, visible: true }}
          resizeLabel="Cambiar ancho de total"
          onReorder={onReorder}
          onMove={vi.fn()}
          onResize={vi.fn()}
        >Total</TableLayoutHeaderCell>
      </div>
    );

    const source = container.querySelector('[data-column-key="name"]') as HTMLElement;
    const handle = source.querySelector(".table-layout-drag-handle") as HTMLElement;
    const target = container.querySelector('[data-column-key="total"]') as HTMLElement;
    expect(handle).toHaveAttribute("draggable", "true");

    fireEvent.dragStart(handle, { dataTransfer });
    fireEvent.dragOver(target, { dataTransfer });
    fireEvent.drop(target, { dataTransfer });

    expect(onReorder).toHaveBeenCalledWith("name", "total");
  });

  it("shows the neutral arrow and reports the selected column", () => {
    const onSort = vi.fn();
    render(
      <table><thead><tr><TableLayoutHeaderCell
        column={{ key: "name", width: 180, visible: true }}
        sortDirection={null}
        sortLabel="Ordenar por nombre"
        onSort={onSort}
        resizeLabel="Cambiar ancho de nombre"
        onReorder={vi.fn()}
        onMove={vi.fn()}
        onResize={vi.fn()}
      >Nombre</TableLayoutHeaderCell></tr></thead></table>
    );

    const button = screen.getByRole("button", { name: "Ordenar por nombre" });
    expect(screen.getByRole("columnheader")).toHaveAttribute("aria-sort", "none");
    expect(button).toHaveTextContent("\u2195");
    expect(button).toHaveAttribute("data-sort-direction", "none");
    fireEvent.click(button);
    expect(onSort).toHaveBeenCalledWith("name");
  });

  it("exposes ascending state without losing column movement shortcuts", () => {
    const onMove = vi.fn();
    render(
      <div><TableLayoutHeaderCell
        as="span"
        column={{ key: "total", width: 120, visible: true }}
        sortDirection="asc"
        sortLabel="Ordenar por total"
        onSort={vi.fn()}
        resizeLabel="Cambiar ancho de total"
        onReorder={vi.fn()}
        onMove={onMove}
        onResize={vi.fn()}
      >Total</TableLayoutHeaderCell></div>
    );

    const header = screen.getByRole("columnheader");
    const sortButton = screen.getByRole("button", { name: "Ordenar por total" });
    expect(header).toHaveAttribute("aria-sort", "ascending");
    expect(sortButton).toHaveTextContent("\u2191");
    expect(sortButton).toHaveClass("is-active");
    expect(sortButton).toHaveAttribute("data-sort-direction", "asc");
    fireEvent.keyDown(header, { key: "ArrowLeft", ctrlKey: true });
    expect(onMove).toHaveBeenCalledWith("total", -1);
  });

  it("renders a compact header action beside the sort control without nesting buttons", () => {
    const onAction = vi.fn();
    const { container } = render(
      <table><thead><tr><TableLayoutHeaderCell
        column={{ key: "customer", width: 180, visible: true }}
        sortDirection={null}
        sortLabel="Ordenar cliente"
        onSort={vi.fn()}
        headerAction={<button type="button" onClick={onAction}>N</button>}
        resizeLabel="Cambiar ancho de cliente"
        onReorder={vi.fn()}
        onMove={vi.fn()}
        onResize={vi.fn()}
      >Cliente</TableLayoutHeaderCell></tr></thead></table>
    );

    expect(container.querySelector("button button")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "N" }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it("opens the column menu, moves or hides the column, and closes with Escape", () => {
    const onMove = vi.fn();
    const onToggleVisibility = vi.fn();
    render(
      <table><thead><tr><TableLayoutHeaderCell
        column={{ key: "customer", width: 180, visible: true }}
        resizeLabel="Cambiar ancho de cliente"
        onReorder={vi.fn()}
        onMove={onMove}
        onResize={vi.fn()}
        onToggleVisibility={onToggleVisibility}
      >Cliente</TableLayoutHeaderCell></tr></thead></table>
    );

    const menuButton = screen.getByRole("button", { name: "Opciones de columna" });
    fireEvent.click(menuButton);
    expect(screen.getByRole("menu")).toBeVisible();
    fireEvent.click(screen.getByRole("menuitem", { name: /Mover a la derecha/ }));
    expect(onMove).toHaveBeenCalledWith("customer", 1);
    expect(screen.queryByRole("menu")).toBeNull();

    fireEvent.click(menuButton);
    fireEvent.click(screen.getByRole("menuitem", { name: "Ocultar columna" }));
    expect(onToggleVisibility).toHaveBeenCalledWith("customer");

    fireEvent.click(menuButton);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("menu")).toBeNull();
    expect(menuButton).toHaveFocus();
  });

  it("keeps the column editor open while scrolling and modifying several columns", async () => {
    const onToggleVisibility = vi.fn();
    render(
      <table><thead><tr><TableLayoutHeaderCell
        column={{ key: "customer", width: 180, visible: true }}
        resizeLabel="Cambiar ancho de cliente"
        onReorder={vi.fn()}
        onMove={vi.fn()}
        onResize={vi.fn()}
        onToggleVisibility={onToggleVisibility}
        columnVisibilityOptions={[
          { key: "customer", label: "Cliente", visible: true },
          { key: "status", label: "Estado", visible: false }
        ]}
      >Cliente</TableLayoutHeaderCell></tr></thead></table>
    );

    fireEvent.click(screen.getByRole("button", { name: "Opciones de columna" }));
    expect(screen.getByText("Modificar columnas")).toBeVisible();
    expect(screen.getByRole("menuitemcheckbox", { name: "Cliente" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("menuitemcheckbox", { name: "Estado" })).toHaveAttribute("aria-checked", "false");

    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "Estado" }));
    expect(onToggleVisibility).toHaveBeenCalledWith("status");
    expect(screen.getByRole("menu")).toBeVisible();

    const visibilityList = screen.getByRole("group", { name: "Modificar columnas" });
    fireEvent.scroll(visibilityList);
    expect(screen.getByRole("menu")).toBeVisible();

    onToggleVisibility.mockClear();
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "Cliente" }));
    expect(screen.getByRole("menu")).toBeVisible();
    expect(onToggleVisibility).not.toHaveBeenCalled();

    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(onToggleVisibility).toHaveBeenCalledWith("customer"));
  });
});
