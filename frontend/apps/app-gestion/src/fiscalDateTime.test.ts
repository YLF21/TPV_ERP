import { describe, expect, it } from "vitest";
import { datetimeLocalToIso, isValidDatetimeLocal } from "./fiscalDateTime";

describe("datetimeLocalToIso", () => {
  it("uses the store timezone instead of the browser timezone", () => {
    expect(datetimeLocalToIso("2026-01-15T12:00", "Europe/Madrid")).toBe("2026-01-15T11:00:00.000Z");
    expect(datetimeLocalToIso("2026-01-15T12:00", "Atlantic/Canary")).toBe("2026-01-15T12:00:00.000Z");
  });

  it("rejects a nonexistent DST wall-clock time and accepts the valid side", () => {
    expect(isValidDatetimeLocal("2026-03-29T02:30", "Europe/Madrid")).toBe(false);
    expect(datetimeLocalToIso("2026-03-29T03:30", "Europe/Madrid")).toBe("2026-03-29T01:30:00.000Z");
  });

  it("rejects invalid calendar values without browser-dependent normalisation", () => {
    expect(isValidDatetimeLocal("2026-02-30T10:00", "Atlantic/Canary")).toBe(false);
    expect(isValidDatetimeLocal("2026-01-15T10:00", "Not/AZone")).toBe(false);
  });
});
