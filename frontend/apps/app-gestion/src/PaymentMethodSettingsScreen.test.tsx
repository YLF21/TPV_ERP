// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type apiRequest, type InvoiceBankAccountView, type PaymentMethodView, type UserSession } from "@tpverp/app-common";
import { PaymentMethodSettingsScreen } from "./PaymentMethodSettingsScreen";

const session: UserSession = { username: "admin", displayName: "ADMIN", permissions: ["ADMIN"], accessToken: "token" };
const method = (name: string): PaymentMethodView => ({ id: name.toLowerCase(), companyId: "company-1", name, protectedMethod: true, active: true, requiresReference: false, opensCashDrawer: true });
const bank = (overrides: Partial<InvoiceBankAccountView> = {}): InvoiceBankAccountView => ({ id: "bank-1", bankName: "Banco existente", iban: "ES9121000418450200051332", displayIban: "ES91 2100 0418 4502 0005 1332", active: true, order: 0, ...overrides });
const t = createTranslator("es");
type Options = { method?: string; body?: Record<string, unknown> };
type ServerOptions = { beforeWrite?: (path: string, options: Options) => void | Promise<void>; afterWrite?: (path: string, result: unknown) => void; failGet?: string };
function createServer(options: ServerOptions = {}) {
  const methods = ["EFECTIVO", "TARJETA", "TRANSFERENCIA", "VALE", "DESCUENTO"].map(method);
  const accounts = [bank()];
  let policy = "REFUND_ALLOWED", sequence = 1;
  const request = vi.fn(async (path: string, init: Options = {}) => {
    if (!init.method || init.method === "GET") {
      if (path === options.failGet) throw new Error("Carga incompleta");
      if (path === "/payment-methods") return methods.map(value => ({ ...value }));
      if (path === "/return-policy") return { policy };
      if (path === "/invoice-print-configuration") return { observations: "", bankAccounts: accounts.map(value => ({ ...value })) };
      throw new Error(`Unexpected GET ${path}`);
    }
    await options.beforeWrite?.(path, init);
    let result: unknown;
    if (path === "/return-policy") { policy = String(init.body?.policy); result = { policy }; }
    else if (path === "/invoice-print-configuration/bank-accounts" && init.method === "POST") {
      const iban = String(init.body?.iban).replace(/\s/g, "").toUpperCase();
      if (accounts.some(value => value.iban === iban)) throw new Error("invoice_bank_iban_duplicate");
      const saved = bank({ id: `created-${sequence++}`, bankName: String(init.body?.bankName).trim(), iban, displayIban: iban, order: accounts.length });
      accounts.push(saved); result = { ...saved };
    } else if (path.startsWith("/invoice-print-configuration/bank-accounts/") && path.endsWith("/active")) {
      const saved = accounts.find(value => value.id === path.split("/").at(-2))!;
      saved.active = Boolean(init.body?.active); result = { ...saved };
    } else if (path.startsWith("/payment-methods/")) {
      const saved = methods.find(value => value.id === path.split("/")[2])!;
      if (path.endsWith("/active")) saved.active = Boolean(init.body?.active);
      else if (path.endsWith("/configuration")) { saved.requiresReference = Boolean(init.body?.requiresReference); saved.opensCashDrawer = Boolean(init.body?.opensCashDrawer); }
      else throw new Error(`Unexpected write ${path}`);
      result = { ...saved };
    } else throw new Error(`Unexpected write ${path}`);
    options.afterWrite?.(path, result); return result;
  });
  return { request, methods, accounts, writes: () => request.mock.calls.filter(([, init]) => init?.method && init.method !== "GET") };
}
async function open(server = createServer(), user = session) {
  const view = render(<PaymentMethodSettingsScreen session={user} t={t} request={server.request as unknown as typeof apiRequest} />);
  await screen.findByRole("switch", { name: "Activo en cobro · Efectivo" });
  return { ...server, ...view };
}
const saveButton = () => screen.getByRole("button", { name: /^Guardar$/ }) as HTMLButtonElement;
const toggle = (name: string) => screen.getByRole("switch", { name }) as HTMLInputElement;
function fillBank(name = "Banco nuevo", iban = "ES7921000813610123456789") {
  fireEvent.change(screen.getByRole("textbox", { name: "Entidad bancaria" }), { target: { value: name } });
  fireEvent.change(screen.getByRole("textbox", { name: "IBAN" }), { target: { value: iban } });
}
async function saved() { await screen.findByText("Configuración guardada"); }
afterEach(cleanup);

