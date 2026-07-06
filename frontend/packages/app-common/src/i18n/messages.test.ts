import { describe, expect, it } from "vitest";
import { LocalizedMessages } from "./LocalizedMessages";

describe("messages", () => {
  it("keeps every visible key translated in all locales", () => {
    const keys = Object.keys(LocalizedMessages.values.es);
    expect(Object.keys(LocalizedMessages.values.en).sort()).toEqual(keys.sort());
    expect(Object.keys(LocalizedMessages.values.zh).sort()).toEqual(keys.sort());
  });
});
