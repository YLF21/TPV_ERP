// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "../types";
import { SessionTopControls } from "./SessionTopControls";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

function renderControls(onPrepareShutdown?: () => Promise<boolean>) {
  render(
    <SessionTopControls
      locale="es"
      session={session}
      languageLabel="Idioma"
      shutdownLabel="Cerrar aplicación"
      changePasswordLabel="Cambiar contraseña"
      logoutLabel="Cerrar usuario"
      shutdownConfirmTitle="Cerrar aplicación"
      shutdownConfirmText="¿Deseas cerrar la aplicación?"
      noLabel="No"
      yesLabel="Sí"
      onLocaleChange={vi.fn()}
      onPrepareShutdown={onPrepareShutdown}
    />
  );
}

function openShutdownConfirmation() {
  fireEvent.click(screen.getByRole("button", { name: "Cerrar aplicación" }));
}

describe("SessionTopControls shutdown", () => {
  const closeApplication = vi.fn<() => Promise<void>>();

  afterEach(() => {
    cleanup();
    closeApplication.mockReset();
    delete window.tpvDesktop;
  });

  it("waits for successful preparation before closing once", async () => {
    let resolvePreparation!: (ready: boolean) => void;
    const onPrepareShutdown = vi.fn(() => new Promise<boolean>((resolve) => {
      resolvePreparation = resolve;
    }));
    window.tpvDesktop = { closeApplication };
    renderControls(onPrepareShutdown);
    openShutdownConfirmation();

    fireEvent.click(screen.getByRole("button", { name: "Sí" }));

    expect(onPrepareShutdown).toHaveBeenCalledTimes(1);
    expect(closeApplication).not.toHaveBeenCalled();
    resolvePreparation(true);
    await waitFor(() => expect(closeApplication).toHaveBeenCalledTimes(1));
  });

  it.each([
    ["blocked preparation", vi.fn().mockResolvedValue(false)],
    ["rejected preparation", vi.fn().mockRejectedValue(new Error("cleanup failed"))]
  ])("keeps the application open after %s", async (_label, onPrepareShutdown) => {
    window.tpvDesktop = { closeApplication };
    renderControls(onPrepareShutdown);
    openShutdownConfirmation();

    fireEvent.click(screen.getByRole("button", { name: "Sí" }));

    await waitFor(() => expect(onPrepareShutdown).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole("button", { name: "Cerrar aplicación" })).toBeInTheDocument());
    expect(closeApplication).not.toHaveBeenCalled();
  });

  it("guards concurrent confirmations while preparation is pending", async () => {
    let resolvePreparation!: (ready: boolean) => void;
    const onPrepareShutdown = vi.fn(() => new Promise<boolean>((resolve) => {
      resolvePreparation = resolve;
    }));
    window.tpvDesktop = { closeApplication };
    renderControls(onPrepareShutdown);
    openShutdownConfirmation();
    const confirm = screen.getByRole("button", { name: "Sí" });

    fireEvent.click(confirm);
    fireEvent.click(confirm);
    resolvePreparation(true);

    await waitFor(() => expect(closeApplication).toHaveBeenCalledTimes(1));
    expect(onPrepareShutdown).toHaveBeenCalledTimes(1);
  });

  it("does not prepare or close when the user chooses No", () => {
    const onPrepareShutdown = vi.fn().mockResolvedValue(true);
    window.tpvDesktop = { closeApplication };
    renderControls(onPrepareShutdown);
    openShutdownConfirmation();

    fireEvent.click(screen.getByRole("button", { name: "No" }));

    expect(onPrepareShutdown).not.toHaveBeenCalled();
    expect(closeApplication).not.toHaveBeenCalled();
  });
});
