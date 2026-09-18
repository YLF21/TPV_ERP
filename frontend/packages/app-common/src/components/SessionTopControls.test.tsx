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

function renderControls(
  onPrepareShutdown?: () => Promise<boolean>,
  onBrowserClose?: () => void | Promise<void>,
  exitBlocked = false,
  onLogout = vi.fn(),
) {
  return render(
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
      onBrowserClose={onBrowserClose}
      exitBlocked={exitBlocked}
      onLogout={onLogout}
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

  it("silently blocks shutdown and logout without opening the confirmation", () => {
    const onPrepareShutdown = vi.fn().mockResolvedValue(true);
    const onLogout = vi.fn();
    window.tpvDesktop = { closeApplication };
    renderControls(onPrepareShutdown, undefined, true, onLogout);
    openShutdownConfirmation();
    fireEvent.click(screen.getByRole("button", { name: "ADMIN" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Cerrar usuario" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(onPrepareShutdown).not.toHaveBeenCalled();
    expect(closeApplication).not.toHaveBeenCalled();
    expect(onLogout).not.toHaveBeenCalled();
  });

  it("cancels a pending shutdown if exit becomes blocked and permits a fresh attempt afterwards", async () => {
    let resolvePreparation!: (ready: boolean) => void;
    const onPrepareShutdown = vi.fn(() => new Promise<boolean>((resolve) => {
      resolvePreparation = resolve;
    }));
    window.tpvDesktop = { closeApplication };
    const view = (exitBlocked: boolean) => <SessionTopControls
      locale="es" session={session} languageLabel="Idioma" shutdownLabel="Cerrar aplicación"
      changePasswordLabel="Cambiar contraseña" logoutLabel="Cerrar usuario"
      shutdownConfirmTitle="Cerrar aplicación" shutdownConfirmText="¿Deseas cerrar la aplicación?"
      noLabel="No" yesLabel="Sí" onLocaleChange={vi.fn()}
      onPrepareShutdown={onPrepareShutdown} exitBlocked={exitBlocked}
    />;
    const { rerender } = render(view(false));
    openShutdownConfirmation();
    fireEvent.click(screen.getByRole("button", { name: "Sí" }));
    rerender(view(true));
    resolvePreparation(true);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(closeApplication).not.toHaveBeenCalled();

    rerender(view(false));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    openShutdownConfirmation();
    await waitFor(() => expect(screen.getByRole("button", { name: "Sí" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Sí" }));
    resolvePreparation(true);
    await waitFor(() => expect(closeApplication).toHaveBeenCalledOnce());
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

  it("allows another shutdown after a late desktop veto while the close IPC is pending", async () => {
    let resolveClose!: () => void;
    closeApplication.mockImplementationOnce(() => new Promise<void>((resolve) => { resolveClose = resolve; }));
    window.tpvDesktop = { closeApplication };
    const view = (exitBlocked: boolean) => <SessionTopControls
      locale="es" session={session} languageLabel="Idioma" shutdownLabel="Cerrar aplicación"
      changePasswordLabel="Cambiar contraseña" logoutLabel="Cerrar usuario"
      shutdownConfirmTitle="Cerrar aplicación" shutdownConfirmText="¿Deseas cerrar la aplicación?"
      noLabel="No" yesLabel="Sí" onLocaleChange={vi.fn()}
      onPrepareShutdown={async () => true} exitBlocked={exitBlocked}
    />;
    const { rerender } = render(view(false));
    openShutdownConfirmation();
    fireEvent.click(screen.getByRole("button", { name: "Sí" }));
    await waitFor(() => expect(closeApplication).toHaveBeenCalledOnce());
    rerender(view(true));
    resolveClose();
    rerender(view(false));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    openShutdownConfirmation();
    expect(screen.getByRole("button", { name: "Sí" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Sí" }));
    await waitFor(() => expect(closeApplication).toHaveBeenCalledTimes(2));
  });

  it("uses the browser close fallback after successful preparation outside Electron", async () => {
    const onPrepareShutdown = vi.fn().mockResolvedValue(true);
    const onBrowserClose = vi.fn().mockResolvedValue(undefined);
    renderControls(onPrepareShutdown, onBrowserClose);
    openShutdownConfirmation();

    fireEvent.click(screen.getByRole("button", { name: /^S/ }));

    await waitFor(() => expect(onBrowserClose).toHaveBeenCalledTimes(1));
    expect(onPrepareShutdown).toHaveBeenCalledTimes(1);
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
