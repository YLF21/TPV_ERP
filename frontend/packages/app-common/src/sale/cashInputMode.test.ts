import { describe, expect, it, vi } from "vitest";
import { readCashInputMode, writeCashInputMode } from "./cashInputMode";

function storageWith(value: string | null): Storage {
  return {
    getItem: vi.fn(() => value),
    setItem: vi.fn(),
  } as unknown as Storage;
}

describe("cash input mode", () => {
  it("uses touch when no preference is stored", () => {
    expect(readCashInputMode(storageWith(null))).toBe("touch");
  });

  it.each(["touch", "keyboard"] as const)("reads the valid %s preference", (mode) => {
    expect(readCashInputMode(storageWith(mode))).toBe(mode);
  });

  it("recovers from invalid values and storage read errors", () => {
    expect(readCashInputMode(storageWith("mouse"))).toBe("touch");

    const brokenStorage = storageWith(null);
    vi.mocked(brokenStorage.getItem).mockImplementation(() => {
      throw new Error("storage unavailable");
    });
    expect(readCashInputMode(brokenStorage)).toBe("touch");
  });

  it("writes the preference and ignores storage write errors", () => {
    const storage = storageWith(null);
    writeCashInputMode("keyboard", storage);
    expect(storage.setItem).toHaveBeenCalledWith("tpverp.cashInputMode.v1", "keyboard");

    vi.mocked(storage.setItem).mockImplementation(() => {
      throw new Error("storage unavailable");
    });
    expect(() => writeCashInputMode("touch", storage)).not.toThrow();
  });
});
