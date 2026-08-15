// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import {
  documentTemplateArtifactFiles,
  DocumentTemplateSettingsScreen,
} from "./DocumentTemplateSettingsScreen";

const session: UserSession = {
  username: "manager",
  displayName: "GESTOR",
  accessToken: "token",
  permissions: ["APP_GESTION_ACCESS", "DOCUMENT_TEMPLATES_MANAGE"],
};

afterEach(cleanup);

describe("DocumentTemplateSettingsScreen", () => {
  it("keeps only the exact 19 ticket JRXML files when a shared folder is selected", () => {
    const sections = ["cabecera", "cliente", "contenido", "impuesto", "pago", "pie"];
    const ticketFiles = [
      new File(["master"], "ticket.jrxml"),
      ...sections.flatMap((section) => [
        new File([section], `ticket_${section}.jrxml`),
        new File([section], `ticket_${section}_compacta.jrxml`),
        new File([section], `ticket_${section}_minimalista.jrxml`),
      ]),
    ];
    const selected = documentTemplateArtifactFiles("TICKET", [
      ...ticketFiles,
      new File(["a4"], "FACTURA_VENTA_A4.jrxml"),
      new File(["compiled"], "ticket.jasper"),
    ]);

    expect(selected).toHaveLength(19);
    expect(selected.map((file) => file.name)).toEqual(ticketFiles.map((file) => file.name));
  });

  it("accepts a single custom ticket JRXML regardless of its filename", () => {
    const custom = new File(["master"], "mi-ticket-personalizado.jrxml");

    expect(documentTemplateArtifactFiles("TICKET", [custom])).toEqual([custom]);
  });

  it("keeps the manual workflow available when the active JRXML is missing", async () => {
    const request = vi.fn().mockImplementation(async () => ({
      effective: null,
      storeTemplates: [],
    }));

    render(
      <DocumentTemplateSettingsScreen
        session={session}
        t={createTranslator("es")}
        request={request}
      />,
    );

    expect(await screen.findByText("Falta plantilla JRXML activa")).toBeInTheDocument();
    expect(screen.getByText(/modelo integrado o una plantilla JRXML activa/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear borrador" })).toBeDisabled();

    fireEvent.click(screen.getByRole("tab", { name: "Albarán" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-templates?type=ALBARAN_VENTA&format=A4",
      { token: "token" },
    ));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-templates/presentation?type=ALBARAN_VENTA&format=A4",
      { token: "token" },
    ));
    expect(screen.getByRole("combobox", { name: "Origen del modelo" }))
      .toHaveValue("INTEGRATED");
    expect(screen.getByText("Falta plantilla JRXML activa")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Vale" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-templates?type=VALE&format=TICKET_80",
      { token: "token" },
    ));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-templates/presentation?type=VALE&format=TICKET_80",
      { token: "token" },
    ));
  });

  it("selects invoice origins independently for A4 and ticket 80", async () => {
    const request = vi.fn().mockImplementation(async (path: string, options?: { body?: unknown }) => {
      if (path.startsWith("/document-templates/presentation?")) {
        return {
          type: "FACTURA_VENTA",
          format: path.includes("TICKET_80") ? "TICKET_80" : "A4",
          origin: path.includes("TICKET_80") ? "IMPORTED" : "INTEGRATED",
        };
      }
      if (path === "/document-templates/presentation") {
        return options?.body;
      }
      return {
        effective: {
          id: "invoice-template", type: "FACTURA_VENTA",
          format: path.includes("TICKET_80") ? "TICKET_80" : "A4",
          scope: "STORE", code: "FACTURA_TIENDA", version: 3,
          schemaVersion: 1, artifactReference: "invoice-template",
          sha256: "a".repeat(64), builtIn: false,
        },
        storeTemplates: [],
      };
    });

    render(<DocumentTemplateSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request}
    />);

    const origin = await screen.findByRole("combobox", { name: "Origen del modelo" });
    await waitFor(() => expect(origin).toHaveValue("INTEGRATED"));
    fireEvent.change(origin, { target: { value: "IMPORTED" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar este diseño" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-templates/presentation",
      { method: "PUT", token: "token", body: {
        type: "FACTURA_VENTA", format: "A4", origin: "IMPORTED",
      } },
    ));

    fireEvent.click(screen.getByRole("tab", { name: "Ticket 80 mm" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-templates/presentation?type=FACTURA_VENTA&format=TICKET_80",
      { token: "token" },
    ));
    await waitFor(() => expect(
      screen.getByRole("combobox", { name: "Origen del modelo" }),
    ).toHaveValue("IMPORTED"));
  });

  it.each([
    ["Albarán", "ALBARAN_VENTA", "A4"],
    ["Vale", "VALE", "TICKET_80"],
  ])("saves the imported model for %s", async (tab, type, format) => {
    const request = vi.fn().mockImplementation(async (path: string, options?: { body?: unknown }) => {
      if (path.startsWith("/document-templates/presentation?")) {
        return { type, format, origin: "INTEGRATED" };
      }
      if (path === "/document-templates/presentation") return options?.body;
      return {
        effective: {
          id: "template", type, format, scope: "STORE", code: `${type}_${format}`,
          version: 2, schemaVersion: 1, artifactReference: "template",
          sha256: "a".repeat(64), builtIn: false,
        },
        storeTemplates: [],
      };
    });
    render(<DocumentTemplateSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request}
    />);

    fireEvent.click(await screen.findByRole("tab", { name: tab }));
    const origin = await screen.findByRole("combobox", { name: "Origen del modelo" });
    await waitFor(() => expect(origin).toHaveValue("INTEGRATED"));
    fireEvent.change(origin, { target: { value: "IMPORTED" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar este diseño" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-templates/presentation",
      { method: "PUT", token: "token", body: { type, format, origin: "IMPORTED" } },
    ));
  });

  it("loads and saves the ticket layout selected for the current store", async () => {
    const request = vi.fn().mockImplementation(async (path: string, options?: { body?: unknown }) => {
      if (path === "/store-document-print-configuration") {
        return { ticketStyle: "COMPACTA", ticketTemplateOrigin: "INTEGRATED" };
      }
      if (path === "/store-document-print-configuration/ticket-presentation") {
        const body = options?.body as { origin: string; style: string };
        return { ticketStyle: body.style, ticketTemplateOrigin: body.origin };
      }
      return {
        effective: {
          id: null, type: "TICKET", scope: "SYSTEM", code: "TICKET_80",
          version: 1, schemaVersion: 1, artifactReference: "builtin:ticket",
          sha256: null, builtIn: true,
        },
        storeTemplates: [],
      };
    });

    render(<DocumentTemplateSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request}
    />);

    fireEvent.click(await screen.findByRole("tab", { name: "Ticket" }));
    const origin = await screen.findByRole("combobox", { name: "Origen del diseño" });
    await waitFor(() => expect(origin).toHaveValue("INTEGRATED"));
    const selector = await screen.findByRole("combobox", { name: "Plantilla elegida" });
    await waitFor(() => expect(selector).toHaveValue("COMPACTA"));
    expect(screen.getByText("Versión JRXML en uso")).toBeInTheDocument();
    expect(screen.getByText("Predeterminada del sistema · v1")).toBeInTheDocument();
    expect(screen.getByText("Diseño en uso")).toBeInTheDocument();
    expect(screen.getByText("Compacta", { selector: "dd" })).toBeInTheDocument();
    const compactPreview = screen.getByRole("img", {
      name: /Vista previa de la plantilla: Compacta/,
    });
    const compactPreviewSource = compactPreview.getAttribute("src");
    expect(compactPreviewSource).toBeTruthy();
    expect(screen.getByRole("button", { name: "Diseño activo" })).toBeDisabled();
    fireEvent.change(selector, { target: { value: "MINIMALISTA" } });
    expect(screen.getByRole("img", { name: /Vista previa de la plantilla: Minimalista/ }))
      .not.toHaveAttribute("src", compactPreviewSource);
    fireEvent.click(screen.getByRole("button", { name: "Aplicar este diseño" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/store-document-print-configuration/ticket-presentation",
      { method: "PUT", token: "token", body: {
        origin: "INTEGRATED", style: "MINIMALISTA",
      } },
    ));
  });

  it("selects the active imported ticket without silently showing an integrated model", async () => {
    const request = vi.fn().mockImplementation(async (path: string, options?: { body?: unknown }) => {
      if (path === "/store-document-print-configuration") {
        return { ticketStyle: "COMPACTA", ticketTemplateOrigin: "IMPORTED" };
      }
      if (path === "/store-document-print-configuration/ticket-presentation") {
        const body = options?.body as { origin: string; style: string };
        return { ticketStyle: body.style, ticketTemplateOrigin: body.origin };
      }
      return {
        effective: {
          id: "template-2", type: "TICKET", format: "TICKET_80", scope: "STORE",
          code: "TICKET_80", version: 2, schemaVersion: 1,
          artifactReference: "template-2", sha256: "a".repeat(64), builtIn: false,
        },
        storeTemplates: [],
      };
    });

    render(<DocumentTemplateSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request}
    />);

    fireEvent.click(await screen.findByRole("tab", { name: "Ticket" }));
    const origin = await screen.findByRole("combobox", { name: "Origen del diseño" });
    await waitFor(() => expect(origin).toHaveValue("IMPORTED"));
    expect(screen.getAllByText("TICKET_80")).toHaveLength(2);
    expect(screen.queryByRole("combobox", { name: "Plantilla elegida" })).toBeNull();
    expect(screen.getByRole("button", { name: "Diseño activo" })).toBeDisabled();
  });

  it("explains that saving a default design replaces the active custom JRXML", async () => {
    const request = vi.fn().mockImplementation(async (path: string) => {
      if (path === "/store-document-print-configuration") {
        return { ticketStyle: "PRINCIPAL" };
      }
      return {
        effective: {
          id: "custom-2", type: "TICKET", scope: "STORE", code: "TICKET_80",
          version: 2, schemaVersion: 1, artifactReference: "bundle:custom-2",
          sha256: "a".repeat(64), builtIn: false,
        },
        storeTemplates: [{
          id: "custom-2", type: "TICKET", format: "TICKET_80", scope: "STORE",
          code: "TICKET_80", version: 2, name: "Mi ticket", status: "ACTIVE",
          schemaVersion: 1, sha256: "a".repeat(64), createdByUserId: null,
          createdAt: "2026-08-15T10:00:00Z", validatedAt: "2026-08-15T10:01:00Z",
          activatedAt: "2026-08-15T10:02:00Z", retiredAt: null,
          reactivatable: false,
        }],
      };
    });

    render(<DocumentTemplateSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request}
    />);

    fireEvent.click(await screen.findByRole("tab", { name: "Ticket" }));
    expect(await screen.findByText("Plantilla JRXML personalizada")).toBeInTheDocument();
    expect(screen.getByText(/Al guardar uno de estos diseños predeterminados/))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cambiar a este diseño" })).toBeEnabled();
  });

  it("shows Reactivate only for a version retired by a predefined design", async () => {
    const retiredBase = {
      type: "TICKET" as const, format: "TICKET_80" as const, scope: "STORE" as const,
      code: "TICKET_80", schemaVersion: 1, sha256: "a".repeat(64),
      createdByUserId: null, createdAt: "2026-08-15T10:00:00Z",
      validatedAt: "2026-08-15T10:01:00Z", activatedAt: "2026-08-15T10:02:00Z",
      retiredAt: "2026-08-15T10:03:00Z", status: "RETIRED" as const,
    };
    const request = vi.fn().mockImplementation(async (path: string) => {
      if (path === "/store-document-print-configuration") {
        return { ticketStyle: "PRINCIPAL" };
      }
      if (path.endsWith("/reactivate")) {
        return { ...retiredBase, id: "recoverable", version: 2, name: "Recuperable",
          status: "ACTIVE", retiredAt: null, reactivatable: false };
      }
      return {
        effective: {
          id: null, type: "TICKET", format: "TICKET_80", scope: "SYSTEM",
          code: "TICKET_80", version: 1, schemaVersion: 1,
          artifactReference: "builtin:ticket", sha256: null, builtIn: true,
        },
        storeTemplates: [
          { ...retiredBase, id: "recoverable", version: 2, name: "Recuperable",
            reactivatable: true },
          { ...retiredBase, id: "historical", version: 1, name: "Histórica",
            reactivatable: false },
        ],
      };
    });

    render(<DocumentTemplateSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request}
    />);

    fireEvent.click(await screen.findByRole("tab", { name: "Ticket" }));
    const reactivate = await screen.findByRole("button", { name: "Reactivar" });
    expect(screen.getAllByRole("button", { name: "Reactivar" })).toHaveLength(1);
    fireEvent.click(reactivate);

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-templates/recoverable/reactivate",
      { method: "POST", token: "token" },
    ));
  });
});
