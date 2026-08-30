// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type apiRequest, type UserSession } from "@tpverp/app-common";
import { TaxSettingsScreen } from "./TaxSettingsScreen";

const admin: UserSession = {
  username: "admin", displayName: "ADMIN", permissions: ["ADMIN"], accessToken: "token",
};
const taxManager: UserSession = {
  username: "tax-manager", displayName: "Tax manager", permissions: ["APP_GESTION_ACCESS", "TAXES_MANAGE"], accessToken: "token",
};
const rows = [
  { id: "tax-7", percentage: 7, active: true, defaultTax: true },
  { id: "tax-21", percentage: 21, active: false, defaultTax: false },
];

afterEach(cleanup);

describe("TaxSettingsScreen", () => {
  it("allows TAXES_MANAGE to create, edit, activate and set the default tax", async () => {
    let current = [...rows];
    const request = vi.fn(async (path: string, options?: { method?: string; body?: { percentage?: number; active?: boolean } }) => {
      if (path === "/taxes" && !options?.method) return current;
      if (path === "/taxes" && options?.method === "POST") {
        const saved = { id: "tax-10", percentage: options.body?.percentage ?? 0, active: true, defaultTax: false };
        current = [...current, saved];
        return saved;
      }
      if (path === "/taxes/tax-21" && options?.method === "PUT") return { ...current[1], percentage: options.body?.percentage };
      if (path === "/taxes/tax-21/active") return { ...current[1], active: options?.body?.active };
      if (path === "/taxes/tax-21/default") return { ...current[1], active: true, defaultTax: true };
      throw new Error(`unexpected request ${path}`);
    });
    render(<TaxSettingsScreen session={taxManager} t={createTranslator("es")} request={request as unknown as typeof apiRequest} />);

    expect(await screen.findByText("7 %")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Nuevo impuesto" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Nuevo impuesto" }));
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/taxes", expect.objectContaining({ method: "POST", body: { percentage: 10 } })));

    fireEvent.click(screen.getAllByRole("button", { name: "Editar" })[1]);
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "22" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/taxes/tax-21", expect.objectContaining({ method: "PUT", body: { percentage: 22 } })));

    fireEvent.click(screen.getAllByRole("switch")[1]);
    await waitFor(() => expect(request).toHaveBeenCalledWith("/taxes/tax-21/active", expect.objectContaining({ method: "PATCH", body: { active: true } })));
    fireEvent.click(screen.getAllByRole("button", { name: "Marcar predeterminado" })[0]);
    await waitFor(() => expect(request).toHaveBeenCalledWith("/taxes/tax-21/default", expect.objectContaining({ method: "PATCH" })));
    expect(screen.getAllByText("Predeterminado")).toHaveLength(1);
  });

  it("keeps default protection and reports delete failures clearly", async () => {
    const request = vi.fn(async (path: string, options?: { method?: string }) => {
      if (path === "/taxes") return rows;
      if (path === "/taxes/tax-21" && options?.method === "DELETE") throw new Error("No se puede eliminar un impuesto utilizado por productos");
      throw new Error(`unexpected request ${path}`);
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<TaxSettingsScreen session={admin} t={createTranslator("es")} request={request as unknown as typeof apiRequest} />);
    await screen.findByText("7 %");
    expect(screen.getAllByRole("button", { name: "Eliminar" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Eliminar" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("No se puede eliminar un impuesto utilizado por productos"));
  });
});