describe("PaymentMethodSettingsScreen general save", () => {
  it("starts clean and preserves protected controls", async () => {
    const server = await open();
    expect(screen.queryByText("DESCUENTO")).toBeNull();
    expect(screen.getByText("No aplicable")).toBeTruthy();
    expect(toggle("Solicitar Nº documento · Vale").disabled).toBe(true);
    expect(saveButton().disabled).toBe(true);
    expect(server.writes()).toHaveLength(0);
    expect(screen.queryByText(/Guardando/)).toBeNull();
  });
  it("keeps all sections local and saves them together without losing another pending field", async () => {
    const server = await open();
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "EXCHANGE_OR_VOUCHER_ONLY" } });
    fireEvent.click(toggle("Activo en cobro · Tarjeta"));
    fireEvent.click(toggle("Solicitar Nº documento · Tarjeta"));
    fireEvent.click(toggle("Mostrar en factura · Banco existente"));
    fillBank(); fireEvent.click(screen.getByRole("button", { name: "Añadir cuenta" }));
    fireEvent.click(toggle("Mostrar en factura · Banco nuevo"));
    expect(server.writes()).toHaveLength(0);
    expect(toggle("Activo en cobro · Tarjeta").checked).toBe(false);
    expect(toggle("Solicitar Nº documento · Tarjeta").checked).toBe(true);
    fireEvent.change(screen.getByRole("searchbox", { name: "Búsqueda" }), { target: { value: "Efectivo" } });
    fireEvent.click(saveButton()); await saved();
    expect(server.methods.find(value => value.name === "TARJETA")).toMatchObject({ active: false, requiresReference: true, opensCashDrawer: true });
    expect(server.request).toHaveBeenCalledWith("/payment-methods/tarjeta/configuration", expect.objectContaining({ body: { requiresReference: true, opensCashDrawer: true } }));
    expect(server.request).toHaveBeenCalledWith("/return-policy", expect.objectContaining({ method: "PUT", body: { policy: "EXCHANGE_OR_VOUCHER_ONLY" } }));
    expect(server.accounts).toHaveLength(2); expect(server.accounts.every(value => !value.active)).toBe(true);
    expect(saveButton().disabled).toBe(true); expect(screen.queryByText(/Guardando/)).toBeNull();
  });
  it("includes a completed bank form without a separate Add click", async () => {
    const server = await open(); fillBank();
    expect(server.writes()).toHaveLength(0);
    fireEvent.click(saveButton()); await saved();
    expect(server.accounts).toHaveLength(2); expect(server.writes()).toHaveLength(1);
    expect((screen.getByRole("textbox", { name: "Entidad bancaria" }) as HTMLInputElement).value).toBe("");
  });
  it.each(["Entidad bancaria", "IBAN"])("validates incomplete %s before any write", async field => {
    const server = await open(); fireEvent.click(toggle("Activo en cobro · Transferencia"));
    fireEvent.change(screen.getByRole("textbox", { name: field }), { target: { value: field === "IBAN" ? "ES00" : "Banco incompleto" } });
    fireEvent.click(saveButton()); await screen.findByText(t("gestion.paymentMethods.bankFieldsRequired"));
    expect(server.writes()).toHaveLength(0); expect(toggle("Activo en cobro · Transferencia").checked).toBe(false);
  });
  it("can remove a local account and undo changes without writing", async () => {
    const server = await open(); fillBank(); fireEvent.click(screen.getByRole("button", { name: "Añadir cuenta" }));
    fireEvent.click(screen.getByRole("button", { name: "Eliminar · Banco nuevo" }));
    fireEvent.click(toggle("Activo en cobro · Transferencia")); fireEvent.click(toggle("Activo en cobro · Transferencia"));
    expect(saveButton().disabled).toBe(true); expect(server.writes()).toHaveLength(0);
  });
  it("blocks duplicate submission and never shows Guardando", async () => {
    let release!: () => void; const pending = new Promise<void>(resolve => { release = resolve; });
    const server = await open(createServer({ beforeWrite: () => pending }));
    fireEvent.click(toggle("Activo en cobro · Transferencia"));
    const button = saveButton(); fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(server.writes()).toHaveLength(1));
    expect(button.disabled).toBe(true); expect(toggle("Activo en cobro · Transferencia").disabled).toBe(true);
    expect(toggle("Activo en cobro · Transferencia").checked).toBe(false);
    expect(screen.queryByText(/Guardando/)).toBeNull(); expect(screen.queryByText("Configuración guardada")).toBeNull();
    await act(async () => { release(); }); await saved();
  });
  it("retains a pending reference after active succeeds and retries only remaining writes", async () => {
    let fail = true;
    const server = await open(createServer({ beforeWrite: path => { if (path === "/payment-methods/tarjeta/configuration" && fail) { fail = false; throw new Error("Fallo referencia"); } } }));
    fireEvent.click(toggle("Activo en cobro · Tarjeta")); fireEvent.click(toggle("Solicitar Nº documento · Tarjeta"));
    fireEvent.click(saveButton()); await screen.findByRole("alert");
    expect(screen.queryByText("Configuración guardada")).toBeNull();
    expect(toggle("Activo en cobro · Tarjeta").checked).toBe(false); expect(toggle("Solicitar Nº documento · Tarjeta").checked).toBe(true);
    expect(saveButton().disabled).toBe(false); fireEvent.click(saveButton()); await saved();
    expect(server.writes().filter(([path]) => path === "/payment-methods/tarjeta/active")).toHaveLength(1);
    expect(server.writes().filter(([path]) => path === "/payment-methods/tarjeta/configuration")).toHaveLength(2);
  });
  it("does not recreate a confirmed bank account when its activation fails", async () => {
    let fail = true;
    const server = await open(createServer({ beforeWrite: path => { if (path.endsWith("/created-1/active") && fail) { fail = false; throw new Error("Fallo activación"); } } }));
    fillBank(); fireEvent.click(screen.getByRole("button", { name: "Añadir cuenta" })); fireEvent.click(toggle("Mostrar en factura · Banco nuevo"));
    fireEvent.click(saveButton()); await screen.findByRole("alert");
    expect(toggle("Mostrar en factura · Banco nuevo").checked).toBe(false);
    fireEvent.click(saveButton()); await saved();
    expect(server.writes().filter(([path]) => path === "/invoice-print-configuration/bank-accounts")).toHaveLength(1);
    expect(server.accounts).toHaveLength(2); expect(server.accounts[1].active).toBe(false);
  });
  it("reconciles a lost POST response without creating duplicates", async () => {
    let fail = true;
    const server = await open(createServer({ afterWrite: path => { if (path === "/invoice-print-configuration/bank-accounts" && fail) { fail = false; throw new Error("Respuesta perdida"); } } }));
    fillBank(); fireEvent.click(saveButton());
    await waitFor(() => expect(saveButton().closest("section")?.getAttribute("aria-busy")).toBe("false"));
    if (!saveButton().disabled) fireEvent.click(saveButton()); await saved();
    expect(server.accounts).toHaveLength(2);
    expect(server.writes().filter(([path]) => path === "/invoice-print-configuration/bank-accounts")).toHaveLength(1);
  });
  it.each(["/payment-methods", "/return-policy", "/invoice-print-configuration"])("blocks saving after an incomplete load of %s", async path => {
    const server = createServer({ failGet: path });
    render(<PaymentMethodSettingsScreen session={session} t={t} request={server.request as unknown as typeof apiRequest} />);
    await screen.findByRole("alert"); expect(saveButton().disabled).toBe(true); expect(server.writes()).toHaveLength(0);
  });
  it("keeps drafts across language changes without reloading", async () => {
    const server = await open(); fireEvent.click(toggle("Activo en cobro · Transferencia"));
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "EXCHANGE_OR_VOUCHER_ONLY" } }); fillBank();
    const calls = server.request.mock.calls.length;
    server.rerender(<PaymentMethodSettingsScreen session={session} t={createTranslator("en")} request={server.request as unknown as typeof apiRequest} />);
    expect((screen.getByRole("switch", { name: "Active at checkout · Transfer" }) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByRole("combobox") as HTMLSelectElement).value).toBe("EXCHANGE_OR_VOUCHER_ONLY");
    expect((screen.getByRole("textbox", { name: "Bank" }) as HTMLInputElement).value).toBe("Banco nuevo");
    expect(server.request).toHaveBeenCalledTimes(calls); expect(server.writes()).toHaveLength(0);
  });
  it("does not permit a user without ADMIN to write", async () => {
    const server = await open(createServer(), { ...session, permissions: ["VENTA"] });
    expect(saveButton().disabled).toBe(true); expect(screen.getAllByRole("switch").every(value => (value as HTMLInputElement).disabled)).toBe(true);
    fireEvent.click(saveButton()); expect(server.writes()).toHaveLength(0);
  });

  it("stops remaining writes when the screen is closed during a save", async () => {
    let release!: () => void; const pending = new Promise<void>(resolve => { release = resolve; });
    const server = await open(createServer({ beforeWrite: path => path.endsWith("/active") ? pending : undefined }));
    fireEvent.click(toggle("Activo en cobro · Tarjeta")); fireEvent.click(toggle("Solicitar Nº documento · Tarjeta"));
    fireEvent.click(saveButton()); await waitFor(() => expect(server.writes()).toHaveLength(1));
    server.unmount(); await act(async () => { release(); });
    expect(server.writes()).toHaveLength(1);
  });

  it("invalidates the old request and never lets its finally unlock a newer save", async () => {
    let releaseOld!: () => void, releaseNew!: () => void;
    const oldPending = new Promise<void>(resolve => { releaseOld = resolve; });
    const newPending = new Promise<void>(resolve => { releaseNew = resolve; });
    const old = await open(createServer({ beforeWrite: () => oldPending }));
    fireEvent.click(toggle("Activo en cobro · Tarjeta")); fireEvent.click(toggle("Solicitar Nº documento · Tarjeta"));
    fireEvent.click(saveButton()); await waitFor(() => expect(old.writes()).toHaveLength(1));
    const next = createServer({ beforeWrite: () => newPending });
    old.rerender(<PaymentMethodSettingsScreen session={session} t={t} request={next.request as unknown as typeof apiRequest} />);
    await waitFor(() => expect(toggle("Activo en cobro · Tarjeta").checked).toBe(true));
    fireEvent.click(toggle("Activo en cobro · Transferencia")); fireEvent.click(saveButton());
    await waitFor(() => expect(next.writes()).toHaveLength(1));
    await act(async () => { releaseOld(); });
    expect(old.writes()).toHaveLength(1); expect(saveButton().disabled).toBe(true);
    expect(toggle("Activo en cobro · Tarjeta").checked).toBe(true);
    expect(toggle("Activo en cobro · Transferencia").checked).toBe(false);
    await act(async () => { releaseNew(); }); await saved();
    expect(saveButton().disabled).toBe(true);
  });
});
