import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");

describe("Sale customer receivables layout", () => {
  it("removes the shared filter dialog gutters without changing other dialogs", () => {
    const rule = css.match(/\.sale-customer-receivables-dialog\s*\{[\s\S]*?\}/)?.[0];

    expect(rule).toMatch(/padding:\s*0;/);
    expect(rule).toMatch(/gap:\s*0;/);
    expect(rule).toMatch(/grid-template-rows:\s*58px\s+auto\s+minmax\(0,\s*1fr\)\s+58px;/);
  });
});
