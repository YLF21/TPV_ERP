// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { ReportDateRangeFilter, isValidReportDate, reportDateRangeLabel } from "./ReportDateRangeFilter";
import type { ReportDateRange } from "./ReportDateRangeFilter";
import type { LocaleCode } from "../types";

afterEach(cleanup);

function mount(today = "2026-09-11", earliestDate = "2024-02-10", locale: LocaleCode = "es", inlineCustomRange = true) {
  const change = vi.fn();
  function Harness() {
    const [value, setValue] = useState<ReportDateRange>({ from: today, to: today, label: "", preset: "TODAY" });
    return <ReportDateRangeFilter locale={locale} today={today} earliestDate={earliestDate} value={value} inlineCustomRange={inlineCustomRange}
      onChange={(next) => { change(next); setValue(next); }} />;
  }
  return { ...render(<Harness />), change };
}

describe("shared report date range filter", () => {
  it.each([
    ["2024-02-29", true], ["2026-02-29", false], ["2026-04-31", false], ["2026-12-31", true],
    ["2026-13-01", false], ["", false], [undefined, false],
  ])("validates real calendar dates %s", (input, valid) => expect(isValidReportDate(input)).toBe(valid));

  it("uses the store day, previous day and Monday as the start of the current week", () => {
    const { change, container } = mount("2026-01-01");
    const summary = container.querySelector(".report-date-range-summary")!;
    const dates = () => [...summary.querySelectorAll("time > span")].map((date) => date.textContent);
    expect(dates()).toEqual(["01/01/2026"]);
    expect(summary.querySelector(".report-date-range-weekday")).toHaveTextContent("Jueves");
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    expect(change).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2025-12-31", to: "2025-12-31", preset: "YESTERDAY" }));
    expect(dates()).toEqual(["31/12/2025"]);
    fireEvent.click(screen.getByRole("button", { name: "Semana actual" }));
    expect(change).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2025-12-29", to: "2026-01-01", preset: "WEEK" }));
    expect(dates()).toEqual(["29/12/2025", "01/01/2026"]);
    fireEvent.click(screen.getByRole("button", { name: "Hoy" }));
    expect(change).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2026-01-01", to: "2026-01-01", preset: "TODAY" }));
    expect(dates()).toEqual(["01/01/2026"]);
    expect(screen.getByRole("button", { name: "Hoy" })).toHaveAttribute("aria-pressed", "true");
  });

  it("includes leap days and bounds historical options to the first document", () => {
    const { change } = mount();
    expect(screen.queryByRole("option", { name: "enero de 2024" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "Mes" }), { target: { value: "2024-02" } });
    expect(change).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2024-02-01", to: "2024-02-29", preset: "MONTH" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Trimestre" }), { target: { value: "2025-Q4" } });
    expect(change).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2025-10-01", to: "2025-12-31", preset: "QUARTER" }));
    expect(screen.getByRole("combobox", { name: "Mes" })).toHaveValue("");
    fireEvent.change(screen.getByRole("combobox", { name: "Año" }), { target: { value: "2025" } });
    expect(change).toHaveBeenLastCalledWith(expect.objectContaining({ from: "2025-01-01", to: "2025-12-31", preset: "YEAR" }));
    expect(screen.getByRole("combobox", { name: "Trimestre" })).toHaveValue("");
  });

  it.each([["Mes", "2026-09", "2026-09-01"], ["Trimestre", "2026-Q3", "2026-07-01"], ["Año", "2026", "2026-01-01"]])(
    "ends the current %s at the store day", (label, option, from) => {
      const { change } = mount();
      fireEvent.change(screen.getByRole("combobox", { name: label }), { target: { value: option } });
      expect(change).toHaveBeenLastCalledWith(expect.objectContaining({ from, to: "2026-09-11" }));
    });

  it("keeps inline dates as a draft until Apply and synchronizes presets and Escape", () => {
    const { change, container } = mount();
    expect(screen.queryByRole("button", { name: "Periodo personalizado" })).not.toBeInTheDocument();
    const from = screen.getByLabelText("Desde");
    const to = screen.getByLabelText("Hasta");
    expect(from).toHaveValue("2026-09-11");
    expect(to).toHaveValue("2026-09-11");
    fireEvent.change(from, { target: { value: "2026-08-01" } });
    fireEvent.change(to, { target: { value: "2026-07-31" } });
    expect(screen.getByRole("button", { name: "Aplicar" })).toBeDisabled();
    fireEvent.change(to, { target: { value: "2026-09-12" } });
    expect(screen.getByRole("button", { name: "Aplicar" })).toBeDisabled();
    fireEvent.change(to, { target: { value: "2026-08-31" } });
    expect(change).not.toHaveBeenCalled();
    expect(container.querySelector(".report-date-range-summary time")).toHaveAttribute("dateTime", "2026-09-11");
    fireEvent.submit(from.closest("form")!);
    expect(change).toHaveBeenLastCalledWith({ from: "2026-08-01", to: "2026-08-31", label: "", preset: "CUSTOM" });
    expect(screen.getByLabelText("Desde")).toHaveValue("2026-08-01");
    fireEvent.change(from, { target: { value: "2026-08-02" } });
    fireEvent.keyDown(from, { key: "Escape" });
    expect(from).toHaveValue("2026-08-01");
    expect(change).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Hoy" }));
    expect(from).toHaveValue("2026-09-11");
    expect(to).toHaveValue("2026-09-11");
    fireEvent.change(from, { target: { value: "2026-08-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Hoy" }));
    expect(from).toHaveValue("2026-09-11");
  });

  it("preserves the legacy custom period popover with validation, submit and Escape", () => {
    const { change } = mount("2026-09-11", "2024-02-10", "es", false);
    fireEvent.click(screen.getByRole("button", { name: "Periodo personalizado" }));
    const from = screen.getByLabelText("Desde");
    const to = screen.getByLabelText("Hasta");
    fireEvent.change(from, { target: { value: "2026-08-01" } });
    fireEvent.change(to, { target: { value: "2026-07-31" } });
    expect(screen.getByRole("button", { name: "Aplicar periodo" })).toBeDisabled();
    fireEvent.change(to, { target: { value: "2026-09-12" } });
    expect(screen.getByRole("button", { name: "Aplicar periodo" })).toBeDisabled();
    fireEvent.change(to, { target: { value: "2026-08-31" } });
    expect(change).not.toHaveBeenCalled();
    fireEvent.submit(from.closest("form")!);
    expect(change).toHaveBeenLastCalledWith({ from: "2026-08-01", to: "2026-08-31", label: "", preset: "CUSTOM" });
    expect(screen.queryByLabelText("Desde")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Periodo personalizado" }));
    expect(screen.getByLabelText("Desde")).toHaveValue("2026-08-01");
    fireEvent.keyDown(screen.getByLabelText("Desde"), { key: "Escape" });
    expect(change).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText("Desde")).not.toBeInTheDocument();
  });

  it("disables presets until server calendar metadata is available", () => {
    const { change } = mount("", "");
    screen.getAllByRole("button").forEach((button) => expect(button).toBeDisabled());
    screen.getAllByRole("combobox").forEach((select) => expect(select).toBeDisabled());
    expect(screen.getByLabelText("Desde")).toBeDisabled();
    expect(screen.getByLabelText("Hasta")).toBeDisabled();
    expect(change).not.toHaveBeenCalled();
  });

  it.each([["es", "Hoy", "Mes", "Aplicar"], ["en", "Today", "Month", "Apply"], ["zh", "今天", "月份", "应用"]] as const)(
    "reuses translated controls in %s", (locale, todayLabel, monthLabel, applyLabel) => {
      mount("2026-09-11", "2024-02-10", locale);
      expect(screen.getByRole("button", { name: todayLabel })).toBeEnabled();
      expect(screen.getByRole("combobox", { name: monthLabel })).toBeEnabled();
      expect(screen.getByRole("button", { name: applyLabel })).toBeEnabled();
      expect(reportDateRangeLabel({ from: "2026-09-11", to: "2026-09-11", label: "", preset: "TODAY" }, locale)).toBe(todayLabel);
    });
});
