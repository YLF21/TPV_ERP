// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";

afterEach(cleanup);

describe("TableLayoutHeaderCell sorting", () => {
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
});
