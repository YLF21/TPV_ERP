import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");
const rules = Array.from(css.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/([^{}]+)\{([^{}]*)\}/g));
const ruleFor = (selector: string) => rules.find(([ , selectors]) => (
  selectors.split(",").some((value) => value.trim() === selector)
))?.[2] ?? "";

describe("Warehouse full-height table layout", () => {
  it("allocates the workspace independently of empty or conditional children", () => {
    const workspace = ruleFor(".warehouse-screen .stock-list.work-panel");
    expect(workspace).toMatch(/display:\s*flex;/);
    expect(workspace).toMatch(/flex-direction:\s*column;/);
    for (const panel of [".stock-sales-history-panel", ".goods-check-panel"]) {
      const rule = ruleFor(`.warehouse-screen ${panel}`);
      expect(rule).toMatch(/flex:\s*1 1 0;/);
      expect(rule).toMatch(/min-height:\s*0;/);
      expect(rule).toMatch(/height:\s*auto !important;/);
    }
  });

  it("uses vertical flow for document and goods-check panels with optional messages", () => {
    for (const panel of [".stock-sales-history-panel", ".goods-check-documents", ".goods-check-workspace"]) {
      const flow = rules.find(([ , selectors, body]) => (
        selectors.split(",").some((value) => value.trim() === `.warehouse-screen ${panel}`)
        && body.includes("display: flex")
      ))?.[2];
      expect(flow).toMatch(/flex-direction:\s*column;/);
      expect(ruleFor(`.warehouse-screen ${panel} > *`)).toMatch(/flex-shrink:\s*0;/);
    }
  });

  it("expands the scroll viewport, not the data rows, including the empty check state", () => {
    for (const area of [".stock-history-table-scroll", ".goods-check-workspace-empty"]) {
      const rule = ruleFor(`.warehouse-screen ${area}`);
      expect(rule).toMatch(/flex:\s*1 1 0;/);
      expect(rule).toMatch(/min-height:\s*0;/);
      expect(rule).toMatch(/height:\s*auto !important;/);
    }
  });
});
