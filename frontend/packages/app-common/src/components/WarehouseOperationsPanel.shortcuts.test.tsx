// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { WarehouseOperationsPanel } from "./WarehouseOperationsPanel";
import type { UserSession } from "../types";

vi.mock("../api/client", async () => ({
  ...await vi.importActual("../api/client"),
  apiRequest: vi.fn()
}));

const apiRequestMock = vi.mocked(apiRequest);
const session: UserSession = {
  username: "shortcut-tests",
  displayName: "Shortcut tests",
  accessToken: "token",
  permissions: ["ADMIN"]
};
const createObjectURLMock = vi.fn(() => "blob:warehouse-report");
const revokeObjectURLMock = vi.fn();
const originalCreateObjectURL = Object.getOwnPropertyDescriptor(URL, "createObjectURL");
const originalRevokeObjectURL = Object.getOwnPropertyDescriptor(URL, "revokeObjectURL");
const t = (key: string) => key;

function show(options: {
  app?: "venta" | "gestion" | "pda";
  mode?: "input" | "output";
  documentType?: "ENTRADA_ALMACEN" | "ALBARAN_ENTRADA" | "FACTURA_ENTRADA";
  token?: string;
  permissions?: { read?: boolean; create?: boolean; edit?: boolean; delete?: boolean; canConfirm?: boolean };
  userSession?: UserSession;
  onCreateDocument?: () => void;
} = {}) {
  return render(
    <WarehouseOperationsPanel
      app={options.app ?? "gestion"}
      mode={options.mode ?? "input"}
      documentType={"documentType" in options ? options.documentType : "ALBARAN_ENTRADA"}
      token={options.token ?? "token"}
      session={options.userSession ?? session}
      permissions={options.permissions}
      onCreateDocument={options.onCreateDocument}
      products={[]}
      warehouses={[]}
      customers={[]}
      suppliers={[]}
      t={t}
      locale="es"
    />
  );
}

function reportCalls() {
  return apiRequestMock.mock.calls.filter(([path]) => String(path).startsWith("/sales-reports/"));
}

beforeEach(() => {
  localStorage.clear();
  apiRequestMock.mockReset();
  apiRequestMock.mockImplementation(async (path) => {
    if (String(path).startsWith("/document-reports/date-options")) {
      return { currentDate: "2026-10-15", earliestDate: "2026-01-01" };
    }
    if (String(path).startsWith("/sales-reports/")) return new Blob(["report"]);
    return [];
  });
  createObjectURLMock.mockClear();
  revokeObjectURLMock.mockClear();
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createObjectURLMock });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectURLMock });
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
  if (originalCreateObjectURL) Object.defineProperty(URL, "createObjectURL", originalCreateObjectURL);
  else delete (URL as Partial<typeof URL>).createObjectURL;
  if (originalRevokeObjectURL) Object.defineProperty(URL, "revokeObjectURL", originalRevokeObjectURL);
  else delete (URL as Partial<typeof URL>).revokeObjectURL;
});

