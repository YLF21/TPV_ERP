// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pressTouchNumericKey, TouchNumericKeypad } from "./TouchNumericKeypad";

afterEach(cleanup);

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
});
