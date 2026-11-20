/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest, createTranslator, type UserSession } from "@tpverp/app-common";
import { MailCommunicationsScreen } from "./MailCommunicationsScreen";
import { isMailHttpsUrl, mailErrorMessage } from "./mail/MailShared";
import { ApiError } from "../../../packages/app-common/src/api/client";

const session: UserSession = { username: "admin", displayName: "ADMIN", accessToken: "token", permissions: ["ADMIN"] };
const smtp = { configured: true, enabled: true, host: "smtp.example.test", port: 587, username: "shop@example.test", fromEmail: "shop@example.test", fromName: "Shop", startTls: true, sslEnabled: false, passwordConfigured: true, credentialsReady: true, deliveryEnabled: true };
const template = { id: "template-1", name: "Oferta", subject: "Ofertas", heading: "Hola", body: "Hola {name}", buttonText: "", buttonUrl: "", accentColor: "#173e67", version: 0 };
const campaign = { id: "campaign-1", name: "Novedades", templateId: "template-1", status: "DRAFT", scheduledAt: null, createdAt: "2026-11-20T10:00:00Z", version: 0, recipientCount: 0 };
const audience = { eligible: 2, total: 8, excludedNoConsent: 1, excludedNoEmail: 1, excludedChannel: 1, excludedSuppressed: 1, excludedInactive: 1, excludedDuplicate: 1, html: "<h1>Oferta</h1>", text: "Oferta", previewToken: "a".repeat(64) };
const member = { balanceAccrualEnabled: true, balanceAccrualBaseAmount: 1, balanceAccrualPercent: 5, balanceExpirationPolicy: "NO_CADUCA", pointsAccrualEnabled: true, pointsAccrualBaseAmount: 1, pointsPerEuro: 1, categoryAutoEnabled: true, memberWelcomeEnabled: false, memberCardCodeFormat: "QR", welcomeSubjectTemplate: "Bienvenido", welcomeBodyTemplate: "Hola {memberId}" };
type Options = { method?: string; body?: unknown };
function requests(override?: (path: string, options: Options) => unknown) {
  return vi.fn(async (path: string, options: Options = {}) => {
    const special = override?.(path, options);
    if (special !== undefined) return special;
    if (path === "/member-smtp-settings") return options.method === "PUT" ? { ...smtp, ...(options.body as object) } : smtp;
    if (path === "/mail-oauth/status") return { providers: [{ provider: "GOOGLE", available: false }, { provider: "MICROSOFT", available: false }], connection: null };
    if (path === "/communications/templates") return [template];
    if (path === "/communications/campaigns") return [campaign];
    if (path === "/communications/status") return { campaignsEnabled: true, publicUnsubscribeConfigured: true, workerEnabled: true };
    if (path === "/communications/campaigns/campaign-1/preview") return audience;
    if (path === "/communications/history" || path === "/member-card-deliveries" || path === "/customers") return [];
    if (path === "/member-settings") return member;
    if (path === "/communications/welcome-template") return { templateId: null };
    return null;
  });
}
function mount(request: ReturnType<typeof requests>, user = session) {
  return render(<MailCommunicationsScreen session={user} t={createTranslator("es")} request={request as typeof apiRequest} />);
}
const tab = (name: string) => fireEvent.click(screen.getByRole("tab", { name }));
afterEach(cleanup);

