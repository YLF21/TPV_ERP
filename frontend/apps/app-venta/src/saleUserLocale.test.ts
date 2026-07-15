// @vitest-environment jsdom

import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { UserSession } from "../../../packages/app-common/src/types";
import {
  readSaleUserLocale,
  saleUserLocaleStorageKey,
  saveSaleUserLocale,
  useSaleUserLocalePreference,
} from "./saleUserLocale";

const userA: UserSession = { userId: " USER/A ", username: "ADMIN", displayName: "Admin", permissions: [] };
const userB: UserSession = { username: " VENTA.B ", displayName: "Venta B", permissions: [] };

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe("sale user locale", () => {
  it("builds a normalized APP VENTA key from userId or username", () => {
    expect(saleUserLocaleStorageKey(userA)).toBe("tpv-erp:venta:user:user%2Fa:locale");
    expect(saleUserLocaleStorageKey(userB)).toBe("tpv-erp:venta:user:venta.b:locale");
  });

  it("stores valid locales independently and rejects invalid stored values", () => {
    saveSaleUserLocale(userA, "en");
    saveSaleUserLocale(userB, "zh");
    expect(readSaleUserLocale(userA)).toBe("en");
    expect(readSaleUserLocale(userB)).toBe("zh");
    localStorage.setItem(saleUserLocaleStorageKey(userA), "fr");
    expect(readSaleUserLocale(userA)).toBe("es");
  });

  it("starts and resets in Spanish, loads on login and persists active changes", () => {
    saveSaleUserLocale(userA, "en");
    const { result } = renderHook(() => useSaleUserLocalePreference());
    expect(result.current.locale).toBe("es");

    act(() => result.current.applyUserLocale(userA));
    expect(result.current.locale).toBe("en");
    act(() => result.current.changeLocale(userA, "zh"));
    expect(result.current.locale).toBe("zh");
    expect(readSaleUserLocale(userA)).toBe("zh");

    act(() => result.current.resetLocale());
    expect(result.current.locale).toBe("es");
  });

  it("keeps the in-memory locale usable when storage throws", () => {
    const unavailable = {
      getItem: () => { throw new Error("blocked"); },
      setItem: () => { throw new Error("blocked"); },
    } as unknown as Storage;
    const { result } = renderHook(() => useSaleUserLocalePreference(unavailable));

    act(() => result.current.applyUserLocale(userA));
    expect(result.current.locale).toBe("es");
    act(() => result.current.changeLocale(userA, "en"));
    expect(result.current.locale).toBe("en");
  });
});
