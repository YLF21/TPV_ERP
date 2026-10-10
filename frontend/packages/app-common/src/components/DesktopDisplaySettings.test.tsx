// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DesktopDisplaySettings } from "./DesktopDisplaySettings";

function desktopDisplay() {
  const display = {
    load: vi.fn().mockResolvedValue({ ok: true, mode: "FULLSCREEN" }),
    setMode: vi.fn().mockImplementation(async (mode: string) => ({ ok: true, mode }))
  };
  window.tpvDesktop = { closeApplication: vi.fn(), display };
  return display;
}

async function selectMode(label: string) {
  const control = screen.getByRole("button", { name: "Formato de ventana" });
  await waitFor(() => expect(control).toBeEnabled());
  fireEvent.click(control);
  fireEvent.click(screen.getByRole("option", { name: label }));
  return control;
}

describe("DesktopDisplaySettings", () => {
  afterEach(() => {
    cleanup();
    delete window.tpvDesktop;
  });

  it("loads the real desktop mode and applies changes in both directions immediately", async () => {
    const display = desktopDisplay();
    display.load.mockResolvedValue({ ok: true, mode: "WINDOWED" });
    render(<DesktopDisplaySettings locale="es" />);
    const control = screen.getByRole("button", { name: "Formato de ventana" });
    await waitFor(() => expect(control).toHaveTextContent("Ventana"));
    expect(display.load).toHaveBeenCalledOnce();

    await selectMode("Pantalla completa");
    await waitFor(() => expect(control).toHaveTextContent("Pantalla completa"));
    expect(display.setMode).toHaveBeenLastCalledWith("FULLSCREEN");
    await selectMode("Ventana");
    await waitFor(() => expect(control).toHaveTextContent("Ventana"));
    expect(display.setMode).toHaveBeenLastCalledWith("WINDOWED");
  });

  it("waits for the native change before updating the selection and blocks repeated requests", async () => {
    const display = desktopDisplay();
    let finish!: (value: { ok: true; mode: "WINDOWED" }) => void;
    display.setMode.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    render(<DesktopDisplaySettings locale="es" />);
    const control = await selectMode("Ventana");
    expect(control).toBeDisabled();
    expect(control).toHaveTextContent("Pantalla completa");
    expect(screen.getByRole("status")).toHaveTextContent("Aplicando…");
    expect(display.setMode).toHaveBeenCalledOnce();

    finish({ ok: true, mode: "WINDOWED" });
    await waitFor(() => expect(control).toBeEnabled());
    expect(control).toHaveTextContent("Ventana");
  });

  it.each(["result", "rejection"])("preserves the current mode and allows retry after a %s failure", async (failure) => {
    const display = desktopDisplay();
    if (failure === "result") display.setMode.mockResolvedValueOnce({ ok: false, code: "WRITE_FAILED" });
    else display.setMode.mockRejectedValueOnce(new Error("IPC failed"));
    render(<DesktopDisplaySettings locale="es" />);
    const control = await selectMode("Ventana");
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo cambiar el formato");
    expect(control).toHaveTextContent("Pantalla completa");
    expect(control).toBeEnabled();

    await selectMode("Ventana");
    await waitFor(() => expect(control).toHaveTextContent("Ventana"));
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("disables the selector after a load failure instead of guessing the current mode", async () => {
    const display = desktopDisplay();
    display.load.mockRejectedValue(new Error("IPC failed"));
    render(<DesktopDisplaySettings locale="es" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo consultar el formato");
    expect(screen.getByRole("button", { name: "Formato de ventana" })).toBeDisabled();
    expect(display.setMode).not.toHaveBeenCalled();
  });

  it("refreshes the actual mode if a failed native change could not be rolled back", async () => {
    const display = desktopDisplay();
    display.load.mockResolvedValueOnce({ ok: true, mode: "FULLSCREEN" })
      .mockResolvedValue({ ok: true, mode: "WINDOWED" });
    display.setMode.mockResolvedValue({ ok: false, code: "DISPLAY_CHANGE_FAILED" });
    render(<DesktopDisplaySettings locale="es" />);
    const control = await selectMode("Ventana");
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(control).toHaveTextContent("Ventana");
  });

  it("explains desktop availability when opened in a browser", () => {
    render(<DesktopDisplaySettings locale="es" />);
    expect(screen.getByText("Disponible en la aplicación de escritorio.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Formato de ventana" })).toBeDisabled();
  });

  it.each([
    ["en", "Window mode", "Full screen"],
    ["zh", "窗口模式", "全屏"]
  ] as const)("localizes the selector in %s", async (locale, label, option) => {
    desktopDisplay();
    render(<DesktopDisplaySettings locale={locale} />);
    await waitFor(() => expect(screen.getByRole("button", { name: label })).toHaveTextContent(option));
  });
});
