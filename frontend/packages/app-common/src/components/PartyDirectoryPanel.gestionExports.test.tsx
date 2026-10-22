// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import { PartyDirectoryPanel, partyDirectoryPreferenceStorageKey } from "./PartyDirectoryPanel";
import { savePartyDirectoryExport } from "./partyDirectoryExport";
import { StockScreen } from "./StockScreen";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
vi.mock("./partyDirectoryExport", () => ({ savePartyDirectoryExport: vi.fn() }));

const writer: UserSession = { username: "exports", displayName: "Exports", accessToken: "test", permissions: ["ADMIN"] };
const reader: UserSession = { ...writer, username: "reader", permissions: [] };
const customer = { id: "c1", clientId: "C-001", fiscalName: "Ana", documentType: "NIF", documentNumber: "12345678Z", active: true, isMember: false, phone: "600111222", email: "ana@example.test" };
const supplier = { id: "s1", supplierId: "P-001", legalName: "Proveedor", tradeName: "Marca", documentType: "NIF", documentNumber: "B12345678", active: true };
const member = { id: "m1", customerId: "c1", memberId: "M-001", numMember: "M-001", fiscalName: "Ana", documentType: "NIF", documentNumber: "12345678Z", active: true, customerActive: true, balance: 0, points: 7 };

