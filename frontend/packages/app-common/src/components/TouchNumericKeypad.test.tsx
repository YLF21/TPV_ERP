// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useRef, useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { editTouchNumber, pressTouchNumericKey, TouchNumericKeypad } from "./TouchNumericKeypad";

afterEach(cleanup);

function KeypadField({ initialValue = "123.456", readOnly = false, disabled = false, numberInput = false }: {
  initialValue?: string; readOnly?: boolean; disabled?: boolean; numberInput?: boolean;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [value, setValue] = useState(initialValue);
  return <>
    <input aria-label="Valor" type={numberInput ? "number" : "text"} value={value} ref={inputRef}
      readOnly={readOnly} disabled={disabled} onChange={(event) => setValue(event.target.value)} />
    <output data-testid="draft-value">{value}</output>
    <TouchNumericKeypad value={value} inputRef={inputRef} onChange={setValue} allowDecimal allowNegative
      maximumFractionDigits={3} replaceOnFirstKey={numberInput} ariaLabel="Números" clearLabel="Limpiar"
      backspaceLabel="Retroceso" signLabel="Cambiar signo" />
  </>;
}

describe("TouchNumericKeypad", () => {
  it("edits integer values and supports clear and backspace", () => {
    expect(pressTouchNumericKey("12", "3", false)).toBe("123");
    expect(pressTouchNumericKey("12", "BACKSPACE", false)).toBe("1");
    expect(pressTouchNumericKey("12", "CLEAR", false)).toBe("");
    expect(pressTouchNumericKey("12", "DECIMAL", false)).toBe("12");
  });

  it("limits decimal values to two fraction digits", () => {
    expect(pressTouchNumericKey("", "DECIMAL", true)).toBe("0.");
    expect(pressTouchNumericKey("1.2", "5", true)).toBe("1.25");
    expect(pressTouchNumericKey("1.25", "9", true)).toBe("1.25");
  });

  it("opts in to signed values and three decimal places without changing payment defaults", () => {
    expect(pressTouchNumericKey("12", "SIGN", false)).toBe("12");
    expect(pressTouchNumericKey("12", "SIGN", false, 2, true)).toBe("-12");
    expect(pressTouchNumericKey("-12", "SIGN", false, 2, true)).toBe("12");
    expect(pressTouchNumericKey("", "SIGN", true, 3, true)).toBe("-");
    expect(pressTouchNumericKey("-", "DECIMAL", true, 3, true)).toBe("-0.");
    expect(pressTouchNumericKey("-0", "2", true, 3, true)).toBe("-2");
    expect(pressTouchNumericKey("-1.25", "9", true, 3, true)).toBe("-1.259");
    expect(pressTouchNumericKey("-1.259", "9", true, 3, true)).toBe("-1.259");
    expect(pressTouchNumericKey("1", "DECIMAL", true, 0)).toBe("1");
  });

  it("exposes the sign only when enabled and disables every action while busy", () => {
    const onChange = vi.fn();
    const { rerender } = render(<TouchNumericKeypad value="1.25" allowDecimal allowNegative maximumFractionDigits={3} ariaLabel="Números" clearLabel="Limpiar" backspaceLabel="Retroceso" signLabel="Cambiar signo" onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: "9" }));
    expect(onChange).toHaveBeenLastCalledWith("1.259");
    fireEvent.click(screen.getByRole("button", { name: "Cambiar signo" }));
    expect(onChange).toHaveBeenLastCalledWith("-1.25");
    rerender(<TouchNumericKeypad value="1.25" allowDecimal allowNegative disabled ariaLabel="Números" clearLabel="Limpiar" backspaceLabel="Retroceso" onChange={onChange} />);
    expect(screen.getAllByRole("button").every((button) => button.hasAttribute("disabled"))).toBe(true);
    onChange.mockClear();
    fireEvent.click(screen.getByRole("button", { name: "9" }));
    expect(onChange).not.toHaveBeenCalled();
  });

  it("exposes large explicit buttons without submitting the parent form", () => {
    const onChange = vi.fn();
    render(
      <form onSubmit={vi.fn()}>
        <TouchNumericKeypad
          value="1"
          allowDecimal
          ariaLabel="Teclado numérico"
          clearLabel="Borrar todo"
          backspaceLabel="Borrar último dígito"
          onChange={onChange}
        />
      </form>,
    );

    fireEvent.click(screen.getByRole("button", { name: "2" }));
    expect(onChange).toHaveBeenCalledWith("12");
    expect(screen.getByRole("button", { name: "Borrar todo" })).toHaveAttribute("type", "button");
  });

  it("uses the approved 4 by 4 arrangement with a double-width zero and signed utilities", () => {
    render(<TouchNumericKeypad value="" allowDecimal allowNegative ariaLabel="Números" clearLabel="Limpiar"
      backspaceLabel="Retroceso" signLabel="Cambiar signo" onChange={vi.fn()} />);
    const positions = Object.fromEntries(screen.getAllByRole<HTMLButtonElement>("button")
      .map((button) => [button.dataset.numericKey, button.style.gridArea]));
    expect(positions).toEqual({
      "7": "1 / 1", "8": "1 / 2", "9": "1 / 3", BACKSPACE: "1 / 4",
      "4": "2 / 1", "5": "2 / 2", "6": "2 / 3", CLEAR: "2 / 4",
      "1": "3 / 1", "2": "3 / 2", "3": "3 / 3", SIGN: "3 / 4 / 5 / 5",
      "0": "4 / 1 / 5 / 3", DECIMAL: "4 / 3",
    });
    expect(screen.getByRole("button", { name: "Retroceso" }).querySelector('svg[aria-hidden="true"]')).not.toBeNull();
  });

  it("spans the utilities across two rows when unsigned and keeps the unavailable comma visible", () => {
    const onChange = vi.fn();
    const { rerender } = render(<TouchNumericKeypad value="12" ariaLabel="Números" clearLabel="Limpiar"
      backspaceLabel="Retroceso" onChange={onChange} />);
    expect(screen.getByRole<HTMLButtonElement>("button", { name: "Retroceso" }).style.gridArea).toBe("1 / 4 / 3 / 5");
    expect(screen.getByRole<HTMLButtonElement>("button", { name: "Limpiar" }).style.gridArea).toBe("3 / 4 / 5 / 5");
    expect(screen.queryByRole("button", { name: "±" })).toBeNull();
    const decimal = screen.getByRole("button", { name: "," });
    expect(decimal).toBeDisabled();
    expect(decimal).toHaveTextContent(",");
    fireEvent.click(decimal);
    expect(onChange).not.toHaveBeenCalled();
    rerender(<TouchNumericKeypad value="12" allowDecimal maximumFractionDigits={0} ariaLabel="Números"
      clearLabel="Limpiar" backspaceLabel="Retroceso" onChange={onChange} />);
    expect(screen.getByRole("button", { name: "," })).toBeDisabled();
  });

  it("replaces a selected value and keeps the next insertion at the resulting cursor", () => {
    render(<KeypadField />);
    const field = screen.getByRole<HTMLInputElement>("textbox", { name: "Valor" });
    field.focus();
    field.select();
    fireEvent.click(screen.getByRole("button", { name: "2" }));
    expect(field).toHaveValue("2");
    expect(field.selectionStart).toBe(1);
    fireEvent.click(screen.getByRole("button", { name: "," }));
    fireEvent.click(screen.getByRole("button", { name: "9" }));
    expect(field).toHaveValue("2.9");
    expect(field.selectionStart).toBe(3);
    expect(document.activeElement).toBe(field);
  });

  it("replaces decimal selections, deletes selected text, and preserves three fractional digits", () => {
    render(<KeypadField initialValue="123,456" />);
    const field = screen.getByRole<HTMLInputElement>("textbox", { name: "Valor" });
    field.focus();
    field.setSelectionRange(5, 6);
    fireEvent.click(screen.getByRole("button", { name: "9" }));
    expect(field).toHaveValue("123.496");
    expect(field.selectionStart).toBe(6);
    field.setSelectionRange(0, 2);
    fireEvent.click(screen.getByRole("button", { name: "Retroceso" }));
    expect(field).toHaveValue("3.496");
    expect(field.selectionStart).toBe(0);
    field.setSelectionRange(5, 5);
    fireEvent.click(screen.getByRole("button", { name: "8" }));
    expect(field).toHaveValue("3.496");
    expect(field.selectionStart).toBe(5);
  });

  it("preserves the cursor on sign toggles and ignores a second decimal separator", () => {
    render(<KeypadField initialValue="123.456" />);
    const field = screen.getByRole<HTMLInputElement>("textbox", { name: "Valor" });
    field.focus();
    field.setSelectionRange(2, 2);
    fireEvent.click(screen.getByRole("button", { name: "Cambiar signo" }));
    expect(field).toHaveValue("-123.456");
    expect(field.selectionStart).toBe(3);
    fireEvent.click(screen.getByRole("button", { name: "Cambiar signo" }));
    expect(field).toHaveValue("123.456");
    expect(field.selectionStart).toBe(2);
    fireEvent.click(screen.getByRole("button", { name: "," }));
    expect(field).toHaveValue("123.456");
    expect(field.selectionStart).toBe(2);
  });

  it.each([{ readOnly: true }, { disabled: true }])("does not edit a protected target field: %j", (state) => {
    render(<KeypadField {...state} />);
    fireEvent.click(screen.getByRole("button", { name: "9" }));
    fireEvent.click(screen.getByRole("button", { name: "Limpiar" }));
    fireEvent.click(screen.getByRole("button", { name: "Cambiar signo" }));
    expect(screen.getByTestId("draft-value")).toHaveTextContent("123.456");
  });

  it("retains initial replacement for native number inputs without losing an incomplete decimal draft", () => {
    render(<KeypadField initialValue="123.456" numberInput />);
    fireEvent.click(screen.getByRole("button", { name: "2" }));
    expect(screen.getByTestId("draft-value")).toHaveTextContent("2");
    fireEvent.click(screen.getByRole("button", { name: "," }));
    expect(screen.getByTestId("draft-value")).toHaveTextContent("2.");
    fireEvent.click(screen.getByRole("button", { name: "9" }));
    expect(screen.getByTestId("draft-value")).toHaveTextContent("2.9");
  });

  it("normalizes comma decimals without treating them as thousands or exceeding configured precision", () => {
    expect(pressTouchNumericKey("1,25", "9", true, 3)).toBe("1.259");
    expect(pressTouchNumericKey("1,259", "9", true, 3)).toBe("1.259");
    expect(editTouchNumber("1,259", "5", 4, 5, true, 3, false)).toEqual({ value: "1.255", cursor: 5 });
    expect(editTouchNumber("123", "DECIMAL", 3, 3, true, 0, false)).toEqual({ value: "123", cursor: 3 });
  });
});
