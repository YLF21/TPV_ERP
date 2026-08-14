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
});
