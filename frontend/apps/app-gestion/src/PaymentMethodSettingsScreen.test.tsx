// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type apiRequest, type PaymentMethodView, type UserSession } from "@tpverp/app-common";
import { PaymentMethodSettingsScreen } from "./PaymentMethodSettingsScreen";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
  accessToken: "token",
};

const method = (name: string, overrides: Partial<PaymentMethodView> = {}): PaymentMethodView => ({
  id: name.toLowerCase(),
  companyId: "company-1",
  name,
  protectedMethod: true,
  active: true,
  requiresReference: false,
  opensCashDrawer: name === "EFECTIVO",
  ...overrides,
});

afterEach(cleanup);

describe("PaymentMethodSettingsScreen", () => {
  it("shows only operational checkout methods and limits document configuration", async () => {
    const request = vi.fn(async (path: string) => path === "/return-policy"
      ? { policy: "REFUND_ALLOWED" }
      : [method("EFECTIVO"), method("TARJETA"), method("TRANSFERENCIA"), method("VALE"), method("DESCUENTO")]);
    render(<PaymentMethodSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    expect(await screen.findByText("Efectivo")).toBeTruthy();
    expect(screen.getByText("Tarjeta")).toBeTruthy();
    expect(screen.getByText("Transferencia")).toBeTruthy();
    expect(screen.getByText("Vale")).toBeTruthy();
    expect(screen.queryByText("DESCUENTO")).toBeNull();
    expect(screen.getAllByRole("switch")).toHaveLength(7);
    expect(screen.getByText("No aplicable")).toBeTruthy();
    expect((screen.getByRole("switch", {
      name: "Solicitar Nº documento · Vale",
    }) as HTMLInputElement).disabled).toBe(true);
    expect(screen.queryByText("Vale: dos datos diferentes")).toBeNull();
  });

  it("updates the external document requirement while preserving drawer configuration", async () => {
    const card = method("TARJETA", { opensCashDrawer: true });
    const request = vi.fn(async (path: string, options?: { method?: string }) => {
      if (path === "/return-policy") return { policy: "REFUND_ALLOWED" };
      if (options?.method === "PATCH") return { ...card, requiresReference: true };
      return [method("EFECTIVO"), card, method("TRANSFERENCIA"), method("VALE")];
    });
    render(<PaymentMethodSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    const toggle = await screen.findByRole("switch", {
      name: "Solicitar Nº documento · Tarjeta",
    });
    fireEvent.click(toggle);

    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/payment-methods/tarjeta/configuration",
      {
        token: "token",
        method: "PATCH",
        body: { requiresReference: true, opensCashDrawer: true },
      },
    ));
    expect(await screen.findByText("Configuración guardada")).toBeTruthy();
  });

  it("updates whether a payment method is available", async () => {
    const transfer = method("TRANSFERENCIA");
    const request = vi.fn(async (path: string, options?: { method?: string }) => {
      if (path === "/return-policy") return { policy: "REFUND_ALLOWED" };
      if (options?.method === "PATCH") return { ...transfer, active: false };
      return [method("EFECTIVO"), method("TARJETA"), transfer, method("VALE")];
    });
    render(<PaymentMethodSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    fireEvent.click(await screen.findByRole("switch", {
      name: "Activo en cobro · Transferencia",
    }));

    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/payment-methods/transferencia/active",
      {
        token: "token",
        method: "PATCH",
        body: { active: false },
      },
    ));
  });

  it("configures whether the store permits monetary refunds", async () => {
    const request = vi.fn(async (path: string, options?: { method?: string; body?: unknown }) => {
      if (path === "/return-policy" && options?.method === "PUT") {
        return { policy: "EXCHANGE_OR_VOUCHER_ONLY" };
      }
      if (path === "/return-policy") return { policy: "REFUND_ALLOWED" };
      return [method("EFECTIVO"), method("TARJETA"), method("TRANSFERENCIA"), method("VALE")];
    });
    render(<PaymentMethodSettingsScreen
      session={session}
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    const policy = await screen.findByRole("combobox", { name: /liquidación permitida/i });
    fireEvent.change(policy, { target: { value: "EXCHANGE_OR_VOUCHER_ONLY" } });

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/return-policy",
      {
        token: "token",
        method: "PUT",
        body: { policy: "EXCHANGE_OR_VOUCHER_ONLY" },
      },
    ));
    expect((policy as HTMLSelectElement).value).toBe("EXCHANGE_OR_VOUCHER_ONLY");
  });
});
