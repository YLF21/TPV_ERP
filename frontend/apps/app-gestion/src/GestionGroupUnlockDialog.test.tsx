/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type UserSession } from "@tpverp/app-common";
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
  it("unlocks with the password and never mounts the protected destination before success", async () => {
    request.mockResolvedValue({ group: "CONFIGURACION", unlockedAt: "2026-09-02T10:00:00Z" });
    const onUnlocked = vi.fn();
    render(<GestionGroupUnlockDialog group="CONFIGURACION" session={session} t={t} onUnlocked={onUnlocked} onLocked={vi.fn()} onCancel={vi.fn()} />);
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
    render(<GestionGroupUnlockDialog group="FISCAL" session={session} t={t} onUnlocked={vi.fn()} onLocked={onLocked} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("gestion.groupUnlock.password"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "gestion.groupUnlock.submit" }));
    await waitFor(() => expect(onLocked).toHaveBeenCalledOnce());
  });

  it("keeps keyboard focus inside the modal and closes with Escape", () => {
    const onCancel = vi.fn();
    render(<GestionGroupUnlockDialog group="SEGURIDAD" session={session} t={t} onUnlocked={vi.fn()} onLocked={vi.fn()} onCancel={onCancel} />);
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
