// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SaleCalculatorDialog,
  calculatorReducer,
  initialCalculatorState,
  type CalculatorAction,
} from "./SaleCalculatorDialog";

afterEach(cleanup);

function calculate(actions: CalculatorAction[]) {
  return actions.reduce(calculatorReducer, initialCalculatorState);
}

const digits = (value: string): CalculatorAction[] => [...value].map((digit) => (
  digit === "." ? { type: "DECIMAL" } : { type: "DIGIT", digit }
));

describe("SaleCalculatorDialog arithmetic", () => {
  it("adds, subtracts, multiplies and divides", () => {
    expect(calculate([...digits("12"), { type: "OPERATOR", operator: "ADD" }, ...digits("8"), { type: "EQUALS" }]).display).toBe("20");
    expect(calculate([...digits("12"), { type: "OPERATOR", operator: "SUBTRACT" }, ...digits("8"), { type: "EQUALS" }]).display).toBe("4");
    expect(calculate([...digits("12"), { type: "OPERATOR", operator: "MULTIPLY" }, ...digits("8"), { type: "EQUALS" }]).display).toBe("96");
    expect(calculate([...digits("12"), { type: "OPERATOR", operator: "DIVIDE" }, ...digits("8"), { type: "EQUALS" }]).display).toBe("1.5");
  });

  it("uses contextual percentage for additions and subtractions", () => {
    const surcharge = calculate([
      ...digits("200"), { type: "OPERATOR", operator: "ADD" },
      ...digits("10"), { type: "PERCENT" }, { type: "EQUALS" },
    ]);
    const discount = calculate([
      ...digits("200"), { type: "OPERATOR", operator: "SUBTRACT" },
      ...digits("10"), { type: "PERCENT" }, { type: "EQUALS" },
    ]);

    expect(surcharge.display).toBe("220");
    expect(discount.display).toBe("180");
  });

  it("converts the right operand to a fraction for multiplication and division percentages", () => {
    expect(calculate([
      ...digits("200"), { type: "OPERATOR", operator: "MULTIPLY" },
      ...digits("10"), { type: "PERCENT" }, { type: "EQUALS" },
    ]).display).toBe("20");
    expect(calculate([
      ...digits("200"), { type: "OPERATOR", operator: "DIVIDE" },
      ...digits("10"), { type: "PERCENT" }, { type: "EQUALS" },
    ]).display).toBe("2000");
  });

  it("chains operations and repeats the last equals operation", () => {
    const chained = calculate([
      ...digits("2"), { type: "OPERATOR", operator: "ADD" }, ...digits("3"),
      { type: "OPERATOR", operator: "MULTIPLY" }, ...digits("4"), { type: "EQUALS" },
    ]);
    const repeated = calculatorReducer(
      calculate([...digits("2"), { type: "OPERATOR", operator: "ADD" }, ...digits("3"), { type: "EQUALS" }]),
      { type: "EQUALS" },
    );

    expect(chained.display).toBe("20");
    expect(repeated.display).toBe("8");
  });

  it("starts a new calculation when a digit is entered after equals", () => {
    const completed = calculate([
      ...digits("2"), { type: "OPERATOR", operator: "ADD" },
      ...digits("3"), { type: "EQUALS" },
    ]);
    const newEntry = calculatorReducer(completed, { type: "DIGIT", digit: "7" });

    expect(newEntry.display).toBe("7");
    expect(newEntry.lastOperator).toBeNull();
    expect(calculatorReducer(newEntry, { type: "EQUALS" }).display).toBe("7");
  });

  it("reports division by zero without producing infinity", () => {
    const state = calculate([
      ...digits("8"), { type: "OPERATOR", operator: "DIVIDE" },
      { type: "DIGIT", digit: "0" }, { type: "EQUALS" },
    ]);

    expect(state.error).toBe(true);
    expect(state.display).toBe("Error");
  });
});

describe("SaleCalculatorDialog interaction", () => {
  it("accepts keyboard arithmetic and Enter", () => {
    render(<SaleCalculatorDialog locale="es" defaultTaxPercent={21} onClose={vi.fn()} />);
    const dialog = screen.getByRole("dialog");

    for (const key of ["1", "2", "+", "8", "Enter"]) fireEvent.keyDown(dialog, { key });

    expect(document.querySelector("output")?.textContent).toBe("20");
  });

  it("adds and removes the configured tax from the current display", () => {
    render(<SaleCalculatorDialog locale="es" defaultTaxPercent={21} onClose={vi.fn()} />);
    const dialog = screen.getByRole("dialog");
    for (const key of ["1", "0", "0"]) fireEvent.keyDown(dialog, { key });

    expect(screen.getByText("121,00")).toBeTruthy();
    expect(screen.getByText("82,64")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Añadir impuesto" }));
    expect(document.querySelector("output")?.textContent).toBe("121,00");
  });

  it("closes with Escape", () => {
    const onClose = vi.fn();
    render(<SaleCalculatorDialog locale="es" defaultTaxPercent={7} onClose={onClose} />);

    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });

    expect(onClose).toHaveBeenCalledOnce();
  });

  it("uses generic tax wording and the product tax percentage", () => {
    render(<SaleCalculatorDialog locale="es" defaultTaxPercent={10} onClose={vi.fn()} />);

    expect(screen.getByDisplayValue("10")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Añadir impuesto" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Quitar impuesto" })).toBeTruthy();
    expect(screen.queryByText(/IVA|IGIC/i)).toBeNull();
    expect(screen.queryByText(/Teclado:/i)).toBeNull();
  });

  it("keeps the previous 21 percent default when there is no selected product", () => {
    render(<SaleCalculatorDialog locale="es" onClose={vi.fn()} />);

    expect(screen.getByDisplayValue("21")).toBeTruthy();
  });
});
