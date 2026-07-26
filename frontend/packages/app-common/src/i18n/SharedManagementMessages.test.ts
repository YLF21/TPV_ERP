import { describe, expect, it } from "vitest";
import { sharedManagementMessages } from "./SharedManagementMessages";

describe("shared management messages", () => {
  it("contains a non-empty Spanish, English and Chinese value for every key", () => {
    const spanish = sharedManagementMessages("es");
    const keys = Object.keys(spanish).sort();

    for (const locale of ["es", "en", "zh"] as const) {
      const localized = sharedManagementMessages(locale);
      expect(Object.keys(localized).sort()).toEqual(keys);
      for (const key of keys) {
        expect(localized[key]?.trim(), `${locale}:${key}`).toBeTruthy();
        expect(localized[key], `${locale}:${key}`).not.toBe(key);
      }
    }
  });
});
