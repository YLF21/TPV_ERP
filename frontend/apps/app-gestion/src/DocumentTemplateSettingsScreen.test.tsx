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
  });

  it("loads and saves the ticket layout selected for the current store", async () => {
    const request = vi.fn().mockImplementation(async (path: string, options?: { body?: unknown }) => {
      if (path === "/store-document-print-configuration") {
        return { ticketStyle: "COMPACTA" };
      }
      if (path === "/store-document-print-configuration/ticket-style") {
        return { ticketStyle: (options?.body as { style: string }).style };
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
    fireEvent.click(screen.getByRole("button", { name: "Guardar diseño" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/store-document-print-configuration/ticket-style",
      { method: "PUT", token: "token", body: { style: "MINIMALISTA" } },
    ));
  });
});