beforeEach(() => {
  localStorage.clear();
  vi.mocked(savePartyDirectoryExport).mockReset().mockResolvedValue(undefined);
  vi.mocked(apiRequest).mockReset().mockImplementation(async path => {
    if (path.startsWith("/customers/management/page?")) return { items: [customer], hasMore: false };
    if (path.startsWith("/suppliers/management/page?")) return { items: [supplier], hasMore: false };
    if (path === "/customers") return [customer];
    if (path === "/suppliers") return [supplier];
    if (path === "/members") return [member];
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Gestión directory export actions", () => {
  it("routes directory shortcuts through its StockScreen parent", async () => {
    render(<StockScreen app="gestion" locale="es" session={writer}
      terminalContext={{ storeName: "Test", terminalCode: "001" }}
      onBack={vi.fn()} onLocaleChange={vi.fn()}
      initialPartyDirectory="customers" allowSafeRetirement />);
    await screen.findByText("Ana");
    // Flush the directory's keyboard effect after the async row render.
    await act(async () => {});

    fireEvent.keyDown(document.body, { key: "F6" });
    await waitFor(() => expect(savePartyDirectoryExport).toHaveBeenCalledWith("xlsx", expect.anything(), expect.stringMatching(/\.xlsx$/)));
    await waitFor(() => expect(screen.getByRole("button", { name: /F7/ })).toBeEnabled());
    fireEvent.keyDown(document.body, { key: "F7" });
    await waitFor(() => expect(savePartyDirectoryExport).toHaveBeenCalledWith("pdf", expect.anything(), expect.stringMatching(/\.pdf$/)));
    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).not.toBeInTheDocument();
    expect(vi.mocked(apiRequest).mock.calls.some(([path]) => path.startsWith("/stock/exports"))).toBe(false);

    fireEvent.keyDown(document.body, { key: "F5" });
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("textbox", { name: "Nombre o razón social" })).toHaveValue("");
    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).not.toBeInTheDocument();
  });

  it.each(["customers", "members", "suppliers"] as const)("routes F5/F6/F7 for %s and leaves F7 out of the editor", async kind => {
    const respond = vi.mocked(apiRequest).getMockImplementation()!;
    let finishInitialLoad!: () => void;
    const initialLoad = new Promise<void>(resolve => { finishInitialLoad = resolve; });
    vi.mocked(apiRequest).mockImplementation(async (...args) => {
      await initialLoad;
      return respond(...args);
    });
    render(<PartyDirectoryPanel app="gestion" kind={kind} locale="es" session={writer} allowSafeRetirement />);
    await waitFor(() => expect(vi.mocked(apiRequest).mock.calls.some(([path]) =>
      kind === "members" ? path === "/members" : path.startsWith(`/${kind}/management/page?`)
    )).toBe(true));
    expect(screen.getByRole("button", { name: /F7/ })).toBeDisabled();
    fireEvent.keyDown(window, { key: "F7" });
    expect(savePartyDirectoryExport).not.toHaveBeenCalled();
    // Settle the initial response and its keyboard effect before sending a ready-state shortcut.
    await act(async () => { finishInitialLoad(); await initialLoad; });
    expect(screen.getByText(kind === "suppliers" ? "Proveedor" : "Ana")).toBeVisible();
    expect(screen.getByRole("button", { name: /F5/ })).toHaveAttribute("aria-keyshortcuts", "F5");
    expect(screen.getByRole("button", { name: /F6/ })).toHaveAttribute("aria-keyshortcuts", "F6");
    expect(screen.getByRole("button", { name: /F7/ })).toHaveAttribute("aria-keyshortcuts", "F7");
    fireEvent.keyDown(window, { key: "F7" });
    await waitFor(() => expect(savePartyDirectoryExport).toHaveBeenCalledWith("pdf", expect.anything(), expect.stringMatching(/\.pdf$/)));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: /F6/ })).toBeEnabled());
    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(savePartyDirectoryExport).toHaveBeenCalledWith("xlsx", expect.anything(), expect.stringMatching(/\.xlsx$/)));
    await waitFor(() => expect(screen.getByRole("button", { name: /F7/ })).toBeEnabled());
    fireEvent.keyDown(window, { key: "F5" });
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("lets read-only users export but not open a new record", async () => {
    render(<PartyDirectoryPanel app="gestion" kind="customers" locale="es" session={reader} />);
    await screen.findByText("Ana");
    expect(screen.queryByRole("button", { name: /F5/ })).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: "F5" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: /F6/ })).toBeEnabled());
    await act(async () => {});
    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(savePartyDirectoryExport).toHaveBeenCalledWith("xlsx", expect.anything(), expect.any(String)));
  });

  it("ignores new and export shortcuts while a record dialog is open", async () => {
    render(<PartyDirectoryPanel app="gestion" kind="customers" locale="es" session={writer} />);
    await screen.findByText("Ana");
    fireEvent.keyDown(window, { key: "F5" });
    const dialog = screen.getByRole("dialog");
    const name = within(dialog).getByRole("textbox", { name: "Nombre o razón social" });
    fireEvent.change(name, { target: { value: "Draft" } });
    fireEvent.keyDown(window, { key: "F5" });
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.keyDown(window, { key: "F7" });
    expect(name).toHaveValue("Draft");
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    expect(savePartyDirectoryExport).not.toHaveBeenCalled();
  });

  it.each(["customers", "suppliers"] as const)("exports every management %s page with identical active filters and sort, preserving the visible page", async kind => {
    localStorage.setItem(partyDirectoryPreferenceStorageKey("gestion", writer.username, kind), JSON.stringify({
      query: "  an  ", statusFilter: "active", sort: { column: "name", direction: "desc" }, fieldFilters: { code: "  001  " }
    }));
    const first = kind === "customers" ? customer : supplier;
    const second = kind === "customers" ? { ...customer, id: "c2", clientId: "C-002", fiscalName: "Berta" }
      : { ...supplier, id: "s2", supplierId: "P-002", legalName: "Segundo" };
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path.startsWith(`/${kind}/management/page?`)) {
        const cursor = new URL(path, "http://test").searchParams.get("cursor");
        return cursor ? { items: [second], hasMore: false } : { items: [first], hasMore: true, nextCursor: "next" };
      }
      return [];
    });
    render(<PartyDirectoryPanel app="gestion" kind={kind} locale="es" session={writer} allowSafeRetirement />);
    const table = screen.getByRole("table");
    await within(table).findByText(kind === "customers" ? "Ana" : "Proveedor");
    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(savePartyDirectoryExport).toHaveBeenCalledOnce());
    const paths = vi.mocked(apiRequest).mock.calls.map(([path]) => path).filter(path => path.startsWith(`/${kind}/management/page?`));
    expect(paths).toHaveLength(3); // one visible-page request, then both export pages
    for (const path of paths) {
      const params = new URL(path, "http://test").searchParams;
      expect(Object.fromEntries(["search", "active", "sort", "direction", "field.code"].map(key => [key, params.get(key)]))).toEqual({
        search: "an", active: "true", sort: "name", direction: "desc", "field.code": "001"
      });
    }
    expect(new URL(paths[2], "http://test").searchParams.get("cursor")).toBe("next");
    const report = vi.mocked(savePartyDirectoryExport).mock.calls[0][1];
    expect(report.rows).toHaveLength(2);
    expect(report.rows[0][report.columns.findIndex(column => column.key === "name")]).toContain(kind === "customers" ? "Ana" : "Proveedor");
    expect(report.rows[1][report.columns.findIndex(column => column.key === "name")]).toContain(kind === "customers" ? "Berta" : "Segundo");
    expect(report.rows.flat()).toEqual(expect.arrayContaining([expect.any(String)]));
    expect(report.filters).toEqual(expect.arrayContaining([expect.objectContaining({ value: "an" }), expect.objectContaining({ value: "001" })]));
    expect(within(table).getByText(kind === "customers" ? "Ana" : "Proveedor")).toBeVisible();
    expect(within(table).queryByText(kind === "customers" ? "Berta" : "Segundo")).not.toBeInTheDocument();
  });

  it("reports a later page failure without saving a partial export", async () => {
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path.startsWith("/customers/management/page?")) {
        if (new URL(path, "http://test").searchParams.has("cursor")) throw new Error("offline");
        return { items: [customer], hasMore: true, nextCursor: "next" };
      }
      return [];
    });
    render(<PartyDirectoryPanel app="gestion" kind="customers" locale="es" session={writer} allowSafeRetirement />);
    await screen.findByText("Ana");
    fireEvent.keyDown(window, { key: "F7" });
    expect(await screen.findByRole("status")).toHaveTextContent(createTranslator("es")("salesReport.exportFailed"));
    expect(savePartyDirectoryExport).not.toHaveBeenCalled();
  });

  it("exports the chosen column order and excludes hidden columns", async () => {
    render(<PartyDirectoryPanel app="gestion" kind="customers" locale="es" session={writer} />);
    await screen.findByText("Ana");
    const codeHeader = screen.getByRole("columnheader", { name: "Código" });
    fireEvent.click(within(codeHeader).getByRole("button", { name: "Opciones de columna" }));
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "Email" }));
    fireEvent.keyDown(window, { key: "Escape" });
    const nameHeader = screen.getByRole("columnheader", { name: "Nombre / Razón social" });
    fireEvent.keyDown(nameHeader, { key: "ArrowLeft", ctrlKey: true });
    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(savePartyDirectoryExport).toHaveBeenCalledOnce());
    const report = vi.mocked(savePartyDirectoryExport).mock.calls[0][1];
    expect(report.columns.slice(0, 2).map(column => column.key)).toEqual(["name", "code"]);
    expect(report.columns.some(column => column.key === "email")).toBe(false);
    expect(report.rows[0].slice(0, 2)).toEqual(["Ana", "C-001"]);
    expect(report.rows[0]).not.toContain(customer.email);
  });

  it("exports the locally filtered and sorted complete member list", async () => {
    const otherCustomer = { ...customer, id: "c2", clientId: "C-002", fiscalName: "Zoe", email: "zoe@example.test" };
    const otherMember = { ...member, id: "m2", customerId: "c2", memberId: "M-002", numMember: "M-002", fiscalName: "Zoe" };
    vi.mocked(apiRequest).mockImplementation(async path => path === "/members" ? [otherMember, member] : path === "/customers" ? [otherCustomer, customer] : []);
    render(<PartyDirectoryPanel app="gestion" kind="members" locale="es" session={writer} />);
    await screen.findByText("Zoe");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Ana" } });
    await waitFor(() => expect(screen.queryByText("Zoe")).not.toBeInTheDocument());
    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(savePartyDirectoryExport).toHaveBeenCalledOnce());
    const report = vi.mocked(savePartyDirectoryExport).mock.calls[0][1];
    expect(report.rows).toHaveLength(1);
    expect(report.rows[0][report.columns.findIndex(column => column.key === "name")]).toBe("Ana");
    expect(vi.mocked(apiRequest).mock.calls.some(([path]) => path.includes("/management/page"))).toBe(false);
  });
});
