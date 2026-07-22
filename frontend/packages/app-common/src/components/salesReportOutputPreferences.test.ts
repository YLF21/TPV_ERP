import { describe, expect, it } from "vitest";
import type { TerminalContext } from "../types";
import {
  defaultSalesReportOutputPreferences,
  readSalesReportOutputPreferences,
  salesReportOutputPreferencesStorageKey,
  saveSalesReportOutputPreferences
} from "./salesReportOutputPreferences";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    }
  } as unknown as Storage;
}

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

describe("salesReportOutputPreferences", () => {
  it("keeps report preferences scoped to app, terminal and user", () => {
    const storage = memoryStorage();
    saveSalesReportOutputPreferences("venta", "admin", terminalContext, {
      density: "compact",
      primaryAction: "pdf"
    }, storage);

    expect(readSalesReportOutputPreferences("venta", "admin", terminalContext, storage)).toEqual({
      density: "compact",
      primaryAction: "pdf"
    });
    expect(readSalesReportOutputPreferences("venta", "vendedor", terminalContext, storage)).toEqual(
      defaultSalesReportOutputPreferences
    );
    expect(salesReportOutputPreferencesStorageKey("venta", "admin", terminalContext)).toContain(
      "terminal:01:user:admin"
    );
  });

  it("falls back safely when stored preferences are invalid", () => {
    const storage = memoryStorage();
    storage.setItem(salesReportOutputPreferencesStorageKey("venta", "admin", terminalContext), "{invalid");

    expect(readSalesReportOutputPreferences("venta", "admin", terminalContext, storage)).toEqual(
      defaultSalesReportOutputPreferences
    );
  });
});
