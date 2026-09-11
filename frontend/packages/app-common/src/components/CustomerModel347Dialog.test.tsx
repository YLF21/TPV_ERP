// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import { CustomerModel347Dialog } from "./CustomerModel347Dialog";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const session: UserSession = { username: "test", displayName: "Test", accessToken: "test-token", permissions: ["INVOICES_READ"] };
const customer = { id: "customer-1", clientId: "C-001", fiscalName: "Cliente de prueba" };
const pdf = { arrayBuffer: async () => new Uint8Array([37, 80, 68, 70]).buffer };
function mount(props: Partial<Parameters<typeof CustomerModel347Dialog>[0]> = {}) {
  return render(<CustomerModel347Dialog customer={customer} session={session} locale="es" onClose={vi.fn()} {...props} />);
}
beforeEach(() => {
  vi.mocked(apiRequest).mockReset().mockResolvedValue(pdf);
  window.tpvDesktop = { reports: { saveFile: vi.fn().mockResolvedValue({ ok: true }) } } as unknown as typeof window.tpvDesktop;
});
afterEach(() => { cleanup(); delete window.tpvDesktop; vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe("CustomerModel347Dialog", () => {
  it.each([
    ["SAAS_CUSTOMER_BINDING_REQUIRED", "vínculo central"],
    ["SAAS_CUSTOMER_DOCUMENTS_CURRENCY_UNSUPPORTED", "solo admite EUR"],
  ])("explains %s without downloading a misleading PDF", async (code, message) => {
    vi.mocked(apiRequest).mockRejectedValue(new ApiError("internal", 422, { code }));
    mount(); fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(window.tpvDesktop!.reports!.saveFile).not.toHaveBeenCalled();
  });

  it("focuses the current year, submits on Enter and saves the Jasper PDF bytes", async () => {
    const user = userEvent.setup();
    mount();
    expect(screen.getByRole("spinbutton", { name: "Año" })).toHaveFocus();
    expect(screen.getByRole("spinbutton")).toHaveValue(new Date().getFullYear());
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "2024" } });
    await user.keyboard("{Enter}");
    await waitFor(() => expect(window.tpvDesktop!.reports!.saveFile).toHaveBeenCalledExactlyOnceWith({
      defaultFileName: "C-001-Cliente de prueba-Modelo 347-2024.pdf",
      filters: [{ name: "PDF", extensions: ["pdf"] }], bytes: new Uint8Array([37, 80, 68, 70]),
    }));
    expect(apiRequest).toHaveBeenCalledExactlyOnceWith("/customer-document-reports/saas/customer-1/annual.pdf?year=2024&locale=es", {
      token: "test-token", responseType: "blob", signal: expect.any(AbortSignal),
    });
    expect(await screen.findByRole("status")).toHaveTextContent("PDF generado.");
  });

  it("traps Tab inside the small dialog and restores its opener on close", async () => {
    const opener = document.createElement("button"); document.body.append(opener); opener.focus();
    const user = userEvent.setup();
    const view = mount();
    await user.tab({ shift: true });
    expect(screen.getByRole("button", { name: "Generar PDF" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("spinbutton")).toHaveFocus();
    view.unmount(); expect(opener).toHaveFocus(); opener.remove();
  });

  it.each(["", "0", "-1", "2024.5", "9999", "10000", "2e3"])("rejects invalid year %s before making a request", async (year) => {
    mount();
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: year } });
    fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Introduce un año entero entre 1 y 9998.");
    expect(screen.getByRole("spinbutton")).toHaveAttribute("aria-invalid", "true");
    expect(apiRequest).not.toHaveBeenCalled();
  });

  it.each(["1", "9998"])("accepts boundary year %s", async (year) => {
    mount(); fireEvent.change(screen.getByRole("spinbutton"), { target: { value: year } });
    fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    await screen.findByRole("status");
    expect(apiRequest).toHaveBeenCalledWith(`/customer-document-reports/saas/customer-1/annual.pdf?year=${year}&locale=es`, expect.anything());
  });

  it("prevents duplicate generation and aborts on Escape without saving late responses", async () => {
    let finish!: (value: unknown) => void;
    vi.mocked(apiRequest).mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const close = vi.fn(); mount({ onClose: close });
    const generate = screen.getByRole("button", { name: "Generar PDF" });
    fireEvent.click(generate); fireEvent.click(generate);
    fireEvent.submit(generate.closest("form")!);
    expect(apiRequest).toHaveBeenCalledOnce();
    expect(screen.getByRole("spinbutton")).toHaveAttribute("readonly");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(close).toHaveBeenCalledOnce();
    expect(vi.mocked(apiRequest).mock.calls[0][1]!.signal?.aborted).toBe(true);
    await act(async () => finish(pdf));
    expect(window.tpvDesktop!.reports!.saveFile).not.toHaveBeenCalled();
  });

  it("aborts on unmount while PDF bytes are being read", async () => {
    let finish!: (value: ArrayBuffer) => void;
    const arrayBuffer = vi.fn(() => new Promise<ArrayBuffer>((resolve) => { finish = resolve; }));
    vi.mocked(apiRequest).mockResolvedValue({ arrayBuffer });
    const view = mount(); fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    await waitFor(() => expect(arrayBuffer).toHaveBeenCalledOnce());
    view.unmount();
    await act(async () => finish(new ArrayBuffer(0)));
    expect(window.tpvDesktop!.reports!.saveFile).not.toHaveBeenCalled();
  });

  it.each([
    ["es", "Modelo 347", "Año", "Generar PDF", "todas las tiendas", "No es una declaración oficial"],
    ["en", "Form 347", "Year", "Generate PDF", "all company stores", "not an official tax return"],
    ["zh", "347 表", "年度", "生成 PDF", "所有门店", "并非正式税务申报表"],
  ] as const)("renders and requests the report in %s", async (locale, title, yearLabel, generate, scope, notice) => {
    mount({ locale });
    expect(screen.getByRole("dialog", { name: title })).toBeInTheDocument();
    expect(screen.getByRole("spinbutton", { name: yearLabel })).toBeInTheDocument();
    expect(screen.getByText(new RegExp(scope))).toBeInTheDocument();
    expect(screen.getByText(new RegExp(notice))).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: generate }));
    await waitFor(() => expect(window.tpvDesktop!.reports!.saveFile).toHaveBeenCalledOnce());
    expect(apiRequest).toHaveBeenCalledWith(expect.stringContaining(`&locale=${locale}`), expect.anything());
    expect(await screen.findByRole("status")).toHaveTextContent(createTranslator(locale)("customerModel347.saved"));
  });

  it("downloads the same safe browser filename while preserving accents and Chinese", async () => {
    delete window.tpvDesktop;
    const createObjectURL = vi.fn().mockReturnValue("blob:model347"); const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", class extends URL { static createObjectURL = createObjectURL; static revokeObjectURL = revokeObjectURL; });
    let downloadedName = "";
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) { downloadedName = this.download; });
    const blob = new Blob(["%PDF"], { type: "application/pdf" }); vi.mocked(apiRequest).mockResolvedValue(blob);
    mount({ customer: { ...customer, clientId: " AC/001 ", fiscalName: 'Niño "海洋" ' + "X".repeat(150) } });
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "2025" } });
    fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    await waitFor(() => expect(downloadedName).toBe(`AC-001-Niño -海洋- ${"X".repeat(70)}-Modelo 347-2025.pdf`));
    expect(downloadedName.length).toBeLessThan(255);
    expect(createObjectURL).toHaveBeenCalledWith(blob); expect(revokeObjectURL).toHaveBeenCalledWith("blob:model347");
  });

  it("keeps the dialog ready for retry after canceling the desktop save dialog", async () => {
    vi.mocked(window.tpvDesktop!.reports!.saveFile).mockResolvedValueOnce({ ok: true, canceled: true });
    mount(); fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Generar PDF" })).toBeEnabled());
    expect(screen.queryByRole("status")).not.toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    await waitFor(() => expect(window.tpvDesktop!.reports!.saveFile).toHaveBeenCalledTimes(2));
  });

  it.each([403, 500])("shows a translated error for HTTP %s without internal details, and retries", async (status) => {
    vi.mocked(apiRequest).mockRejectedValueOnce(new ApiError("internal SQL", status));
    mount(); fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(status === 403
      ? "No tienes permiso para consultar estos documentos." : "No se pudo generar o guardar el PDF.");
    expect(screen.getByRole("alert")).not.toHaveTextContent("SQL");
    fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    await waitFor(() => expect(window.tpvDesktop!.reports!.saveFile).toHaveBeenCalledOnce());
  });

  it("shows save failures as a recoverable error", async () => {
    vi.mocked(window.tpvDesktop!.reports!.saveFile).mockResolvedValue({ ok: false, code: "disk", message: "internal path" });
    mount(); fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo generar o guardar el PDF.");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Generar PDF" })).toBeEnabled();
  });
});
