// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { useLayoutEffect, useRef, useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ExcelImportReviewTable } from "./ExcelImportReviewTable";

afterEach(cleanup);
describe("Excel import review table", () => {
  it.each([false, true])("keeps an immediate row-detail click before passive mount effects flush (controlled: %s)", (controlled) => {
    const props = { title: "Review", columns: [{ key: "rowNumber", label: "Row" }], onExport: vi.fn(), exportDisabled: false,
      labels: { export: "Export", empty: "Empty", review: "Review row", resize: (name: string) => "Resize " + name } };
    const rows = [{ id: 2, status: "error", values: { rowNumber: "2" }, details: <p>Fix this row</p> }];
    function ReviewOnMount() {
      const host = useRef<HTMLDivElement>(null);
      const [selectedRowId, setSelectedRowId] = useState<number | null>(null);
      useLayoutEffect(() => {
        // Model the first click after DOM commit, before passive initialization runs.
        host.current?.querySelector<HTMLButtonElement>(".shared-excel-review-row-button")?.click();
      }, []);
      return <div ref={host}><ExcelImportReviewTable {...props} rows={rows}
        {...(controlled ? { selectedRowId, onSelectRow: setSelectedRowId } : {})} /></div>;
    }
    render(<ReviewOnMount />);
    const button = screen.getByRole("button", { name: "Review row 2" });
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("region", { name: "Review row 2" })).toHaveTextContent("Fix this row");
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("region", { name: "Review row 2" })).not.toBeInTheDocument();
  });
  it("reveals a distant selected row without filtering and synchronizes a separate header", () => {
    const props = { title: "Review", columns: [{ key: "rowNumber", label: "Row" }], onExport: vi.fn(), exportDisabled: false,
      labels: { export: "Export", empty: "Empty", review: "Review row", resize: (name: string) => "Resize " + name } };
    const rows = Array.from({ length: 5000 }, (_, index) => ({ id: index + 2, status: "accepted", values: { rowNumber: String(index + 2) } }));
    const onSelectRow = vi.fn();
    const { container, rerender } = render(<ExcelImportReviewTable {...props} rows={rows} selectedRowId={null} onSelectRow={onSelectRow} />);
    const body = container.querySelector(".shared-excel-review-viewport") as HTMLElement;
    const header = container.querySelector(".shared-excel-fixed-header") as HTMLElement;
    Object.defineProperty(body, "clientHeight", { configurable: true, value: 320 });
    rerender(<ExcelImportReviewTable {...props} rows={rows} selectedRowId={4002} onSelectRow={onSelectRow} />);
    expect(body.scrollTop).toBe(4001 * 32 - 320);
    expect(body.querySelector('tr[data-source-row="4002"]')).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("table")).toHaveAttribute("aria-rowcount", "5001");
    expect(body).not.toContainElement(header);
    fireEvent.scroll(body, { target: { scrollLeft: 280 } });
    expect(header.scrollLeft).toBe(280);
    fireEvent.scroll(header, { target: { scrollLeft: 310 } });
    expect(body.scrollLeft).toBe(310);
    fireEvent.keyDown(body, { key: "ArrowDown" });
    expect(onSelectRow).toHaveBeenLastCalledWith(4003);
    fireEvent.click(screen.getByRole("button", { name: "Review row 4002" }));
    expect(onSelectRow).toHaveBeenLastCalledWith(4002);
    rerender(<ExcelImportReviewTable {...props} rows={rows} selectedRowId={4003} onSelectRow={onSelectRow} />);
    expect(screen.getByRole("region", { name: "Review row 4003" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Review row 4002" })).not.toBeInTheDocument();
  });
  it("clears difference markers in both table and open detail when the refreshed values match", () => {
    const props = { title: "Review", columns: [{ key: "rowNumber", label: "Row" },
      { key: "excel.name", label: "New name", source: "excel" as const }], onExport: vi.fn(), exportDisabled: false,
      labels: { export: "Export", empty: "Empty", review: "Review row", resize: (name: string) => "Resize " + name,
        comparison: { current: "Database", excel: "Excel", changed: "Different value", hint: "Differences do not imply writes" } } };
    const row = { id: 2, status: "accepted", values: { rowNumber: "2", "excel.name": "Product" }, changedColumns: ["excel.name"] };
    const { container, rerender } = render(<ExcelImportReviewTable {...props} rows={[row]} />);
    fireEvent.click(screen.getByRole("button", { name: "Review row 2" }));
    expect(screen.getAllByRole("img", { name: "Different value" })).toHaveLength(2);
    rerender(<ExcelImportReviewTable {...props} rows={[{ ...row, changedColumns: [] }]} />);
    expect(screen.queryByRole("img", { name: "Different value" })).not.toBeInTheDocument();
    expect(container.querySelectorAll(".shared-excel-review-cell--changed")).toHaveLength(0);
    expect(screen.getByRole("region", { name: "Review row 2" })).toHaveTextContent("Product");
    expect(screen.queryByText("Database")).not.toBeInTheDocument();
  });
  it("keeps the open row detail when an export or status update recreates equivalent row objects", () => {
    const props = { title: "Review", columns: [{ key: "rowNumber", label: "Row" }], onExport: vi.fn(), exportDisabled: false,
      labels: { export: "Export", empty: "Empty", review: "Review row", resize: (name: string) => "Resize " + name } };
    const row = { id: 2, status: "error", values: { rowNumber: "2" }, details: <p>Fix this row</p> };
    const { rerender } = render(<ExcelImportReviewTable {...props} rows={[row]} />);
    fireEvent.click(screen.getByRole("button", { name: "Review row 2" }));
    rerender(<ExcelImportReviewTable {...props} rows={[{ ...row }]} exportDisabled />);
    expect(screen.getByRole("region", { name: "Review row 2" })).toHaveTextContent("Fix this row");
  });
  it("windows thousands of source rows, resizes columns and exports without truncating rows", () => {
    const rows = Array.from({ length: 5000 }, (_, index) => ({ id: index + 2, status: "accepted",
      values: { rowNumber: String(index + 2), "excel.name": `Product ${index}` } }));
    const exportAll = vi.fn();
    const { container } = render(<ExcelImportReviewTable title="Review" rows={rows}
      columns={[{ key: "rowNumber", label: "Row" }, { key: "excel.name", label: "Name" }]}
      onExport={() => exportAll(rows)} exportDisabled={false}
      labels={{ export: "Export XLSX", empty: "Empty", review: "Review row", resize: (name) => "Resize " + name }} />);
    expect(container.querySelectorAll("tbody tr").length).toBeLessThan(50);
    expect(screen.getByRole("table")).toHaveAttribute("aria-rowcount", "5001");
    const scroll = container.querySelector(".shared-excel-review-viewport")!;
    fireEvent.scroll(scroll, { target: { scrollTop: 32000 } });
    expect(screen.queryByText("Product 0")).not.toBeInTheDocument();
    expect(screen.getByText("Product 1000")).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("button", { name: "Resize Name" }), { key: "ArrowRight" });
    expect(container.querySelectorAll("col")[1]).toHaveStyle({ width: "188px" });
    fireEvent.click(screen.getByRole("button", { name: "Export XLSX" }));
    expect(exportAll).toHaveBeenCalledWith(rows);
    fireEvent.click(screen.getByRole("button", { name: "Review row 1002" }));
    expect(screen.getByRole("region", { name: "Review row 1002" })).toHaveTextContent("Product 1000");
  });
});
