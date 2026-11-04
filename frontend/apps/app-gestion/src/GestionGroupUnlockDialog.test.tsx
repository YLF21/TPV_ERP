/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, createTranslator, type UserSession } from "@tpverp/app-common";
import { GestionGroupUnlockDialog } from "./GestionGroupUnlockDialog";

const request = vi.hoisted(() => vi.fn());
vi.mock("@tpverp/app-common", async () => ({
  ...(await vi.importActual<typeof import("@tpverp/app-common")>("@tpverp/app-common")),
  apiRequest: request,
}));

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN"],
};
const t = (key: string) => key;

afterEach(() => { cleanup(); request.mockReset(); });

describe("GestionGroupUnlockDialog", () => {
  it("opens and closes the shared keyboard without submitting or exposing the password", () => {
    render(<GestionGroupUnlockDialog group="SEGURIDAD" session={session} locale="es" t={createTranslator("es")} onUnlocked={vi.fn()} onLocked={vi.fn()} onCancel={vi.fn()} />);
    const input = screen.getByLabelText("Contraseña o PIN");
    const open = screen.getByRole("button", { name: "Mostrar teclado" });
    expect(open).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();

    fireEvent.click(open);
    const keyboard = within(screen.getByRole("group", { name: "Teclado alfanumérico" }));
    expect(input).toHaveFocus();
    fireEvent.click(keyboard.getByRole("button", { name: "A" }));
    fireEvent.click(keyboard.getByRole("button", { name: "7" }));
    fireEvent.click(keyboard.getByRole("button", { name: "Borrar carácter" }));
    fireEvent.click(keyboard.getByRole("button", { name: "2" }));
    expect(input).toHaveValue("A2");
    expect(input).toHaveAttribute("type", "password");
    expect(screen.queryByText("A2")).not.toBeInTheDocument();
    expect(input).toHaveFocus();

    fireEvent.click(screen.getByRole("button", { name: "Cerrar teclado" }));
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
    expect(input).toHaveValue("A2");
    expect(input).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Mostrar teclado" }));
    expect(input).toHaveValue("A2");
    expect(request).not.toHaveBeenCalled();
  });

  it("uses the existing unlock request once from virtual Enter and disables input while checking", async () => {
    let finish!: (value: unknown) => void;
    request.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const onUnlocked = vi.fn();
    render(<GestionGroupUnlockDialog group="SEGURIDAD" session={session} locale="es" t={createTranslator("es")} onUnlocked={onUnlocked} onLocked={vi.fn()} onCancel={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Mostrar teclado" }));
    const keyboard = within(screen.getByRole("group", { name: "Teclado alfanumérico" }));
    fireEvent.click(keyboard.getByRole("button", { name: "8" }));
    fireEvent.click(keyboard.getByRole("button", { name: "Enter" }));
    expect(request).toHaveBeenCalledExactlyOnceWith("/auth/gestion-groups/SEGURIDAD/unlock", {
      method: "POST", token: "token", body: { password: "8" },
    });
    expect(onUnlocked).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Contraseña o PIN")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cerrar teclado" })).toBeDisabled();
    expect(keyboard.getByRole("button", { name: "Enter" })).toBeDisabled();
    await act(async () => finish({ group: "SEGURIDAD" }));
    expect(onUnlocked).toHaveBeenCalledOnce();
    expect(screen.getByLabelText("Contraseña o PIN")).toHaveValue("");
  });

  it("unlocks with the password and never mounts the protected destination before success", async () => {
    request.mockResolvedValue({ group: "CONFIGURACION", unlockedAt: "2026-09-02T10:00:00Z" });
    const onUnlocked = vi.fn();
    render(<GestionGroupUnlockDialog group="CONFIGURACION" session={session} locale="es" t={t} onUnlocked={onUnlocked} onLocked={vi.fn()} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("gestion.groupUnlock.password"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.groupUnlock.submit" }));
    await waitFor(() => expect(onUnlocked).toHaveBeenCalledOnce());
    expect(request).toHaveBeenCalledWith("/auth/gestion-groups/CONFIGURACION/unlock", expect.objectContaining({
      method: "POST", token: "token", body: { password: "1234" },
    }));
  });

  it("reports a locked group to the shell so local unlock state can be cleared", async () => {
    request.mockRejectedValue(new ApiError("locked", 403, { code: "GESTION_GROUP_LOCKED" }));
    const onLocked = vi.fn();
    render(<GestionGroupUnlockDialog group="FISCAL" session={session} locale="es" t={t} onUnlocked={vi.fn()} onLocked={onLocked} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("gestion.groupUnlock.password"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.groupUnlock.submit" }));
    await waitFor(() => expect(onLocked).toHaveBeenCalledOnce());
  });

  it("keeps keyboard focus inside the modal and closes with Escape", () => {
    const onCancel = vi.fn();
    render(<GestionGroupUnlockDialog group="SEGURIDAD" session={session} locale="es" t={t} onUnlocked={vi.fn()} onLocked={vi.fn()} onCancel={onCancel} />);
    fireEvent.change(screen.getByLabelText("gestion.groupUnlock.password"), { target: { value: "1234" } });
    const buttons = screen.getAllByRole("button");
    const first = buttons[0];
    const last = buttons.at(-1)!;
    last.focus();
    fireEvent.keyDown(last, { key: "Tab" });
    expect(first).toHaveFocus();
    fireEvent.keyDown(first, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledOnce();
  });
});
