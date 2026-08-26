/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type apiRequest, type UserSession } from "@tpverp/app-common";
import { LicenseSaasManagementScreen } from "./LicenseSaasManagementScreen";
import type { LicenseHistoryItem } from "./licenseSaasApi";

const admin: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN"],
};

const activeLicense: LicenseHistoryItem = {
  reference: "LIC-2026-0001",
  validFrom: "2026-08-01T00:00:00Z",
  validUntil: "2027-08-01T00:00:00Z",
  maxWindows: 3,
  maxPda: 2,
  taxId: "B12345674",
  taxpayerType: "JURIDICA",
  impuestos: "IGIC",
  commercialProfile: "MAYORISTA",
  active: true,
  saasStatus: "VALIDA",
  lastSaasValidationAt: "2026-08-25T10:00:00Z",
  verifactuActivationDate: "2027-01-01",
  licenseVersion: 4,
};

afterEach(cleanup);

describe("LicenseSaasManagementScreen", () => {
  it("shows the effective licence and validates it against SaaS", async () => {
    const request = vi.fn(async (path: string, options?: { method?: string }) => {
      if (path === "/licenses/validate-saas") {
        return {
          status: "VALIDA",
          validUntil: activeLicense.validUntil,
          maxWindows: 3,
          maxPda: 2,
          licenseVersion: 4,
        };
      }
      expect(options?.method).toBeUndefined();
      return [activeLicense];
    });

    render(<LicenseSaasManagementScreen
      locale="es"
      session={admin}
      storeName="Tienda Centro"
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    expect(await screen.findAllByText("LIC-2026-0001")).toHaveLength(2);
    expect(screen.getByText("Activación obligatoria VERI*FACTU")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Validar ahora" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/licenses/validate-saas",
      { method: "POST", token: "token" },
    ));
    expect(await screen.findByText("Licencia validada y condiciones locales actualizadas.")).toBeInTheDocument();
  });

  it("links a single-use pairing code and reloads the local licence", async () => {
    let linked = false;
    const request = vi.fn(async (path: string, options?: { method?: string; body?: unknown }) => {
      if (path === "/licenses/link-saas") {
        linked = true;
        expect(options).toMatchObject({
          method: "POST",
          token: "token",
          body: { pairingCode: "PAIR-123" },
        });
        return {
          licenseReference: activeLicense.reference,
          companyId: "company",
          storeId: "store",
          serverTerminalId: "terminal",
          validUntil: activeLicense.validUntil,
          status: "VALIDA",
          maxWindows: 3,
          maxPda: 2,
        };
      }
      return linked ? [activeLicense] : [];
    });

    render(<LicenseSaasManagementScreen
      locale="es"
      session={admin}
      storeName="Tienda Centro"
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    fireEvent.change(await screen.findByLabelText("Código de emparejamiento"), {
      target: { value: "  PAIR-123  " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Vincular licencia" }));

    expect(await screen.findByText("Licencia SaaS vinculada correctamente.")).toBeInTheDocument();
    expect(screen.getAllByText("LIC-2026-0001")).toHaveLength(2);
  });

  it("keeps licence managers read-only when they are not ADMIN", async () => {
    const manager: UserSession = { ...admin, permissions: ["LICENSES_MANAGE"] };
    const request = vi.fn(async () => [activeLicense]);

    render(<LicenseSaasManagementScreen
      locale="en"
      session={manager}
      storeName="Central Store"
      t={createTranslator("en")}
      request={request as unknown as typeof apiRequest}
    />);

    expect(await screen.findByText("Read-only access.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Validate now" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Link licence" })).toBeDisabled();
    expect(screen.getByLabelText("Pairing code")).toBeDisabled();
  });
});
