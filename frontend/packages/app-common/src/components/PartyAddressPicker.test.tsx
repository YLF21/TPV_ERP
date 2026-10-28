// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { PartyAddressPicker } from "./PartyAddressPicker";

function Picker({ kind = "province", uppercase = true }: { kind?: "province" | "country"; uppercase?: boolean }) {
  const [value, setValue] = useState(kind === "province" ? "Las Palmas" : "ES");
  return <><PartyAddressPicker kind={kind} value={value} label={kind} locale="es" country="ES" uppercase={uppercase} onChange={setValue} /><output>{value}</output></>;
}
afterEach(cleanup);

describe("PartyAddressPicker uppercase location values", () => {
  it("matches a legacy mixed-case province without duplicating its option and stores the uppercase choice", () => {
    render(<Picker />);
    const input = screen.getByRole("combobox", { name: "province" });
    expect(input).toHaveValue("LAS PALMAS");
    fireEvent.focus(input);
    expect(screen.getAllByRole("option", { name: "LAS PALMAS" })).toHaveLength(1);
    expect(screen.getByRole("option", { name: "LAS PALMAS" })).toHaveAttribute("aria-selected", "true");
    fireEvent.change(input, { target: { value: "barc" } });
    expect(input).toHaveValue("BARC");
    fireEvent.click(screen.getByRole("option", { name: "BARCELONA" }));
    expect(input).toHaveValue("BARCELONA");
    expect(screen.getByRole("status")).toHaveTextContent("BARCELONA");
  });

  it("shows country names in uppercase while preserving the ISO code", () => {
    render(<Picker kind="country" />);
    const input = screen.getByRole("combobox", { name: "country" });
    expect(input).toHaveValue("ESPAÑA (ES)");
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "portu" } });
    expect(input).toHaveValue("PORTU");
    fireEvent.click(screen.getByRole("option", { name: "PORTUGAL (PT)" }));
    expect(input).toHaveValue("PORTUGAL (PT)");
    expect(screen.getByRole("status")).toHaveTextContent("PT");
  });

  it("preserves the original presentation for callers outside the three Venta forms", () => {
    render(<Picker uppercase={false} />);
    const input = screen.getByRole("combobox", { name: "province" });
    expect(input).toHaveValue("Las Palmas");
    fireEvent.focus(input);
    expect(screen.getByRole("option", { name: "Las Palmas" })).toHaveAttribute("aria-selected", "true");
  });
});