describe("Mail communications", () => {
  it("keeps instructions open and updates their destination across provider and email changes", async () => {
    const request = requests(); mount(request);
    const selector = await screen.findByLabelText("Servidor de correo");
    fireEvent.click(screen.getByText("Cómo obtener la contraseña · paso a paso"));
    const steps = screen.getByText("Cómo obtener la contraseña · paso a paso").closest("details")!;
    for (const [provider, url] of [
      ["gmail", "https://myaccount.google.com/apppasswords"],
      ["yahoo", "https://login.yahoo.com/account/security"],
      ["qq", "https://mail.qq.com/"],
      ["icloud", "https://account.apple.com/"],
      ["netease163", "https://mail.163.com/"],
      ["exmail", "https://exmail.qq.com/login"],
      ["custom", ""],
      ["gmail", "https://myaccount.google.com/apppasswords"],
    ]) {
      fireEvent.change(selector, { target: { value: provider } });
      fireEvent.change(screen.getByLabelText("Correo de la tienda"), { target: { value: `${provider}@example.test` } });
      expect(steps).toHaveAttribute("open");
      if (url) expect(within(steps).getByRole("link")).toHaveAttribute("href", url);
      else expect(within(steps).queryByRole("link")).not.toBeInTheDocument();
    }
    tab("Plantillas"); tab("Cuenta");
    expect(steps).toHaveAttribute("open");
    expect(request.mock.calls.some(([path, options]) => options?.method === "PUT" || path.endsWith("/test"))).toBe(false);
  });

  it.each([
    ["gmail", "Google", "https://myaccount.google.com/apppasswords", "verificación en dos pasos"],
    ["yahoo", "Yahoo", "https://login.yahoo.com/account/security", "Conexiones externas"],
    ["qq", "QQ", "https://mail.qq.com/", "Cuenta o Cuenta y seguridad"],
    ["icloud", "Apple", "https://account.apple.com/", "Contraseñas específicas de apps"],
    ["netease163", "163", "https://mail.163.com/", "POP3/SMTP/IMAP"],
    ["exmail", "Exmail", "https://exmail.qq.com/login", "Vinculación del correo"],
  ])("shows password help for %s without saving or sending", async (provider, name, url, step) => {
    const request = requests(); mount(request);
    await screen.findByLabelText("Servidor de correo");
    fireEvent.change(screen.getByLabelText("Servidor de correo"), { target: { value: provider } });
    fireEvent.change(screen.getByLabelText("Contraseña de aplicación"), { target: { value: "unsaved-secret" } });
    const link = screen.getByRole("link", { name: `Abrir ${name}` });
    expect(link).toHaveAttribute("href", url);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    fireEvent.click(link);
    const steps = screen.getByText("Cómo obtener la contraseña · paso a paso").closest("details");
    expect(steps).toHaveAttribute("open");
    fireEvent.click(link);
    fireEvent.change(screen.getByLabelText("Nombre del remitente"), { target: { value: "Mi tienda" } });
    expect(steps).toHaveAttribute("open");
    fireEvent.click(screen.getByText("Cómo obtener la contraseña · paso a paso"));
    expect(steps).not.toHaveAttribute("open");
    fireEvent.click(link);
    expect(steps).toHaveAttribute("open");
    expect(steps).toHaveTextContent(step);
    expect(within(steps!).getByRole("link", { name: url })).toHaveAttribute("href", url);
    expect(within(steps!).getAllByRole("listitem")).toHaveLength(4);
    expect(screen.getByLabelText("Contraseña de aplicación")).toHaveValue("unsaved-secret");
    expect(request.mock.calls.some(([path, options]) => options?.method === "PUT" || path.endsWith("/test"))).toBe(false);
    fireEvent.change(screen.getByLabelText("Servidor de correo"), { target: { value: "custom" } });
    expect(screen.queryByRole("link", { name: /^Abrir/ })).not.toBeInTheDocument();
    expect(steps).toHaveTextContent("web oficial de tu proveedor");
  });

  it("shows a test failure beside the send button and clears it after editing the account", async () => {
    const request = requests((path) => {
      if (path === "/member-smtp-settings/test") throw new ApiError("hidden-provider-reply", 502, { code: "message.smtp.authentication_failed" });
    });
    mount(request);
    const password = await screen.findByLabelText("Contraseña de aplicación");
    expect(password).toHaveAccessibleDescription(/Hay una contraseña guardada/);
    fireEvent.change(screen.getByLabelText("Tu correo para la prueba"), { target: { value: "test@example.org" } });
    fireEvent.click(screen.getByRole("checkbox", { name: /Confirmo que esta dirección/ }));
    const send = screen.getByRole("button", { name: "Enviarme una prueba" });
    fireEvent.click(send);
    const error = await screen.findByRole("alert");
    expect(error).toHaveTextContent(/El proveedor rechazó el acceso/);
    expect(send.closest("section")).toContainElement(error);
    expect(error).not.toHaveTextContent("hidden-provider-reply");
    fireEvent.change(password, { target: { value: "new-app-password" } });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(send).toBeDisabled();
  });

  it.each([
    ["yahoo", "Yahoo Mail", "smtp.mail.yahoo.com", 465, "owner@yahoo.com"],
    ["qq", "QQ Mail", "smtp.qq.com", 465, "123456@qq.com"],
    ["icloud", "iCloud Mail", "smtp.mail.me.com", 587, "owner@icloud.com"],
    ["netease163", "NetEase 163", "smtp.163.com", 465, "owner@163.com"],
    ["exmail", "Tencent Exmail", "smtp.exmail.qq.com", 465, "owner@store.example"],
  ] as const)("configures and restores %s without sending mail", async (id, label, host, port, email) => {
    const request = requests();
    const view = mount(request);
    await screen.findByLabelText("Correo de la tienda");
    expect(screen.getByRole("option", { name: label })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Correo de la tienda"), { target: { value: email } });
    fireEvent.change(screen.getByLabelText("Servidor de correo"), { target: { value: id } });
    expect(screen.getByLabelText("Puerto")).toHaveValue(port);
    expect(screen.getByLabelText("Conexión segura")).toHaveValue(port === 465 ? "ssl" : "starttls");
    expect(screen.getByLabelText("Usuario")).toHaveValue(email);
    expect(screen.queryByLabelText("Servidor SMTP")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Tu correo para la prueba")).toBeDisabled();
    expect(request.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await screen.findByText("Cambios guardados.");
    const saved = request.mock.calls.find(([path, options]) => path === "/member-smtp-settings" && options?.method === "PUT")?.[1]?.body;
    expect(saved).toMatchObject({ host, port, username: email, startTls: port === 587, sslEnabled: port === 465 });
    expect(request.mock.calls.some(([path]) => path.endsWith("/test"))).toBe(false);
    view.unmount();
    mount(requests((path) => path === "/member-smtp-settings" ? { ...smtp, ...(saved as object), host: ` ${host.toUpperCase()} ` } : undefined));
    await screen.findByLabelText("Servidor de correo");
    expect(screen.getByLabelText("Servidor de correo")).toHaveValue(id);
    expect(screen.getByLabelText("Puerto")).toHaveValue(port);
    fireEvent.change(screen.getByLabelText("Servidor de correo"), { target: { value: "gmail" } });
    expect(screen.getByLabelText("Puerto")).toHaveValue(587);
    expect(screen.getByLabelText("Conexión segura")).toHaveValue("starttls");
  });

  it("configures Gmail from the selector and still permits a custom SMTP server", async () => {
    const request = requests(); mount(request);
    await screen.findByLabelText("Correo de la tienda");
    fireEvent.change(screen.getByLabelText("Correo de la tienda"), { target: { value: "owner@gmail.com" } });
    fireEvent.change(screen.getByLabelText("Servidor de correo"), { target: { value: "gmail" } });
    expect(screen.getByLabelText("Usuario")).toHaveValue("owner@gmail.com");
    expect(screen.getByLabelText("Puerto")).toHaveValue(587);
    expect(screen.getByLabelText("Conexión segura")).toHaveValue("starttls");
    expect(screen.queryByLabelText("Servidor SMTP")).not.toBeInTheDocument();
    expect(screen.getByText(/Gmail necesita una contraseña de aplicación de Google/)).toBeInTheDocument();
    expect(screen.getByLabelText("Tu correo para la prueba")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await screen.findByText("Cambios guardados.");
    expect(request.mock.calls.find(([path, options]) => path === "/member-smtp-settings" && options?.method === "PUT")?.[1]?.body)
      .toMatchObject({ host: "smtp.gmail.com", port: 587, startTls: true, sslEnabled: false, username: "owner@gmail.com", password: "" });
    fireEvent.change(screen.getByLabelText("Servidor de correo"), { target: { value: "custom" } });
    fireEvent.change(screen.getByLabelText("Servidor SMTP"), { target: { value: "smtp.store.example" } });
    tab("Plantillas"); tab("Cuenta");
    expect(screen.getByLabelText("Servidor SMTP")).toHaveValue("smtp.store.example");
    expect(request.mock.calls.some(([path]) => path.endsWith("/test"))).toBe(false);
  });

  it("does not load or expose settings for a non administrator", () => {
    const request = requests();
    mount(request, { ...session, permissions: [] });
    expect(screen.getByRole("alert")).toHaveTextContent("Solo un administrador");
    expect(request).not.toHaveBeenCalled();
  });

  it("keeps the saved password, requires save before testing, and sends only after explicit confirmation", async () => {
    const request = requests(); mount(request);
    const password = await screen.findByLabelText("Contraseña de aplicación");
    expect(password).toHaveValue("");
    expect(screen.getByRole("button", { name: "Conectar Google" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Nombre del remitente"), { target: { value: "Tienda" } });
    expect(screen.getByLabelText("Tu correo para la prueba")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await screen.findByText("Cambios guardados.");
    const save = request.mock.calls.find((call) => call[0] === "/member-smtp-settings" && call[1]?.method === "PUT");
    expect(save?.[1]?.body).toMatchObject({ password: "", fromName: "Tienda" });
    expect(request.mock.calls.some(([path]) => path.endsWith("/test"))).toBe(false);
    fireEvent.change(screen.getByLabelText("Tu correo para la prueba"), { target: { value: "owner@example.test" } });
    expect(screen.getByRole("button", { name: "Enviarme una prueba" })).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: /Confirmo que esta dirección/ }));
    fireEvent.click(screen.getByRole("button", { name: "Enviarme una prueba" }));
    await screen.findByText(/El servidor de correo aceptó la prueba/);
    expect(request.mock.calls.filter(([path]) => path === "/member-smtp-settings/test")).toHaveLength(1);
  });

  it("retains unsaved input when changing tabs and supports keyboard navigation", async () => {
    const request = requests(); mount(request);
    fireEvent.change(await screen.findByLabelText("Nombre del remitente"), { target: { value: "Sin guardar" } });
    fireEvent.keyDown(screen.getByRole("tab", { name: "Cuenta" }), { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: "Plantillas" })).toHaveFocus();
    tab("Cuenta");
    expect(screen.getByLabelText("Nombre del remitente")).toHaveValue("Sin guardar");
  });

  it("allows replacing legacy credentials and accepts templates with null optional fields", async () => {
    const request = requests((path) => {
      if (path === "/member-smtp-settings") return { ...smtp, credentialsReady: false };
      if (path === "/communications/templates") return [{ ...template, buttonText: null, buttonUrl: null, logoUrl: null }];
    });
    mount(request);
    expect(await screen.findByLabelText("Contraseña de aplicación")).toBeEnabled();
    fireEvent.change(screen.getByLabelText("Contraseña de aplicación"), { target: { value: "new-secret" } });
    expect(screen.getByRole("button", { name: "Guardar" })).toBeEnabled();
    tab("Plantillas");
    fireEvent.change(await screen.findByLabelText("Plantilla"), { target: { value: "template-1" } });
    expect(screen.getByLabelText("Texto del botón (opcional)")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Actualizar vista previa" })).toBeEnabled();
  });

  it("maps known backend errors without exposing transport credentials", () => {
    const t = createTranslator("es");
    expect(mailErrorMessage(new ApiError("hidden", 400, { detail: "message.smtp.authentication_failed" }), t)).toContain("El proveedor rechazó");
    expect(mailErrorMessage(new Error("smtp://user:secret@example.test"), t)).not.toContain("secret");
    expect(mailErrorMessage(new ApiError("hidden", 409), t)).toContain("Los datos han cambiado");
  });

  it("rejects an authorization URL outside the provider allowlist", async () => {
    const request = requests((path) => {
      if (path === "/mail-oauth/status") return { providers: [{ provider: "GOOGLE", available: true }], connection: null };
      if (path === "/mail-oauth/connect") return { authorizationUrl: "https://evil.example/phish" };
    });
    mount(request);
    fireEvent.click(await screen.findByRole("button", { name: "Conectar Google" }));
    await screen.findByRole("alert");
    expect(screen.queryByRole("link", { name: "Continuar en la página segura del proveedor" })).not.toBeInTheDocument();
  });

  it("previews HTML in a sandbox and resets the preview when the draft changes", async () => {
    const request = requests((path) => path === "/communications/templates/preview" ? { html: '<script>alert(1)</script><h1>Preview</h1>', text: "Preview" } : undefined);
    mount(request); tab("Plantillas");
    fireEvent.change(await screen.findByLabelText("Nombre de la plantilla"), { target: { value: "Prueba" } });
    fireEvent.click(screen.getByRole("button", { name: "Actualizar vista previa" }));
    const frame = await screen.findByTitle("Vista previa");
    expect(frame).toHaveAttribute("sandbox", "");
    expect(frame.getAttribute("srcdoc")).toContain("default-src 'none'");
    expect(request.mock.calls.some(([path]) => path.endsWith("/test"))).toBe(false);
    fireEvent.change(screen.getByLabelText("Mensaje"), { target: { value: "Otro mensaje" } });
    expect(screen.queryByTitle("Vista previa")).not.toBeInTheDocument();
  });

  it("requires recipient review and final confirmation before scheduling", async () => {
    const request = requests((path) => path.endsWith("/schedule") ? { ...campaign, status: "SCHEDULED" } : undefined);
    mount(request); tab("Campañas");
    fireEvent.click(await screen.findByRole("button", { name: /Novedades Borrador/ }));
    await screen.findByText("2 destinatarios recibirán la campaña");
    expect(screen.getByText("Sin autorización: 1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Enviar ahora" }));
    const dialog = screen.getByRole("alertdialog");
    expect(dialog).toHaveTextContent("2 destinatarios");
    expect(request.mock.calls.some(([path]) => path.endsWith("/schedule"))).toBe(false);
    fireEvent.click(within(dialog).getByRole("button", { name: "Enviar ahora" }));
    await screen.findByText(/Campaña preparada para el envío/);
    expect(request.mock.calls.find(([path]) => path.endsWith("/schedule"))?.[1]?.body).toEqual({ scheduledAt: null, expectedRecipients: 2, previewToken: audience.previewToken });
  });

  it("blocks campaigns without public unsubscribe and prevents an empty selected audience becoming all customers", async () => {
    const request = requests((path) => path === "/communications/status" ? { campaignsEnabled: true, publicUnsubscribeConfigured: false, workerEnabled: true } : undefined);
    mount(request); tab("Campañas");
    fireEvent.change(await screen.findByLabelText("Destinatarios"), { target: { value: "selected" } });
    fireEvent.change(screen.getByLabelText("Plantilla"), { target: { value: "template-1" } });
    expect(screen.getByRole("button", { name: "Guardar borrador y revisar" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /Novedades Borrador/ }));
    await screen.findByText("2 destinatarios recibirán la campaña");
    expect(screen.getByRole("button", { name: "Enviar ahora" })).toBeDisabled();
  });

  it("does not claim success when recipients change between review and confirmation", async () => {
    const request = requests((path) => { if (path.endsWith("/schedule")) throw new Error("Recipient count changed"); });
    mount(request); tab("Campañas");
    fireEvent.click(await screen.findByRole("button", { name: /Novedades Borrador/ }));
    await screen.findByText("2 destinatarios recibirán la campaña");
    fireEvent.click(screen.getByRole("button", { name: "Enviar ahora" }));
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Enviar ahora" }));
    await waitFor(() => expect(within(screen.getByRole("alertdialog")).getByRole("alert")).toHaveTextContent("actualiza la revisión"));
    expect(screen.queryByText(/Campaña preparada para el envío/)).not.toBeInTheDocument();
  });

  it("preserves current loyalty rules when saving welcome settings", async () => {
    let reads = 0;
    const request = requests((path, options) => {
      if (path === "/member-settings" && !options.method) return { ...member, pointsPerEuro: ++reads === 1 ? 1 : 9 };
      if (path === "/member-settings" && options.method === "PUT") return options.body;
    });
    mount(request); tab("Automáticos");
    fireEvent.click(await screen.findByRole("checkbox", { name: "Enviar bienvenida automáticamente" }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await screen.findByText("Cambios guardados.");
    expect(request.mock.calls.find(([path, options]) => path === "/member-settings" && options?.method === "PUT")?.[1]?.body).toMatchObject({ memberWelcomeEnabled: true, pointsPerEuro: 9 });
  });

  it("distinguishes accepted from uncertain results and never offers a blind uncertain retry", async () => {
    const request = requests((path) => path === "/member-card-deliveries" ? [
      { id: "one", email: "one@example.test", subject: "Carnet", status: "ENVIADO", createdAt: "2026-11-20T10:00:00Z" },
      { id: "two", email: "two@example.test", subject: "Carnet", status: "INCIERTO", createdAt: "2026-11-20T11:00:00Z" },
    ] : undefined);
    mount(request); tab("Historial");
    await screen.findByText("Aceptado por el servidor");
    expect(screen.getByText("Resultado incierto")).toBeInTheDocument();
    const uncertain = screen.getByText("two@example.test").closest("tr")!;
    expect(within(uncertain).queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Reenviar carnet" })).toHaveLength(1);
  });

  it("reuses the manual resend request ID when the first response is lost", async () => {
    let attempts = 0;
    const request = requests((path) => {
      if (path === "/member-card-deliveries") return [{ id: "one", email: "one@example.test", subject: "Carnet", status: "ENVIADO", createdAt: "2026-11-20T10:00:00Z" }];
      if (path.endsWith("/resend")) { if (++attempts === 1) throw new Error("Response lost"); return { id: "new", status: "PENDIENTE" }; }
    });
    mount(request); tab("Historial");
    fireEvent.click(await screen.findByRole("button", { name: "Reenviar carnet" }));
    let dialog = screen.getByRole("alertdialog");
    expect(request.mock.calls.some(([path]) => path.endsWith("/resend"))).toBe(false);
    fireEvent.click(within(dialog).getByRole("button", { name: "Reenviar carnet" }));
    await waitFor(() => expect(within(screen.getByRole("alertdialog")).getByRole("alert")).toBeInTheDocument());
    dialog = screen.getByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Reenviar carnet" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    const sends = request.mock.calls.filter(([path]) => path.endsWith("/resend"));
    expect(sends).toHaveLength(2);
    expect(sends[0][1]?.body).toEqual(sends[1][1]?.body);
    expect((sends[0][1]?.body as { requestId: string }).requestId).toMatch(/^[a-f0-9-]{36}$/);
  });
});

describe("Mail deep regression scenarios", () => {
  it("merges only locally edited welcome fields after token refresh brings concurrent changes", async () => {
    let reads = 0;
    const request = requests((path, options) => {
      if (path === "/member-settings" && !options.method) return { ...member, welcomeSubjectTemplate: ++reads === 1 ? "Original" : "Concurrent subject" };
      if (path === "/member-settings" && options.method === "PUT") return options.body;
    });
    const view = mount(request); tab("Automáticos");
    fireEvent.change(await screen.findByLabelText("Formato del carnet"), { target: { value: "BARCODE" } });
    view.rerender(<MailCommunicationsScreen session={{ ...session, accessToken: "new-token" }} t={createTranslator("es")} request={request as typeof apiRequest} />);
    expect(await screen.findByLabelText("Formato del carnet")).toHaveValue("BARCODE");
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await screen.findByText("Cambios guardados.");
    expect(request.mock.calls.find(([path, options]) => path === "/member-settings" && options?.method === "PUT")?.[1]?.body).toMatchObject({ memberCardCodeFormat: "BARCODE", welcomeSubjectTemplate: "Concurrent subject" });
  });

  it("ignores duplicate submissions while the first account save is unresolved", async () => {
    let finish!: (value: unknown) => void;
    const request = requests((path, options) => path === "/member-smtp-settings" && options.method === "PUT" ? new Promise((resolve) => { finish = resolve; }) : undefined);
    mount(request);
    fireEvent.change(await screen.findByLabelText("Nombre del remitente"), { target: { value: "Saved" } });
    const form = screen.getByRole("button", { name: "Guardar" }).closest("form")!;
    fireEvent.submit(form); fireEvent.submit(form);
    expect(request.mock.calls.filter(([path, options]) => path === "/member-smtp-settings" && options?.method === "PUT")).toHaveLength(1);
    finish({ ...smtp, fromName: "Saved" });
    await screen.findByText("Cambios guardados.");
    expect(screen.getByRole("button", { name: "Guardar" })).toBeDisabled();
  });

  it("saves a welcome design and can restore the default without touching loyalty settings", async () => {
    const request = requests((path, options) => path === "/communications/welcome-template" && options.method === "PUT" ? options.body : undefined);
    mount(request); tab("Automáticos");
    fireEvent.change(await screen.findByLabelText("Plantilla"), { target: { value: "template-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar diseño" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Guardar diseño" })).toBeDisabled());
    fireEvent.change(screen.getByLabelText("Plantilla"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar diseño" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Guardar diseño" })).toBeDisabled());
    const saves = request.mock.calls.filter(([path, options]) => path === "/communications/welcome-template" && options?.method === "PUT");
    expect(saves.map((call) => call[1]?.body)).toEqual([{ templateId: "template-1" }, { templateId: null }]);
    expect(request.mock.calls.some(([path, options]) => path === "/member-settings" && options?.method === "PUT")).toBe(false);
  });

  it.each(["http://accounts.google.com", "https://accounts.google.com.evil.test", "https://accounts.google.com:8443", "https://user@accounts.google.com"]) ("rejects deceptive provider URL %s", async (authorizationUrl) => {
    const request = requests((path) => {
      if (path === "/mail-oauth/status") return { providers: [{ provider: "GOOGLE", available: true }], connection: null };
      if (path === "/mail-oauth/connect") return { authorizationUrl };
    });
    mount(request);
    fireEvent.click(await screen.findByRole("button", { name: "Conectar Google" }));
    await screen.findByRole("alert");
    expect(screen.queryByRole("link", { name: "Continuar en la página segura del proveedor" })).not.toBeInTheDocument();
  });

  it("retains account, welcome and visual design edits when the same session token renews", async () => {
    const request = requests((path, options) => {
      if (path === "/member-smtp-settings" && !options.method) return { ...smtp };
      if (path === "/member-settings" && !options.method) return { ...member };
    });
    const view = mount(request);
    fireEvent.change(await screen.findByLabelText("Nombre del remitente"), { target: { value: "Pendiente" } });
    fireEvent.change(screen.getByLabelText("Contraseña de aplicación"), { target: { value: "not-yet-saved" } });
    tab("Automáticos");
    fireEvent.change(await screen.findByLabelText("Asunto"), { target: { value: "Bienvenida pendiente" } });
    fireEvent.change(await screen.findByLabelText("Plantilla"), { target: { value: "template-1" } });
    view.rerender(<MailCommunicationsScreen session={{ ...session, accessToken: "refreshed-token" }} t={createTranslator("es")} request={request as typeof apiRequest} />);
    expect(await screen.findByLabelText("Asunto")).toHaveValue("Bienvenida pendiente");
    expect(await screen.findByLabelText("Plantilla")).toHaveValue("template-1");
    tab("Cuenta");
    expect(await screen.findByLabelText("Nombre del remitente")).toHaveValue("Pendiente");
    expect(screen.getByLabelText("Contraseña de aplicación")).toHaveValue("not-yet-saved");
  });

  it("keeps customer selections across filtering and submits only selected IDs", async () => {
    const request = requests((path, options) => {
      if (path === "/customers") return [
        { id: "alice", fiscalName: "Alice", email: "a@example.test", clientId: "1" },
        { id: "bob", fiscalName: "Bob", email: "b@example.test", clientId: "2" },
      ];
      if (path === "/communications/campaigns" && options.method === "POST") return { ...campaign, id: "new-draft", name: "Selected" };
      if (path === "/communications/campaigns/new-draft/preview") return audience;
    });
    mount(request); tab("Campañas");
    fireEvent.change(await screen.findByLabelText("Destinatarios"), { target: { value: "selected" } });
    fireEvent.click(await screen.findByRole("checkbox", { name: /Alice/ }));
    fireEvent.change(screen.getByLabelText("Buscar cliente o correo"), { target: { value: "bob" } });
    fireEvent.click(screen.getByRole("checkbox", { name: /Bob/ }));
    fireEvent.change(screen.getByLabelText("Buscar cliente o correo"), { target: { value: "alice" } });
    expect(screen.getByRole("checkbox", { name: /Alice/ })).toBeChecked();
    expect(screen.getByText("2 clientes seleccionados")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Nombre de la campaña"), { target: { value: "Selected" } });
    fireEvent.change(screen.getByLabelText("Plantilla"), { target: { value: "template-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar borrador y revisar" }));
    await screen.findByText("Borrador guardado. Todavía no se ha enviado ningún correo.");
    expect(request.mock.calls.find(([path, options]) => path === "/communications/campaigns" && options?.method === "POST")?.[1]?.body).toEqual({ name: "Selected", templateId: "template-1", customerIds: ["alice", "bob"] });
    expect(request.mock.calls.some(([path]) => path.endsWith("/schedule"))).toBe(false);
  });

  it("sends the refreshed review token with the chosen local future date", async () => {
    let reviews = 0;
    const request = requests((path) => {
      if (path.endsWith("/preview")) return { ...audience, previewToken: (++reviews === 1 ? "a" : "b").repeat(64) };
      if (path.endsWith("/schedule")) return { ...campaign, status: "SCHEDULED", version: 1 };
    });
    mount(request); tab("Campañas");
    fireEvent.click(await screen.findByRole("button", { name: /Novedades Borrador/ }));
    const future = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 16);
    fireEvent.change(await screen.findByLabelText("Fecha y hora de envío (opcional)"), { target: { value: future } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar destinatarios y mensaje" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Programar envío" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Programar envío" }));
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Programar envío" }));
    await screen.findByText(/Campaña preparada para el envío/);
    expect(request.mock.calls.find(([path]) => path.endsWith("/schedule"))?.[1]?.body).toEqual({ scheduledAt: new Date(future).toISOString(), expectedRecipients: 2, previewToken: "b".repeat(64) });
  });

  it.each([-1, 368])("blocks scheduling outside the permitted future interval (%s days)", async (days) => {
    const request = requests(); mount(request); tab("Campañas");
    fireEvent.click(await screen.findByRole("button", { name: /Novedades Borrador/ }));
    fireEvent.change(await screen.findByLabelText("Fecha y hora de envío (opcional)"), { target: { value: new Date(Date.now() + days * 86400000).toISOString().slice(0, 16) } });
    expect(screen.getByRole("button", { name: "Programar envío" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("próximos 12 meses");
    expect(request.mock.calls.some(([path]) => path.endsWith("/schedule"))).toBe(false);
  });

  it.each([{ eligible: 0 }, { previewToken: "" }])("blocks incomplete or empty audience reviews: %j", async (change) => {
    const request = requests((path) => path.endsWith("/preview") ? { ...audience, ...change } : undefined);
    mount(request); tab("Campañas");
    fireEvent.click(await screen.findByRole("button", { name: /Novedades Borrador/ }));
    expect(await screen.findByRole("button", { name: "Enviar ahora" })).toBeDisabled();
  });

  it("invalidates the selected preview if a refreshed campaign was cancelled elsewhere", async () => {
    let reads = 0;
    const request = requests((path) => path === "/communications/campaigns" ? [{ ...campaign, status: ++reads === 1 ? "DRAFT" : "CANCELLED", version: reads - 1 }] : undefined);
    mount(request); tab("Campañas");
    fireEvent.click(await screen.findByRole("button", { name: /Novedades Borrador/ }));
    await screen.findByRole("button", { name: "Enviar ahora" });
    fireEvent.click(screen.getByRole("button", { name: "Actualizar" }));
    await screen.findByRole("button", { name: /Novedades Cancelada/ });
    await waitFor(() => expect(screen.queryByRole("button", { name: "Enviar ahora" })).not.toBeInTheDocument());
    expect(screen.queryByTitle("Vista previa")).not.toBeInTheDocument();
  });

  it("requires confirmation to cancel and does not submit cancellation when dismissed", async () => {
    const request = requests((path) => path.endsWith("/cancel") ? { ...campaign, status: "CANCELLED", version: 1 } : undefined);
    mount(request); tab("Campañas");
    fireEvent.click(await screen.findByRole("button", { name: /Novedades Borrador/ }));
    await screen.findByRole("button", { name: "Enviar ahora" });
    fireEvent.click(screen.getByRole("button", { name: "Cancelar campaña" }));
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Cancelar" }));
    expect(request.mock.calls.some(([path]) => path.endsWith("/cancel"))).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Cancelar campaña" }));
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Cancelar campaña" }));
    await screen.findByText("Campaña cancelada.");
    expect(request.mock.calls.filter(([path]) => path.endsWith("/cancel"))).toHaveLength(1);
  });

  it("preserves a rejected template draft and reloads its latest version only after discarding", async () => {
    let reads = 0;
    const request = requests((path, options) => {
      if (path === "/communications/templates") return [{ ...template, version: reads++, subject: reads === 1 ? "Ofertas" : "Nueva versión" }];
      if (path === "/communications/templates/template-1" && options.method === "PUT") throw new ApiError("Conflict", 409);
    });
    mount(request); tab("Plantillas");
    fireEvent.change(await screen.findByLabelText("Plantilla"), { target: { value: "template-1" } });
    fireEvent.change(screen.getByLabelText("Asunto"), { target: { value: "Mi cambio" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await screen.findByRole("alert");
    expect(screen.getByLabelText("Asunto")).toHaveValue("Mi cambio");
    expect(screen.getByRole("button", { name: "Actualizar plantillas" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Descartar cambios" }));
    fireEvent.click(screen.getByRole("button", { name: "Actualizar plantillas" }));
    await waitFor(() => expect(screen.getByLabelText("Asunto")).toHaveValue("Nueva versión"));
  });

  it("normalizes legacy insecure transport and preserves a failed password edit without displaying the secret", async () => {
    const request = requests((path, options) => {
      if (path === "/member-smtp-settings" && !options.method) return { ...smtp, startTls: false, sslEnabled: false };
      if (path === "/member-smtp-settings" && options.method === "PUT") throw new ApiError("smtp://u:very-secret@host", 400, { detail: "message.smtp.authentication_failed" });
    });
    mount(request);
    fireEvent.change(await screen.findByLabelText("Contraseña de aplicación"), { target: { value: "very-secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("El proveedor rechazó");
    expect(document.body.textContent).not.toContain("very-secret");
    expect(screen.getByLabelText("Contraseña de aplicación")).toHaveValue("very-secret");
    expect(request.mock.calls.find(([path, options]) => path === "/member-smtp-settings" && options?.method === "PUT")?.[1]?.body).toMatchObject({ startTls: true, sslEnabled: false });
  });

  it("retries the correct delivery API and never exposes raw history errors", async () => {
    const row = { createdAt: "2026-11-20T10:00:00Z", errorMessage: "smtp://secret@host" };
    const request = requests((path) => {
      if (path === "/member-card-deliveries") return [{ ...row, id: "card", email: "card@example.test", status: "ERROR" }];
      if (path === "/communications/history") return [{ ...row, id: "campaign", email: "campaign@example.test", status: "FAILED", campaignName: "Sale" }, { ...row, id: "uncertain", email: "uncertain@example.test", status: "UNCERTAIN" }];
    });
    mount(request); tab("Historial");
    const card = (await screen.findByText("card@example.test")).closest("tr")!;
    fireEvent.click(within(card).getByRole("button", { name: "Reintentar" }));
    await waitFor(() => expect(request.mock.calls.some(([path, options]) => path === "/member-card-deliveries/card/retry" && options?.method === "PATCH")).toBe(true));
    const campaignRow = (await screen.findByText("campaign@example.test")).closest("tr")!;
    await waitFor(() => expect(within(campaignRow).getByRole("button", { name: "Reintentar" })).toBeEnabled());
    fireEvent.click(within(campaignRow).getByRole("button", { name: "Reintentar" }));
    await waitFor(() => expect(request.mock.calls.some(([path, options]) => path === "/communications/history/campaign/retry" && options?.method === "POST")).toBe(true));
    expect(document.body.textContent).not.toContain("smtp://secret");
    fireEvent.change(screen.getByLabelText("Tipo"), { target: { value: "campaign" } });
    expect(screen.queryByText("card@example.test")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Buscar correo o mensaje"), { target: { value: "UNCERTAIN" } });
    expect(within((await screen.findByText("uncertain@example.test")).closest("tr")!).queryByRole("button")).not.toBeInTheDocument();
  });

  it("rejects unsafe HTTPS links and prototype-shaped transport errors", () => {
    for (const value of ["https://", "https://user:password@example.test", "http://example.test", "https://example.test\\evil", "https://exa mple.test"]) expect(isMailHttpsUrl(value)).toBe(false);
    expect(isMailHttpsUrl("HTTPS://example.test/path?q=1")).toBe(true);
    expect(mailErrorMessage(new Error("constructor"), createTranslator("es"))).toContain("No se pudo completar");
  });

  it("preserves the chosen sending date when refreshing the same campaign audience", async () => {
    const request = requests(); mount(request); tab("Campañas");
    fireEvent.click(await screen.findByRole("button", { name: /Novedades Borrador/ }));
    const date = await screen.findByLabelText("Fecha y hora de envío (opcional)");
    const future = new Date(Date.now() + 86400000).toISOString().slice(0, 16);
    fireEvent.change(date, { target: { value: future } });
    fireEvent.click(screen.getByRole("button", { name: "Revisar destinatarios y mensaje" }));
    await waitFor(() => expect(screen.getByLabelText("Fecha y hora de envío (opcional)")).toHaveValue(future));
    expect(screen.getByRole("button", { name: "Programar envío" })).toBeEnabled();
  });

  it("requires confirmation before refreshing account data would discard unsaved credentials", async () => {
    const request = requests(); mount(request);
    fireEvent.change(await screen.findByLabelText("Nombre del remitente"), { target: { value: "Nombre sin guardar" } });
    fireEvent.change(screen.getByLabelText("Contraseña de aplicación"), { target: { value: "unsaved-secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Ya he autorizado: actualizar" }));
    const dialog = screen.getByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancelar" }));
    expect(screen.getByLabelText("Nombre del remitente")).toHaveValue("Nombre sin guardar");
    expect(screen.getByLabelText("Contraseña de aplicación")).toHaveValue("unsaved-secret");
    expect(request.mock.calls.filter(([path]) => path === "/member-smtp-settings")).toHaveLength(1);
  });

  it("does not retain another administrator's unsaved draft after changing sessions", async () => {
    const request = requests();
    const view = mount(request); tab("Plantillas");
    fireEvent.change(await screen.findByLabelText("Nombre de la plantilla"), { target: { value: "Private previous draft" } });
    view.rerender(<MailCommunicationsScreen session={{ ...session, username: "second-admin" }} t={createTranslator("es")} request={request as typeof apiRequest} />);
    expect(screen.getByRole("tab", { name: "Cuenta" })).toHaveAttribute("aria-selected", "true");
    tab("Plantillas");
    expect(await screen.findByLabelText("Nombre de la plantilla")).toHaveValue("");
  });

  it("preserves untouched welcome fields changed elsewhere while saving card format", async () => {
    let reads = 0;
    const request = requests((path, options) => {
      if (path === "/member-settings" && !options.method) return { ...member, welcomeSubjectTemplate: ++reads === 1 ? "Original" : "Actualizado por otra persona" };
      if (path === "/member-settings" && options.method === "PUT") return options.body;
    });
    mount(request); tab("Automáticos");
    fireEvent.change(await screen.findByLabelText("Formato del carnet"), { target: { value: "BARCODE" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await screen.findByText("Cambios guardados.");
    expect(request.mock.calls.find(([path, options]) => path === "/member-settings" && options?.method === "PUT")?.[1]?.body).toMatchObject({ memberCardCodeFormat: "BARCODE", welcomeSubjectTemplate: "Actualizado por otra persona" });
  });
});
