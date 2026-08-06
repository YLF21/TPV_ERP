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
  it("focuses the current user's password when the modal opens", async () => {
    render(<SaleMutationAuthorizationDialog
      open
      locale="es"
      currentUsername="ADMIN"
      requirements={[{
        code: "TEMPORARY_PRICE_CHANGE",
        label: "Cambiar precio en esta venta",
        authorization: {
          mode: "CURRENT_PASSWORD",
          requireUsername: false,
          requirePassword: true,
        },
      }]}
      onCancel={vi.fn()}
      onConfirm={vi.fn()}
    />);

    await waitFor(() => expect(screen.getByLabelText(/Tu contrase/i)).toHaveFocus());
  });

  it("focuses the authorizing username first for delegated authorization", async () => {
    render(<SaleMutationAuthorizationDialog
      open
      locale="es"
      currentUsername="CAJERO"
      requirements={[{
        code: "TEMPORARY_PRICE_CHANGE",
        label: "Cambiar precio en esta venta",
        authorization: {
          mode: "DELEGATED",
          requireUsername: true,
          requirePassword: true,
        },
      }]}
      onCancel={vi.fn()}
      onConfirm={vi.fn()}
    />);

    await waitFor(() => expect(screen.getByLabelText("Usuario autorizador")).toHaveFocus());
  });

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

  it("confirms with Enter and suppresses a synchronous duplicate submission", async () => {
    const onConfirm = vi.fn();
    render(<SaleMutationAuthorizationDialog
      open
      locale="es"
      currentUsername="ADMIN"
      requirements={[{
        code: "REFUND_TENDER_OVERRIDE",
        label: "Devolver mediante una forma de pago distinta a la original",
        authorization: {
          mode: "CURRENT_PASSWORD",
          requireUsername: false,
          requirePassword: true,
        },
      }]}
      onCancel={vi.fn()}
      onConfirm={onConfirm}
    />);

    const password = await screen.findByLabelText(/Tu contraseña/i);
    fireEvent.change(password, { target: { value: "secret" } });
    const form = password.closest("form");
    expect(form).not.toBeNull();
    fireEvent.submit(form!);
    fireEvent.submit(form!);

    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
    expect(onConfirm).toHaveBeenCalledWith({
      REFUND_TENDER_OVERRIDE: {
        authorizerPassword: "secret",
      },
    });
  });
});
