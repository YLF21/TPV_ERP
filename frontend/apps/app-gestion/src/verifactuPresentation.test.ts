import { describe, expect, it } from "vitest";
import { formatVerifactuDate, formatVerifactuDateTime, validatedIanaTimeZone } from "./verifactuPresentation";

describe("verifactuPresentation", () => {
  it("formats fiscal timestamps using the selected locale", () => {
    const value = "2026-07-01T10:30:00Z";
    expect(formatVerifactuDateTime(value, "es")).toMatch(/^1\/7\/26, \d{1,2}:30$/);
    expect(formatVerifactuDateTime(value, "en")).toMatch(/^01\/07\/2026, \d{1,2}:30$/);
    expect(formatVerifactuDateTime(value, "zh")).toMatch(/^2026\/7\/1 \d{1,2}:30$/);
  });

  it("formats fiscal timestamps in the store timezone", () => {
    const value = "2026-07-01T10:30:00Z";
    expect(formatVerifactuDateTime(value, "es", "Atlantic/Canary")).toMatch(/^1\/7\/26, 11:30$/);
    expect(formatVerifactuDateTime(value, "es", "America/New_York")).toMatch(/^1\/7\/26, 0?6:30$/);
    expect(validatedIanaTimeZone("not/a-timezone")).toBeUndefined();
    expect(formatVerifactuDateTime(value, "es", "not/a-timezone")).toBe("—");
  });

  it("keeps missing and invalid timestamps safe", () => {
    expect(formatVerifactuDateTime(null, "es")).toBe("—");
    expect(formatVerifactuDateTime("not-a-date", "es")).toBe("—");
  });

  it("formats date-only fiscal values without timezone drift", () => {
    expect(formatVerifactuDate("2026-07-01", "es")).toBe("1/7/26");
    expect(formatVerifactuDate("2026-07-01", "en")).toBe("01/07/2026");
    expect(formatVerifactuDate("2026-07-01", "zh")).toBe("2026/7/1");
    expect(formatVerifactuDate("invalid", "es")).toBe("—");
  });
});