describe("WarehouseOperationsPanel warehouse shortcuts", () => {
  it.each([
    ["ALBARAN_ENTRADA", "input", "salesReport.inputDeliveryNotes"],
    ["FACTURA_ENTRADA", "input", "salesReport.inputInvoices"],
    ["ENTRADA_ALMACEN", "input", "salesReport.inputWarehouse"],
    [undefined, "output", "salesReport.warehouseOutputs"]
  ] as const)("exports %s %s to Excel with F6 and PDF with F7", async (documentType, mode, reportKey) => {
    show({ documentType, mode });

    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(reportCalls()).toHaveLength(1));
    expect(reportCalls()[0]).toEqual(expect.arrayContaining([
      "/sales-reports/export",
      expect.objectContaining({
        method: "POST",
        responseType: "blob",
        body: expect.objectContaining({ reportKey })
      })
    ]));
    await waitFor(() => expect(screen.getByRole("button", { name: "Excel" })).toBeEnabled());

    fireEvent.keyDown(window, { key: "F7" });
    await waitFor(() => expect(reportCalls()).toHaveLength(2));
    expect(reportCalls()[1]).toEqual(expect.arrayContaining([
      "/sales-reports/export-pdf",
      expect.objectContaining({
        method: "POST",
        responseType: "blob",
        body: expect.objectContaining({ reportKey })
      })
    ]));
    await waitFor(() => expect(screen.getByRole("button", { name: "PDF" })).toBeEnabled());
    expect(createObjectURLMock).toHaveBeenCalledTimes(2);
  });

  it.each([
    ["ALBARAN_ENTRADA", "input"], ["FACTURA_ENTRADA", "input"],
    ["ENTRADA_ALMACEN", "input"], [undefined, "output"]
  ] as const)("creates %s %s from the search field with F5", async (documentType, mode) => {
    const onCreateDocument = vi.fn();
    show({ documentType, mode, onCreateDocument });

    fireEvent.keyDown(screen.getByRole("searchbox"), { key: "F5" });

    expect(onCreateDocument).toHaveBeenCalledOnce();
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });

  it("does not repeat parent actions while the document dialog is open", async () => {
    const onCreateDocument = vi.fn();
    show({ onCreateDocument });
    fireEvent.click(screen.getByRole("button", { name: "Crear documento" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "F5" });
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.keyDown(window, { key: "F7" });

    expect(onCreateDocument).toHaveBeenCalledOnce();
    expect(reportCalls()).toHaveLength(0);
  });

  it("honors create and export permissions", () => {
    const onCreateDocument = vi.fn();
    const { unmount } = show({ permissions: { create: false }, onCreateDocument });
    fireEvent.keyDown(window, { key: "F5" });
    expect(onCreateDocument).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    unmount();

    show({ userSession: { ...session, permissions: [] } });
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.keyDown(window, { key: "F7" });
    expect(reportCalls()).toHaveLength(0);
    expect(screen.queryByRole("button", { name: "Excel" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "PDF" })).not.toBeInTheDocument();
  });

  it("does not act when the shortcut buttons are disabled for lack of a token", () => {
    const onCreateDocument = vi.fn();
    show({ token: "", onCreateDocument });

    fireEvent.keyDown(window, { key: "F5" });
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.keyDown(window, { key: "F7" });

    expect(onCreateDocument).not.toHaveBeenCalled();
    expect(reportCalls()).toHaveLength(0);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("does not start a second export while the first one is pending", async () => {
    let finishExport!: (blob: Blob) => void;
    apiRequestMock.mockImplementation((path) => {
      if (String(path).startsWith("/sales-reports/")) {
        return new Promise<Blob>((resolve) => { finishExport = resolve; });
      }
      if (String(path).startsWith("/document-reports/date-options")) {
        return Promise.resolve({ currentDate: "2026-10-15", earliestDate: "2026-01-01" });
      }
      return Promise.resolve([]);
    });
    show();

    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(reportCalls()).toHaveLength(1));
    expect(screen.getByRole("button", { name: "Excel" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "PDF" })).toBeDisabled();
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.keyDown(window, { key: "F7" });
    expect(reportCalls()).toHaveLength(1);

    finishExport(new Blob(["report"]));
    await waitFor(() => expect(screen.getByRole("button", { name: "PDF" })).toBeEnabled());
  });

  it("ignores repeat, modified, already-prevented, and externally modalized shortcuts", () => {
    const onCreateDocument = vi.fn();
    show({ onCreateDocument });

    fireEvent.keyDown(window, { key: "F5", repeat: true });
    fireEvent.keyDown(window, { key: "F5", ctrlKey: true });
    fireEvent.keyDown(window, { key: "F6", altKey: true });
    fireEvent.keyDown(window, { key: "F7", shiftKey: true });
    fireEvent.keyDown(window, { key: "F5", metaKey: true });
    const prevented = new KeyboardEvent("keydown", { key: "F5", bubbles: true, cancelable: true });
    prevented.preventDefault();
    fireEvent(window, prevented);

    expect(onCreateDocument).not.toHaveBeenCalled();
    expect(reportCalls()).toHaveLength(0);

    render(<div role="dialog" aria-modal="true">External modal</div>);
    fireEvent.keyDown(window, { key: "F5" });
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.keyDown(window, { key: "F7" });
    expect(onCreateDocument).not.toHaveBeenCalled();
    expect(reportCalls()).toHaveLength(0);
  });

  it.each([
    ["venta", "input", "ALBARAN_ENTRADA"],
    ["pda", "input", "FACTURA_ENTRADA"]
  ] as const)("does not register warehouse shortcuts for %s %s %s", (app, mode, documentType) => {
    const onCreateDocument = vi.fn();
    show({ app, mode, documentType, onCreateDocument });

    fireEvent.keyDown(window, { key: "F5" });
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.keyDown(window, { key: "F7" });

    expect(onCreateDocument).not.toHaveBeenCalled();
    expect(reportCalls()).toHaveLength(0);
  });
});
