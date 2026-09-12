// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { useRef, useState } from "react";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { editTouchText, TouchAlphaKeyboard } from "./TouchAlphaKeyboard";

afterEach(cleanup);

function Editor({ type = "text" }: { type?: "text" | "password" | "email" }) {
  const [value, setValue] = useState("ABCD");
  const ref = useRef<HTMLInputElement>(null);
  return <form>
    <input aria-label="Entrada" type={type} ref={ref} value={value} onChange={(event) => setValue(event.target.value)} />
    <TouchAlphaKeyboard locale="es" value={value} onChange={setValue} inputRef={ref} maxLength={6} />
  </form>;
}

function TextAreaEditor({ readOnly = false }: { readOnly?: boolean }) {
  const [value, setValue] = useState("AB\nCD");
  const ref = useRef<HTMLTextAreaElement>(null);
  return <>
    <textarea aria-label="Comentario" ref={ref} value={value} readOnly={readOnly} onChange={(event) => setValue(event.target.value)} />
    <TouchAlphaKeyboard locale="es" value={value} onChange={setValue} inputRef={ref} />
  </>;
}

describe("TouchAlphaKeyboard", () => {
  it("replaces selection, inserts at the caret and limits text without discarding its suffix", () => {
    expect(editTouchText("ABCD", "X", 1, 3)).toEqual({ value: "AXD", cursor: 2 });
    expect(editTouchText("ABCD", "X", 2, 2)).toEqual({ value: "ABXCD", cursor: 3 });
    expect(editTouchText("ABCD", "X", 2, 2, 4)).toEqual({ value: "ABCD", cursor: 2 });
    expect(editTouchText("ABCD", "X", 1, 3, 4)).toEqual({ value: "AXD", cursor: 2 });
    expect(editTouchText("ABCD", "CLEAR", 1, 2)).toEqual({ value: "", cursor: 0 });
    expect(editTouchText("A😀B", "BACKSPACE", 3)).toEqual({ value: "AB", cursor: 1 });
    expect(editTouchText("ABCD", "BACKSPACE", 1, 3)).toEqual({ value: "AD", cursor: 1 });
  });

  it("keeps scanning focus and caret after replacing selected text", () => {
    render(<Editor />);
    const input = screen.getByLabelText<HTMLInputElement>("Entrada");
    input.focus();
    input.setSelectionRange(1, 3);
    fireEvent.pointerDown(screen.getByRole("button", { name: "X" }));
    fireEvent.click(screen.getByRole("button", { name: "X" }));
    expect(input).toHaveValue("AXD");
    expect(input).toHaveFocus();
    expect(input.selectionStart).toBe(2);
    fireEvent.click(screen.getByRole("button", { name: "1" }));
    expect(input).toHaveValue("AX1D");
    expect(input.selectionStart).toBe(3);
    fireEvent.change(input, { target: { value: "SCANNED" } });
    expect(input).toHaveValue("SCANNED");
  });

  it("supports lower case, space, symbols and password masking without rendering the entered text", () => {
    render(<Editor type="password" />);
    const input = screen.getByLabelText<HTMLInputElement>("Entrada");
    fireEvent.click(screen.getByRole("button", { name: "Limpiar" }));
    fireEvent.click(screen.getByRole("button", { name: "Mayúsculas" }));
    fireEvent.click(screen.getByRole("button", { name: "a" }));
    fireEvent.click(screen.getByRole("button", { name: "Espacio" }));
    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    fireEvent.click(screen.getByRole("button", { name: "$" }));
    expect(input).toHaveAttribute("type", "password");
    expect(input).toHaveValue("a $");
    expect(screen.getByRole("group", { name: "Teclado alfanumérico" })).not.toHaveTextContent("a $");
    fireEvent.click(screen.getByRole("button", { name: "Borrar carácter" }));
    expect(input).toHaveValue("a ");
  });

  it("has no submit buttons and does not edit while disabled", () => {
    const onChange = vi.fn();
    render(<TouchAlphaKeyboard locale="es" value="" onChange={onChange} disabled />);
    for (const button of screen.getAllByRole("button")) {
      expect(button).toHaveAttribute("type", "button");
      expect(button).toBeDisabled();
    }
    fireEvent.click(screen.getByRole("button", { name: "A" }));
    expect(onChange).not.toHaveBeenCalled();
  });

  it("edits native email inputs without using the unsupported selection API", () => {
    render(<Editor type="email" />);
    const input = screen.getByLabelText<HTMLInputElement>("Entrada");
    const setSelectionRange = vi.spyOn(input, "setSelectionRange");
    expect(input.selectionStart).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Z" }));
    expect(input).toHaveValue("ABCDZ");
    expect(input).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Borrar carácter" }));
    expect(input).toHaveValue("ABCD");
    expect(setSelectionRange).not.toHaveBeenCalled();
  });

  it("keeps email inputs usable when a key leaves the value unchanged", () => {
    render(<Editor type="email" />);
    const input = screen.getByLabelText<HTMLInputElement>("Entrada");
    const setSelectionRange = vi.spyOn(input, "setSelectionRange");
    fireEvent.change(input, { target: { value: "ABCDEF" } });
    fireEvent.click(screen.getByRole("button", { name: "7" }));
    expect(input).toHaveValue("ABCDEF");
    fireEvent.click(screen.getByRole("button", { name: "Limpiar" }));
    fireEvent.click(screen.getByRole("button", { name: "Limpiar" }));
    fireEvent.click(screen.getByRole("button", { name: "Borrar carácter" }));
    expect(input).toHaveValue("");
    expect(input).toHaveFocus();
    expect(setSelectionRange).not.toHaveBeenCalled();
  });

  it("keeps a single independent numeric pad available in letters and symbols modes", () => {
    render(<Editor />);
    const keyboard = screen.getByRole("group", { name: "Teclado alfanumérico" });
    const numberPad = keyboard.querySelector<HTMLElement>(".touch-alpha-keyboard-number-pad");
    expect(numberPad).not.toBeNull();
    if (!numberPad) throw new Error("Numeric pad missing");
    const expectedKeys = ["7", "8", "9", "4", "5", "6", "1", "2", "3", "0", ","];
    expect(within(numberPad).getAllByRole("button").map((button) => button.textContent)).toEqual(expectedKeys);
    for (const digit of "0123456789") {
      expect(within(keyboard).getAllByRole("button", { name: digit })).toHaveLength(1);
      expect(within(numberPad).getByRole("button", { name: digit })).toBeEnabled();
    }

    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    expect(within(numberPad).getAllByRole("button").map((button) => button.textContent)).toEqual(expectedKeys);
    for (const digit of "0123456789") {
      expect(within(keyboard).getAllByRole("button", { name: digit })).toHaveLength(1);
    }
  });

  it("reveals @ only in symbols mode and returns to letters without losing numeric editing or focus", () => {
    render(<Editor />);
    const input = screen.getByLabelText<HTMLInputElement>("Entrada");
    fireEvent.click(screen.getByRole("button", { name: "Limpiar" }));
    expect(screen.queryByRole("button", { name: "@" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    fireEvent.click(screen.getByRole("button", { name: "@" }));
    fireEvent.click(screen.getByRole("button", { name: "7" }));
    fireEvent.click(screen.getAllByRole("button", { name: "," })[0]);
    fireEvent.click(screen.getByRole("button", { name: "2" }));
    expect(input).toHaveValue("@7,2");
    expect(input).toHaveFocus();
    expect(input.selectionStart).toBe(4);

    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    expect(screen.queryByRole("button", { name: "@" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Q" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Ñ" }));
    expect(input).toHaveValue("@7,2Ñ");
    expect(input).toHaveFocus();
    expect(input.selectionStart).toBe(5);
  });

  it("retains punctuation, accented letters and the previous extended symbols", () => {
    render(<Editor />);
    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    for (const symbol of "-_.,/@#+!?()$%&*=:;'\"\\[]{}<>|~`^ÁÉÍÓÚÜ") {
      expect(screen.getAllByRole("button", { name: symbol }).length).toBeGreaterThan(0);
    }
    fireEvent.click(screen.getByRole("button", { name: "Mayúsculas" }));
    expect(screen.getByRole("button", { name: "á" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    expect(screen.getByRole("button", { name: "ñ" })).toBeEnabled();
  });

  it("edits a textarea selection with the numeric pad while preserving the caret and newlines", () => {
    render(<TextAreaEditor />);
    const textarea = screen.getByLabelText<HTMLTextAreaElement>("Comentario");
    textarea.focus();
    textarea.setSelectionRange(3, 4);
    fireEvent.click(screen.getByRole("button", { name: "1" }));
    fireEvent.click(screen.getByRole("button", { name: "," }));
    expect(textarea).toHaveValue("AB\n1,D");
    expect(textarea).toHaveFocus();
    expect(textarea.selectionStart).toBe(5);
    fireEvent.click(screen.getByRole("button", { name: "Borrar carácter" }));
    expect(textarea).toHaveValue("AB\n1D");
    expect(textarea.selectionStart).toBe(4);
  });

  it("does not modify readonly input through numbers, punctuation or utilities", () => {
    render(<TextAreaEditor readOnly />);
    fireEvent.click(screen.getByRole("button", { name: "9" }));
    fireEvent.click(screen.getByRole("button", { name: "," }));
    fireEvent.click(screen.getByRole("button", { name: "Borrar carácter" }));
    fireEvent.click(screen.getByRole("button", { name: "Limpiar" }));
    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    fireEvent.click(screen.getByRole("button", { name: "@" }));
    expect(screen.getByLabelText("Comentario")).toHaveValue("AB\nCD");
  });
});
