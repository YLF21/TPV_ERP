import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  ErpSelect,
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
});
