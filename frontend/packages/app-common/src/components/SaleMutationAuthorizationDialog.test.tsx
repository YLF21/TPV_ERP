// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SaleMutationAuthorizationDialog } from "./SaleMutationAuthorizationDialog";

afterEach(cleanup);

describe("SaleMutationAuthorizationDialog", () => {
  it("returns separate per-operation credentials and clears passwords before the caller runs", async () => {
    const onConfirm = vi.fn(() => {
      expect(screen.getAllByLabelText<HTMLInputElement>(
        /Contraseña del autorizador/i,
      ).every((input) => input.value === "")).toBe(true);
    });
    render(<SaleMutationAuthorizationDialog
      open
      locale="es"
      requirements={[{
        code: "MANUAL_RETURN_WITHOUT_TICKET",
        label: "Devolución manual",
        authorization: {
          mode: "DELEGATED",
          requireUsername: true,
          requirePassword: true,
        },
      }, {
        code: "APPLY_SALE_DISCOUNT",
        label: "Descuento",
        authorization: {
          mode: "DELEGATED",
          requireUsername: true,
          requirePassword: true,
        },
      }]}
      onCancel={vi.fn()}
      onConfirm={onConfirm}
    />);

    const dialog = screen.getByRole("dialog", { name: "Autorización de la venta" });
    const usernames = within(dialog).getAllByRole("textbox", {
      name: "Usuario autorizador",
    });
    const passwords = within(dialog).getAllByLabelText(
      "Contraseña del autorizador",
    );
    fireEvent.change(usernames[0], { target: { value: "manager-return" } });
    fireEvent.change(passwords[0], { target: { value: "return-secret" } });
    fireEvent.change(usernames[1], { target: { value: "manager-discount" } });
    fireEvent.change(passwords[1], { target: { value: "discount-secret" } });
    fireEvent.click(within(dialog).getByRole("button", {
      name: "Confirmar y continuar",
    }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({
      MANUAL_RETURN_WITHOUT_TICKET: {
        authorizerUsername: "manager-return",
        authorizerPassword: "return-secret",
      },
      APPLY_SALE_DISCOUNT: {
        authorizerUsername: "manager-discount",
        authorizerPassword: "discount-secret",
      },
    }));
  });
});
