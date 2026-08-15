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
    expect(screen.getByText(/Sin ella, la emisión y la impresión quedan bloqueadas/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear borrador" })).toBeDisabled();

    fireEvent.click(screen.getByRole("tab", { name: "Albarán" }));
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/document-templates?type=ALBARAN_VENTA&format=A4",
      { token: "token" },
    ));
    expect(screen.getByText("Falta plantilla JRXML activa")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Vale" }));
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/document-templates?type=VALE&format=TICKET_80",
      { token: "token" },
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
    const compactPreview = screen.getByRole("img", {
      name: /Vista previa de la plantilla: Compacta/,
    });
    const compactPreviewSource = compactPreview.getAttribute("src");
    expect(compactPreviewSource).toBeTruthy();
    fireEvent.change(selector, { target: { value: "MINIMALISTA" } });
    expect(screen.getByRole("img", { name: /Vista previa de la plantilla: Minimalista/ }))
      .not.toHaveAttribute("src", compactPreviewSource);
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));

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
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/store-document-print-configuration/ticket-presentation",
      { method: "PUT", token: "token", body: {
        origin: "IMPORTED", style: "COMPACTA",
      } },
    ));
  });
});
