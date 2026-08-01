// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  createTranslator,
  type apiRequest,
  type SalesOperationSecurityConfiguration,
  type UserSession,
} from "@tpverp/app-common";
import { SalesOperationSecurityScreen } from "./SalesOperationSecurityScreen";

const adminSession: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
  accessToken: "token",
};

const configuration = (
  overrides: Partial<SalesOperationSecurityConfiguration> = {},
): SalesOperationSecurityConfiguration => ({
  storeId: "store-1",
  version: 7,
  operations: [{
    code: "OPEN_CASH_DRAWER",
    category: "CASH",
    shortcuts: ["F3"],
    permissions: ["ABRIR_CAJON"],
    defaultRequirePermission: true,
    defaultRequirePassword: false,
    requirePermission: true,
    requirePassword: false,
    customized: false,
  }, {
    code: "CANCEL_TICKET",
    category: "TICKET",
    shortcuts: ["F11", "Ctrl+F11"],
    permissions: ["GESTION_VENTAS", "GESTION_CUENTAS"],
    defaultRequirePermission: true,
    defaultRequirePassword: true,
    requirePermission: true,
    requirePassword: true,
    customized: false,
  }],
  ...overrides,
});

afterEach(cleanup);

describe("SalesOperationSecurityScreen", () => {
  it("edits a draft and saves the complete versioned configuration explicitly", async () => {
    const current = configuration();
    const saved = configuration({
      version: 8,
      operations: current.operations.map((operation) => (
        operation.code === "OPEN_CASH_DRAWER"
          ? { ...operation, requirePassword: true, customized: true }
          : operation
      )),
    });
    const request = vi.fn(async (path: string, options?: { method?: string }) => {
      if (path === "/sales/operation-security" && options?.method === "PUT") return saved;
      return current;
    });

    render(<SalesOperationSecurityScreen
      session={adminSession}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    expect(await screen.findByText("Abrir cajón")).toBeInTheDocument();
    expect(screen.getByText("Caja")).toBeInTheDocument();
    expect(screen.getByText("Tickets")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("switch", {
      name: "Pedir contraseña: Abrir cajón",
    }));

    expect(request).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Cambios sin guardar: 1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Guardar cambios" }));

    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/sales/operation-security",
      {
        token: "token",
        method: "PUT",
        body: {
          expectedVersion: 7,
          operations: [{
            code: "OPEN_CASH_DRAWER",
            requirePermission: true,
            requirePassword: true,
          }, {
            code: "CANCEL_TICKET",
            requirePermission: true,
            requirePassword: true,
          }],
        },
      },
    ));
    expect(await screen.findByText("Configuración guardada.")).toBeInTheDocument();
    expect(screen.getByText("Versión:").parentElement).toHaveTextContent("8");
  });

  it("confirms reset and uses the configuration returned by the backend", async () => {
    const current = configuration({
      operations: configuration().operations.map((operation) => ({
        ...operation,
        requirePermission: false,
        customized: true,
      })),
    });
    const restored = configuration({ version: 8 });
    const request = vi.fn(async (path: string) => (
      path.endsWith("/reset") ? restored : current
    ));

    render(<SalesOperationSecurityScreen
      session={adminSession}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    await screen.findByText("Abrir cajón");
    fireEvent.click(screen.getByRole("button", {
      name: "Restaurar valores predeterminados",
    }));

    const dialog = screen.getByRole("dialog", {
      name: "Restaurar valores predeterminados",
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Restaurar" }));

    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/sales/operation-security/reset",
      {
        token: "token",
        method: "POST",
        body: { expectedVersion: 7 },
      },
    ));
    expect(await screen.findByText("Se han restaurado los valores predeterminados.")).toBeInTheDocument();
    expect(screen.getAllByText("Predeterminado")).toHaveLength(2);
  });

  it("reports an optimistic-lock conflict and offers a current reload", async () => {
    const current = configuration();
    let gets = 0;
    const request = vi.fn(async (path: string, options?: { method?: string }) => {
      if (path === "/sales/operation-security" && options?.method === "PUT") {
        throw new ApiError("stale", 409);
      }
      gets += 1;
      return configuration({ version: gets === 1 ? 7 : 8 });
    });

    render(<SalesOperationSecurityScreen
      session={adminSession}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    await screen.findByText("Abrir cajón");
    fireEvent.click(screen.getByRole("switch", {
      name: "Pedir contraseña: Abrir cajón",
    }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar cambios" }));

    expect(await screen.findByText("La configuración cambió mientras la estabas editando.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Recargar configuración" }));

    await waitFor(() => expect(gets).toBe(2));
    expect(screen.getByText("Versión:").parentElement).toHaveTextContent("8");
  });

  it("keeps the unsaved draft when the interface language changes", async () => {
    const request = vi.fn(async () => configuration());
    const view = render(<SalesOperationSecurityScreen
      session={adminSession}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    await screen.findByText("Abrir cajón");
    fireEvent.click(screen.getByRole("switch", {
      name: "Pedir contraseña: Abrir cajón",
    }));

    view.rerender(<SalesOperationSecurityScreen
      session={adminSession}
      t={createTranslator("en")}
      request={request as unknown as typeof apiRequest}
    />);

    expect(screen.getByText("Unsaved changes: 1")).toBeInTheDocument();
    expect(screen.getByRole("switch", {
      name: "Require password: Open cash drawer",
    })).toBeChecked();
    expect(request).toHaveBeenCalledTimes(1);
  });

  it("does not load or expose controls to a non administrator", () => {
    const request = vi.fn();
    render(<SalesOperationSecurityScreen
      session={{ ...adminSession, permissions: ["GESTION_VENTAS"] }}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Solo un administrador puede configurar la seguridad de las funciones de venta.",
    );
    expect(request).not.toHaveBeenCalled();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });
});
