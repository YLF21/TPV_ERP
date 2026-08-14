import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  ErpSelect,
  erpSelectPopoverLayout,
  erpSelectKeyIntent,
  nextErpSelectOptionIndex,
  type ErpSelectOption
} from "./ErpSelect";

const options: readonly ErpSelectOption[] = [
  { value: "confirmed", label: "Confirmado" },
  { value: "blocked", label: "Bloqueado", disabled: true },
  { value: "cancelled", label: "Anulado" }
];

describe("ErpSelect", () => {
  it("renders a labelled listbox trigger with the selected value", () => {
    const html = renderToStaticMarkup(
      <ErpSelect
        id="status-filter"
        aria-label="Estado"
        value="confirmed"
        options={options}
        onChange={vi.fn()}
      />
    );

    expect(html).toContain('id="status-filter"');
    expect(html).toContain('aria-label="Estado"');
    expect(html).toContain('aria-haspopup="listbox"');
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain('aria-controls="status-filter-listbox"');
    expect(html).toContain("Confirmado");
  });

  it("exposes a real disabled button", () => {
    const html = renderToStaticMarkup(
      <ErpSelect aria-label="Estado" value="confirmed" options={options} onChange={vi.fn()} disabled />
    );

    expect(html).toContain("erp-select--disabled");
    expect(html).toContain("disabled");
    expect(html).toContain('aria-expanded="false"');
  });

  it("maps the required keyboard commands", () => {
    expect(erpSelectKeyIntent("ArrowDown")).toBe("next");
    expect(erpSelectKeyIntent("ArrowUp")).toBe("previous");
    expect(erpSelectKeyIntent("Enter")).toBe("select");
    expect(erpSelectKeyIntent("Escape")).toBe("close");
    expect(erpSelectKeyIntent("Tab")).toBeNull();
  });

  it("wraps arrow navigation and skips disabled options", () => {
    expect(nextErpSelectOptionIndex(options, 0, 1)).toBe(2);
    expect(nextErpSelectOptionIndex(options, 2, 1)).toBe(0);
    expect(nextErpSelectOptionIndex(options, 0, -1)).toBe(2);
    expect(nextErpSelectOptionIndex(options, -1, 1)).toBe(0);
    expect(nextErpSelectOptionIndex(options, -1, -1)).toBe(2);
    expect(nextErpSelectOptionIndex([], -1, 1)).toBe(-1);
  });

  it("keeps the popover inside the viewport and opens it above when needed", () => {
    expect(erpSelectPopoverLayout(
      { top: 720, bottom: 766, left: 1080, width: 220 },
      { width: 340, height: 240 },
      { width: 1329, height: 912 },
    )).toEqual({
      top: 476,
      left: 981,
      minWidth: 220,
      maxWidth: 360,
      maxHeight: 240,
    });
  });

  it("caps a dropdown opened from a full-width settings control", () => {
    expect(erpSelectPopoverLayout(
      { top: 475, bottom: 521, left: 165, width: 1850 },
      { width: 1850, height: 140 },
      { width: 2048, height: 1024 },
    )).toEqual({
      top: 525,
      left: 165,
      minWidth: 360,
      maxWidth: 360,
      maxHeight: 240,
    });
  });
});
