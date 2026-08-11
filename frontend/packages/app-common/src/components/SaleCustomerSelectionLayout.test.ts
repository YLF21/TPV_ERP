import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const tpvCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");

describe("Sale customer selection layout", () => {
  it("reserves a row for the header, actions, search, table and footer", () => {
    const dialogRule = tpvCss.match(/\.sale-action-dialog\.sale-customer-selection-dialog\s*\{[\s\S]*?\}/)?.[0];

    expect(dialogRule).toMatch(
      /grid-template-rows:\s*54px\s+auto\s+auto\s+minmax\(0,\s*1fr\)\s+54px;/,
    );
  });
});
