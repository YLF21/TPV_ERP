// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SerialBaudRateSelect } from "./SerialBaudRateSelect";

const label = "Velocidad en baudios";

afterEach(() => cleanup());

describe("SerialBaudRateSelect", () => {
  it("selects a common rate through the ERP menu and reports a number once", () => {
    const onChange = vi.fn();
    render(<SerialBaudRateSelect label={label} value={9600} onChange={onChange} />);
    const input = screen.getByRole("combobox", { name: label });

    fireEvent.click(screen.getByRole("button", { name: label }));
    fireEvent.click(screen.getByRole("option", { name: "19200" }));

    expect(input).toHaveValue("19200");
    expect(onChange).toHaveBeenCalledOnce();
    expect(onChange).toHaveBeenCalledWith(19200);
  });

  it("shows a saved custom rate and keeps a manually entered rate across rerenders", () => {
    const onChange = vi.fn();
    const view = render(<SerialBaudRateSelect label={label} value={74880} onChange={onChange} />);
    const input = screen.getByRole("combobox", { name: label });

    expect(input).toHaveValue("74880");
    fireEvent.change(input, { target: { value: "14400" } });
    expect(onChange).toHaveBeenCalledWith(14400);
    view.rerender(<SerialBaudRateSelect label={label} value={14400} onChange={onChange} />);
    expect(input).toHaveValue("14400");

    view.rerender(<SerialBaudRateSelect label={label} value={57600} onChange={onChange} />);
    expect(input).toHaveValue("57600");
  });

  it("allows temporary empty text without changing configuration and restores it on blur", () => {
    const onChange = vi.fn();
    render(<><SerialBaudRateSelect label={label} value={9600} onChange={onChange} /><button>Outside</button></>);
    const input = screen.getByRole("combobox", { name: label });

    fireEvent.change(input, { target: { value: "" } });
    expect(input).toHaveValue("");
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.blur(input, { relatedTarget: screen.getByRole("button", { name: "Outside" }) });
    expect(input).toHaveValue("9600");
  });

  it("does not propagate invalid or redundant rates", () => {
    const onChange = vi.fn();
    render(<><SerialBaudRateSelect label={label} value={9600} onChange={onChange} /><button>Outside</button></>);
    const input = screen.getByRole("combobox", { name: label });

    for (const invalid of ["abc", "-9600", "9.6", "NaN", "0", "9007199254740992"]) {
      fireEvent.change(input, { target: { value: invalid } });
      expect(onChange).not.toHaveBeenCalled();
    }
    fireEvent.blur(input, { relatedTarget: screen.getByRole("button", { name: "Outside" }) });
    expect(input).toHaveValue("9600");

    fireEvent.change(input, { target: { value: "09600" } });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: "115200" } });
    fireEvent.change(input, { target: { value: "0115200" } });
    expect(onChange).toHaveBeenCalledOnce();
    expect(onChange).toHaveBeenCalledWith(115200);
  });
});
