// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ManualCardReferenceDialog } from "./ManualCardReferenceDialog";

afterEach(cleanup);

describe("ManualCardReferenceDialog", () => {
  it("rejects blank references and confirms a trimmed reference", () => {
    const onConfirm = vi.fn();
    render(<ManualCardReferenceDialog busy={false} onCancel={vi.fn()} onConfirm={onConfirm} />);

    const input = screen.getByRole("textbox", { name: "Referencia obligatoria" });
    const confirm = screen.getByRole("button", { name: "Confirmar" });
    expect(screen.getByRole("dialog", { name: "Cobro con tarjeta manual" })).toBeVisible();
    expect(confirm).toBeDisabled();

    fireEvent.change(input, { target: { value: "   " } });
    expect(confirm).toBeDisabled();
    fireEvent.change(input, { target: { value: " REF-1 " } });
    fireEvent.click(confirm);

    expect(onConfirm).toHaveBeenCalledWith("REF-1");
  });
});
