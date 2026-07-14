// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { IndividualPaymentActions } from "./IndividualPaymentActions";

afterEach(cleanup);

const callbacks = () => ({ onCash: vi.fn(), onCard: vi.fn() });

describe("IndividualPaymentActions", () => {
  it("renders accessible payment actions with shortcuts and the pending explanation", () => {
    render(<IndividualPaymentActions disabled={false} busy={false} cardEnabled {...callbacks()} />);

    const cash = screen.getByRole("button", { name: /Efectivo/ });
    const card = screen.getByRole("button", { name: /Tarjeta/ });
    const pending = screen.getByRole("button", { name: /Pendiente cliente/ });
    expect(cash).toBeEnabled();
    expect(card).toBeEnabled();
    expect(pending).toBeDisabled();
    expect(within(cash).getByText("F10")).toBeInTheDocument();
    expect(within(card).getByText("F11")).toBeInTheDocument();
    expect(within(pending).getByText("F12")).toBeInTheDocument();
    expect(screen.getByTitle("Funcionalidad pendiente de definir")).toBe(
      pending,
    );
  });

  it("dispatches cash and card callbacks through DOM click events", () => {
    const handlers = callbacks();
    render(<IndividualPaymentActions disabled={false} busy={false} cardEnabled {...handlers} />);

    fireEvent.click(screen.getByRole("button", { name: /Efectivo/ }));
    fireEvent.click(screen.getByRole("button", { name: /Tarjeta/ }));

    expect(handlers.onCash).toHaveBeenCalledOnce();
    expect(handlers.onCard).toHaveBeenCalledOnce();
  });

  it("disables cash and card when the action bar is disabled", () => {
    const handlers = callbacks();
    render(<IndividualPaymentActions disabled busy={false} cardEnabled {...handlers} />);

    const cash = screen.getByRole("button", { name: /Efectivo/ });
    const card = screen.getByRole("button", { name: /Tarjeta/ });
    expect(cash).toBeDisabled();
    expect(card).toBeDisabled();
    fireEvent.click(cash);
    fireEvent.click(card);
    expect(handlers.onCash).not.toHaveBeenCalled();
    expect(handlers.onCard).not.toHaveBeenCalled();
  });

  it("disables cash and card while busy", () => {
    render(<IndividualPaymentActions disabled={false} busy cardEnabled {...callbacks()} />);

    expect(screen.getByRole("button", { name: /Efectivo/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Tarjeta/ })).toBeDisabled();
  });

  it("keeps cash enabled but disables card when card payments are unavailable", () => {
    render(<IndividualPaymentActions disabled={false} busy={false} cardEnabled={false} {...callbacks()} />);

    expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled();
    expect(screen.getByRole("button", { name: /Tarjeta/ })).toBeDisabled();
  });
});
