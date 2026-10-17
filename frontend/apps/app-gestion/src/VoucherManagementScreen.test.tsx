/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createTranslator,
  defaultHardwareConfig,
  type HardwareBridge,
  type UserSession
} from "@tpverp/app-common";
import { VoucherManagementScreen } from "./VoucherManagementScreen";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN", "GESTION_VENTAS"]
};

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, "tpvDesktop");
  vi.unstubAllGlobals();
});

describe("VoucherManagementScreen", () => {
  it("applies search and status automatically while preserving dates, and removes one chip at a time", async () => {
    const requests: URL[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      if (isDateOptions(url)) return dateOptions();
      if (url.pathname.includes("/vouchers/management")) {
        requests.push(url);
        return response({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 1 });
      }
      return response({});
    }));
    const t = createTranslator("es");
    render(<VoucherManagementScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "SERVIDOR" }} t={t} />);
    await waitFor(() => expect(requests).toHaveLength(1));
    fireEvent.click(await screen.findByRole("button", { name: "Hoy" }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.get("from")).toBe("2026-10-17"));
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "V-" } });
    await waitFor(() => expect(requests.at(-1)?.searchParams.get("query")).toBe("V-"));
    fireEvent.click(screen.getByRole("button", { name: t("gestion.vouchers.status") }));
    fireEvent.click(screen.getByRole("option", { name: t("gestion.vouchers.status.EXPIRED") }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.get("status")).toBe("EXPIRED"));
    expect(requests.at(-1)?.searchParams.get("query")).toBe("V-");
    expect(requests.at(-1)?.searchParams.get("from")).toBe("2026-10-17");
    expect(requests.at(-1)?.searchParams.get("to")).toBe("2026-10-17");
    fireEvent.click(screen.getByRole("button", { name: `Quitar filtro ${t("gestion.vouchers.search")}` }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.has("query")).toBe(false));
    expect(requests.at(-1)?.searchParams.get("status")).toBe("EXPIRED");
    expect(requests.at(-1)?.searchParams.get("from")).toBe("2026-10-17");
    expect(requests.at(-1)?.searchParams.get("page")).toBe("0");
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.queryByRole("button", { name: "Limpiar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: t("gestion.vouchers.apply") })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Anterior" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Siguiente" })).not.toBeInTheDocument();
    expect(screen.getByRole("contentinfo", { name: "Periodo seleccionado" })).toBeInTheDocument();
  });

  it("keeps custom dates as a draft until Apply and preserves the remaining range when a chip is removed", async () => {
    const requests: URL[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      if (isDateOptions(url)) return dateOptions();
      if (url.pathname.includes("/vouchers/management")) {
        requests.push(url);
        return response({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 1 });
      }
      return response({});
    }));
    renderVoucherScreen();
    await waitFor(() => expect(requests).toHaveLength(1));
    const footer = await screen.findByRole("contentinfo", { name: "Periodo seleccionado" });
    const custom = within(footer).getByRole("form", { name: "Periodo personalizado" });
    fireEvent.change(within(custom).getByLabelText("Desde"), { target: { value: "2026-08-01" } });
    fireEvent.change(within(custom).getByLabelText("Hasta"), { target: { value: "2026-08-31" } });
    expect(requests).toHaveLength(1);
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    fireEvent.click(within(custom).getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.get("to")).toBe("2026-08-31"));
    expect(requests.at(-1)?.searchParams.get("from")).toBe("2026-08-01");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Emitido desde" }));
    await waitFor(() => expect(requests.at(-1)?.searchParams.has("from")).toBe(false));
    expect(requests.at(-1)?.searchParams.get("to")).toBe("2026-08-31");
    expect(within(custom).getByLabelText("Hasta")).toHaveValue("2026-08-31");
  });

  it("appends pages on scroll once, deduplicates codes and stops at the last page", async () => {
    const pages: number[] = [];
    let resolveSecond!: (value: Response) => void;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      if (isDateOptions(url)) return dateOptions();
      if (url.pathname.includes("/vouchers/management")) {
        const page = Number(url.searchParams.get("page"));
        pages.push(page);
        if (page === 1) return new Promise<Response>((resolve) => { resolveSecond = resolve; });
        return response(voucherPage([voucher("V1")], page, 2));
      }
      return response({});
    }));
    renderVoucherScreen();
    expect(await screen.findByText("V1")).toBeInTheDocument();
    const table = screen.getByRole("table");
    fireEvent.scroll(table);
    fireEvent.scroll(table);
    expect(pages).toEqual([0, 1]);
    resolveSecond(await response(voucherPage([voucher("V1"), voucher("V2")], 1, 2)));
    expect(await screen.findByText("V2")).toBeInTheDocument();
    expect(screen.getAllByText("V1")).toHaveLength(1);
    fireEvent.scroll(table);
    expect(pages).toEqual([0, 1]);
  });

  it("ignores an old append response after filters change", async () => {
    const requests: URL[] = [];
    let resolveOld!: (value: Response) => void;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      if (isDateOptions(url)) return dateOptions();
      if (url.pathname.includes("/vouchers/management")) {
        requests.push(url);
        if (url.searchParams.get("page") === "1") return new Promise<Response>((resolve) => { resolveOld = resolve; });
        return response(voucherPage([voucher(url.searchParams.get("status") ? "NEW" : "OLD")], 0, url.searchParams.get("status") ? 1 : 2));
      }
      return response({});
    }));
    renderVoucherScreen();
    expect(await screen.findByText("OLD")).toBeInTheDocument();
    fireEvent.scroll(screen.getByRole("table"));
    await waitFor(() => expect(requests).toHaveLength(2));
    fireEvent.click(screen.getByRole("button", { name: "Estado" }));
    fireEvent.click(screen.getByRole("option", { name: "Caducado" }));
    expect(await screen.findByText("NEW")).toBeInTheDocument();
    await act(async () => { resolveOld(await response(voucherPage([voucher("STALE")], 1, 2))); });
    expect(screen.queryByText("STALE")).not.toBeInTheDocument();
    expect(screen.queryByText("OLD")).not.toBeInTheDocument();
    expect(requests.at(-1)?.searchParams.get("status")).toBe("EXPIRED");
  });

  it("keeps existing rows after an append failure and retries the same page", async () => {
    const pages: number[] = [];
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      if (isDateOptions(url)) return dateOptions();
      if (url.pathname.includes("/vouchers/management")) {
        const page = Number(url.searchParams.get("page"));
        pages.push(page);
        if (page === 1 && ++attempts === 1) return new Response("error", { status: 500 });
        return response(voucherPage([voucher(page ? "V2" : "V1")], page, 2));
      }
      return response({});
    }));
    renderVoucherScreen();
    expect(await screen.findByText("V1")).toBeInTheDocument();
    fireEvent.scroll(screen.getByRole("table"));
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudieron cargar los vales");
    expect(screen.getByText("V1")).toBeInTheDocument();
    fireEvent.scroll(screen.getByRole("table"));
    expect(await screen.findByText("V2")).toBeInTheDocument();
    expect(pages).toEqual([0, 1, 1]);
  });

  it("shows expiry, opens traceability and limits reactivation to expired vouchers", async () => {
    const voucher = {
      code: "VEXP",
      familyIdentifier: "001-000001",
      initialAmount: 100,
      balance: 40,
      status: "EXPIRED",
      createdAt: "2026-08-16T10:00:00Z",
      expiresOn: "2027-08-16",
      originTickets: ["T-1", "T-2"]
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (isDateOptions(new URL(url, "http://localhost"))) return dateOptions();
      if (url.includes("/vouchers/VEXP/management")) {
        return response({ voucher, events: [] });
      }
      if (url.includes("/vouchers/management")) {
        return response({ items: [voucher], page: 0, size: 50, totalElements: 1, totalPages: 1 });
      }
      return response({});
    }));

    render(<VoucherManagementScreen
      locale="es"
      session={session}
      terminalContext={{ storeName: "Tienda", terminalCode: "SERVIDOR" }}
      t={createTranslator("es")}
    />);

    expect(await screen.findByText("VEXP")).toBeInTheDocument();
    expect(screen.getByText("001-000001")).toBeInTheDocument();
    expect(screen.getByText("Caducado")).toBeInTheDocument();
    fireEvent.click(screen.getByText("VEXP"));

    expect(await screen.findByText("T-1 · T-2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reactivar" })).toBeInTheDocument();
    expect(screen.queryByText("Caducidad de nuevos vales")).not.toBeInTheDocument();
  });

  it("uses the print-event response as the updated audited detail", async () => {
    const voucher = {
      code: "VPRINT",
      familyIdentifier: "001-000002",
      initialAmount: 25,
      balance: 25,
      status: "ACTIVE",
      createdAt: "2026-08-16T10:00:00Z",
      expiresOn: null,
      originTickets: ["T-1"]
    };
    const initialDetail = { voucher, events: [] };
    const auditedDetail = {
      voucher,
      events: [{
        type: "REPRINTED",
        userId: "00000000-0000-0000-0000-000000000001",
        operatorUsername: "ADMIN",
        terminalId: null,
        occurredAt: "2026-08-16T12:00:00Z",
        reason: null
      }]
    };
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (isDateOptions(new URL(url, "http://localhost"))) return dateOptions();
      if (url.includes("/vouchers/VPRINT/print-document")) {
        return response({
          code: voucher.code,
          familyIdentifier: voucher.familyIdentifier,
          amount: voucher.initialAmount,
          issuedAt: voucher.createdAt,
          expiresOn: voucher.expiresOn,
          originTicketNumber: "T-1",
          renderedPdf: { contentType: "application/pdf", base64: "JVBERi0=" },
          ticketRenderedImage: { contentType: "image/png", base64: "iVBORw0=" }
        });
      }
      if (url.includes("/vouchers/VPRINT/print-events")) {
        expect(init?.method).toBe("POST");
        return response(auditedDetail);
      }
      if (url.includes("/vouchers/VPRINT/management")) {
        return response(initialDetail);
      }
      if (url.includes("/vouchers/management")) {
        return response({
          items: [voucher], page: 0, size: 50, totalElements: 1, totalPages: 1
        });
      }
      return response({});
    });
    vi.stubGlobal("fetch", fetch);
    window.tpvDesktop = {
      closeApplication: vi.fn(async () => undefined),
      hardware: {
        getHardwareConfig: vi.fn(async () => ({
          ...defaultHardwareConfig,
          ticketPrinterName: "EPSON"
        })),
        printA4Document: vi.fn(async () => ({ ok: true }))
      } as unknown as HardwareBridge
    };

    render(<VoucherManagementScreen
      locale="es"
      session={session}
      terminalContext={{ storeName: "Tienda", terminalCode: "SERVIDOR" }}
      t={createTranslator("es")}
    />);

    fireEvent.click(await screen.findByText("VPRINT"));
    fireEvent.click(await screen.findByRole("button", { name: "Reimprimir vale" }));

    expect(await screen.findByText("Vale enviado a la impresora.")).toBeInTheDocument();
    expect(await screen.findByText("Reimpresión correcta")).toBeInTheDocument();
    expect(screen.getByText("ADMIN")).toBeInTheDocument();
    expect(screen.queryByText(
      "La impresión terminó, pero no pudo registrarse en la auditoría."
    )).not.toBeInTheDocument();
    await waitFor(() => expect(fetch.mock.calls.filter(([input]) =>
      String(input).includes("/vouchers/VPRINT/management")
    )).toHaveLength(1));
  });
});

function renderVoucherScreen() {
  return render(<VoucherManagementScreen
    locale="es"
    session={session}
    terminalContext={{ storeName: "Tienda", terminalCode: "SERVIDOR" }}
    t={createTranslator("es")}
  />);
}

function isDateOptions(url: URL) {
  return url.pathname.endsWith("/document-reports/date-options")
    && url.searchParams.get("report") === "vouchers";
}

function dateOptions() {
  return response({ currentDate: "2026-10-17", earliestDate: "2024-01-01" });
}

function voucher(code: string) {
  return {
    code,
    familyIdentifier: `001-${code}`,
    initialAmount: 25,
    balance: 25,
    status: "ACTIVE",
    createdAt: "2026-08-16T10:00:00Z",
    expiresOn: null,
    originTickets: ["T-1"]
  };
}

function voucherPage(items: ReturnType<typeof voucher>[], page: number, totalPages: number) {
  return { items, page, size: 50, totalElements: 2, totalPages };
}

function response(body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  }));
}
