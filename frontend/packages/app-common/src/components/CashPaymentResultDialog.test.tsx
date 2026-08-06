// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fireEvent, render } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { CashPaymentResultDialog } from "./CashPaymentResultDialog";

const tpvCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");

describe("CashPaymentResultDialog", () => {
  it.each([
    ["PRINTING", "Imprimiendo ticket...", 'role="status"'],
    ["PRINTED", "Ticket enviado a la impresora", 'role="status"'],
  ] as const)("renders the %s ticket print state", (printStatus, message, role) => {
    const html = renderToStaticMarkup(
      <CashPaymentResultDialog
        locale="es"
        ticketNumber="T-0042"
        totalCents={1543}
        printStatus={printStatus}
        onRetryPrint={vi.fn()}
        onFinish={vi.fn()}
      />,
    );

    expect(html).toContain(role);
    expect(html).toContain(message);
  });

  it("keeps payment completed and only offers print retry after hardware failure", () => {
    const html = renderToStaticMarkup(
      <CashPaymentResultDialog
        locale="es"
        ticketNumber="T-0042"
        totalCents={1543}
        printStatus="FAILED"
        onRetryPrint={vi.fn()}
        onFinish={vi.fn()}
      />,
    );

    expect(html).toContain("Pago completado");
    expect(html).toContain("El cobro se ha completado, pero no ha sido posible imprimir el ticket.");
    expect(html).toContain("Reintentar impresión");
    expect(html).not.toContain("Finalizar");
    expect(html).toContain('role="alert"');
  });

  it("does not render a print message when printing is skipped", () => {
    const html = renderToStaticMarkup(
      <CashPaymentResultDialog locale="es" ticketNumber="T-0042" totalCents={1543} printStatus="SKIPPED" onRetryPrint={vi.fn()} onFinish={vi.fn()} />,
    );

    expect(html).not.toContain("Imprimiendo ticket");
    expect(html).not.toContain("Ticket impreso");
    expect(html).not.toContain("Reintentar impresión");
  });

  it("uses the requested locale for ticket print feedback", () => {
    const html = renderToStaticMarkup(
      <CashPaymentResultDialog locale="en" ticketNumber="T-0042" totalCents={1543} printStatus="FAILED" onRetryPrint={vi.fn()} onFinish={vi.fn()} />,
    );

    expect(html).toContain("Payment has been completed, but the ticket could not be printed.");
    expect(html).toContain("Retry printing");
  });

  it("uses the compact rectangular ERP notice layout without a blocking backdrop", () => {
    expect(tpvCss).toMatch(/\.cash-payment-result-layer\s*{[^}]*pointer-events:\s*none;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog\s*{[^}]*width:\s*min\(420px,\s*calc\(100vw - 32px\)\)\s*!important;[^}]*padding:\s*0\s*!important;[^}]*border:\s*1px solid var\(--tpv-v3-line\)\s*!important;[^}]*border-radius:\s*4px\s*!important;[^}]*pointer-events:\s*auto;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog\s*>\s*header\s*{[^}]*min-height:\s*38px;[^}]*border-bottom:\s*1px solid var\(--tpv-v3-line\);/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary\s*>\s*div\s*{[^}]*min-height:\s*34px;[^}]*border-radius:\s*3px;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary strong\s*{[^}]*font-size:\s*16px;[^}]*font-variant-numeric:\s*tabular-nums;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-print-error\s*{[^}]*border-color:\s*#e4b5b5;[^}]*color:\s*#a12626;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-print-retry\s*{[^}]*min-height:\s*28px;[^}]*border-radius:\s*3px;/s);
  });

  it("shows the completed cash payment summary as a non-modal status notice", () => {
    const html = renderToStaticMarkup(
      <CashPaymentResultDialog
        ticketNumber="T-0042"
        totalCents={1543}
        receivedCents={2000}
        changeCents={457}
        onFinish={vi.fn()}
      />,
    );

    expect(html).toContain('role="region"');
    expect(html).not.toContain('role="dialog"');
    expect(html).not.toContain('aria-modal="true"');
    expect(html).not.toContain('autofocus=""');
    expect(html).toContain("Pago completado");
    expect(html).toContain("T-0042");
    expect(html).toContain("15,43");
    expect(html).toContain("Dinero recibido");
    expect(html).toContain("20,00");
    expect(html).toContain("Cambio");
    expect(html).toContain("4,57");
    expect(html).not.toContain("Finalizar");
  });

  it("dismisses on the next key without consuming that key", () => {
    const onFinish = vi.fn();
    const observed = vi.fn();
    render(<CashPaymentResultDialog ticketNumber="T-0042" totalCents={1543} printStatus="PRINTED" onFinish={onFinish} />);
    window.addEventListener("keydown", observed);
    fireEvent.keyDown(document.body, { key: "7" });
    window.removeEventListener("keydown", observed);

    expect(onFinish).toHaveBeenCalledOnce();
    expect(observed).toHaveBeenCalledOnce();
    expect((observed.mock.calls[0][0] as KeyboardEvent).defaultPrevented).toBe(false);
  });

  it("dismisses on a pointer action without blocking the underlying action", () => {
    const onFinish = vi.fn();
    const onBackgroundAction = vi.fn();
    const view = render(<>
      <button type="button" onPointerDown={onBackgroundAction}>Nueva acción</button>
      <CashPaymentResultDialog ticketNumber="T-0042" totalCents={1543} printStatus="PRINTED" onFinish={onFinish} />
    </>);

    fireEvent.pointerDown(view.getByRole("button", { name: "Nueva acción" }));
    expect(onFinish).toHaveBeenCalledOnce();
    expect(onBackgroundAction).toHaveBeenCalledOnce();
  });

  it("shows card metadata without cash-only received and change rows", () => {
    const html = renderToStaticMarkup(<CashPaymentResultDialog ticketNumber="T-9" totalCents={1234} method="Tarjeta" authorization="A-1" reference="R-1" onFinish={vi.fn()} />);
    expect(html).toContain("Método");
    expect(html).toContain("Autorización");
    expect(html).toContain("Referencia");
    expect(html).not.toContain("Dinero recibido");
    expect(html).not.toContain("Cambio");
  });

  it("waits for printing to settle and preserves retry when printing fails", () => {
    const onFinish = vi.fn();
    const onRetryPrint = vi.fn();
    const view = render(<CashPaymentResultDialog
      ticketNumber="T-0042"
      totalCents={1543}
      printStatus="PRINTING"
      onRetryPrint={onRetryPrint}
      onFinish={onFinish}
    />);

    fireEvent.keyDown(document.body, { key: "A" });
    expect(onFinish).not.toHaveBeenCalled();

    view.rerender(<CashPaymentResultDialog
      ticketNumber="T-0042"
      totalCents={1543}
      printStatus="FAILED"
      onRetryPrint={onRetryPrint}
      onFinish={onFinish}
    />);
    fireEvent.keyDown(document.body, { key: "B" });
    expect(onFinish).not.toHaveBeenCalled();
    fireEvent.click(view.getByRole("button", { name: "Reintentar impresión" }));
    expect(onRetryPrint).toHaveBeenCalledOnce();
    expect(onFinish).not.toHaveBeenCalled();
  });

  it("dismisses after printing succeeds when an interaction already occurred", () => {
    const onFinish = vi.fn();
    const view = render(<CashPaymentResultDialog ticketNumber="T-0042" totalCents={1543} printStatus="PRINTING" onFinish={onFinish} />);
    fireEvent.keyDown(document.body, { key: "A" });
    view.rerender(<CashPaymentResultDialog ticketNumber="T-0042" totalCents={1543} printStatus="PRINTED" onFinish={onFinish} />);
    expect(onFinish).toHaveBeenCalledOnce();
  });

  it("shows the issued voucher and keeps an independent print retry", () => {
    const onFinish = vi.fn();
    const onRetryVoucherPrint = vi.fn();
    const view = render(<CashPaymentResultDialog
      ticketNumber="R-1"
      totalCents={-2550}
      method="Vale"
      printStatus="PRINTED"
      issuedVoucher={{
        code: "VABC123",
        amount: "25.50",
        issuedAt: "2026-08-04T12:00:00Z",
        originTicketNumber: "R-1",
      }}
      voucherPrintStatus="FAILED"
      onRetryVoucherPrint={onRetryVoucherPrint}
      onFinish={onFinish}
    />);

    expect(view.getByText("VABC123")).toBeTruthy();
    expect(view.getByText("25,50")).toBeTruthy();
    fireEvent.keyDown(document.body, { key: "A" });
    expect(onFinish).not.toHaveBeenCalled();
    fireEvent.click(view.getByRole("button", { name: "Reintentar impresión del vale" }));
    expect(onRetryVoucherPrint).toHaveBeenCalledOnce();
  });

  it("does not dismiss until both ticket and voucher printing have settled", () => {
    const onFinish = vi.fn();
    const voucher = {
      code: "VABC123",
      amount: "25.50",
      issuedAt: "2026-08-04T12:00:00Z",
      originTicketNumber: "R-1",
    };
    const view = render(<CashPaymentResultDialog
      ticketNumber="R-1" totalCents={-2550} printStatus="PRINTED"
      issuedVoucher={voucher} voucherPrintStatus="PRINTING" onFinish={onFinish}
    />);
    fireEvent.keyDown(document.body, { key: "A" });
    expect(onFinish).not.toHaveBeenCalled();

    view.rerender(<CashPaymentResultDialog
      ticketNumber="R-1" totalCents={-2550} printStatus="PRINTED"
      issuedVoucher={voucher} voucherPrintStatus="PRINTED" onFinish={onFinish}
    />);
    expect(onFinish).toHaveBeenCalledOnce();
  });
});
