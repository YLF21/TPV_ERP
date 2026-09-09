import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");

describe("Home launcher alignment", () => {
  it("only reserves gaps for visible cash-session controls", () => {
    const launcher = css.match(/\.home-screen \.home-sale-launcher\s*\{([^}]*)\}/)?.[1];
    const sale = css.match(/\.home-screen \.home-action-sale\s*\{([^}]*)\}/)?.[1];
    expect(launcher).toMatch(/height:\s*var\(--home-launcher-height\);/);
    expect(launcher).toMatch(/display:\s*flex;/);
    expect(launcher).toMatch(/flex-direction:\s*column;/);
    expect(launcher).not.toContain("grid-template-rows");
    expect(sale).toMatch(/flex:\s*1 1 0;/);
  });
});
