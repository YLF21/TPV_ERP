// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";

import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { HardwareBridge } from "../hardware/hardware";
import { createTranslator } from "../i18n/LocalizedMessages";
import { SerialPortSelect } from "./SerialPortSelect";

const t = createTranslator("es");
const label = "Puerto de prueba";

function renderSelector({
  value = "",
  onChange = vi.fn(),
  listSerialPorts,
  available = true,
}: {
  value?: string;
  onChange?: (value: string) => void;
  listSerialPorts?: HardwareBridge["listSerialPorts"];
  available?: boolean;
} = {}) {
  return render(<SerialPortSelect t={t} label={label} value={value} onChange={onChange}
    listSerialPorts={listSerialPorts} available={available} />);
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("SerialPortSelect", () => {
  it("keeps refresh disabled during detection and does not change the selection when detection resolves", async () => {
    let resolvePorts!: (result: Awaited<ReturnType<HardwareBridge["listSerialPorts"]>>) => void;
    const listSerialPorts = vi.fn(() => new Promise<Awaited<ReturnType<HardwareBridge["listSerialPorts"]>>>(resolve => {
      resolvePorts = resolve;
    }));
    const onChange = vi.fn();

    renderSelector({ value: "COM7", onChange, listSerialPorts });

    const refreshButton = screen.getByRole("button", { name: t("hardware.refreshSerialPorts") });
    expect(refreshButton).toBeDisabled();
    expect(listSerialPorts).toHaveBeenCalledOnce();

    await act(async () => {
      resolvePorts({ ok: true, ports: [{ path: "COM4" }] });
    });

    expect(refreshButton).toBeEnabled();
    expect(screen.getByRole("button", { name: label })).toHaveTextContent("COM7");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("retains the configured port after a structured failure and allows retry", async () => {
    const listSerialPorts = vi.fn()
      .mockResolvedValueOnce({ ok: false as const, code: "HARDWARE_UNAVAILABLE" as const, message: "No disponible" })
      .mockResolvedValueOnce({ ok: true as const, ports: [{ path: "COM4" }] });
    const onChange = vi.fn();

    renderSelector({ value: "COM7", onChange, listSerialPorts });

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(t("hardware.serialPortsFailed")));
    expect(screen.getByRole("button", { name: label })).toHaveTextContent("COM7");
    const refreshButton = screen.getByRole("button", { name: t("hardware.refreshSerialPorts") });
    expect(refreshButton).toBeEnabled();

    fireEvent.click(refreshButton);
    await waitFor(() => expect(listSerialPorts).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole("status")).toBeNull());
    expect(screen.getByRole("button", { name: label })).toHaveTextContent("COM7 (no detectado)");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("does not enumerate when unavailable and keeps the saved port visible", () => {
    const listSerialPorts = vi.fn(async () => ({ ok: true as const, ports: [{ path: "COM4" }] }));

    renderSelector({ value: "COM7", listSerialPorts, available: false });

    expect(listSerialPorts).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: label })).toHaveTextContent("COM7");
    expect(screen.getByRole("button", { name: t("hardware.refreshSerialPorts") })).toBeDisabled();
  });

  it("changes the value only when the user selects a COM port with the keyboard", async () => {
    const listSerialPorts = vi.fn(async () => ({ ok: true as const, ports: [{ path: "COM8" }] }));
    const onChange = vi.fn();

    renderSelector({ onChange, listSerialPorts });

    await waitFor(() => expect(listSerialPorts).toHaveBeenCalledOnce());
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.keyDown(screen.getByRole("button", { name: label }), { key: "ArrowDown" });
    const placeholderOption = screen.getByRole("option", { name: t("hardware.chooseSerialPort") });
    fireEvent.keyDown(placeholderOption, { key: "ArrowDown" });
    const comOption = screen.getByRole("option", { name: "COM8" });
    await waitFor(() => expect(document.activeElement).toBe(comOption));
    fireEvent.keyDown(comOption, { key: "Enter" });

    expect(onChange).toHaveBeenCalledOnce();
    expect(onChange).toHaveBeenCalledWith("COM8");
  });
});
