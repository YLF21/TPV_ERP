// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import { DocumentTemplateSettingsScreen } from "./DocumentTemplateSettingsScreen";

const session: UserSession = {
  username: "manager",
  displayName: "GESTOR",
  accessToken: "token",
  permissions: ["APP_GESTION_ACCESS", "DOCUMENT_TEMPLATES_MANAGE"],
};

afterEach(cleanup);

describe("DocumentTemplateSettingsScreen", () => {
  it("loads each catalog and exposes only the real draft workflow", async () => {
    const request = vi.fn().mockImplementation(async (path: string) => ({
      effective: {
        id: null,
        type: path.includes("ALBARAN_VENTA") ? "ALBARAN_VENTA" : "FACTURA_VENTA",
        scope: "SYSTEM",
        code: path.includes("ALBARAN_VENTA") ? "ALBARAN_A4" : "FACTURA_A4",
        version: 1,
        schemaVersion: 1,
        artifactReference: "builtin:test",
        sha256: null,
        builtIn: true,
      },
      storeTemplates: [],
    }));

    render(
      <DocumentTemplateSettingsScreen
        session={session}
        t={createTranslator("es")}
        request={request}
      />,
    );

    expect(await screen.findByText("FACTURA_A4")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear borrador" })).toBeDisabled();

    fireEvent.click(screen.getByRole("tab", { name: "Albarán" }));
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/document-templates?type=ALBARAN_VENTA",
      { token: "token" },
    ));
    expect(await screen.findByText("ALBARAN_A4")).toBeInTheDocument();
  });
});
