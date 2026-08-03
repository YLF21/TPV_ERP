// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";

describe("SaleOperationAuthorizationFields", () => {
  it("identifica al usuario actual cuando solo solicita su contraseña", () => {
    render(
      <SaleOperationAuthorizationFields
        locale="es"
        authorization={{ mode: "CURRENT_PASSWORD", requireUsername: false, requirePassword: true }}
        currentUsername="ADMIN"
        username=""
        password=""
        onUsernameChange={vi.fn()}
        onPasswordChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Usuario que confirma")).toBeVisible();
    expect(screen.getByText("ADMIN")).toBeVisible();
    expect(screen.queryByText("Usuario autorizador")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Tu contraseña")).toBeVisible();
  });

  it("diferencia al operador actual del usuario autorizador delegado", () => {
    render(
      <SaleOperationAuthorizationFields
        locale="es"
        authorization={{ mode: "DELEGATED", requireUsername: true, requirePassword: true }}
        currentUsername="CAJERO01"
        username="SUPERVISOR"
        password=""
        onUsernameChange={vi.fn()}
        onPasswordChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Operador actual")).toBeVisible();
    expect(screen.getByText("CAJERO01")).toBeVisible();
    expect(screen.getByLabelText("Usuario autorizador")).toHaveValue("SUPERVISOR");
    expect(screen.getByLabelText("Contraseña del autorizador")).toBeVisible();
  });
});
